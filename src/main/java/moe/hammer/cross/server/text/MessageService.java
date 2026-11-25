package moe.hammer.cross.server.text;

import moe.hammer.cross.server.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

public final class MessageService {
    private final MiniMessage miniMessage = MiniMessage.builder().strict(false).build();
    private volatile Catalog catalog;

    private record Catalog(Map<String, String> messages, String prefix) {
    }

    public MessageService(YamlConfiguration config) {
        replace(config);
    }

    public void replace(YamlConfiguration config) {
        Map<String, String> entries = new HashMap<>();
        for (String key : config.getKeys(false)) {
            if ("prefix".equals(key) || ConfigManager.VERSION_KEY.equals(key)) {
                continue;
            }
            String value = config.getString(key);
            if (value != null) {
                entries.put(key, value);
            }
        }
        catalog = new Catalog(Map.copyOf(entries), config.getString("prefix", ""));
    }

    public Component component(String key, Map<String, String> placeholders) {
        return component(catalog, key, placeholders);
    }

    private Component component(Catalog current, String key, Map<String, String> placeholders) {
        String raw = current.messages().getOrDefault(key, key);
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }
        try {
            return miniMessage.deserialize(raw, builder.build());
        } catch (Throwable ignored) {
            return Component.text(raw);
        }
    }

    public Component component(String key) {
        return component(key, Collections.emptyMap());
    }

    public Component prefixed(String key, Map<String, String> placeholders) {
        Catalog current = catalog;
        Component body = component(current, key, placeholders);
        String prefix = current.prefix();
        if (prefix.isBlank()) {
            return body;
        }
        try {
            return miniMessage.deserialize(prefix).append(body);
        } catch (Throwable ignored) {
            return body;
        }
    }

    public Component prefixed(String key) {
        return prefixed(key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(prefixed(key));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(prefixed(key, placeholders));
    }

    public void sendRaw(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(component(key, placeholders));
    }
}
