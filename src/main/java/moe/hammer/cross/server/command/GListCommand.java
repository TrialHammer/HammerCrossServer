package moe.hammer.cross.server.command;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.model.ServerEntry;
import moe.hammer.cross.server.text.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code /glist} – lists every online player across all servers, grouped by
 * server, together with per-server and network totals. Player data comes from
 * the Redis-backed presence cache, so this works from any node and from the
 * console.
 */
public final class GListCommand implements CommandExecutor, TabCompleter {
    private final HammerCrossServer plugin;
    private final MessageService messages;

    public GListCommand(HammerCrossServer plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hammercross.glist")) {
            messages.send(sender, "no-permission");
            return true;
        }
        String filter = args.length > 0 ? args[0] : null;
        show(sender, filter);
        return true;
    }

    /** Renders the full list (or one server when {@code filter} is given). */
    public void show(CommandSender sender, String filter) {
        List<PresenceEntry> all = dedupe(plugin.playerCache().all());
        Map<String, List<PresenceEntry>> byServer = groupByServer(all);
        Map<String, ServerEntry> knownServers = knownServers();

        // Ensure known-but-empty servers still show up.
        for (String serverId : knownServers.keySet()) {
            byServer.computeIfAbsent(serverId, ignored -> new ArrayList<>());
        }

        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<String> serverIds = new ArrayList<>(byServer.keySet());
        serverIds.sort(String.CASE_INSENSITIVE_ORDER);

        if (!needle.isEmpty()) {
            serverIds.removeIf(serverId -> {
                String display = plugin.formatService().plainDisplayName(
                        serverId, fallbackDisplay(serverId, knownServers));
                return !serverId.toLowerCase(Locale.ROOT).equals(needle)
                        && !serverId.toLowerCase(Locale.ROOT).contains(needle)
                        && !display.toLowerCase(Locale.ROOT).contains(needle);
            });
            if (serverIds.isEmpty()) {
                messages.send(sender, "glist-server-not-found", Map.of("server", filter.trim()));
                return;
            }
        }

        int totalPlayers = 0;
        int activeServers = 0;
        for (String serverId : serverIds) {
            int count = byServer.get(serverId).size();
            totalPlayers += count;
            if (count > 0) {
                activeServers++;
            }
        }
        if (totalPlayers == 0) {
            messages.send(sender, "glist-empty");
            return;
        }

        messages.sendRaw(sender, "glist-header", Map.of(
                "players", Integer.toString(totalPlayers),
                "servers", Integer.toString(activeServers)));

        for (String serverId : serverIds) {
            List<PresenceEntry> players = byServer.get(serverId);
            ServerEntry server = knownServers.get(serverId);
            String display = plugin.formatService().plainDisplayName(
                    serverId, fallbackDisplay(serverId, knownServers));
            int max = server == null ? 0 : server.maxPlayers;
            int online = players.size();
            messages.sendRaw(sender, "glist-server", Map.of(
                    "server", display,
                    "server_id", serverId,
                    "online", Integer.toString(online),
                    "max", Integer.toString(max)));
            if (players.isEmpty()) {
                messages.sendRaw(sender, "glist-server-empty", Map.of());
                continue;
            }
            for (PresenceEntry player : players) {
                messages.sendRaw(sender, "glist-player", Map.of(
                        "player", player.name == null ? "" : player.name,
                        "server", display,
                        "server_id", serverId));
            }
        }
    }

    private List<PresenceEntry> dedupe(java.util.Collection<PresenceEntry> entries) {
        Map<UUID, PresenceEntry> unique = new LinkedHashMap<>();
        for (PresenceEntry entry : entries) {
            UUID uuid = entry.uuidOrNull();
            if (uuid != null) {
                unique.putIfAbsent(uuid, entry);
            }
        }
        List<PresenceEntry> result = new ArrayList<>(unique.values());
        result.sort(Comparator
                .comparing((PresenceEntry entry) -> entry.serverId == null ? "" : entry.serverId,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.name == null ? "" : entry.name,
                        String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private Map<String, List<PresenceEntry>> groupByServer(List<PresenceEntry> players) {
        Map<String, List<PresenceEntry>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PresenceEntry player : players) {
            String serverId = player.serverId == null || player.serverId.isBlank()
                    ? "unknown" : player.serverId;
            grouped.computeIfAbsent(serverId, ignored -> new ArrayList<>()).add(player);
        }
        return grouped;
    }

    private Map<String, ServerEntry> knownServers() {
        Map<String, ServerEntry> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ServerEntry server : plugin.playerCache().servers()) {
            if (server.serverId != null && !server.serverId.isBlank()) {
                result.put(server.serverId, server);
            }
        }
        return result;
    }

    private String fallbackDisplay(String serverId, Map<String, ServerEntry> knownServers) {
        ServerEntry server = knownServers.get(serverId);
        if (server != null && server.displayName != null && !server.displayName.isBlank()) {
            return server.displayName;
        }
        return serverId;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("hammercross.glist")) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        Map<String, ServerEntry> knownServers = knownServers();
        for (String serverId : knownServers.keySet()) {
            if (serverId.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(serverId);
            }
        }
        for (PresenceEntry entry : dedupe(plugin.playerCache().all())) {
            String serverId = entry.serverId;
            if (serverId != null && serverId.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(serverId);
            }
        }
        return new ArrayList<>(result);
    }
}
