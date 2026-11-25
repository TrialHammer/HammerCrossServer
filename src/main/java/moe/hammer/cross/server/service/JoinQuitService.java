package moe.hammer.cross.server.service;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.CrossEvent;
import moe.hammer.cross.server.text.FormatService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

public final class JoinQuitService {
    private final HammerCrossServer plugin;
    private final FormatService formatService;
    private volatile CrossConfig config;

    public JoinQuitService(HammerCrossServer plugin, FormatService formatService) {
        this.plugin = plugin;
        this.formatService = formatService;
    }

    public void start(CrossConfig config) {
        this.config = config;
    }

    public void broadcastRemoteJoin(CrossEvent event) {
        CrossConfig current = config;
        if (current == null || !current.sync().join() || event.silent) {
            return;
        }
        Component rendered = formatService.renderJoin(parseUuid(event.playerUuid),
                event.serverId, event.serverDisplayName, event.playerName, event.playerDisplayName);
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, () -> Bukkit.getServer().broadcast(rendered));
    }

    public void broadcastRemoteQuit(CrossEvent event) {
        CrossConfig current = config;
        if (current == null || !current.sync().quit() || event.silent) {
            return;
        }
        Component rendered = formatService.renderQuit(parseUuid(event.playerUuid),
                event.serverId, event.serverDisplayName, event.playerName, event.playerDisplayName);
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, () -> Bukkit.getServer().broadcast(rendered));
    }

    private static java.util.UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
