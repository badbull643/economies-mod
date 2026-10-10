package io.github.badbull643.economiesmod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

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

    static void text(GuiGraphicsExtractor c, Font tr, FormattedCharSequence t, int x, int y, int colour) {
        c.text(tr, t, x, y, opaque(colour));
    }

    static void text(GuiGraphicsExtractor c, Font tr, Component t, int x, int y, int colour) {
        c.text(tr, t, x, y, opaque(colour));
    }

    static void text(GuiGraphicsExtractor c, Font tr, String t, int x, int y, int colour) {
        c.text(tr, t, x, y, opaque(colour));
    }

    static void centered(GuiGraphicsExtractor c, Font tr, Component t, int centreX, int y, int colour) {
        c.centeredText(tr, t, centreX, y, opaque(colour));
    }
}
