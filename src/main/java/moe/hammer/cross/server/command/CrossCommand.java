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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrossCommand implements CommandExecutor, TabCompleter {
    private final HammerCrossServer plugin;
    private final MessageService messages;

    public CrossCommand(HammerCrossServer plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("hammercross.admin")) {
                messages.send(sender, "no-permission");
                return true;
            }
            showStatus(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hammercross.admin")) {
                messages.send(sender, "no-permission");
                return true;
            }
            plugin.reloadCrossServer(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("players")) {
            if (!sender.hasPermission("hammercross.admin")) {
                messages.send(sender, "no-permission");
                return true;
            }
            showPlayers(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("glist")) {
            if (!sender.hasPermission("hammercross.glist")) {
                messages.send(sender, "no-permission");
                return true;
            }
            String filter = args.length > 1 ? args[1] : null;
            new GListCommand(plugin, messages).show(sender, filter);
            return true;
        }
        messages.send(sender, "unknown-command");
        return true;
    }

    private void showStatus(CommandSender sender) {
        Collection<ServerEntry> servers = plugin.playerCache().servers();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("server", plugin.formatService().plainDisplayName(
                plugin.config().serverId(), plugin.config().localDisplayName()));
        values.put("server_id", plugin.config().serverId());
        values.put("redis", plugin.redis().isConnected() ? "已连接" : "未连接");
        values.put("online", Integer.toString(plugin.playerCache().totalUniqueOnline(plugin.config())));
        values.put("local_online", Integer.toString(plugin.playerCache().localOnline()));
        values.put("servers", Integer.toString(servers.size()));
        messages.sendRaw(sender, "status", values);
        sender.sendMessage(net.kyori.adventure.text.Component.text("  KOOK · " + plugin.kookStatus()));

        for (ServerEntry server : servers) {
            Map<String, String> line = new LinkedHashMap<>();
            line.put("server", plugin.formatService().plainDisplayName(server.serverId, server.displayName));
            line.put("server_id", server.serverId);
            line.put("online", Integer.toString(server.onlinePlayers));
            line.put("max", Integer.toString(server.maxPlayers));
            messages.sendRaw(sender, "status-server", line);
        }
    }

    private void showPlayers(CommandSender sender) {
        List<PresenceEntry> players = new ArrayList<>(plugin.playerCache().all());
        if (players.isEmpty()) {
            messages.send(sender, "no-players");
            return;
        }
        players.sort((a, b) -> a.fieldKey().compareToIgnoreCase(b.fieldKey()));
        Map<String, String> header = new LinkedHashMap<>();
        header.put("count", Integer.toString(players.size()));
        messages.sendRaw(sender, "players-header", header);
        for (PresenceEntry entry : players) {
            Map<String, String> line = new LinkedHashMap<>();
            line.put("player", entry.name);
            line.put("server", plugin.formatService().plainDisplayName(entry.serverId, entry.serverDisplayName));
            line.put("server_id", entry.serverId);
            messages.sendRaw(sender, "players-line", line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String sub : List.of("status", "reload", "players", "glist")) {
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            return result;
        }
        return List.of();
    }
}
