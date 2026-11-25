package moe.hammer.cross.server.listener;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.service.MotdService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

public final class PingListener implements Listener {
    private final HammerCrossServer plugin;
    private final MotdService motdService;

    public PingListener(HammerCrossServer plugin, MotdService motdService) {
        this.plugin = plugin;
        this.motdService = motdService;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        if (!plugin.config().sync().motd()) {
            return;
        }
        motdService.applyMotd(event);
        if (plugin.config().motd().crossServerSample()) {
            List<PresenceEntry> sample = motdService.sample();
            if (!sample.isEmpty()) {
                List<PaperServerListPingEvent.ListedPlayerInfo> listed = new ArrayList<>(event.getListedPlayers());
                java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
                for (PaperServerListPingEvent.ListedPlayerInfo info : listed) {
                    if (info.id() != null) {
                        seen.add(info.id());
                    }
                }
                for (PresenceEntry entry : sample) {
                    java.util.UUID uuid = entry.uuidOrNull();
                    if (uuid == null || entry.name == null || entry.name.isBlank() || !seen.add(uuid)) {
                        continue;
                    }
                    listed.add(new PaperServerListPingEvent.ListedPlayerInfo(entry.name, uuid));
                }
                try {
                    event.getListedPlayers().clear();
                    event.getListedPlayers().addAll(listed);
                } catch (UnsupportedOperationException ignored) {
                    // Some Paper builds expose an immutable sample list; numeric
                    // count and MOTD synchronization still work in that case.
                }
            }
        }
    }
}
