package moe.hammer.cross.server.service;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.CrossEvent;
import moe.hammer.cross.server.text.FormatService;
import moe.hammer.cross.server.text.RichChatRenderer;
import moe.hammer.cross.server.util.TextUtil;
import net.kyori.adventure.text.Component;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class ChatSyncService {
    private final HammerCrossServer plugin;
    private final FormatService formatService;
    private final RichChatRenderer richChat = new RichChatRenderer();
    private volatile CrossConfig config;

    public ChatSyncService(HammerCrossServer plugin, FormatService formatService) {
        this.plugin = plugin;
        this.formatService = formatService;
    }

    public void start(CrossConfig config) {
        this.config = config;
    }

    public void publishChat(String playerUuid, String playerName, String playerDisplayName,
                            String message, String itemData) {
        CrossConfig current = config;
        if (current == null || !current.sync().chat()) {
            return;
        }
        String cleaned = TextUtil.cleanChatPreservingItems(message, current.chat().maxLength());
        if (cleaned.isEmpty()) {
            return;
        }
        CrossEvent event = CrossEvent.chat(current.serverId(), current.localDisplayName(),
                playerUuid, playerName, playerDisplayName, cleaned, itemData);
        plugin.publishPublicEvent(event);
    }

    public void handleRemoteChat(CrossEvent event) {
        CrossConfig current = config;
        if (current == null || !current.sync().chat()) {
            return;
        }
        String text = TextUtil.cleanChatPreservingItems(event.message, current.chat().maxLength());
        if (text.isEmpty()) {
            return;
        }
        // Deserialize any remote item payload on the main thread only.
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            RichChatRenderer.Rendered content = richChat.renderRemote(text, event.chatItem, current);
            UUID author = parseUuid(event.playerUuid);
            Component rendered = formatService.renderChat(
                    author,
                    event.serverId,
                    event.serverDisplayName,
                    event.playerName,
                    event.playerDisplayName,
                    content.component()
            );
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.sendMessage(rendered);
            }
            if (current.chat().logToConsole()) {
                plugin.getLogger().info("[聊天][" + event.serverId + "] " + event.playerName + ": " + content.plainText());
            }
        });
    }

    public void handleKookChat(CrossEvent event) {
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            CrossConfig current = config;
            if (current == null || !current.sync().chat()) {
                return;
            }
            String text = TextUtil.cleanChat(event.message, current.chat().maxLength());
            if (text.isEmpty()) {
                return;
            }
            String nickname = TextUtil.cleanName(event.playerName);
            Component rendered = formatService.renderKookChat(event.serverId, nickname,
                    richChat.renderExternalText(text, current));
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.sendMessage(rendered);
            }
            if (current.chat().logToConsole()) {
                plugin.getLogger().info("[聊天][KOOK] " + nickname + ": " + text);
            }
        });
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
