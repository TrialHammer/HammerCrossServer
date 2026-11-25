package moe.hammer.cross.server.config;

import moe.hammer.cross.server.HammerCrossServer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigManager {
    public static final String VERSION_KEY = VersionedYaml.VERSION_KEY;
    public static final String DEFAULT_CHAT = "<gray>[<server>]</gray> <white><player></white> <dark_gray>»</dark_gray> <message>";
    public static final String DEFAULT_KOOK_CHAT = "<dark_gray>[<gold><server></gold>]</dark_gray> <gray><player>:</gray> <white><message></white>";
    public static final String DEFAULT_JOIN = "<green><player></green> <gray>加入了</gray> <aqua><server></aqua>";
    public static final String DEFAULT_QUIT = "<yellow><player></yellow> <gray>离开了</gray> <aqua><server></aqua>";
    public static final String DEFAULT_TAB = "<white><player></white> <dark_gray>@</dark_gray> <gray><server></gray>";
    public static final String DEFAULT_MOTD = "<gradient:#56CCF2:#2F80ED>Hammer Network</gradient>\n<gray>全服在线 <white><online></white>/<white><max></white></gray> <dark_gray>|</dark_gray> <gray>本服 <white><local_online></white></gray>";
    public static final String DEFAULT_DM_SEND = "<gray>[私聊]</gray> <white>你</white> <dark_gray>→</dark_gray> <aqua><target></aqua> <dark_gray>@</dark_gray> <gray><server></gray><gray>:</gray> <white><message></white>";
    public static final String DEFAULT_DM_RECEIVE = "<gray>[私聊]</gray> <aqua><player></aqua> <dark_gray>@</dark_gray> <gray><server></gray> <dark_gray>→</dark_gray> <white>你</white><gray>:</gray> <white><message></white>";

    private static final Map<String, String> DEFAULT_SYMBOLS;

    static {
        Map<String, String> symbols = new LinkedHashMap<>();
        symbols.put("star", "★");
        symbols.put("moon", "☾");
        symbols.put("divider", " » ");
        symbols.put("dot", "•");
        DEFAULT_SYMBOLS = Collections.unmodifiableMap(symbols);
    }

    // List index is the source version. Append a step and bump the bundled version together.
    static final VersionedYaml.Schema CONFIG_SCHEMA = new VersionedYaml.Schema("config.yml",
            List.of(ConfigManager::migrateLegacyItemKeys), Set.of("servers", "symbols"));
    static final VersionedYaml.Schema MESSAGES_SCHEMA = new VersionedYaml.Schema("messages.yml",
            List.of(yaml -> {}), Set.of());

    private final HammerCrossServer plugin;

    public ConfigManager(HammerCrossServer plugin) {
        this.plugin = plugin;
    }

    public Prepared prepare() {
        VersionedYaml.Prepared main = prepare(CONFIG_SCHEMA);
        VersionedYaml.Prepared messages = prepare(MESSAGES_SCHEMA);
        return new Prepared(parse(main.yaml()), main, messages);
    }

    private VersionedYaml.Prepared prepare(VersionedYaml.Schema schema) {
        try (InputStream defaults = plugin.getResource(schema.resource())) {
            if (defaults == null) {
                throw new IllegalStateException("缺少内置 " + schema.resource());
            }
            return VersionedYaml.prepare(plugin.getDataFolder().toPath().resolve(schema.resource()), schema,
                    new String(defaults.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("读取内置 " + schema.resource() + " 失败", error);
        }
    }

    public static final class Prepared {
        private final CrossConfig config;
        private final VersionedYaml.Prepared main;
        private final VersionedYaml.Prepared messages;

        private Prepared(CrossConfig config, VersionedYaml.Prepared main, VersionedYaml.Prepared messages) {
            this.config = config;
            this.main = main;
            this.messages = messages;
        }

        public CrossConfig config() {
            return config;
        }

        public YamlConfiguration messages() {
            return messages.yaml();
        }

        public void save(Logger logger) {
            save(main, logger);
            save(messages, logger);
        }

        private static void save(VersionedYaml.Prepared file, Logger logger) {
            try {
                Path backup = file.save();
                if (backup != null) {
                    logger.info(file.path().getFileName() + " 已迁移 v" + file.from() + " → v" + file.to()
                            + "，原文件备份于 " + backup.getFileName());
                }
            } catch (IOException error) {
                throw new IllegalStateException("保存 " + file.path().getFileName() + " 失败: " + error.getMessage(), error);
            }
        }
    }

    private CrossConfig parse(FileConfiguration config) {
        String serverId = config.getString("server-id", "survival-1");
        String displayName = config.getString("local.display-name", "生存服");

        CrossConfig.RedisSettings redis = new CrossConfig.RedisSettings(
                config.getString("redis.host", "127.0.0.1"),
                config.getInt("redis.port", 6379),
                config.getString("redis.username", ""),
                config.getString("redis.password", ""),
                config.getInt("redis.database", 0),
                config.getBoolean("redis.ssl", false),
                config.getInt("redis.connection-timeout-ms", 3000),
                config.getInt("redis.socket-timeout-ms", 3000),
                config.getString("redis.prefix", "hammer:cross")
        );

        CrossConfig.PresenceSettings presence = new CrossConfig.PresenceSettings(
                config.getInt("presence.heartbeat-seconds", 10),
                config.getInt("presence.reconcile-seconds", 5),
                config.getInt("presence.stale-seconds", 30)
        );

        CrossConfig.SyncSettings sync = new CrossConfig.SyncSettings(
                config.getBoolean("sync.chat", true),
                config.getBoolean("sync.tab", true),
                config.getBoolean("sync.join", true),
                config.getBoolean("sync.quit", true),
                config.getBoolean("sync.completion", true),
                config.getBoolean("sync.motd", true),
                config.getBoolean("sync.dm", true)
        );

        CrossConfig.LinkSettings link = new CrossConfig.LinkSettings(
                config.getBoolean("chat.link.enabled", true),
                config.getString("chat.link.color", "#58A6FF"),
                config.getBoolean("chat.link.underline", true),
                config.getBoolean("chat.link.open-in-browser", true),
                config.getString("chat.link.hover", "<gray>点击打开链接</gray>")
        );
        Set<String> itemPlaceholders = new LinkedHashSet<>();
        // Accept both the new "keys" name (TrChat-compatible) and the legacy "placeholders".
        for (String placeholder : config.getStringList("chat.item.placeholders")) {
            if (placeholder != null && !placeholder.isBlank()) {
                itemPlaceholders.add(placeholder.trim());
            }
        }
        for (String key : config.getStringList("chat.item.keys")) {
            if (key != null && !key.isBlank()) {
                itemPlaceholders.add(key.trim());
            }
        }
        if (itemPlaceholders.isEmpty()) {
            itemPlaceholders.add("%i%");
            itemPlaceholders.add("%i");
            itemPlaceholders.add("%item%");
            itemPlaceholders.add("%item");
            itemPlaceholders.add("[i]");
            itemPlaceholders.add("[item]");
        }
        CrossConfig.ItemDisplaySettings itemDisplay = new CrossConfig.ItemDisplaySettings(
                config.getBoolean("chat.item.enabled", true),
                itemPlaceholders,
                config.getString("chat.item.format", "<aqua>[<item>]</aqua>"),
                config.getBoolean("chat.item.hover", true),
                config.getBoolean("chat.item.cross-server", true),
                config.getInt("chat.item.max-serialized-bytes", 8192),
                config.getString("chat.item.empty-text", "<gray>[空手]</gray>"),
                config.getInt("chat.item.max-items-per-message", 8)
        );
        CrossConfig.ChatSettings chat = new CrossConfig.ChatSettings(
                config.getBoolean("chat.format-local", true),
                config.getInt("chat.max-length", 512),
                config.getBoolean("chat.override-vanilla", true),
                config.getBoolean("chat.log-to-console", true),
                link,
                itemDisplay
        );

        CrossConfig.JoinQuitSettings joinQuit = new CrossConfig.JoinQuitSettings(
                config.getBoolean("join-quit.broadcast-local", true),
                config.getBoolean("join-quit.suppress-vanilla", true)
        );

        CrossConfig.TabSettings tab = new CrossConfig.TabSettings(
                config.getBoolean("tab.format-local-players", true),
                config.getInt("tab.latency", 0)
        );

        Set<String> completionCommands = new LinkedHashSet<>();
        for (String command : config.getStringList("completion.commands")) {
            if (command == null) {
                continue;
            }
            String cleaned = command.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty()) {
                completionCommands.add(cleaned);
            }
        }

        CrossConfig.CompletionSettings completion = new CrossConfig.CompletionSettings(
                config.getBoolean("completion.packet-fallback", true),
                config.getBoolean("completion.smart", true),
                completionCommands,
                config.getInt("completion.min-prefix-length", 0),
                config.getInt("completion.max-results", 100)
        );

        CrossConfig.MotdSettings motd = new CrossConfig.MotdSettings(
                config.getBoolean("motd.sync-player-count", true),
                config.getBoolean("motd.sync-max-players", true),
                config.getString("motd.max-players-mode", "aggregate"),
                config.getInt("motd.fixed-max-players", 100),
                config.getBoolean("motd.cross-server-sample", false),
                config.getInt("motd.sample-size", 12),
                config.getBoolean("motd.override-motd", true)
        );

        Set<String> dmCommands = readCommandSet(config.getStringList("dm.commands"),
                List.of("msg", "tell", "w", "whisper", "m"));
        Set<String> dmReplyCommands = readCommandSet(config.getStringList("dm.reply-commands"),
                List.of("r", "reply"));
        CrossConfig.DmSettings dm = new CrossConfig.DmSettings(
                config.getBoolean("dm.enabled", true),
                dmCommands,
                dmReplyCommands,
                config.getBoolean("dm.sender-confirm", true),
                config.getBoolean("dm.override-vanilla", true),
                config.getBoolean("dm.log-to-console", true)
        );

        CrossConfig.PapiSettings papi = new CrossConfig.PapiSettings(
                config.getBoolean("papi.enabled", true)
        );

        ConfigurationSection formats = config.getConfigurationSection("formats");
        CrossConfig.Templates templates = new CrossConfig.Templates(
                readMultiline(formats, "chat", DEFAULT_CHAT),
                readMultiline(formats, "join", DEFAULT_JOIN),
                readMultiline(formats, "quit", DEFAULT_QUIT),
                readMultiline(formats, "tab", DEFAULT_TAB),
                readMultiline(formats, "motd", DEFAULT_MOTD),
                readMultiline(formats, "dm-send", DEFAULT_DM_SEND),
                readMultiline(formats, "dm-receive", DEFAULT_DM_RECEIVE),
                readMultiline(formats, "kook-chat", DEFAULT_KOOK_CHAT)
        );

        Map<String, String> symbols = readStringMap(config.getConfigurationSection("symbols"), DEFAULT_SYMBOLS);
        Map<String, CrossConfig.ServerProfile> servers = readServers(config.getConfigurationSection("servers"));

        return new CrossConfig(
                serverId,
                displayName,
                redis,
                presence,
                sync,
                chat,
                joinQuit,
                tab,
                completion,
                motd,
                dm,
                papi,
                KookSettings.read(config),
                templates,
                symbols,
                servers
        );
    }

    private static void migrateLegacyItemKeys(YamlConfiguration yaml) {
        String from = "chat.item.placeholders";
        String to = "chat.item.keys";
        Object old = yaml.get(from);
        Object next = yaml.get(to);
        if (!(old instanceof List<?> oldKeys) || (next != null && !(next instanceof List<?>))) {
            return;
        }
        if (next == null) {
            yaml.set(to, oldKeys);
            yaml.setComments(to, yaml.getComments(from));
            yaml.setInlineComments(to, yaml.getInlineComments(from));
        } else {
            // Both spellings used to contribute to the key set, in this order.
            Set<Object> combined = new LinkedHashSet<>(oldKeys);
            combined.addAll((List<?>) next);
            yaml.set(to, new java.util.ArrayList<>(combined));
        }
        yaml.set(from, null);
    }

    private Map<String, CrossConfig.ServerProfile> readServers(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, CrossConfig.ServerProfile> result = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection server = section.getConfigurationSection(id);
            if (server == null) {
                continue;
            }
            String displayName = server.getString("display-name", id);
            Map<String, String> symbols = readStringMap(server.getConfigurationSection("symbols"), Map.of());
            ConfigurationSection formats = server.getConfigurationSection("formats");
            CrossConfig.Templates templates = new CrossConfig.Templates(
                    readOptionalMultiline(formats, "chat"),
                    readOptionalMultiline(formats, "join"),
                    readOptionalMultiline(formats, "quit"),
                    readOptionalMultiline(formats, "tab"),
                    readOptionalMultiline(formats, "motd"),
                    readOptionalMultiline(formats, "dm-send"),
                    readOptionalMultiline(formats, "dm-receive"),
                    readOptionalMultiline(formats, "kook-chat")
            );
            result.put(id, new CrossConfig.ServerProfile(displayName, templates, symbols));
        }
        return result;
    }

    private Set<String> readCommandSet(List<String> raw, List<String> fallback) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String command : raw) {
                if (command == null) {
                    continue;
                }
                String cleaned = command.trim().toLowerCase(Locale.ROOT);
                while (cleaned.startsWith("/")) {
                    cleaned = cleaned.substring(1);
                }
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
        }
        if (result.isEmpty() && fallback != null) {
            result.addAll(fallback);
        }
        return result;
    }

    private Map<String, String> readStringMap(ConfigurationSection section, Map<String, String> fallback) {
        if (section == null) {
            return new LinkedHashMap<>(fallback);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null && !value.isEmpty()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private String readMultiline(ConfigurationSection section, String path, String fallback) {
        String value = readOptionalMultiline(section, path);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private String readOptionalMultiline(ConfigurationSection section, String path) {
        if (section == null) {
            return null;
        }
        Object raw = section.get(path);
        if (raw instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object line : list) {
                if (line == null) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }
        String value = section.getString(path);
        return value == null ? null : value;
    }
}
