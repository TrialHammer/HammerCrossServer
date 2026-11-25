package moe.hammer.cross.server.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class TemplateFormattingTest {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String GLYPHS = "ꐜ";
    private static final String MESSAGE = "<red>正文</red> &a %title_rank%";
    private static final TagResolver TAGS = TagResolver.resolver(
            Placeholder.unparsed("server", "生存服"),
            Placeholder.unparsed("player", "Alex"),
            Placeholder.unparsed("message", MESSAGE));

    @Test
    void sectionColoredTitleAndResetStayInsideTheTitle() {
        Component actual = render("<gray>«<server>»</gray> <white>%title_rank%<player></white>"
                + " <dark_gray>»</dark_gray> <message>",
                token -> token.equals("%title_rank%") ? "§6§l" + GLYPHS + "§r" : token);
        assertEquals("«生存服» " + GLYPHS + "Alex » " + MESSAGE, plain(actual));
        assertEquals(NamedTextColor.GOLD, color(actual, GLYPHS, null));
        assertEquals(NamedTextColor.WHITE, color(actual, "Alex", null));
        assertFalse(bold(actual, "Alex", false));
    }

    @Test
    void legacyHexFormatsWorkInTemplateAndExpansion() {
        Component actual = render("§x§A§1§B§2§C§3甲&#123456乙§#ff0099丙&9丁 <white>%title_rank%<player></white>",
                token -> "§X§F§F§A§A§0§0" + GLYPHS + "§r");
        assertEquals("甲乙丙丁 " + GLYPHS + "Alex", plain(actual));
        assertEquals(TextColor.color(0xa1b2c3), color(actual, "甲", null));
        assertEquals(TextColor.color(0x123456), color(actual, "乙", null));
        assertEquals(TextColor.color(0xff0099), color(actual, "丙", null));
        assertEquals(NamedTextColor.BLUE, color(actual, "丁", null));
        assertEquals(TextColor.color(0xffaa00), color(actual, GLYPHS, null));
        assertEquals(NamedTextColor.WHITE, color(actual, "Alex", null));
    }

    @Test
    void modernPlaceholderAndUnknownVariablesKeepExistingBehavior() {
        Component actual = render("%server% %title_mm with space%%unknown_expansion% &a%player% %message%",
                token -> token.equals("%title_mm with space%") ? "<yellow>称号</yellow>" : token);
        assertEquals("生存服 称号%unknown_expansion% Alex " + MESSAGE, plain(actual));
        assertEquals(NamedTextColor.YELLOW, color(actual, "称号", null));
        assertEquals(NamedTextColor.GREEN, color(actual, "Alex", null));
    }

    @Test
    void legacyColorCanBeUsedDirectlyWithoutPapi() {
        Component actual = render("<white>§6" + GLYPHS + "</white> <aqua><player></aqua> <message>", null);
        assertEquals(GLYPHS + " Alex " + MESSAGE, plain(actual));
        assertEquals(NamedTextColor.GOLD, color(actual, GLYPHS, null));
        assertEquals(NamedTextColor.AQUA, color(actual, "Alex", null));
    }

    private static Component render(String template, UnaryOperator<String> papi) {
        return TemplateFormatting.render(MINI_MESSAGE, template, TAGS, papi);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static TextColor color(Component component, String text, TextColor inherited) {
        TextColor effective = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent node && node.content().contains(text)) {
            return effective;
        }
        for (Component child : component.children()) {
            TextColor found = color(child, text, effective);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean bold(Component component, String text, boolean inherited) {
        TextDecoration.State state = component.decoration(TextDecoration.BOLD);
        boolean effective = state == TextDecoration.State.NOT_SET ? inherited : state == TextDecoration.State.TRUE;
        if (component instanceof TextComponent node && node.content().contains(text)) {
            return effective;
        }
        return component.children().stream().anyMatch(child -> bold(child, text, effective));
    }
}
