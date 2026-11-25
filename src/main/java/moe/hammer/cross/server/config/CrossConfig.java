package moe.hammer.cross.server.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable runtime view of config.yml.
 */
public final class CrossConfig {

    public enum TemplateType {
        CHAT("chat"),
        KOOK_CHAT("kook-chat"),
        JOIN("join"),
        QUIT("quit"),
        TAB("tab"),
        MOTD("motd"),
        DM_SEND("dm-send"),
        DM_RECEIVE("dm-receive");

        private final String key;

        TemplateType(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    public record RedisSettings(
            String host,
            int port,
            String username,
            String password,
            int database,
            boolean ssl,
            int connectionTimeoutMs,
            int socketTimeoutMs,
            String prefix
    ) {
        public RedisSettings {
            host = Objects.requireNonNullElse(host, "127.0.0.1").trim();
            if (host.isEmpty()) {
                host = "127.0.0.1";
            }
            port = port <= 0 || port > 65535 ? 6379 : port;
            username = Objects.requireNonNullElse(username, "").trim();
            password = Objects.requireNonNullElse(password, "");
            database = Math.max(0, database);
            connectionTimeoutMs = Math.max(500, connectionTimeoutMs);
            socketTimeoutMs = Math.max(500, socketTimeoutMs);
            prefix = normalizePrefix(prefix);
        }

        private static String normalizePrefix(String prefix) {
            String value = Objects.requireNonNullElse(prefix, "hammer:cross").trim();
            while (value.endsWith(":")) {
                value = value.substring(0, value.length() - 1);
            }
            return value.isEmpty() ? "hammer:cross" : value;
        }

        public boolean sameConnection(RedisSettings other) {
            return other != null
                    && host.equals(other.host)
                    && port == other.port
                    && username.equals(other.username)
                    && password.equals(other.password)
                    && database == other.database
                    && ssl == other.ssl
                    && prefix.equals(other.prefix);
        }
    }

    public record PresenceSettings(int heartbeatSeconds, int reconcileSeconds, int staleSeconds) {
        public PresenceSettings {
            heartbeatSeconds = Math.max(1, heartbeatSeconds);
            reconcileSeconds = Math.max(1, reconcileSeconds);
            staleSeconds = Math.max(heartbeatSeconds * 3, staleSeconds);
        }
    }

    public record SyncSettings(boolean chat, boolean tab, boolean join, boolean quit,
                               boolean completion, boolean motd, boolean dm) {
    }

    public record LinkSettings(boolean enabled, String color, boolean underline,
                               boolean openInBrowser, String hover) {
        public LinkSettings {
            color = Objects.requireNonNullElse(color, "#58A6FF");
            hover = Objects.requireNonNullElse(hover, "");
        }
    }

    public record ItemDisplaySettings(boolean enabled, Set<String> placeholders, String format,
                                      boolean hover, boolean crossServer, int maxSerializedBytes,
                                      String emptyText, int maxItemsPerMessage) {
        public ItemDisplaySettings {
            placeholders = Collections.unmodifiableSet(new LinkedHashSet<>(placeholders));
            format = Objects.requireNonNullElse(format, "<aqua>[<item>]</aqua>");
            maxSerializedBytes = Math.max(256, maxSerializedBytes);
            emptyText = Objects.requireNonNullElse(emptyText, "<gray>[空手]</gray>");
            maxItemsPerMessage = Math.max(1, maxItemsPerMessage);
        }
    }

    public record ChatSettings(boolean formatLocal, int maxLength,
                               boolean overrideVanilla, boolean logToConsole,
                               LinkSettings link, ItemDisplaySettings item) {
        public ChatSettings {
            maxLength = Math.max(32, maxLength);
            link = Objects.requireNonNullElse(link, new LinkSettings(true, "#58A6FF", true, true, ""));
            item = Objects.requireNonNullElse(item,
                    new ItemDisplaySettings(false, Set.of(), "<aqua>[<item>]</aqua>", true, true, 8192, "<gray>[空手]</gray>", 8));
        }
    }

    public record JoinQuitSettings(boolean broadcastLocal, boolean suppressVanilla) {
    }

    public record DmSettings(boolean enabled, Set<String> commands, Set<String> replyCommands,
                             boolean senderConfirm, boolean overrideVanilla, boolean logToConsole) {
        public DmSettings {
            commands = Collections.unmodifiableSet(new LinkedHashSet<>(commands));
            replyCommands = Collections.unmodifiableSet(new LinkedHashSet<>(replyCommands));
        }
    }

    public record PapiSettings(boolean enabled) {
    }

    public record TabSettings(boolean formatLocalPlayers, int latency) {
        public TabSettings {
            latency = Math.max(0, latency);
        }
    }

    public record CompletionSettings(boolean packetFallback, boolean smart,
                                     Set<String> commands, int minPrefixLength, int maxResults) {
        public CompletionSettings {
            commands = Collections.unmodifiableSet(new LinkedHashSet<>(commands));
            minPrefixLength = Math.max(0, minPrefixLength);
            maxResults = Math.max(1, maxResults);
        }
    }

    public record MotdSettings(boolean syncPlayerCount, boolean syncMaxPlayers,
                               String maxPlayersMode, int fixedMaxPlayers,
                               boolean crossServerSample, int sampleSize,
                               boolean overrideMotd) {
        public MotdSettings {
            maxPlayersMode = Objects.requireNonNullElse(maxPlayersMode, "aggregate")
                    .toLowerCase(Locale.ROOT);
            if (!maxPlayersMode.equals("local") && !maxPlayersMode.equals("fixed")) {
                maxPlayersMode = "aggregate";
            }
            fixedMaxPlayers = Math.max(1, fixedMaxPlayers);
            sampleSize = Math.max(0, sampleSize);
        }
    }

    public record Templates(String chat, String join, String quit, String tab, String motd,
                            String dmSend, String dmReceive, String kookChat) {
        public String get(TemplateType type) {
            return switch (type) {
                case CHAT -> chat;
                case KOOK_CHAT -> kookChat;
                case JOIN -> join;
                case QUIT -> quit;
                case TAB -> tab;
                case MOTD -> motd;
                case DM_SEND -> dmSend;
                case DM_RECEIVE -> dmReceive;
            };
        }
    }

    public record ServerProfile(String displayName, Templates templates, Map<String, String> symbols) {
        public ServerProfile {
            displayName = Objects.requireNonNullElse(displayName, "");
            templates = Objects.requireNonNullElse(templates, new Templates(null, null, null, null, null, null, null, null));
            symbols = Collections.unmodifiableMap(new LinkedHashMap<>(symbols));
        }
    }

    private final String serverId;
    private final String localDisplayName;
    private final RedisSettings redis;
    private final PresenceSettings presence;
    private final SyncSettings sync;
    private final ChatSettings chat;
    private final JoinQuitSettings joinQuit;
    private final TabSettings tab;
    private final CompletionSettings completion;
    private final MotdSettings motd;
    private final DmSettings dm;
    private final PapiSettings papi;
    private final KookSettings kook;
    private final Templates templates;
    private final Map<String, String> localSymbols;
    private final Map<String, ServerProfile> servers;

    public CrossConfig(String serverId,
                       String localDisplayName,
                       RedisSettings redis,
                       PresenceSettings presence,
                       SyncSettings sync,
                       ChatSettings chat,
                       JoinQuitSettings joinQuit,
                       TabSettings tab,
                       CompletionSettings completion,
                       MotdSettings motd,
                       DmSettings dm,
                       PapiSettings papi,
                       KookSettings kook,
                       Templates templates,
                       Map<String, String> localSymbols,
                       Map<String, ServerProfile> servers) {
        this.serverId = Objects.requireNonNull(serverId, "serverId").trim();
        if (this.serverId.isEmpty()) {
            throw new IllegalArgumentException("server-id 不能为空");
        }
        this.localDisplayName = Objects.requireNonNullElse(localDisplayName, this.serverId).trim();
        this.redis = Objects.requireNonNull(redis, "redis");
        this.presence = Objects.requireNonNull(presence, "presence");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.chat = Objects.requireNonNull(chat, "chat");
        this.joinQuit = Objects.requireNonNull(joinQuit, "joinQuit");
        this.tab = Objects.requireNonNull(tab, "tab");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.motd = Objects.requireNonNull(motd, "motd");
        this.dm = Objects.requireNonNull(dm, "dm");
        this.papi = Objects.requireNonNull(papi, "papi");
        this.kook = Objects.requireNonNull(kook, "kook");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.localSymbols = Collections.unmodifiableMap(new LinkedHashMap<>(localSymbols));
        this.servers = Collections.unmodifiableMap(new LinkedHashMap<>(servers));
    }

    public String serverId() {
        return serverId;
    }

    public String localDisplayName() {
        return localDisplayName;
    }

    public RedisSettings redis() {
        return redis;
    }

    public PresenceSettings presence() {
        return presence;
    }

    public SyncSettings sync() {
        return sync;
    }

    public ChatSettings chat() {
        return chat;
    }

    public JoinQuitSettings joinQuit() {
        return joinQuit;
    }

    public TabSettings tab() {
        return tab;
    }

    public CompletionSettings completion() {
        return completion;
    }

    public MotdSettings motd() {
        return motd;
    }

    public DmSettings dm() {
        return dm;
    }

    public PapiSettings papi() {
        return papi;
    }

    public Templates templates() {
        return templates;
    }

    public KookSettings kook() {
        return kook;
    }

    public Map<String, String> localSymbols() {
        return localSymbols;
    }

    public Map<String, ServerProfile> servers() {
        return servers;
    }

    public String displayName(String sourceServerId, String fallback) {
        if (serverId.equals(sourceServerId)) {
            return localDisplayName.isEmpty() ? serverId : localDisplayName;
        }
        ServerProfile profile = servers.get(sourceServerId);
        if (profile != null && !profile.displayName().isBlank()) {
            return profile.displayName();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return sourceServerId;
    }

    public Templates templatesFor(String sourceServerId) {
        ServerProfile profile = servers.get(sourceServerId);
        return profile == null ? templates : profile.templates();
    }

    public String template(String sourceServerId, TemplateType type) {
        String override = templatesFor(sourceServerId).get(type);
        if (override != null && !override.isEmpty()) {
            return override;
        }
        String root = templates.get(type);
        return root == null ? "" : root;
    }

    public Map<String, String> symbolsFor(String sourceServerId) {
        Map<String, String> result = new LinkedHashMap<>(localSymbols);
        ServerProfile profile = servers.get(sourceServerId);
        if (profile != null) {
            result.putAll(profile.symbols());
        }
        return result;
    }

    public boolean sameNetwork(CrossConfig other) {
        return other != null
                && serverId.equals(other.serverId)
                && redis.sameConnection(other.redis);
    }
}
