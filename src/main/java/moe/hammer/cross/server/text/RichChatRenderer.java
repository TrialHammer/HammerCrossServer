package moe.hammer.cross.server.text;

import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the rich part of a chat message: clickable links, TrChat-style item
 * placeholders (including explicit inventory slots such as {@code [item-3]}) and
 * PlaceholderAPI expansion. Everything is produced from plain text into Adventure
 * components; remote text is never re-parsed as MiniMessage.
 *
 * <p>Item data is transferred as Base64 of {@link ItemStack#serializeAsBytes()}
 * keyed by slot so the receiving node rebuilds identical hover information.</p>
 */
public final class RichChatRenderer {
    /** {@code itemData} is a comma-separated list of {@code index:base64} pairs. */
    public record Rendered(Component component, String itemData, String plainText) {
    }


    private static final Pattern URL_PATTERN =
            Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'`]+");
    private static final String TRAILING_PUNCTUATION = ".,!?;:)]}，。！？；：、）】》”’";

    private final MiniMessage miniMessage = MiniMessage.builder().strict(false).build();

    public RichChatRenderer() {
    }

    // ------------------------------------------------------------------
    // Local rendering (we can read the sender's inventory directly)
    // ------------------------------------------------------------------

    public Rendered renderLocal(String text, Player sender, CrossConfig config) {
        String source = text;
        PlayerInventory inventory = inventoryOf(sender);
        List<Token> tokens = tokenize(source, config);
        List<Component> parts = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        Map<Integer, String> itemData = new LinkedHashMap<>();

        int itemCount = 0;
        int maxItems = config.chat().item().maxItemsPerMessage();
        for (Token token : tokens) {
            if (token instanceof TextToken textToken) {
                parts.add(linkify(textToken.text(), config));
                log.append(textToken.text());
                continue;
            }
            ItemToken itemToken = (ItemToken) token;
            if (itemCount >= maxItems) {
                parts.add(Component.text(itemToken.raw()));
                log.append(itemToken.raw());
                continue;
            }
            itemCount++;
            if (inventory == null) {
                // No inventory context (e.g. legacy async path): keep literal text.
                parts.add(Component.text(itemToken.raw()));
                log.append(itemToken.raw());
                continue;
            }
            ItemStack stack = itemAt(inventory, itemToken.slot());
            if (stack == null || stack.getType().isAir()) {
                Component empty = itemComponent(null, config);
                parts.add(empty);
                log.append(TextUtil.plain(empty));
                continue;
            }
            Component item = itemComponent(stack, config);
            parts.add(item);
            log.append(TextUtil.plain(item));
            String encoded = serialize(stack, config);
            if (encoded != null) {
                itemData.put(itemToken.slot(), encoded);
            }
        }
        return new Rendered(Component.empty().append(parts), encodeItemData(itemData), log.toString());
    }

    // ------------------------------------------------------------------
    // Remote rendering (item payloads are untrusted)
    // ------------------------------------------------------------------

    public Rendered renderRemote(String text, String itemData, CrossConfig config) {
        Map<Integer, ItemStack> items = decodeItemData(itemData, config);
        List<Token> tokens = tokenize(text, config);
        List<Component> parts = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        int itemCount = 0;
        int maxItems = config.chat().item().maxItemsPerMessage();
        for (Token token : tokens) {
            if (token instanceof TextToken textToken) {
                parts.add(linkify(textToken.text(), config));
                log.append(textToken.text());
                continue;
            }
            ItemToken itemToken = (ItemToken) token;
            if (itemCount >= maxItems) {
                parts.add(Component.text(itemToken.raw()));
                log.append(itemToken.raw());
                continue;
            }
            itemCount++;
            ItemStack stack = items.get(itemToken.slot());
            if (stack == null) {
                parts.add(Component.text(itemToken.raw()));
                log.append(itemToken.raw());
                continue;
            }
            Component item = itemComponent(stack, config);
            parts.add(item);
            log.append(TextUtil.plain(item));
        }
        return new Rendered(Component.empty().append(parts), null, log.toString());
    }

    // ------------------------------------------------------------------
    // Tokenising / matching
    // ------------------------------------------------------------------

    private sealed interface Token permits TextToken, ItemToken {
    }

    private record TextToken(String text) implements Token {
    }

    private record ItemToken(String raw, int slot) implements Token {
    }

    private List<Token> tokenize(String text, CrossConfig config) {
        List<Token> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        CrossConfig.ItemDisplaySettings cfg = config.chat().item();
        if (!cfg.enabled()) {
            out.add(new TextToken(text));
            return out;
        }
        List<SlotPattern> patterns = slotPatterns(cfg);
        if (patterns.isEmpty()) {
            out.add(new TextToken(text));
            return out;
        }
        int index = 0;
        int textStart = 0;
        while (index < text.length()) {
            SlotPattern best = null;
            int bestLength = 0;
            for (SlotPattern candidate : patterns) {
                Matcher matcher = candidate.pattern().matcher(text);
                matcher.region(index, text.length());
                if (!matcher.lookingAt()) {
                    continue;
                }
                int length = matcher.end() - matcher.start();
                if (length > bestLength) {
                    best = candidate;
                    bestLength = length;
                }
            }
            if (best == null) {
                index++;
                continue;
            }
            Matcher matcher = best.pattern().matcher(text);
            matcher.region(index, text.length());
            if (!matcher.lookingAt()) {
                index++;
                continue;
            }
            String raw = matcher.group();
            if (index > textStart) {
                out.add(new TextToken(text.substring(textStart, index)));
            }
            int slot = -1;
            String digits = matcher.group(best.slotGroup());
            if (digits != null && !digits.isBlank()) {
                try {
                    slot = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    slot = -1;
                }
            }
            out.add(new ItemToken(raw, slot));
            index = matcher.end();
            textStart = index;
        }
        if (textStart < text.length()) {
            out.add(new TextToken(text.substring(textStart)));
        }
        if (out.isEmpty()) {
            out.add(new TextToken(text));
        }
        return out;
    }

    private record SlotPattern(Pattern pattern, int slotGroup) {
    }

    /**
     * Builds all accepted slot syntaxes, matching TrChat and the common
     * in-bracket form:
     * <ul>
     *   <li>{@code [item]} / {@code [i]} – main hand</li>
     *   <li>{@code [item]-3} / {@code [item]3} – TrChat style (1-based slot)</li>
     *   <li>{@code [item-3]} / {@code 【item-3】} – inside-bracket style</li>
     *   <li>{@code %i-3%} / {@code %i%-3} – percent styles</li>
     * </ul>
     */
    private List<SlotPattern> slotPatterns(CrossConfig.ItemDisplaySettings cfg) {
        List<SlotPattern> patterns = new ArrayList<>();
        for (String key : cfg.placeholders()) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            // Suffix form: key optionally followed by "-N" or "N".
            patterns.add(new SlotPattern(Pattern.compile(
                    "(?i)" + Pattern.quote(key) + "(?:-?([0-9]))?"), 1));
            // Inside-bracket form: "[item-3]" / "【item-3】".
            char open = key.charAt(0);
            char close = key.charAt(key.length() - 1);
            if ((open == '[' && close == ']') || (open == '【' && close == '】')) {
                String inner = key.substring(1, key.length() - 1);
                if (!inner.isEmpty()) {
                    patterns.add(new SlotPattern(Pattern.compile(
                            "(?i)" + Pattern.quote(String.valueOf(open)) + Pattern.quote(inner)
                                    + "-([1-9][0-9]?)" + Pattern.quote(String.valueOf(close))), 1));
                }
            }
            // Percent form inside the percent signs: "%i-3%".
            if (key.startsWith("%") && key.endsWith("%") && key.length() > 2) {
                String inner = key.substring(1, key.length() - 1);
                patterns.add(new SlotPattern(Pattern.compile(
                        "(?i)%" + Pattern.quote(inner) + "-([1-9][0-9]?)%"), 1));
            }
        }
        return patterns;
    }

    // ------------------------------------------------------------------
    // Item payload transport
    // ------------------------------------------------------------------

    private String encodeItemData(Map<Integer, String> items) {
        if (items.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> entry : items.entrySet()) {
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return out.toString();
    }

    private Map<Integer, ItemStack> decodeItemData(String data, CrossConfig config) {
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        if (data == null || data.isBlank() || !config.chat().item().enabled()) {
            return result;
        }
        int maxBytes = config.chat().item().maxSerializedBytes();
        int maxItems = config.chat().item().maxItemsPerMessage();
        int count = 0;
        for (String part : data.split(",")) {
            if (count >= maxItems) {
                break;
            }
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            int slot;
            try {
                slot = Integer.parseInt(part.substring(0, colon));
            } catch (NumberFormatException error) {
                continue;
            }
            // -1 == main hand (no explicit slot); 1..41 == explicit inventory slot.
            if (slot != -1 && (slot < 1 || slot > 41)) {
                continue;
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(part.substring(colon + 1));
                if (bytes.length == 0 || bytes.length > maxBytes) {
                    continue;
                }
                ItemStack stack = ItemStack.deserializeBytes(bytes);
                if (stack != null && !stack.getType().isAir()) {
                    result.put(slot, stack);
                    count++;
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private String serialize(ItemStack stack, CrossConfig config) {
        try {
            byte[] bytes = stack.serializeAsBytes();
            if (bytes == null || bytes.length == 0
                    || bytes.length > config.chat().item().maxSerializedBytes()) {
                return null;
            }
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable error) {
            return null;
        }
    }

    private ItemStack itemAt(PlayerInventory inventory, int slot) {
        if (inventory == null) {
            return null;
        }
        try {
            if (slot < 0) {
                return inventory.getItemInMainHand();
            }
            // TrChat-compatible: the number is 1-based and maps to getItem(n-1).
            return inventory.getItem(slot - 1);
        } catch (Throwable error) {
            return null;
        }
    }

    private PlayerInventory inventoryOf(Player player) {
        try {
            return player == null ? null : player.getInventory();
        } catch (Throwable error) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // PlaceholderAPI
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Generic components
    // ------------------------------------------------------------------

    /** External messages can contain links, but cannot reference a Minecraft inventory. */
    public Component renderExternalText(String text, CrossConfig config) {
        return linkify(text, config);
    }

    private Component linkify(String text, CrossConfig config) {
        List<Component> out = new ArrayList<>();
        if (text.isEmpty()) {
            return Component.empty();
        }
        CrossConfig.LinkSettings link = config.chat().link();
        if (!link.enabled()) {
            return Component.text(text);
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                out.add(Component.text(text.substring(last, matcher.start())));
            }
            String raw = matcher.group();
            String url = trimTrailing(raw);
            String trailing = raw.substring(url.length());
            out.add(linkComponent(url, link));
            if (!trailing.isEmpty()) {
                out.add(Component.text(trailing));
            }
            last = matcher.end();
        }
        if (last < text.length()) {
            out.add(Component.text(text.substring(last)));
        }
        return Component.empty().append(out);
    }

    private Component linkComponent(String url, CrossConfig.LinkSettings link) {
        String href = url.toLowerCase(Locale.ROOT).startsWith("www.") ? "https://" + url : url;
        Component component = Component.text(url);
        TextColor color = TextColor.fromHexString(link.color());
        component = component.color(color == null ? NamedTextColor.AQUA : color);
        if (link.underline()) {
            component = component.decorate(TextDecoration.UNDERLINED);
        }
        if (link.openInBrowser()) {
            try {
                component = component.clickEvent(ClickEvent.openUrl(href));
            } catch (Throwable ignored) {
            }
        }
        if (link.hover() != null && !link.hover().isBlank()) {
            try {
                Component hover = miniMessage.deserialize(link.hover(), Placeholder.unparsed("url", href));
                component = component.hoverEvent(HoverEvent.showText(hover));
            } catch (Throwable ignored) {
            }
        }
        return component;
    }

    private Component itemComponent(ItemStack stack, CrossConfig config) {
        CrossConfig.ItemDisplaySettings cfg = config.chat().item();
        if (stack == null || stack.getType().isAir()) {
            try {
                return miniMessage.deserialize(cfg.emptyText());
            } catch (Throwable error) {
                return Component.text("[空手]");
            }
        }
        String name = itemName(stack);
        Component base;
        try {
            base = miniMessage.deserialize(cfg.format(), Placeholder.unparsed("item", name));
        } catch (Throwable error) {
            base = Component.text("[" + name + "]");
        }
        if (cfg.hover()) {
            try {
                base = base.hoverEvent(stack.asHoverEvent(item -> item));
            } catch (Throwable ignored) {
            }
        }
        return base;
    }

    private String itemName(ItemStack stack) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String plain = TextUtil.plain(meta.displayName());
                if (!plain.isBlank()) {
                    return plain;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String i18n = stack.getI18NDisplayName();
            if (i18n != null && !i18n.isBlank()) {
                return i18n;
            }
        } catch (Throwable ignored) {
        }
        return prettify(stack);
    }

    private String prettify(ItemStack stack) {
        String name = stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder(name.length());
        boolean upper = true;
        for (char c : name.toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = c == ' ';
        }
        return out.toString();
    }

    private String trimTrailing(String url) {
        int end = url.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }
}
