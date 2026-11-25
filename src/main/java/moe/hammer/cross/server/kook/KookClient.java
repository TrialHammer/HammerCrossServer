package moe.hammer.cross.server.kook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** KOOK v3 HTTP and WebSocket client, created only by an enabled bridge. */
final class KookClient implements AutoCloseable {
    private static final URI API = URI.create("https://www.kookapp.cn/api/v3/");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_FRAME = 262_144;

    private final URI api;
    private final String token;
    private final Consumer<JsonObject> inbound;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledThreadPoolExecutor gateway = new ScheduledThreadPoolExecutor(1,
            task -> daemon(task, "HammerCrossServer-KOOK-Gateway"));
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> daemon(task, "HammerCrossServer-KOOK-Sender"));

    private volatile boolean closed;
    private volatile boolean connected;
    private volatile Connection current;
    private long nextSendNanos = System.nanoTime(); // sender thread only
    private long lastSendWarning; // accessed under warnSend lock

    // Gateway state lives on the gateway executor. It survives resumable disconnects.
    private URI gatewayUri;
    private String sessionId;
    private long lastSequence;
    private int resumeTries;
    private int failures;

    KookClient(String token, Consumer<JsonObject> inbound, Consumer<String> log) {
        this(API, token, inbound, log);
    }

    KookClient(URI api, String token, Consumer<JsonObject> inbound, Consumer<String> log) {
        this.api = api;
        this.token = token;
        this.inbound = inbound;
        this.log = log;
        gateway.setRemoveOnCancelPolicy(true);
    }

    void start() {
        submit(this::connect);
    }

    boolean connected() {
        return connected && !closed;
    }

    void send(String channel, int type, String content, String quote) {
        if (closed || content.isBlank()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("target_id", channel);
        payload.addProperty("content", content);
        if (quote != null) {
            payload.addProperty("quote", quote);
        }
        try {
            sender.execute(() -> send(payload));
        } catch (RejectedExecutionException ignored) {
            warnSend("KOOK 发送队列已满，已丢弃一条待转发消息。");
        }
    }

    private void send(JsonObject payload) {
        try {
            HttpRequest request = request("message/create")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            for (int attempt = 0; attempt < 3 && !closed; attempt++) {
                long wait = nextSendNanos - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                if (closed) {
                    return;
                }
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                boolean limited = response.statusCode() == 429;
                if (limited || response.headers().firstValue("X-Rate-Limit-Remaining").orElse("").equals("0")) {
                    long delay = response.headers().firstValueAsLong("Retry-After").orElse(0);
                    delay = Math.max(delay, response.headers().firstValueAsLong("X-Rate-Limit-Reset").orElse(0));
                    nextSendNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.clamp(delay, 1, 3600));
                }
                if (!limited) {
                    requireSuccess(response);
                    return;
                }
            }
            warnSend("KOOK 发送消息达到限流，已跳过。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            // Do not retry ambiguous POST failures, which could duplicate an already accepted message.
            warnSend("KOOK 发送消息失败（" + safeProblem(ex) + "）。");
        }
    }

    private synchronized void warnSend(String message) {
        long now = System.currentTimeMillis();
        if (!closed && now - lastSendWarning >= 10_000) {
            lastSendWarning = now;
            log.accept(message);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(api.resolve(path)).timeout(TIMEOUT)
                .header("Authorization", "Bot " + token);
    }

    private static JsonObject requireSuccess(HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        long code = number(root, "code", -1);
        if (code != 0) {
            throw new IOException("API code " + code);
        }
        return root;
    }

    private void connect() {
        if (closed) {
            return;
        }
        boolean resume = sessionId != null && resumeTries < 2;
        Connection connection = new Connection(resume);
        current = connection;
        try {
            URI uri;
            if (resume) {
                resumeTries++;
                uri = URI.create(gatewayUri + (gatewayUri.getRawQuery() == null ? "?" : "&")
                        + "resume=1&sn=" + lastSequence
                        + "&session_id=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8));
            } else {
                resetSession();
                JsonObject response = requireSuccess(http.send(request("gateway/index?compress=0").GET().build(),
                        HttpResponse.BodyHandlers.ofString()));
                uri = URI.create(string(object(response, "data"), "url"));
                if (!"wss".equalsIgnoreCase(uri.getScheme())
                        && !("http".equals(api.getScheme()) && "ws".equals(uri.getScheme()))) {
                    throw new IllegalArgumentException("Invalid gateway scheme");
                }
                gatewayUri = uri;
            }
            if (!closed) {
                http.newWebSocketBuilder().connectTimeout(TIMEOUT).buildAsync(uri, connection)
                        .whenComplete((socket, error) -> {
                            if (error != null) {
                                submit(() -> disconnected(connection, "WebSocket 握手失败", false));
                            } else if (closed || current != connection) {
                                socket.abort();
                            }
                        });
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            disconnected(connection, safeProblem(ex), false);
        }
    }

    private void handle(Connection connection, String frame) {
        if (closed || current != connection) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(frame).getAsJsonObject();
            JsonObject data = object(root, "d");
            switch ((int) number(root, "s", -1)) {
                case 0 -> {
                    long sn = number(root, "sn", 0);
                    if ((connected || connection.resume) && sn > lastSequence) {
                        lastSequence = sn;
                        if (data != null) {
                            inbound.accept(data);
                        }
                    }
                }
                case 1 -> {
                    if (number(data, "code", -1) != 0) {
                        disconnected(connection, "KOOK 拒绝会话，code=" + number(data, "code", -1), true);
                    } else if (!connection.resume) {
                        sessionId = string(data, "session_id");
                        if (sessionId.isBlank()) {
                            disconnected(connection, "KOOK 握手缺少会话 ID", true);
                        } else {
                            ready(connection);
                        }
                    }
                }
                case 3 -> {
                    if (connected && connection.pingAttempts > 0) {
                        connection.pingAttempts = 0;
                        nextHeartbeat(connection);
                    }
                }
                case 5 -> disconnected(connection, "KOOK 要求重新连接", true);
                case 6 -> {
                    if (connection.resume && !connected) {
                        String resumed = string(data, "session_id");
                        if (!resumed.isBlank()) {
                            sessionId = resumed;
                        }
                        ready(connection);
                    }
                }
                default -> { }
            }
        } catch (RuntimeException ex) {
            log.accept("KOOK 跳过无效事件（" + ex.getClass().getSimpleName() + "）。");
        }
    }

    private void ready(Connection connection) {
        if (connected) {
            return;
        }
        connected = true;
        resumeTries = 0;
        failures = 0;
        nextHeartbeat(connection);
        log.accept("KOOK 网关已连接。");
    }

    private void nextHeartbeat(Connection connection) {
        connection.schedule(() -> ping(connection), ThreadLocalRandom.current().nextInt(25, 36));
    }

    private void ping(Connection connection) {
        if (closed || current != connection) {
            return;
        }
        connection.pingAttempts++;
        connection.socket.sendText("{\"s\":2,\"sn\":" + lastSequence + "}", true)
                .exceptionally(error -> {
                    submit(() -> disconnected(connection, "KOOK 心跳发送失败", false));
                    return null;
                });
        connection.schedule(() -> {
            if (connection.pingAttempts < 3) {
                connection.schedule(() -> ping(connection), connection.pingAttempts == 1 ? 2 : 4);
            } else {
                disconnected(connection, "KOOK 心跳超时", false);
            }
        }, 6);
    }

    private void disconnected(Connection connection, String reason, boolean fresh) {
        if (closed || current != connection) {
            return;
        }
        connected = false;
        current = null;
        connection.abort();
        if (fresh) {
            resetSession();
        }
        int delay = Math.min(60, 2 << Math.min(failures++, 5));
        if (sessionId != null && resumeTries < 2) {
            delay = resumeTries == 0 ? 8 : 16;
        }
        log.accept("KOOK 网关未连接（" + reason + "），" + delay + " 秒后重试。");
        try {
            gateway.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Close can race an in-flight callback.
        }
    }

    private void resetSession() {
        sessionId = null;
        lastSequence = 0;
        resumeTries = 0;
    }

    private boolean submit(Runnable action) {
        if (closed) {
            return false;
        }
        try {
            gateway.execute(action);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        Connection connection = current;
        if (connection != null) {
            connection.abort();
        }
        gateway.shutdownNow();
        sender.shutdownNow();
        http.shutdownNow();
    }

    private final class Connection implements WebSocket.Listener {
        private final boolean resume;
        private final StringBuilder fragments = new StringBuilder();
        private volatile WebSocket socket;
        private volatile ScheduledFuture<?> timer;
        private int pingAttempts;

        private Connection(boolean resume) {
            this.resume = resume;
        }

        @Override
        public void onOpen(WebSocket socket) {
            this.socket = socket;
            if (!submit(() -> {
                if (current == this && !closed) {
                    schedule(() -> disconnected(this, "KOOK 握手响应超时", resume), resume ? 15 : 6);
                    socket.request(1);
                } else {
                    socket.abort();
                }
            })) {
                socket.abort();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (fragments.length() + data.length() > MAX_FRAME) {
                submit(() -> disconnected(this, "KOOK 网关消息过大", true));
                return null;
            }
            fragments.append(data);
            if (!last) {
                socket.request(1);
                return null;
            }
            String frame = fragments.toString();
            fragments.setLength(0);
            submit(() -> {
                handle(this, frame);
                if (!closed && current == this) {
                    socket.request(1);
                }
            });
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            submit(() -> disconnected(this, "KOOK 网关未按请求发送非压缩文本", true));
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            submit(() -> disconnected(this, "WebSocket " + status, false));
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            submit(() -> disconnected(this, "WebSocket 连接错误", false));
        }

        private void schedule(Runnable action, int seconds) {
            ScheduledFuture<?> previous = timer;
            if (previous != null) {
                previous.cancel(false);
            }
            try {
                timer = gateway.schedule(action, seconds, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // The bridge was unloaded.
            }
        }

        private void abort() {
            ScheduledFuture<?> scheduled = timer;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            WebSocket active = socket;
            if (active != null) {
                active.abort();
            }
        }
    }

    static JsonObject object(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
    }

    static String string(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    static long number(JsonObject json, String key, long fallback) {
        try {
            return Long.parseLong(string(json, key));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String safeProblem(Exception error) {
        // Avoid logging signed WebSocket URLs, headers, or arbitrary response content.
        if (error.getClass() == IOException.class && error.getMessage() != null
                && error.getMessage().matches("(HTTP|API code) -?[0-9]+")) {
            return error.getMessage();
        }
        return error.getClass().getSimpleName();
    }
}
