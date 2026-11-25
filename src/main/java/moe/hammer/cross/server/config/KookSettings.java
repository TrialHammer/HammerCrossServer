package moe.hammer.cross.server.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record KookSettings(
        boolean enabled, String token, String channel, String serverName, String avatarUrl,
        boolean toMinecraft, boolean toKook, boolean join, boolean quit,
        boolean onlinePlayers, Set<String> keywords, String separator,
        String joinTheme, String quitTheme, String cardSize,
        String chatText, String joinText, String quitText, String onlineText, String emptyText
) {
    private static final Set<String> THEMES = Set.of("primary", "secondary", "success", "danger", "warning", "info", "none");

    public KookSettings {
        keywords = Set.copyOf(keywords);
    }

    public static KookSettings read(ConfigurationSection config) {
        List<String> keywords = config.contains("kook.online-players.keywords")
                ? config.getStringList("kook.online-players.keywords") : List.of("在线玩家", "在线列表");
        return new KookSettings(
                config.getBoolean("kook.enabled", false),
                config.getString("kook.token", "").strip(),
                config.getString("kook.channel", "").strip(),
                config.getString("kook.server-name", "Minecraft 网络"),
                config.getString("kook.avatar-url", "https://cravatar.eu/helmavatar/%player_name%/256.png"),
                config.getBoolean("kook.chat.kook-to-minecraft.enabled", true),
                config.getBoolean("kook.chat.minecraft-to-kook.enabled", true),
                config.getBoolean("kook.chat.player-join.enabled", true),
                config.getBoolean("kook.chat.player-quit.enabled", true),
                config.getBoolean("kook.online-players.enabled", true),
                keywords.stream().map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet()),
                config.getString("kook.online-players.separator", "，"),
                choice(config, "kook.cards.join-theme", "info", THEMES),
                choice(config, "kook.cards.quit-theme", "danger", THEMES),
                choice(config, "kook.cards.size", "sm", Set.of("sm", "lg")),
                config.getString("kook.messages.minecraft2kook", "[%server_name%] %player%: %message%"),
                config.getString("kook.messages.player-join", "%player% 加入了 %server_name%。"),
                config.getString("kook.messages.player-quit", "%player% 离开了 %server_name%。"),
                config.getString("kook.messages.online-player-list", "[%server_name% (%online_players%)]: %list%"),
                config.getString("kook.messages.no-online-player", "没有玩家在线。")
        );
    }

    private static String choice(ConfigurationSection config, String path, String fallback, Set<String> allowed) {
        String value = config.getString(path, fallback).strip().toLowerCase(Locale.ROOT);
        return allowed.contains(value) ? value : fallback;
    }

    public boolean valid() {
        return !enabled || (token.matches("[!-~]+") && channel.matches("[0-9]+"));
    }

    public void validate() {
        if (!valid()) {
            throw new IllegalArgumentException("打开 kook.enabled 后，必须填写 kook.token 和纯数字 kook.channel");
        }
    }
}
