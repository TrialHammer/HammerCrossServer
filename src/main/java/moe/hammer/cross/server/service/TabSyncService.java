package moe.hammer.cross.server.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.model.TextureData;
import moe.hammer.cross.server.text.FormatService;
import moe.hammer.cross.server.text.PlayerListCache;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TabSyncService {
    private final HammerCrossServer plugin;
    private final PlayerListCache cache;
    private final FormatService formatService;
    private final Map<UUID, Set<UUID>> sentByViewer = new HashMap<>();
    private volatile CrossConfig config;

    public TabSyncService(HammerCrossServer plugin, PlayerListCache cache, FormatService formatService) {
        this.plugin = plugin;
        this.cache = cache;
        this.formatService = formatService;
    }

    public void start(CrossConfig config) {
        this.config = config;
        sentByViewer.clear();
        reconcileRemoteEntries();
        refreshLocalNames();
    }

    public void reconcileRemoteEntries() {
        CrossConfig current = config;
        if (current == null || !current.sync().tab()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Map<UUID, PresenceEntry> remote = new HashMap<>();
            for (PresenceEntry entry : cache.remote(current)) {
                UUID uuid = entry.uuidOrNull();
                if (uuid != null) {
                    remote.put(uuid, entry);
                }
            }
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                Set<UUID> known = sentByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());
                List<UUID> remove = new ArrayList<>();
                for (UUID uuid : known) {
                    if (!remote.containsKey(uuid)) {
                        remove.add(uuid);
                    }
                }
                if (!remove.isEmpty()) {
                    sendRemove(viewer, remove);
                    known.removeAll(remove);
                }
                List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> add = new ArrayList<>();
                List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> update = new ArrayList<>();
                for (PresenceEntry entry : remote.values()) {
                    UUID uuid = entry.uuidOrNull();
                    if (uuid == null || uuid.equals(viewer.getUniqueId())) {
                        continue;
                    }
                    if (known.contains(uuid)) {
                        update.add(toPlayerInfo(entry));
                    } else {
                        add.add(toPlayerInfo(entry));
                    }
                }
                if (!add.isEmpty()) {
                    sendUpdate(viewer, add, false);
                }
                if (!update.isEmpty()) {
                    sendUpdate(viewer, update, true);
                }
                for (UUID uuid : remote.keySet()) {
                    if (!uuid.equals(viewer.getUniqueId())) {
                        known.add(uuid);
                    }
                }
            }
        });
    }

    public void sendAllRemoteTo(Player viewer) {
        CrossConfig current = config;
        if (current == null || !current.sync().tab()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>();
            Set<UUID> known = sentByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());
            for (PresenceEntry entry : cache.remote(current)) {
                UUID uuid = entry.uuidOrNull();
                if (uuid == null || uuid.equals(viewer.getUniqueId())) {
                    continue;
                }
                entries.add(toPlayerInfo(entry));
                known.add(uuid);
            }
            if (!entries.isEmpty()) {
                sendUpdate(viewer, entries, false);
            }
        });
    }

    public void removeRemoteFromAll(UUID remoteUuid) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                Set<UUID> known = sentByViewer.get(viewer.getUniqueId());
                if (known != null && known.remove(remoteUuid)) {
                    sendRemove(viewer, List.of(remoteUuid));
                }
            }
        });
    }

    public void removeServerFromAll(String serverId) {
        // Capture the UUIDs synchronously: the caller may clear the cache right
        // after this returns, and the packet task runs on a later tick.
        List<UUID> removed = new ArrayList<>();
        for (PresenceEntry entry : cache.all()) {
            if (serverId.equals(entry.serverId)) {
                UUID uuid = entry.uuidOrNull();
                if (uuid != null) {
                    removed.add(uuid);
                }
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                Set<UUID> known = sentByViewer.get(viewer.getUniqueId());
                if (known == null) {
                    continue;
                }
                List<UUID> toRemove = new ArrayList<>();
                for (UUID uuid : removed) {
                    if (known.remove(uuid)) {
                        toRemove.add(uuid);
                    }
                }
                if (!toRemove.isEmpty()) {
                    sendRemove(viewer, toRemove);
                }
            }
        });
    }

    public void refreshDisplayNames() {
        CrossConfig current = config;
        if (current == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!current.sync().tab()) {
                return;
            }
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                Set<UUID> known = sentByViewer.get(viewer.getUniqueId());
                if (known == null || known.isEmpty()) {
                    continue;
                }
                List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> updates = new ArrayList<>();
                for (PresenceEntry entry : cache.remote(current)) {
                    UUID uuid = entry.uuidOrNull();
                    if (uuid != null && known.contains(uuid)) {
                        updates.add(toPlayerInfo(entry));
                    }
                }
                if (!updates.isEmpty()) {
                    sendUpdate(viewer, updates, true);
                }
            }
        });
    }

    private void sendUpdate(Player viewer, List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries, boolean updateOnly) {
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = updateOnly
                ? EnumSet.of(
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER)
                : EnumSet.of(
                        WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER);
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(actions, entries);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private void sendRemove(Player viewer, List<UUID> uuids) {
        if (uuids.isEmpty()) {
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerPlayerInfoRemove(uuids));
    }

    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo toPlayerInfo(PresenceEntry entry) {
        List<TextureProperty> textures = new ArrayList<>();
        for (TextureData texture : entry.textures) {
            if (texture == null || texture.value == null || texture.value.isBlank()) {
                continue;
            }
            textures.add(new TextureProperty(texture.name == null ? "textures" : texture.name,
                    texture.value, texture.signature));
        }
        UserProfile profile = new UserProfile(entry.uuidOrNull(), entry.name, textures);
        net.kyori.adventure.text.Component display = formatService.renderTab(entry);
        GameMode gameMode = parseGameMode(entry.gameMode);
        CrossConfig current = config;
        return new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile,
                true,
                current == null ? 0 : current.tab().latency(),
                gameMode,
                display,
                null
        );
    }

    private GameMode parseGameMode(String value) {
        if (value == null) {
            return GameMode.SURVIVAL;
        }
        try {
            return GameMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return GameMode.SURVIVAL;
        }
    }

    public void refreshLocalNames() {
        CrossConfig current = config;
        if (current == null) {
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            boolean format = current.sync().tab() && current.tab().formatLocalPlayers();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                applyLocalName(player, current, format);
            }
        });
    }

    public void applyLocalName(Player player) {
        CrossConfig current = config;
        if (current == null || player == null) {
            return;
        }
        applyLocalName(player, current, current.sync().tab() && current.tab().formatLocalPlayers());
    }

    private void applyLocalName(Player player, CrossConfig current, boolean format) {
        if (format) {
            net.kyori.adventure.text.Component name = formatService.renderTab(
                    player.getUniqueId(), current.serverId(), current.localDisplayName(),
                    player.getName(), player.getName());
            player.playerListName(name);
        } else {
            player.playerListName(net.kyori.adventure.text.Component.text(player.getName()));
        }
    }

    /** Runs on the main thread during shutdown; restores plain player list names. */
    public void resetLocalNamesNow() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.playerListName(net.kyori.adventure.text.Component.text(player.getName()));
        }
    }

    /**
     * Removes every virtual entry this plugin injected, for all online viewers,
     * and forgets the bookkeeping. Used when {@code sync.tab} is turned off by a
     * hot reload so stale entries do not linger in clients' tab lists.
     */
    public void removeAllVirtual() {
        // Capture synchronously: callers may clear the bookkeeping immediately
        // after this method returns, before the scheduled packet task runs.
        Map<UUID, Set<UUID>> snapshot = new HashMap<>();
        for (Map.Entry<UUID, Set<UUID>> entry : sentByViewer.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                snapshot.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }
        sentByViewer.clear();
        if (snapshot.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                Set<UUID> known = snapshot.get(viewer.getUniqueId());
                if (known != null && !known.isEmpty()) {
                    sendRemove(viewer, new ArrayList<>(known));
                }
            }
        });
    }

    public void clearViewer(UUID viewer) {
        sentByViewer.remove(viewer);
    }
}
