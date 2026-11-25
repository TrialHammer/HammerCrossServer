package moe.hammer.cross.server.service;

import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.text.FormatService;
import moe.hammer.cross.server.text.PlayerListCache;
import moe.hammer.cross.server.util.TextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CompletionService {
    private final HammerCrossServer plugin;
    private final PlayerListCache cache;
    private final FormatService formatService;
    private volatile CrossConfig config;

    public CompletionService(HammerCrossServer plugin, PlayerListCache cache, FormatService formatService) {
        this.plugin = plugin;
        this.cache = cache;
        this.formatService = formatService;
    }

    public void start(CrossConfig config) {
        this.config = config;
    }

    public boolean shouldOfferForPacket() {
        CrossConfig current = config;
        return current != null && current.sync().completion() && current.completion().packetFallback();
    }

    public boolean shouldOffer(String buffer) {
        CrossConfig current = config;
        if (current == null || !current.sync().completion()) {
            return false;
        }
        if (buffer == null || !buffer.startsWith("/")) {
            return false;
        }
        // Without a space we are still completing the command name itself; never
        // inject player names into command-name completion.
        if (buffer.indexOf(' ') < 0) {
            return false;
        }
        String token = currentToken(buffer);
        if (token.startsWith("@") || token.startsWith("#") || token.startsWith("~")) {
            return false;
        }
        // token may legitimately be empty (e.g. "/msg ") so an empty prefix can
        // list every known player when min-prefix-length is 0.
        if (token.codePointCount(0, token.length()) < current.completion().minPrefixLength()) {
            return false;
        }
        if (!current.completion().smart()) {
            return true;
        }
        String command = commandName(buffer);
        if (!current.completion().commands().isEmpty()) {
            return current.completion().commands().contains(command);
        }
        return isLikelyPlayerNameToken(buffer, token);
    }

    public String currentToken(String buffer) {
        if (buffer == null) {
            return "";
        }
        int space = buffer.lastIndexOf(' ');
        return space < 0 ? "" : buffer.substring(space + 1);
    }

    public List<String> completions(String buffer) {
        CrossConfig current = config;
        if (current == null) {
            return List.of();
        }
        String token = currentToken(buffer);
        if (token.codePointCount(0, token.length()) < current.completion().minPrefixLength()) {
            return List.of();
        }
        return cache.uniqueNames(token, current.completion().maxResults());
    }

    public List<com.destroystokyo.paper.event.server.AsyncTabCompleteEvent.Completion> paperCompletions(String buffer,
                                                                                                        Collection<String> existing) {
        CrossConfig current = config;
        if (current == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : existing) {
            if (value != null) {
                seen.add(value.toLowerCase(Locale.ROOT));
            }
        }
        List<com.destroystokyo.paper.event.server.AsyncTabCompleteEvent.Completion> result = new ArrayList<>();
        String token = currentToken(buffer);
        for (String name : cache.uniqueNames(token, current.completion().maxResults())) {
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            PresenceEntry entry = cache.findUnique(name);
            if (entry != null) {
                result.add(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent.Completion.completion(
                        name, formatService.completionTooltip(entry.serverId, entry.serverDisplayName)));
            } else {
                result.add(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent.Completion.completion(name));
            }
        }
        return result;
    }

    public String commandName(String buffer) {
        if (buffer == null || !buffer.startsWith("/")) {
            return "";
        }
        String withoutSlash = buffer.substring(1);
        int space = withoutSlash.indexOf(' ');
        String command = space < 0 ? withoutSlash : withoutSlash.substring(0, space);
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        return command.toLowerCase(Locale.ROOT);
    }

    private boolean isLikelyPlayerNameToken(String buffer, String token) {
        if (token.isEmpty()) {
            return false;
        }
        String command = commandName(buffer);
        if (command.equals("msg") || command.equals("tell") || command.equals("w")
                || command.equals("whisper") || command.equals("tpa") || command.equals("tpaccept")
                || command.equals("tpdeny") || command.equals("pay") || command.equals("mail")
                || command.equals("kick") || command.equals("ban") || command.equals("pardon")
                || command.equals("op") || command.equals("deop") || command.equals("gamemode")
                || command.equals("give") || command.equals("clear") || command.equals("kill")
                || command.equals("mute") || command.equals("unmute") || command.equals("warn")
                || command.equals("invsee") || command.equals("enderchest") || command.equals("spectate")) {
            return true;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        for (String name : cache.uniqueNames("", 500)) {
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                return true;
            }
        }
        return false;
    }
}
