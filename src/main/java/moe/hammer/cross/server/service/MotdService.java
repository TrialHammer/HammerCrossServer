package moe.hammer.cross.server.service;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.text.FormatService;
import moe.hammer.cross.server.text.PlayerListCache;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

public final class MotdService {
    private final HammerCrossServer plugin;
    private final PlayerListCache cache;
    private final FormatService formatService;
    private volatile CrossConfig config;

    public MotdService(HammerCrossServer plugin, PlayerListCache cache, FormatService formatService) {
        this.plugin = plugin;
        this.cache = cache;
        this.formatService = formatService;
    }

    public void start(CrossConfig config) {
        this.config = config;
    }

    public int onlineCount() {
        CrossConfig current = config;
        if (current == null || !current.sync().motd() || !current.motd().syncPlayerCount()) {
            return Bukkit.getOnlinePlayers().size();
        }
        return Math.max(Bukkit.getOnlinePlayers().size(), cache.totalUniqueOnline(current));
    }

    public int maxPlayers() {
        CrossConfig current = config;
        if (current == null || !current.sync().motd() || !current.motd().syncMaxPlayers()) {
            return Bukkit.getMaxPlayers();
        }
        return switch (current.motd().maxPlayersMode()) {
            case "local" -> Bukkit.getMaxPlayers();
            case "fixed" -> current.motd().fixedMaxPlayers();
            default -> Math.max(Bukkit.getMaxPlayers(), cache.totalMaxPlayers(current));
        };
    }

    public void applyMotd(com.destroystokyo.paper.event.server.PaperServerListPingEvent event) {
        CrossConfig current = config;
        if (current == null || !current.sync().motd()) {
            return;
        }
        int online = onlineCount();
        int max = maxPlayers();
        if (current.motd().syncPlayerCount()) {
            event.setNumPlayers(online);
        }
        if (current.motd().syncMaxPlayers()) {
            event.setMaxPlayers(max);
        }
        if (current.motd().overrideMotd() && formatService.hasMotdTemplate()) {
            event.motd(formatService.renderMotd(online, max, Bukkit.getOnlinePlayers().size()));
        }
    }

    public List<PresenceEntry> sample() {
        CrossConfig current = config;
        if (current == null || !current.motd().crossServerSample() || current.motd().sampleSize() <= 0) {
            return List.of();
        }
        List<PresenceEntry> entries = new ArrayList<>(cache.uniqueOnline(current));
        int max = Math.min(current.motd().sampleSize(), entries.size());
        return entries.subList(0, max);
    }
}
