package moe.hammer.cross.server.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

public final class PlayerIdentity {
    private PlayerIdentity() {
    }

    /** Look up the account name on the server where the Floodgate player connected. */
    public static String bedrockUsername(UUID uuid) {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return null;
        }
        try {
            ClassLoader loader = floodgate.getClass().getClassLoader();
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
            Object instance = api.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return null;
            }
            Object player = api.getMethod("getPlayer", UUID.class).invoke(instance, uuid);
            if (player == null) {
                return null;
            }
            Class<?> profile = Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer", false, loader);
            return (String) profile.getMethod("getUsername").invoke(player);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
