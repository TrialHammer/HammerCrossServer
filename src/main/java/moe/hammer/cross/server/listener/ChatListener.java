package moe.hammer.cross.server.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.service.ChatSyncService;
import moe.hammer.cross.server.text.FormatService;
import moe.hammer.cross.server.text.RichChatRenderer;
import moe.hammer.cross.server.util.TextUtil;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles player public chat. With {@code chat.override-vanilla=true} (default)
 * the plugin cancels the vanilla broadcast entirely and sends its own formatted
 * message to local players plus the other servers. Messages support clickable
 * links and the "held item" placeholder, and the full text is mirrored to this
 * server's console.
 */
public final class ChatListener implements Listener {
    private final HammerCrossServer plugin;
    private final ChatSyncService chatSyncService;
    private final FormatService formatService;
    private final RichChatRenderer richChat = new RichChatRenderer();

    public ChatListener(HammerCrossServer plugin, ChatSyncService chatSyncService, FormatService formatService) {
        this.plugin = plugin;
        this.chatSyncService = chatSyncService;
        this.formatService = formatService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        CrossConfig config = plugin.config();
        if (config == null || !config.sync().chat()) {
            return;
        }
        Player player = event.getPlayer();
        String message = TextUtil.cleanChatPreservingItems(TextUtil.plain(event.message()), config.chat().maxLength());
        if (message.isEmpty()) {
            return;
        }
        String serverId = config.serverId();
        String localDisplay = config.localDisplayName();
        String playerName = player.getName();
        UUID uuid = player.getUniqueId();

        if (config.chat().overrideVanilla()) {
            // Take over completely: cancel vanilla so there is no duplicate, then
            // deliver our own rich message locally and to the other servers.
            event.setCancelled(true);
            List<Audience> viewers = new ArrayList<>();
            for (Audience audience : event.viewers()) {
                if (audience instanceof Player) {
                    viewers.add(audience);
                }
            }
            // Read the held item and send messages on the main thread.
            Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                RichChatRenderer.Rendered content = richChat.renderLocal(message, player, config);
                Component rendered = config.chat().formatLocal()
                        ? formatService.renderChat(uuid, serverId, localDisplay, playerName, playerName, content.component())
                        : Component.text(playerName + ": ").append(content.component());
                for (Audience viewer : viewers) {
                    viewer.sendMessage(rendered);
                }
                if (config.chat().logToConsole()) {
                    plugin.getLogger().info("[聊天][" + serverId + "] " + playerName + ": " + content.plainText());
                }
                chatSyncService.publishChat(uuid.toString(), playerName, playerName, message, itemData(config, content));
            });
        } else {
            // Legacy mode: we are on the async chat thread, so we do not touch the
            // player's inventory here (item display requires override-vanilla).
            RichChatRenderer.Rendered content = richChat.renderLocal(message, (org.bukkit.entity.Player) null, config);
            if (config.chat().formatLocal()) {
                Component rendered = formatService.renderChat(uuid, serverId, localDisplay, playerName, playerName, content.component());
                event.renderer((source, sourceDisplayName, sourceMessage, audience) -> rendered);
            }
            chatSyncService.publishChat(uuid.toString(), playerName, playerName, message, itemData(config, content));
        }
    }

    private String itemData(CrossConfig config, RichChatRenderer.Rendered content) {
        return config.chat().item().crossServer() ? content.itemData() : null;
    }
}
