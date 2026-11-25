package moe.hammer.cross.server.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalises formatting from trusted configuration and PlaceholderAPI, not chat text. */
final class TemplateFormatting {
    private static final Pattern SYMBOL_TOKEN = Pattern.compile("%symbol\\.([a-zA-Z0-9_-]+)%");
    // PAPI identifiers cannot contain a space, but parameters after _ can.
    private static final Pattern PAPI_TOKEN = Pattern.compile("%[^% _]*(?:_[^%]*)?%");
    private static final Pattern LEGACY_CODE =
            Pattern.compile("(?i)[&§](?:x((?:[&§][0-9a-f]){6})|#([0-9a-f]{6})|([0-9a-fk-or]))");

    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"),
            Map.entry('1', "dark_blue"),
            Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"),
            Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"),
            Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"),
            Map.entry('a', "green"),
            Map.entry('b', "aqua"),
            Map.entry('c', "red"),
            Map.entry('d', "light_purple"),
            Map.entry('e', "yellow"),
            Map.entry('f', "white"),
            Map.entry('k', "obfuscated"),
            Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"),
            Map.entry('n', "underlined"),
            Map.entry('o', "italic"),
            Map.entry('r', "reset")
    );

    private TemplateFormatting() {
    }

    static Component render(MiniMessage miniMessage, String template, TagResolver base,
                            UnaryOperator<String> resolvePapi) {
        if (resolvePapi == null) {
            return miniMessage.deserialize(prepare(template), base);
        }

        Matcher tokens = PAPI_TOKEN.matcher(template);
        StringBuilder expanded = new StringBuilder(template.length());
        TagResolver.Builder tags = TagResolver.builder().resolver(base);
        int index = 0;
        while (tokens.find()) {
            String token = tokens.group();
            String value = token.indexOf('_') < 0 ? token : resolvePapi.apply(token);
            if (value.indexOf('§') >= 0) {
                // Isolate expansion styling so a trailing §r cannot close the enclosing
                // MiniMessage tags or change subsequent placeholders such as <player>.
                String key = "hcs_papi_legacy_" + index++;
                tags.resolver(Placeholder.component(key, legacyExpansion(miniMessage, value, base)));
                value = "<" + key + "/>";
            }
            tokens.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        tokens.appendTail(expanded);
        return miniMessage.deserialize(prepare(expanded.toString()), tags.build());
    }

    private static Component legacyExpansion(MiniMessage miniMessage, String value, TagResolver base) {
        if (value.indexOf('<') >= 0 || value.indexOf('%') >= 0) {
            // Preserve the existing support for MiniMessage and local symbol tags in
            // expansions, but parse them in an independent formatting scope.
            try {
                return miniMessage.deserialize(prepare(value), base);
            } catch (RuntimeException ignored) {
                // A malformed expansion must not drop the actual chat message.
            }
        }
        String sectionCodes = LEGACY_CODE.matcher(value).replaceAll(match -> Matcher.quoteReplacement(
                match.group(3) != null ? "§" + Character.toLowerCase(match.group(3).charAt(0))
                        : "§#" + hex(match)));
        return LegacyComponentSerializer.legacySection().deserialize(sectionCodes);
    }

    static String prepare(String template) {
        String value = LEGACY_CODE.matcher(template).replaceAll(match -> Matcher.quoteReplacement(
                match.group(3) != null ? "<" + LEGACY_TAGS.get(Character.toLowerCase(match.group(3).charAt(0))) + ">"
                        : "<#" + hex(match) + ">"));
        value = replacePlaceholders(value);
        return SYMBOL_TOKEN.matcher(value).replaceAll(match ->
                Matcher.quoteReplacement("<symbol:" + match.group(1) + ">"));
    }

    private static String hex(java.util.regex.MatchResult match) {
        String repeated = match.group(1);
        if (repeated == null) {
            return match.group(2);
        }
        StringBuilder rgb = new StringBuilder(6);
        for (int i = 1; i < repeated.length(); i += 2) {
            rgb.append(repeated.charAt(i));
        }
        return rgb.toString();
    }

    private static String replacePlaceholders(String template) {
        String value = template;
        value = value.replace("%server_id%", "<server_id>");
        value = value.replace("%server%", "<server>");
        value = value.replace("%player_display%", "<player_display>");
        value = value.replace("%player%", "<player>");
        value = value.replace("%target%", "<target>");
        value = value.replace("%message%", "<message>");
        value = value.replace("%online%", "<online>");
        value = value.replace("%max%", "<max>");
        value = value.replace("%local_online%", "<local_online>");
        return value;
    }

}
