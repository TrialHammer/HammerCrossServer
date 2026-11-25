package moe.hammer.cross.server.service;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.CrossEvent;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.model.PresenceSnapshot;
import moe.hammer.cross.server.model.ServerEntry;
import moe.hammer.cross.server.model.TextureData;
import moe.hammer.cross.server.redis.RedisManager;
import moe.hammer.cross.server.text.PlayerListCache;
import moe.hammer.cross.server.util.PlayerIdentity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PresenceService {
    private final HammerCrossServer plugin;
    private final RedisManager redis;
    private final PlayerListCache cache;
    private final TabSyncService tabSyncService;

    private volatile CrossConfig config;
    private BukkitTask heartbeatTask;
    private BukkitTask reconcileTask;

    public PresenceService(HammerCrossServer plugin,
                           RedisManager redis,
                           PlayerListCache cache,
                           TabSyncService tabSyncService) {
        this.plugin = plugin;
        this.redis = redis;
        this.cache = cache;
        this.tabSyncService = tabSyncService;
    }

    public void start(CrossConfig config) {
        stopTasks();
        this.config = config;
        refreshLocalPlayerCache();
        long heartbeatTicks = Math.max(20L, config.presence().heartbeatSeconds() * 20L);
        long reconcileTicks = Math.max(20L, config.presence().reconcileSeconds() * 20L);
        heartbeatTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::heartbeatTick, 1L, heartbeatTicks);
        reconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcileTick, 20L, reconcileTicks);
    }

    public void stopTasks() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        if (reconcileTask != null) {
            reconcileTask.cancel();
            reconcileTask = null;
        }
    }

    public CrossConfig config() {
        return config;
    }

    public PresenceEntry toPresence(Player player) {
        CrossConfig current = config;
        PlayerProfile profile = player.getPlayerProfile();
        List<TextureData> textures = new ArrayList<>();
        try {
            for (ProfileProperty property : profile.getProperties()) {
                if ("textures".equalsIgnoreCase(property.getName())) {
                    textures.add(new TextureData(property.getName(), property.getValue(), property.getSignature()));
                }
            }
        } catch (Throwable ignored) {
        }
        // Use the raw profile name here. Tab/display formatting is applied by the
        // receiving server, so we must not feed our own rendered tab name back in.
        String displayName = player.getName();
        return new PresenceEntry(
                current.serverId(),
                current.localDisplayName(),
                player.getUniqueId(),
                player.getName(),
                displayName,
                System.currentTimeMillis(),
                Math.max(0, player.getPing()),
                player.getGameMode().name(),
                true,
                textures
        );
    }

    public void onJoin(Player player, boolean silent) {
        PresenceEntry entry = toPresence(player);
        cache.put(entry);
        cache.setLocalCounts(plugin.getServer().getOnlinePlayers().size(), plugin.getServer().getMaxPlayers());
        redis.upsertPresence(entry, null);
        redis.upsertServer(localServerEntry());
        CrossEvent event = CrossEvent.join(config.serverId(), config.localDisplayName(), entry, silent);
        if (!silent) {
            event.bedrockUsername = PlayerIdentity.bedrockUsername(player.getUniqueId());
        }
        plugin.publishPublicEvent(event);
        tabSyncService.applyLocalName(player);
    }

    public void onQuit(Player player, boolean silent) {
        PresenceEntry entry = toPresence(player);
        cache.remove(entry.serverId, player.getUniqueId());
        cache.setLocalCounts(Math.max(0, plugin.getServer().getOnlinePlayers().size() - 1), plugin.getServer().getMaxPlayers());
        redis.removePresence(entry.serverId, player.getUniqueId());
        redis.upsertServer(localServerEntry());
        CrossEvent event = CrossEvent.quit(config.serverId(), config.localDisplayName(),
                player.getUniqueId().toString(), player.getName(), entry.playerDisplayName, silent);
        if (!silent) {
            event.bedrockUsername = PlayerIdentity.bedrockUsername(player.getUniqueId());
        }
        plugin.publishPublicEvent(event);
        tabSyncService.removeRemoteFromAll(player.getUniqueId());
    }

    public void refreshLocalPlayerCache() {
        // May be called from the Redis reconnect thread; always touch player
        // state on the server's main (global region) thread.
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, this::doRefreshLocalPlayerCache);
    }

    private void doRefreshLocalPlayerCache() {
        CrossConfig current = config;
        if (current == null) {
            return;
        }
        List<PresenceEntry> local = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PresenceEntry entry = toPresence(player);
            local.add(entry);
            cache.put(entry);
            redis.upsertPresence(entry, null);
        }
        cache.setLocalCounts(local.size(), plugin.getServer().getMaxPlayers());
        redis.upsertServer(localServerEntry());
        tabSyncService.refreshLocalNames();
    }

    public ServerEntry localServerEntry() {
        return new ServerEntry(
                config.serverId(),
                config.localDisplayName(),
                plugin.getServer().getMaxPlayers(),
                plugin.getServer().getOnlinePlayers().size(),
                System.currentTimeMillis()
        );
    }

    public void acceptSnapshot(PresenceSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        CrossConfig current = config;
        List<PresenceEntry> valid = new ArrayList<>();
        // Local players are authoritative on this server: keep them in the cache
        // so status/players/completion paths never lose them on a reconcile.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            valid.add(toPresence(player));
        }
        long staleBefore = System.currentTimeMillis() - current.presence().staleSeconds() * 1000L;
        for (PresenceEntry entry : snapshot.players().values()) {
            if (entry.updatedAt > 0 && entry.updatedAt < staleBefore) {
                continue;
            }
            if (current.serverId().equals(entry.serverId)) {
                continue;
            }
            valid.add(entry);
        }
        cache.replaceAll(valid, snapshot.servers().values());
        tabSyncService.reconcileRemoteEntries();
    }

    private void heartbeatTick() {
        CrossConfig current = config;
        if (current == null) {
            return;
        }
        // Refresh every local player's presence timestamp so long-online players
        // are never filtered out as stale by other servers.
        List<PresenceEntry> entries = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PresenceEntry entry = toPresence(player);
            cache.put(entry);
            entries.add(entry);
        }
        cache.setLocalCounts(entries.size(), plugin.getServer().getMaxPlayers());
        redis.heartbeat(current.serverId(), Math.max(current.presence().staleSeconds(), current.presence().heartbeatSeconds() * 3));
        redis.upsertPresenceBatch(entries);
        redis.upsertServer(localServerEntry());
    }

    private void reconcileTick() {
        CrossConfig current = config;
        if (current == null) {
            return;
        }
        // The loader runs on the Redis executor; player state must be read on the
        // server's main (global region) thread.
        redis.loadSnapshot(snapshot ->
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> acceptSnapshot(snapshot)));
    }

    public void shutdown() {
        stopTasks();
        CrossConfig current = config;
        if (current == null) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PresenceEntry entry = toPresence(player);
            redis.publish(CrossEvent.quit(current.serverId(), current.localDisplayName(),
                    player.getUniqueId().toString(), player.getName(), entry.playerDisplayName, true));
            redis.removePresence(current.serverId(), player.getUniqueId());
        }
        redis.publish(CrossEvent.serverOffline(current.serverId(), current.localDisplayName()));
        redis.removeServer(current.serverId());
    }
}
