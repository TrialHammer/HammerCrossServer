package moe.hammer.cross.server.kook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.config.KookSettings;
import moe.hammer.cross.server.model.CrossEvent;
import moe.hammer.cross.server.util.Deduper;
import moe.hammer.cross.server.util.TextUtil;
import org.bukkit.Bukkit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static moe.hammer.cross.server.kook.KookClient.number;
import static moe.hammer.cross.server.kook.KookClient.object;
import static moe.hammer.cross.server.kook.KookClient.string;

/** Adapts the existing cross-server event stream to one KOOK text channel. */
public final class KookBridge implements AutoCloseable {
    private static final Pattern VARIABLES = Pattern.compile("%(?:server_name|server_id|network_name|player|message|online_players|list)%");
    private static final Pattern COLORS = Pattern.compile("(?i)(?:[&§]x(?:[&§][0-9a-f]){6}|&#[0-9a-f]{6}|[&§][0-9a-fk-or])");
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\`*_~#>\\[\\]()!]");
    private static final int TEXT_CHUNK = 1800;

    private final HammerCrossServer plugin;
    private final KookClient client;
    private final Deduper inbound = new Deduper(300_000);
    private volatile KookSettings settings;
    private volatile boolean closed;

    public KookBridge(HammerCrossServer plugin, KookSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.client = new KookClient(settings.token(), this::receive, line -> plugin.getLogger().info(line));
    }

    public void start() {
        client.start();
    }

    public boolean canReconfigure(KookSettings next) {
        KookSettings previous = settings;
        return previous.token().equals(next.token()) && previous.channel().equals(next.channel());
    }

    public void reconfigure(KookSettings next) {
        settings = next;
    }

    public boolean connected() {
        return client.connected();
    }

    public void forward(CrossEvent event) {
        KookSettings current = settings;
        if (closed || !enabledFor(current, event)) {
            return;
        }
        // PAPI needs the Bukkit thread. Remote player data comes from the cross-server event.
        if (Bukkit.isPrimaryThread()) {
            sendEvent(event);
        } else {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> sendEvent(event));
        }
    }

    private static boolean enabledFor(KookSettings settings, CrossEvent event) {
        return switch (event.type) {
            case "chat" -> settings.toKook();
            case "join" -> settings.join() && !event.silent;
            case "quit" -> settings.quit() && !event.silent;
            default -> false;
        };
    }

    private void sendEvent(CrossEvent event) {
        KookSettings current = settings;
        if (closed || !enabledFor(current, event)) {
            return;
        }
        String server = plugin.formatService().plainDisplayName(event.serverId, event.serverDisplayName);
        String name = TextUtil.cleanName(event.playerName);
        String message = TextUtil.cleanChat(event.message, plugin.config().chat().maxLength());
        UUID uuid = uuid(event.playerUuid);
        Map<String, String> values = Map.of(
                "%server_name%", server, "%server_id%", event.serverId, "%network_name%", current.serverName(),
                "%player%", name, "%message%", message);
        UnaryOperator<String> resolve = text -> COLORS.matcher(
                plugin.formatService().resolvePlayerPlaceholders(uuid, text)).replaceAll("");
        if ("chat".equals(event.type)) {
            if (!message.isBlank()) {
                sendText(current.channel(), render(current.chatText(), values, resolve), null);
            }
            return;
        }
        boolean join = "join".equals(event.type);
        String text = render(join ? current.joinText() : current.quitText(), values, resolve);
        if (!text.isBlank()) {
            String account = event.bedrockUsername == null || event.bedrockUsername.isBlank()
                    ? name.replace("#", "") : TextUtil.cleanName(event.bedrockUsername);
            String avatar = current.avatarUrl().replace("%player_name%",
                    URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20"));
            client.send(current.channel(), 10,
                    presenceCard(text, avatar, join ? current.joinTheme() : current.quitTheme(), current.cardSize()), null);
        }
    }

    private void receive(JsonObject event) {
        KookSettings current = settings;
        if (closed || !current.channel().equals(string(event, "target_id"))
                || !"GROUP".equals(string(event, "channel_type"))) {
            return;
        }
        long type = number(event, "type", -1);
        if (type != 1 && type != 9) {
            return;
        }
        JsonObject extra = object(event, "extra");
        JsonObject author = object(extra, "author");
        if (author == null || "true".equalsIgnoreCase(string(author, "bot"))) {
            return;
        }
        String id = string(event, "msg_id");
        String message = type == 9 ? string(object(extra, "kmarkdown"), "raw_content") : "";
        if (message.isBlank()) {
            message = string(event, "content");
        }
        if (id.isBlank() || message.isBlank() || !inbound.firstTime(id)) {
            return;
        }
        if (current.onlinePlayers() && current.keywords().contains(message.strip())) {
            replyOnline(current, id);
        } else if (current.toMinecraft()) {
            String nickname = TextUtil.cleanName(string(author, "nickname"));
            if (nickname.isEmpty()) {
                nickname = TextUtil.cleanName(string(author, "username"));
            }
            if (!nickname.isEmpty()) {
                String text = TextUtil.cleanChat(message, plugin.config().chat().maxLength());
                if (!text.isEmpty()) {
                    plugin.acceptKookChat(this, CrossEvent.kookChat(plugin.config().serverId(), id, nickname, text));
                }
            }
        }
    }

    private void replyOnline(KookSettings current, String id) {
        List<String> names = plugin.playerCache().uniqueOnline(plugin.config()).stream()
                .map(entry -> TextUtil.cleanName(entry.name))
                .filter(name -> !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        Map<String, String> values = Map.of(
                "%server_name%", current.serverName(), "%network_name%", current.serverName(),
                "%server_id%", plugin.config().serverId(), "%online_players%", Integer.toString(names.size()),
                "%list%", String.join(current.separator(), names));
        sendText(current.channel(), render(names.isEmpty() ? current.emptyText() : current.onlineText(),
                values, UnaryOperator.identity()), id);
    }

    private void sendText(String channel, String text, String quote) {
        // Split by Unicode code points, before escaping, without losing the end of long player lists.
        while (!text.isEmpty()) {
            String part = TextUtil.truncate(text, TEXT_CHUNK);
            client.send(channel, 9, MARKDOWN.matcher(part).replaceAll(
                    match -> Matcher.quoteReplacement("\\" + match.group())), quote);
            text = text.substring(part.length());
            quote = null;
        }
    }

    static String presenceCard(String text, String avatar, String theme, String size) {
        JsonObject section = new JsonObject();
        section.addProperty("type", "section");
        JsonObject label = new JsonObject();
        label.addProperty("type", "plain-text");
        label.addProperty("content", TextUtil.truncate(text, TEXT_CHUNK));
        label.addProperty("emoji", false);
        section.add("text", label);
        if (avatar.startsWith("https://") || avatar.startsWith("http://")) {
            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.addProperty("src", avatar);
            image.addProperty("size", "sm");
            image.addProperty("circle", false);
            section.add("accessory", image);
            section.addProperty("mode", "left");
        }
        JsonObject card = new JsonObject();
        card.addProperty("type", "card");
        card.addProperty("theme", theme);
        card.addProperty("size", size);
        JsonArray modules = new JsonArray();
        modules.add(section);
        card.add("modules", modules);
        JsonArray cards = new JsonArray();
        cards.add(card);
        return cards.toString();
    }

    static String render(String template, Map<String, String> values, UnaryOperator<String> literals) {
        StringBuilder out = new StringBuilder();
        int start = 0;
        Matcher match = VARIABLES.matcher(template);
        while (match.find()) {
            if (!values.containsKey(match.group())) {
                continue;
            }
            out.append(literals.apply(template.substring(start, match.start())));
            out.append(values.get(match.group()));
            start = match.end();
        }
        return out.append(literals.apply(template.substring(start))).toString();
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        client.close();
    }
}
