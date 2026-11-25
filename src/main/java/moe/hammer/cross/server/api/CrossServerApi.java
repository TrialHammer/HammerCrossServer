package moe.hammer.cross.server.api;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.model.PresenceEntry;

import java.util.Collection;
import java.util.List;

/**
 * Small read-only facade for other plugins on the same server.
 * Obtain it with {@code HammerCrossServer.api()} via Bukkit's ServicesManager
 * or the static {@link #instance()} accessor.
 */
public final class CrossServerApi {
    private static CrossServerApi instance;
    private final HammerCrossServer plugin;

    public CrossServerApi(HammerCrossServer plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static CrossServerApi instance() {
        return instance;
    }

    public Collection<PresenceEntry> allPlayers() {
        return plugin.playerCache().all();
    }

    public List<PresenceEntry> remotePlayers() {
        return plugin.playerCache().remote(plugin.config());
    }

    public int totalOnline() {
        return plugin.playerCache().totalUniqueOnline(plugin.config());
    }

    public boolean isConnected() {
        return plugin.redis().isConnected();
    }
}
