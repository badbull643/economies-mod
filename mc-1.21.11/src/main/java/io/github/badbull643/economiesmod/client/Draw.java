package io.github.badbull643.economiesmod.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

/**
 * Every text call the screens make, in one place.
 *
 * Minecraft 1.16.5 drew text through the screen itself, with colours written as plain
 * 0xRRGGBB. 1.21 draws through a DrawContext, and colours carry an alpha byte — a colour
 * whose alpha is zero is not guaranteed to be drawn at all. The screens were written with
 * the old convention in hundreds of places, so the translation lives here rather than
 * being repeated at each of them: anything with no alpha is made opaque.
 *
 * Also the one place a later Minecraft version that changes text drawing again has to be
 * taught about it.
 */
final class Draw {

    private Draw() {}

    /** A colour written without an alpha byte means fully opaque, as it did before. */
    static int opaque(int colour) {
        return (colour & 0xFF000000) == 0 ? (colour | 0xFF000000) : colour;
    }

    static void text(DrawContext c, TextRenderer tr, OrderedText t, int x, int y, int colour) {
        c.drawTextWithShadow(tr, t, x, y, opaque(colour));
    }

    static void text(DrawContext c, TextRenderer tr, Text t, int x, int y, int colour) {
        c.drawTextWithShadow(tr, t, x, y, opaque(colour));
    }

    static void text(DrawContext c, TextRenderer tr, String t, int x, int y, int colour) {
        c.drawTextWithShadow(tr, t, x, y, opaque(colour));
    }

    static void centered(DrawContext c, TextRenderer tr, Text t, int centreX, int y, int colour) {
        c.drawCenteredTextWithShadow(tr, t, centreX, y, opaque(colour));
    }
}
