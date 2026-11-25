package moe.hammer.cross.server.text;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Soft PlaceholderAPI bridge. Uses reflection so the plugin runs normally when
 * PlaceholderAPI is absent; when present, placeholders are resolved against the
 * chat sender (so things like {@code %player_title%} / prefix expansions show up
 * in chat).
 */
public final class PapiService {
    private volatile boolean initialised;
    private volatile Method setPlaceholders;

    private synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        if (Bukkit.getPluginManager() == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholders = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (Throwable ignored) {
            setPlaceholders = null;
        }
    }

    public boolean available() {
        init();
        return setPlaceholders != null;
    }

    /** Resolves PAPI placeholders in {@code text} as the given player. */
    public String resolve(UUID uuid, String text) {
        if (uuid == null || text == null || text.isEmpty() || text.indexOf('%') < 0) {
            return text;
        }
        UnaryOperator<String> resolver = resolver(uuid);
        return resolver == null ? text : resolver.apply(text);
    }

    /** Binds the player once per render, even when several placeholders are expanded. */
    UnaryOperator<String> resolver(UUID uuid) {
        init();
        Method method = setPlaceholders;
        if (method == null) {
            return null;
        }
        OfflinePlayer player;
        try {
            player = Bukkit.getOfflinePlayer(uuid);
        } catch (Throwable error) {
            return null;
        }
        return text -> {
            try {
                Object result = method.invoke(null, player, text);
                return result instanceof String string ? string : text;
            } catch (Throwable error) {
                return text;
            }
        };
    }
}
