package moe.hammer.cross.server.listener;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.service.PrivateMessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;

/**
 * Intercepts whisper commands. With {@code dm.override-vanilla=true} the plugin
 * delivers both local and cross-server whispers itself; otherwise it only routes
 * whispers whose target is remote and lets the native command handle local ones.
 */
public final class PrivateMessageListener implements Listener {
    private final HammerCrossServer plugin;
    private final PrivateMessageService service;

    public PrivateMessageListener(HammerCrossServer plugin, PrivateMessageService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        CrossConfig config = plugin.config();
        if (config == null || !config.sync().dm() || !config.dm().enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("hammercross.dm")) {
            return;
        }
        String raw = event.getMessage();
        if (raw == null || !raw.startsWith("/")) {
            return;
        }
        String body = raw.substring(1);
        int space = indexOfWhitespace(body);
        String label = space < 0 ? body : body.substring(0, space);
        String rest = space < 0 ? "" : body.substring(space + 1);
        label = normalizeLabel(label);
        if (label.isEmpty()) {
            return;
        }
        if (config.dm().commands().contains(label)) {
            handleSend(event, player, rest);
        } else if (config.dm().replyCommands().contains(label)) {
            handleReply(event, player, rest);
        }
    }

    private void handleSend(PlayerCommandPreprocessEvent event, Player player, String rest) {
        String args = rest.strip();
        if (args.isEmpty()) {
            return;
        }
        int space = indexOfWhitespace(args);
        if (space < 0) {
            // Only a target, no message: let vanilla print usage.
            return;
        }
        String targetName = args.substring(0, space).trim();
        String message = args.substring(space + 1).trim();
        if (targetName.isEmpty() || message.isEmpty()) {
            return;
        }
        switch (service.tryDeliver(player, targetName, message)) {
            case HANDLED -> event.setCancelled(true);
            case SELF -> {
                event.setCancelled(true);
                plugin.messages().send(player, "dm-self");
            }
            case NOT_FOUND -> {
                // Let the native command report "player not found".
            }
            case NOT_HANDLED -> {
                // Let the native command / another whisper plugin handle it.
            }
        }
    }

    private void handleReply(PlayerCommandPreprocessEvent event, Player player, String rest) {
        String message = rest.strip();
        if (message.isEmpty()) {
            return;
        }
        switch (service.tryReply(player, message)) {
            case HANDLED -> event.setCancelled(true);
            case SELF -> {
                event.setCancelled(true);
                plugin.messages().send(player, "dm-self");
            }
            case NOT_FOUND -> {
                event.setCancelled(true);
                PrivateMessageService.ReplyTarget reply = service.lastReply(player.getUniqueId());
                String name = reply == null || reply.name() == null ? "" : reply.name();
                plugin.messages().send(player, "dm-target-offline", Map.of("target", name));
            }
            case NOT_HANDLED -> {
                // No known conversation: let vanilla / a whisper plugin handle it.
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clear(event.getPlayer().getUniqueId());
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeLabel(String label) {
        String value = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value;
    }
}
