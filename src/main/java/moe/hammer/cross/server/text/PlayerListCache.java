package moe.hammer.cross.server.text;

import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.model.ServerEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-memory snapshot of all known players. Nothing in this class
 * performs network I/O, so it is safe to read from ping and tab-complete paths.
 */
public final class PlayerListCache {
    private final Map<String, PresenceEntry> byFieldKey = new ConcurrentHashMap<>();
    private final Map<String, ServerEntry> servers = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private volatile int localOnline;
    private volatile int localMaxPlayers;

    public void setLocalCounts(int online, int max) {
        this.localOnline = Math.max(0, online);
        this.localMaxPlayers = Math.max(0, max);
        revision.incrementAndGet();
    }

    public void replaceAll(Collection<PresenceEntry> entries, Collection<ServerEntry> serverEntries) {
        Map<String, PresenceEntry> players = new LinkedHashMap<>();
        for (PresenceEntry entry : entries) {
            UUID uuid = entry.uuidOrNull();
            if (uuid == null || entry.serverId == null || entry.serverId.isBlank()) {
                continue;
            }
            players.put(entry.fieldKey(), entry);
        }
        Map<String, ServerEntry> knownServers = new LinkedHashMap<>();
        for (ServerEntry server : serverEntries) {
            if (server.serverId != null && !server.serverId.isBlank()) {
                knownServers.put(server.serverId, server);
            }
        }
        byFieldKey.clear();
        byFieldKey.putAll(players);
        servers.clear();
        servers.putAll(knownServers);
        revision.incrementAndGet();
    }

    public void put(PresenceEntry entry) {
        if (entry == null || entry.uuidOrNull() == null) {
            return;
        }
        byFieldKey.put(entry.fieldKey(), entry);
        revision.incrementAndGet();
    }

    public void remove(String serverId, UUID uuid) {
        if (serverId == null || uuid == null) {
            return;
        }
        byFieldKey.remove(serverId + ":" + uuid);
        revision.incrementAndGet();
    }

    public void removeServer(String serverId) {
        if (serverId == null) {
            return;
        }
        String prefix = serverId + ":";
        byFieldKey.keySet().removeIf(key -> key.startsWith(prefix));
        servers.remove(serverId);
        revision.incrementAndGet();
    }

    public void putServer(ServerEntry entry) {
        if (entry == null || entry.serverId == null) {
            return;
        }
        servers.put(entry.serverId, entry);
        revision.incrementAndGet();
    }

    public long revision() {
        return revision.get();
    }

    public Collection<PresenceEntry> all() {
        return new ArrayList<>(byFieldKey.values());
    }

    public Collection<ServerEntry> servers() {
        return new ArrayList<>(servers.values());
    }

    public List<PresenceEntry> remote(CrossConfig config) {
        String local = config.serverId();
        List<PresenceEntry> result = new ArrayList<>();
        for (PresenceEntry entry : byFieldKey.values()) {
            if (!local.equals(entry.serverId)) {
                result.add(entry);
            }
        }
        result.sort(Comparator.comparing(PresenceEntry::fieldKey));
        return result;
    }

    public List<PresenceEntry> uniqueOnline(CrossConfig config) {
        Map<UUID, PresenceEntry> unique = new LinkedHashMap<>();
        for (PresenceEntry entry : byFieldKey.values()) {
            UUID uuid = entry.uuidOrNull();
            if (uuid != null) {
                unique.putIfAbsent(uuid, entry);
            }
        }
        List<PresenceEntry> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing(PresenceEntry::fieldKey));
        return result;
    }

    public int totalUniqueOnline(CrossConfig config) {
        Map<UUID, PresenceEntry> unique = new LinkedHashMap<>();
        for (PresenceEntry entry : byFieldKey.values()) {
            UUID uuid = entry.uuidOrNull();
            if (uuid != null) {
                unique.putIfAbsent(uuid, entry);
            }
        }
        return unique.size();
    }

    public int totalMaxPlayers(CrossConfig config) {
        int total = 0;
        for (ServerEntry entry : servers.values()) {
            total += Math.max(0, entry.maxPlayers);
        }
        return total;
    }

    public List<String> uniqueNames(String prefix, int maxResults) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        Map<String, String> unique = new LinkedHashMap<>();
        for (PresenceEntry entry : byFieldKey.values()) {
            if (entry.name == null || entry.name.isBlank()) {
                continue;
            }
            if (!entry.name.toLowerCase(Locale.ROOT).startsWith(needle)) {
                continue;
            }
            unique.putIfAbsent(entry.name.toLowerCase(Locale.ROOT), entry.name);
        }
        List<String> names = new ArrayList<>(unique.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.size() > maxResults) {
            return new ArrayList<>(names.subList(0, maxResults));
        }
        return names;
    }

    public PresenceEntry findUnique(String name) {
        if (name == null) {
            return null;
        }
        for (PresenceEntry entry : byFieldKey.values()) {
            if (name.equalsIgnoreCase(entry.name)) {
                return entry;
            }
        }
        return null;
    }

    public int localOnline() {
        return localOnline;
    }

    public int localMaxPlayers() {
        return localMaxPlayers;
    }
}
