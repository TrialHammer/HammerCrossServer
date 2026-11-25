package moe.hammer.cross.server.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;

public final class TextUtil {
    private TextUtil() {
    }

    public static String plain(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(component);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String cleanChat(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        String value = input.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == 0xA7) {
                return;
            }
            if (Character.isISOControl(codePoint)) {
                return;
            }
            out.appendCodePoint(codePoint);
        });
        String cleaned = out.toString().replaceAll(" {2,}", " ").trim();
        return truncate(cleaned, maxLength);
    }

    /**
     * Cleans chat text while preserving common item placeholders such as
     * {@code [item]} / {@code [i]} (and full-width brackets) verbatim. Without
     * this, aggressive whitespace collapsing would still be harmless, but future
     * sanitisation changes could silently break the feature.
     */
    public static String cleanChatPreservingItems(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\[item\\]|\\[i\\]|【item】|【i】")
                .matcher(input);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(cleanChat(input.substring(last, matcher.start()), Integer.MAX_VALUE));
            out.append(matcher.group());
            last = matcher.end();
        }
        out.append(cleanChat(input.substring(last), Integer.MAX_VALUE));
        return truncate(out.toString().trim(), maxLength);
    }

    public static String cleanName(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        input.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) {
                out.appendCodePoint(codePoint);
            }
        });
        return truncate(out.toString().trim(), 64);
    }

    public static String truncate(String value, int maxCodePoints) {
        if (value == null || maxCodePoints <= 0) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
