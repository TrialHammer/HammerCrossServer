package moe.hammer.cross.server.redis;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.CrossEvent;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.model.PresenceSnapshot;
import moe.hammer.cross.server.model.ServerEntry;
import moe.hammer.cross.server.util.JsonUtil;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class RedisManager implements AutoCloseable {
    private final HammerCrossServer plugin;
    private final ExecutorService redisExecutor;

    private volatile CrossConfig.RedisSettings settings;
    private volatile JedisPooled pool;
    private volatile boolean running;
    private volatile boolean healthy;
    private volatile int staleSeconds = 30;

    private RedisSubscriber subscriber;
    private Thread subscriberThread;
    private volatile Consumer<CrossEvent> eventConsumer;
    private volatile Runnable reconnectCallback;

    public RedisManager(HammerCrossServer plugin) {
        this.plugin = plugin;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "HammerCrossServer-Redis-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.redisExecutor = Executors.newSingleThreadExecutor(factory);
    }

    public synchronized void connect(CrossConfig config,
                                     Consumer<CrossEvent> eventConsumer,
                                     Runnable reconnectCallback) {
        CrossConfig.RedisSettings next = config.redis();
        this.staleSeconds = Math.max(5, config.presence().staleSeconds());
        this.eventConsumer = eventConsumer;
        this.reconnectCallback = reconnectCallback;
        boolean same = settings != null && settings.sameConnection(next);
        if (same && pool != null) {
            this.settings = next;
            return;
        }
        stopSubscriber();
        closePool();
        this.settings = next;
        this.pool = createPool(next);
        this.running = true;
        startSubscriber();
        try {
            pool.ping();
            this.healthy = true;
            plugin.getLogger().info("Redis 已连接: " + next.host() + ":" + next.port() + " db=" + next.database());
        } catch (RuntimeException ex) {
            this.healthy = false;
            plugin.getLogger().warning("Redis 初次连接失败，将在后台重试: " + ex.getMessage());
        }
    }

    private JedisPooled createPool(CrossConfig.RedisSettings config) {
        org.apache.commons.pool2.impl.GenericObjectPoolConfig<redis.clients.jedis.Connection> poolConfig =
                new org.apache.commons.pool2.impl.GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(0);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(java.time.Duration.ofMillis(Math.max(1000, config.connectionTimeoutMs())));
        JedisClientConfig clientConfig = createClientConfig(config);
        return new JedisPooled(poolConfig, new HostAndPort(config.host(), config.port()), clientConfig);
    }

    private JedisClientConfig createClientConfig(CrossConfig.RedisSettings config) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.connectionTimeoutMs())
                .socketTimeoutMillis(config.socketTimeoutMs())
                .database(config.database())
                .ssl(config.ssl());
        if (!config.username().isEmpty()) {
            builder.user(config.username());
        }
        if (!config.password().isEmpty()) {
            builder.password(config.password());
        }
        return builder.build();
    }

    private void startSubscriber() {
        RedisSubscriber next = new RedisSubscriber();
        this.subscriber = next;
        this.subscriberThread = new Thread(() -> runSubscriberLoop(next), "HammerCrossServer-RedisSub");
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    private void runSubscriberLoop(RedisSubscriber localSubscriber) {
        int failures = 0;
        while (running && subscriber == localSubscriber) {
            CrossConfig.RedisSettings current = settings;
            if (current == null) {
                return;
            }
            try (Jedis jedis = new Jedis(new HostAndPort(current.host(), current.port()), createClientConfig(current))) {
                localSubscriber.connected = true;
                failures = 0;
                plugin.getLogger().info("Redis Pub/Sub 已订阅: " + channel(current.prefix()));
                Runnable callback = reconnectCallback;
                if (callback != null) {
                    redisExecutor.execute(() -> {
                        try {
                            callback.run();
                        } catch (Throwable error) {
                            plugin.getLogger().warning("Redis 重连后的数据刷新失败: " + error.getMessage());
                        }
                    });
                }
                jedis.subscribe(localSubscriber, channel(current.prefix()));
            } catch (Throwable error) {
                localSubscriber.connected = false;
                if (!running || subscriber != localSubscriber) {
                    return;
                }
                failures++;
                long delay = Math.min(30_000L, 1_000L * Math.max(1, failures));
                plugin.getLogger().warning("Redis Pub/Sub 断开，将在 " + delay + "ms 后重连: " + error.getMessage());
                sleep(delay);
            }
        }
    }

    private void stopSubscriber() {
        RedisSubscriber current = subscriber;
        subscriber = null;
        if (current != null) {
            try {
                current.unsubscribe();
            } catch (Throwable ignored) {
            }
        }
        Thread thread = subscriberThread;
        subscriberThread = null;
        if (thread != null) {
            try {
                thread.join(1500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public String keyPresencePlayers() {
        return settings.prefix() + ":presence:players";
    }

    public String keyPresenceServers() {
        return settings.prefix() + ":presence:servers";
    }

    public String keyHeartbeat(String serverId) {
        return settings.prefix() + ":heartbeat:" + serverId;
    }

    public String channel(String prefix) {
        return prefix + ":events";
    }

    public String channel() {
        return channel(settings.prefix());
    }

    public void execute(Runnable task) {
        if (task == null) {
            return;
        }
        redisExecutor.execute(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                plugin.getLogger().warning("Redis 异步任务失败: " + error.getMessage());
            }
        });
    }

    public void healthPing(Consumer<Boolean> callback) {
        execute(() -> {
            boolean ok;
            try {
                JedisPooled current = pool;
                ok = current != null && "PONG".equalsIgnoreCase(current.ping());
            } catch (Throwable error) {
                ok = false;
            }
            healthy = ok;
            boolean result = ok;
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> callback.accept(result));
        });
    }

    public void upsertPresence(PresenceEntry entry, Runnable onError) {
        execute(() -> {
            try {
                JedisPooled current = requirePool();
                current.hset(keyPresencePlayers(), entry.fieldKey(), JsonUtil.toJson(entry));
            } catch (Throwable error) {
                plugin.getLogger().warning("写入在线玩家失败: " + error.getMessage());
                if (onError != null) {
                    onError.run();
                }
            }
        });
    }

    public void upsertPresenceBatch(java.util.Collection<PresenceEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        execute(() -> {
            try {
                JedisPooled current = requirePool();
                for (PresenceEntry entry : entries) {
                    if (entry == null || entry.uuid == null || entry.uuid.isBlank()) {
                        continue;
                    }
                    current.hset(keyPresencePlayers(), entry.fieldKey(), JsonUtil.toJson(entry));
                }
            } catch (Throwable error) {
                plugin.getLogger().warning("批量写入在线玩家失败: " + error.getMessage());
            }
        });
    }

    public void removePresence(String serverId, UUID uuid) {
        execute(() -> {
            try {
                requirePool().hdel(keyPresencePlayers(), serverId + ":" + uuid);
            } catch (Throwable error) {
                plugin.getLogger().warning("删除在线玩家失败: " + error.getMessage());
            }
        });
    }

    public void upsertServer(ServerEntry entry) {
        execute(() -> {
            try {
                JedisPooled current = requirePool();
                current.hset(keyPresenceServers(), entry.serverId, JsonUtil.toJson(entry));
            } catch (Throwable error) {
                plugin.getLogger().warning("写入服务器状态失败: " + error.getMessage());
            }
        });
    }

    public void removeServer(String serverId) {
        execute(() -> {
            try {
                JedisPooled current = requirePool();
                current.hdel(keyPresenceServers(), serverId);
                current.del(keyHeartbeat(serverId));
            } catch (Throwable error) {
                plugin.getLogger().warning("删除服务器状态失败: " + error.getMessage());
            }
        });
    }

    public void heartbeat(String serverId, int ttlSeconds) {
        execute(() -> {
            try {
                requirePool().setex(keyHeartbeat(serverId), ttlSeconds, Long.toString(System.currentTimeMillis()));
                healthy = true;
            } catch (Throwable error) {
                healthy = false;
                plugin.getLogger().warning("刷新心跳失败: " + error.getMessage());
            }
        });
    }

    public void publish(CrossEvent event) {
        String json = JsonUtil.toJson(event);
        execute(() -> {
            try {
                requirePool().publish(channel(), json);
            } catch (Throwable error) {
                plugin.getLogger().warning("发布跨服事件失败: " + error.getMessage());
            }
        });
    }

    public void loadSnapshot(Consumer<PresenceSnapshot> consumer) {
        execute(() -> {
            try {
                JedisPooled current = requirePool();
                Map<String, String> playerRaw = current.hgetAll(keyPresencePlayers());
                Map<String, String> serverRaw = current.hgetAll(keyPresenceServers());
                long staleBefore = System.currentTimeMillis() - staleSeconds * 1000L;

                Map<String, ServerEntry> servers = new HashMap<>();
                java.util.Set<String> deadServers = new java.util.HashSet<>();
                for (Map.Entry<String, String> entry : serverRaw.entrySet()) {
                    try {
                        ServerEntry server = JsonUtil.fromJson(entry.getValue(), ServerEntry.class);
                        if (server == null || server.serverId == null) {
                            continue;
                        }
                        boolean alive;
                        try {
                            alive = current.exists(keyHeartbeat(server.serverId));
                        } catch (Throwable probeError) {
                            // Never treat a server as dead when the probe itself fails.
                            alive = true;
                        }
                        if (!alive && (server.updatedAt <= 0 || server.updatedAt < staleBefore)) {
                            deadServers.add(server.serverId);
                            current.hdel(keyPresenceServers(), server.serverId);
                            current.del(keyHeartbeat(server.serverId));
                            continue;
                        }
                        servers.put(server.serverId, server);
                    } catch (Throwable error) {
                        plugin.getLogger().warning("忽略损坏的服务器记录 " + entry.getKey() + ": " + error.getMessage());
                    }
                }

                Map<UUID, PresenceEntry> players = new HashMap<>();
                for (Map.Entry<String, String> entry : playerRaw.entrySet()) {
                    try {
                        PresenceEntry presence = JsonUtil.fromJson(entry.getValue(), PresenceEntry.class);
                        if (presence == null) {
                            continue;
                        }
                        if (presence.serverId != null && deadServers.contains(presence.serverId)) {
                            current.hdel(keyPresencePlayers(), entry.getKey());
                            continue;
                        }
                        UUID uuid = presence.uuidOrNull();
                        if (uuid != null) {
                            players.put(uuid, presence);
                        }
                    } catch (Throwable error) {
                        plugin.getLogger().warning("忽略损坏的在线玩家记录 " + entry.getKey() + ": " + error.getMessage());
                    }
                }
                consumer.accept(new PresenceSnapshot(players, servers));
            } catch (Throwable error) {
                plugin.getLogger().warning("读取 Redis 在线快照失败: " + error.getMessage());
            }
        });
    }

    /**
     * Non-blocking connectivity hint for command/status paths. This reflects
     * whether a client pool is currently initialised; it never performs network
     * I/O on the calling thread. Use {@link #healthPing(Consumer)} when an
     * authoritative, asynchronous health check is required.
     */
    public boolean isConnected() {
        return pool != null && healthy;
    }

    public JedisPooled requirePool() {
        JedisPooled current = pool;
        if (current == null) {
            throw new IllegalStateException("Redis 尚未连接");
        }
        return current;
    }

    private void closePool() {
        JedisPooled current = pool;
        pool = null;
        healthy = false;
        if (current != null) {
            try {
                current.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void shutdown() {
        running = false;
        stopSubscriber();
        closePool();
        redisExecutor.shutdown();
        try {
            if (!redisExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                redisExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            redisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private final class RedisSubscriber extends JedisPubSub {
        private volatile boolean connected;

        @Override
        public void onMessage(String channel, String message) {
            if (!channel().equals(channel) || message == null) {
                return;
            }
            try {
                CrossEvent event = JsonUtil.fromJson(message, CrossEvent.class);
                if (event == null || event.type == null || event.serverId == null) {
                    return;
                }
                Consumer<CrossEvent> consumer = eventConsumer;
                if (consumer != null) {
                    consumer.accept(event);
                }
            } catch (Throwable error) {
                plugin.getLogger().warning("收到无法解析的跨服事件: " + error.getMessage());
            }
        }

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            plugin.getLogger().info("Redis Pub/Sub 订阅成功: " + channel);
        }
    }
}
