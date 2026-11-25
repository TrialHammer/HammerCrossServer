package moe.hammer.cross.server.service;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.CrossEvent;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.text.FormatService;
import moe.hammer.cross.server.text.PlayerListCache;
import moe.hammer.cross.server.text.RichChatRenderer;
import moe.hammer.cross.server.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-server private messaging. When {@code dm.override-vanilla=true} the
 * plugin handles BOTH local and cross-server whispers itself; otherwise it only
 * intercepts whispers whose target is on another server. Messages support
 * clickable links and the held-item placeholder, and every delivered message is
 * mirrored to each involved server's console.</p>
 */
public final class PrivateMessageService {
    public enum Result {
        HANDLED,
        NOT_HANDLED,
        NOT_FOUND,
        SELF
    }

    public record ReplyTarget(String name, String uuid, String serverId, String displayName) {
    }

    private final HammerCrossServer plugin;
    private final PlayerListCache cache;
    private final FormatService formatService;
    private final RichChatRenderer richChat = new RichChatRenderer();
    private final Map<UUID, ReplyTarget> lastReply = new ConcurrentHashMap<>();
    private volatile CrossConfig config;

    public PrivateMessageService(HammerCrossServer plugin, PlayerListCache cache, FormatService formatService) {
        this.plugin = plugin;
        this.cache = cache;
        this.formatService = formatService;
    }

    public void start(CrossConfig config) {
        this.config = config;
    }

    public ReplyTarget lastReply(UUID playerUuid) {
        return playerUuid == null ? null : lastReply.get(playerUuid);
    }

    public void clear(UUID playerUuid) {
        if (playerUuid != null) {
            lastReply.remove(playerUuid);
        }
    }

    private boolean enabled(CrossConfig current) {
        return current != null && current.sync().dm() && current.dm().enabled();
    }

    public Result tryDeliver(Player sender, String targetName, String message) {
        CrossConfig current = config;
        if (!enabled(current) || targetName == null || targetName.isBlank()) {
            return Result.NOT_HANDLED;
        }
        if (sender.getName().equalsIgnoreCase(targetName)) {
            return Result.SELF;
        }
        Player local = Bukkit.getPlayerExact(targetName);
        if (local != null) {
            if (local.getUniqueId().equals(sender.getUniqueId())) {
                return Result.SELF;
            }
            if (!current.dm().overrideVanilla()) {
                return Result.NOT_HANDLED;
            }
            deliverLocal(sender, local, message, current);
            return Result.HANDLED;
        }
        PresenceEntry entry = cache.findUnique(targetName);
        if (entry == null) {
            return Result.NOT_FOUND;
        }
        if (current.serverId().equals(entry.serverId)) {
            return Result.NOT_FOUND;
        }
        if (entry.uuid != null && entry.uuid.equalsIgnoreCase(sender.getUniqueId().toString())) {
            return Result.SELF;
        }
        sendCrossServer(sender, entry, message, current);
        return Result.HANDLED;
    }

    public Result tryReply(Player sender, String message) {
        CrossConfig current = config;
        if (!enabled(current)) {
            return Result.NOT_HANDLED;
        }
        ReplyTarget reply = lastReply.get(sender.getUniqueId());
        if (reply == null) {
            return Result.NOT_HANDLED;
        }
        Player local = resolveLocal(reply);
        if (local != null) {
            if (!current.dm().overrideVanilla()) {
                return Result.NOT_HANDLED;
            }
            deliverLocal(sender, local, message, current);
            return Result.HANDLED;
        }
        PresenceEntry entry = cache.findUnique(reply.name());
        if (entry == null || current.serverId().equals(entry.serverId)) {
            return Result.NOT_FOUND;
        }
        sendCrossServer(sender, entry, message, current);
        return Result.HANDLED;
    }

    private void deliverLocal(Player sender, Player target, String rawMessage, CrossConfig current) {
        String message = TextUtil.cleanChat(rawMessage, current.chat().maxLength());
        if (message.isEmpty()) {
            return;
        }
        RichChatRenderer.Rendered content = richChat.renderLocal(message, sender, current);
        target.sendMessage(formatService.renderDmReceive(
                sender.getUniqueId(), current.serverId(), current.localDisplayName(),
                sender.getName(), content.component()));
        if (current.dm().senderConfirm()) {
            sender.sendMessage(formatService.renderDmSend(
                    sender.getUniqueId(), current.serverId(), current.localDisplayName(),
                    target.getName(), content.component()));
        }
        lastReply.put(target.getUniqueId(), new ReplyTarget(
                sender.getName(), sender.getUniqueId().toString(), current.serverId(), current.localDisplayName()));
        logDm(current, sender.getName(), target.getName(), content.plainText());
    }

    private void sendCrossServer(Player sender, PresenceEntry target, String rawMessage, CrossConfig current) {
        String message = TextUtil.cleanChat(rawMessage, current.chat().maxLength());
        if (message.isEmpty()) {
            return;
        }
        if (target.uuid == null || target.uuid.isBlank()
                || target.serverId == null || target.serverId.isBlank()) {
            return;
        }
        RichChatRenderer.Rendered content = richChat.renderLocal(message, sender, current);
        String itemData = current.chat().item().crossServer() ? content.itemData() : null;
        CrossEvent event = CrossEvent.privateMessage(
                current.serverId(), current.localDisplayName(),
                sender.getUniqueId().toString(), sender.getName(), sender.getName(),
                target.uuid, target.name, target.serverId, message, itemData);
        plugin.redis().publish(event);

        if (current.dm().senderConfirm()) {
            sender.sendMessage(formatService.renderDmSend(
                    sender.getUniqueId(), target.serverId, target.serverDisplayName,
                    target.name, content.component()));
        }
        logDm(current, sender.getName(), target.name + "@" + target.serverId, content.plainText());
    }

    public void handleRemoteDm(CrossEvent event) {
        CrossConfig current = config;
        if (!enabled(current)) {
            return;
        }
        if (event.targetServerId == null || !event.targetServerId.equals(current.serverId())) {
            return;
        }
        Player target = resolveLocal(event.targetUuid, event.targetName);
        if (target == null) {
            plugin.getLogger().fine("跨服私聊投递失败：目标不在线 " + event.targetName);
            return;
        }
        String message = TextUtil.cleanChat(event.message, current.chat().maxLength());
        if (message.isEmpty()) {
            return;
        }
        RichChatRenderer.Rendered content = richChat.renderRemote(message, event.chatItem, current);
        target.sendMessage(formatService.renderDmReceive(
                parseUuid(event.playerUuid), event.serverId, event.serverDisplayName,
                event.playerName, content.component()));
        lastReply.put(target.getUniqueId(), new ReplyTarget(
                event.playerName, event.playerUuid, event.serverId, event.serverDisplayName));
        logDm(current, event.playerName + "@" + event.serverId, target.getName(), content.plainText());
    }

    private void logDm(CrossConfig current, String from, String to, String message) {
        if (current.dm().logToConsole()) {
            plugin.getLogger().info("[私聊][" + current.serverId() + "] " + from + " -> " + to + ": " + message);
        }
    }

    private Player resolveLocal(ReplyTarget reply) {
        if (reply.uuid() != null && !reply.uuid().isBlank()) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(reply.uuid()));
                if (player != null) {
                    return player;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return reply.name() == null ? null : Bukkit.getPlayerExact(reply.name());
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

    private Player resolveLocal(String uuid, String name) {
        if (uuid != null && !uuid.isBlank()) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(uuid));
                if (player != null) {
                    return player;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (name != null && !name.isBlank()) {
            return Bukkit.getPlayerExact(name);
        }
        return null;
    }
}
