package moe.hammer.cross.server.listener;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.service.JoinQuitService;
import moe.hammer.cross.server.service.PresenceService;
import moe.hammer.cross.server.service.TabSyncService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitListener implements Listener {
    private final HammerCrossServer plugin;
    private final PresenceService presenceService;
    private final JoinQuitService joinQuitService;
    private final TabSyncService tabSyncService;

    public JoinQuitListener(HammerCrossServer plugin,
                            PresenceService presenceService,
                            JoinQuitService joinQuitService,
                            TabSyncService tabSyncService) {
        this.plugin = plugin;
        this.presenceService = presenceService;
        this.joinQuitService = joinQuitService;
        this.tabSyncService = tabSyncService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        CrossConfig config = plugin.config();
        boolean silent = event.joinMessage() == null;
        if (!silent && config.joinQuit().broadcastLocal()) {
            // Replace the vanilla message with our template so local players get
            // exactly one (formatted) notification.
            event.joinMessage(plugin.formatService().renderJoin(event.getPlayer().getUniqueId(),
                    config.serverId(), config.localDisplayName(),
                    event.getPlayer().getName(), event.getPlayer().getName()));
        } else if (config.joinQuit().suppressVanilla()) {
            event.joinMessage(null);
        }
        presenceService.onJoin(event.getPlayer(), silent);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> tabSyncService.sendAllRemoteTo(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        CrossConfig config = plugin.config();
        boolean silent = event.quitMessage() == null;
        if (!silent && config.joinQuit().broadcastLocal()) {
            event.quitMessage(plugin.formatService().renderQuit(event.getPlayer().getUniqueId(),
                    config.serverId(), config.localDisplayName(),
                    event.getPlayer().getName(), event.getPlayer().getName()));
        } else if (config.joinQuit().suppressVanilla()) {
            event.quitMessage(null);
        }
        presenceService.onQuit(event.getPlayer(), silent);
        tabSyncService.clearViewer(event.getPlayer().getUniqueId());
    }
}
