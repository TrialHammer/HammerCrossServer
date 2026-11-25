package moe.hammer.cross.server.text;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Resolves templates into Adventure components. All values that originate from
 * a remote server are inserted as components, never parsed as MiniMessage.
 */
public final class FormatService {
    private final HammerCrossServer plugin;
    private final MiniMessage miniMessage = MiniMessage.builder().strict(false).build();
    private final PapiService papi = new PapiService();
    private final Set<String> warnedTemplates = ConcurrentHashMap.newKeySet();
    private final AtomicLong revision = new AtomicLong();
    private volatile CrossConfig config;

    public FormatService(HammerCrossServer plugin, CrossConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setConfig(CrossConfig config) {
        this.config = config;
        revision.incrementAndGet();
    }

    public CrossConfig config() {
        return config;
    }

    public long revision() {
        return revision.get();
    }

    /** Chat rendering with PlaceholderAPI resolved for the message author. */
    public Component renderChat(java.util.UUID authorUuid,
                                String sourceServerId,
                                String sourceDisplayFallback,
                                String playerName,
                                String playerDisplayName,
                                Component message) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        placeholders.put("message", message == null ? Component.empty() : message);
        return render(sourceServerId, CrossConfig.TemplateType.CHAT, placeholders, authorUuid);
    }

    public Component renderKookChat(String gatewayId, String playerName, Component message) {
        Component player = Component.text(TextUtil.cleanName(playerName));
        return render(gatewayId, CrossConfig.TemplateType.KOOK_CHAT, Map.of(
                "server", Component.text("KOOK"),
                "server_id", Component.text(safe(gatewayId)),
                "player", player,
                "player_display", player,
                "message", message));
    }

    public String resolvePlayerPlaceholders(java.util.UUID uuid, String template) {
        return config.papi().enabled() ? papi.resolve(uuid, template) : template;
    }

    public Component renderChat(String sourceServerId,
                                String sourceDisplayFallback,
                                String playerName,
                                String playerDisplayName,
                                Component message) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        placeholders.put("message", message == null ? Component.empty() : message);
        return render(sourceServerId, CrossConfig.TemplateType.CHAT, placeholders);
    }

    public Component renderJoin(String sourceServerId,
                                String sourceDisplayFallback,
                                String playerName,
                                String playerDisplayName) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        return render(sourceServerId, CrossConfig.TemplateType.JOIN, placeholders);
    }

    public Component renderJoin(java.util.UUID playerUuid,
                                String sourceServerId,
                                String sourceDisplayFallback,
                                String playerName,
                                String playerDisplayName) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        return render(sourceServerId, CrossConfig.TemplateType.JOIN, placeholders, playerUuid);
    }

    public Component renderQuit(String sourceServerId,
                                String sourceDisplayFallback,
                                String playerName,
                                String playerDisplayName) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        return render(sourceServerId, CrossConfig.TemplateType.QUIT, placeholders);
    }

    public Component renderQuit(java.util.UUID playerUuid,
                                String sourceServerId,
                                String sourceDisplayFallback,
                                String playerName,
                                String playerDisplayName) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        return render(sourceServerId, CrossConfig.TemplateType.QUIT, placeholders, playerUuid);
    }

    public Component renderDmSend(String targetServerId,
                                  String targetServerDisplayFallback,
                                  String targetName,
                                  Component message) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(targetServerId, targetServerDisplayFallback));
        placeholders.put("server_id", Component.text(safe(targetServerId)));
        placeholders.put("target", Component.text(TextUtil.cleanName(targetName)));
        placeholders.put("message", message == null ? Component.empty() : message);
        return render(targetServerId, CrossConfig.TemplateType.DM_SEND, placeholders);
    }

    public Component renderDmSend(java.util.UUID viewerUuid,
                                  String targetServerId,
                                  String targetServerDisplayFallback,
                                  String targetName,
                                  Component message) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(targetServerId, targetServerDisplayFallback));
        placeholders.put("server_id", Component.text(safe(targetServerId)));
        placeholders.put("target", Component.text(TextUtil.cleanName(targetName)));
        placeholders.put("message", message == null ? Component.empty() : message);
        return render(targetServerId, CrossConfig.TemplateType.DM_SEND, placeholders, viewerUuid);
    }

    public Component renderDmReceive(java.util.UUID sourceUuid,
                                     String sourceServerId,
                                     String sourceServerDisplayFallback,
                                     String senderName,
                                     Component message) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceServerDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(senderName)));
        placeholders.put("message", message == null ? Component.empty() : message);
        return render(sourceServerId, CrossConfig.TemplateType.DM_RECEIVE, placeholders, sourceUuid);
    }

    public Component renderDmReceive(String sourceServerId,
                                     String sourceServerDisplayFallback,
                                     String senderName,
                                     Component message) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(sourceServerId, sourceServerDisplayFallback));
        placeholders.put("server_id", Component.text(safe(sourceServerId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(senderName)));
        placeholders.put("message", message == null ? Component.empty() : message);
        return render(sourceServerId, CrossConfig.TemplateType.DM_RECEIVE, placeholders);
    }

    public Component renderTab(PresenceEntry entry) {
        return renderTab(entry.uuidOrNull(), entry.serverId, entry.serverDisplayName,
                entry.name, entry.playerDisplayName);
    }

    public Component renderTab(java.util.UUID playerUuid, String serverId, String serverDisplayFallback,
                               String playerName, String playerDisplayName) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(serverId, serverDisplayFallback));
        placeholders.put("server_id", Component.text(safe(serverId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        return render(serverId, CrossConfig.TemplateType.TAB, placeholders, playerUuid);
    }

    public Component renderTab(String serverId, String serverDisplayFallback, String playerName, String playerDisplayName) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server", displayNameComponent(serverId, serverDisplayFallback));
        placeholders.put("server_id", Component.text(safe(serverId)));
        placeholders.put("player", Component.text(TextUtil.cleanName(playerName)));
        placeholders.put("player_display", Component.text(TextUtil.cleanName(playerDisplayName)));
        return render(serverId, CrossConfig.TemplateType.TAB, placeholders);
    }

    public Component renderMotd(int online, int max, int localOnline) {
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("online", Component.text(Integer.toString(online)));
        placeholders.put("max", Component.text(Integer.toString(max)));
        placeholders.put("local_online", Component.text(Integer.toString(localOnline)));
        placeholders.put("server", displayNameComponent(config.serverId(), config.localDisplayName()));
        placeholders.put("server_id", Component.text(config.serverId()));
        return render(config.serverId(), CrossConfig.TemplateType.MOTD, placeholders);
    }

    public boolean hasMotdTemplate() {
        String template = config.template(config.serverId(), CrossConfig.TemplateType.MOTD);
        return template != null && !template.isBlank();
    }

    public Component completionTooltip(String sourceServerId, String sourceDisplayFallback) {
        Component server = displayNameComponent(sourceServerId, sourceDisplayFallback);
        return Component.text("跨服 · ").append(server);
    }

    private Component render(String sourceServerId,
                             CrossConfig.TemplateType type,
                             Map<String, Component> placeholders) {
        return render(sourceServerId, type, placeholders, null);
    }

    private Component render(String sourceServerId,
                             CrossConfig.TemplateType type,
                             Map<String, Component> placeholders,
                             java.util.UUID papiPlayer) {
        CrossConfig current = config;
        String template = current.template(sourceServerId, type);
        if (template == null || template.isBlank()) {
            template = defaultTemplate(type);
        }
        Map<String, String> symbols = current.symbolsFor(sourceServerId);
        TagResolver.Builder resolver = TagResolver.builder();
        for (Map.Entry<String, Component> placeholder : placeholders.entrySet()) {
            resolver.resolver(Placeholder.component(placeholder.getKey(), placeholder.getValue()));
        }
        resolver.tag("symbol", (args, context) -> {
            String key = args.hasNext() ? args.pop().value() : "";
            return Tag.inserting(Component.text(symbols.getOrDefault(key, "")));
        });
        TagResolver tags = resolver.build();
        UnaryOperator<String> resolvePapi = papiPlayer != null && current.papi().enabled() && template.indexOf('%') >= 0
                ? papi.resolver(papiPlayer) : null;
        try {
            return TemplateFormatting.render(miniMessage, template, tags, resolvePapi);
        } catch (RuntimeException error) {
            String warningKey = type.key() + ":" + template;
            if (warnedTemplates.add(warningKey)) {
                plugin.getLogger().warning("无法解析 " + type.key() + " 模板，已使用默认格式: " + error.getMessage());
            }
            return miniMessage.deserialize(defaultTemplate(type), tags);
        }
    }

    private Component displayNameComponent(String sourceServerId, String fallback) {
        CrossConfig current = config;
        String configuredName;
        boolean trusted;
        if (sourceServerId != null && sourceServerId.equals(current.serverId())) {
            configuredName = current.localDisplayName();
            if (configuredName == null || configuredName.isBlank()) {
                configuredName = sourceServerId;
            }
            trusted = true;
        } else {
            CrossConfig.ServerProfile profile =
                    sourceServerId == null ? null : current.servers().get(sourceServerId);
            if (profile != null && profile.displayName() != null && !profile.displayName().isBlank()) {
                configuredName = profile.displayName();
                trusted = true;
            } else {
                // The fallback originates from a remote server and is untrusted:
                // never deserialize it as MiniMessage.
                configuredName = (fallback != null && !fallback.isBlank())
                        ? fallback
                        : (sourceServerId == null ? "" : sourceServerId);
                trusted = false;
            }
        }
        if (!trusted) {
            // Strip any MiniMessage-looking tags from the untrusted fallback so a
            // receiver without an explicit mapping never renders raw tags.
            String plain = configuredName;
            try {
                plain = miniMessage.stripTags(plain);
            } catch (Throwable ignored) {
            }
            return Component.text(TextUtil.cleanName(plain));
        }
        Map<String, String> symbols = current.symbolsFor(sourceServerId);
        String prepared = TemplateFormatting.prepare(configuredName);
        TagResolver.Builder resolver = TagResolver.builder();
        resolver.tag("symbol", (args, context) -> {
            String key = args.hasNext() ? args.pop().value() : "";
            return Tag.inserting(Component.text(symbols.getOrDefault(key, "")));
        });
        try {
            return miniMessage.deserialize(prepared, resolver.build());
        } catch (Throwable error) {
            return Component.text(TextUtil.cleanName(configuredName));
        }
    }

    public String plainDisplayName(String sourceServerId, String fallback) {
        return TextUtil.plain(displayNameComponent(sourceServerId, fallback));
    }

    private String defaultTemplate(CrossConfig.TemplateType type) {
        return switch (type) {
            case CHAT -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_CHAT;
            case KOOK_CHAT -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_KOOK_CHAT;
            case JOIN -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_JOIN;
            case QUIT -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_QUIT;
            case TAB -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_TAB;
            case MOTD -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_MOTD;
            case DM_SEND -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_DM_SEND;
            case DM_RECEIVE -> moe.hammer.cross.server.config.ConfigManager.DEFAULT_DM_RECEIVE;
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
