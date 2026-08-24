package io.github.badbull643.economiesmod.client;

import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;

/**
 * The vanilla tooltip frame — near-black fill, violet gradient edge.
 *
 * Minecraft has one panel look and this is it, so anything the mod draws itself uses it
 * rather than inventing a flat modern box that sits oddly beside the vanilla buttons
 * right next to it.
 *
 * Here rather than on a screen because two screens draw it now: the Market screen, and
 * the listings panel beside the inventory. A second copy of these four colours and
 * fourteen fills is the defect this codebase is named after — two things that must agree,
 * kept in two places — and it would show up as a panel that stopped matching the one
 * beside it after somebody adjusted an edge.
 */
public final class Panels extends DrawableHelper {

    /**
     * One instance, only so the gradients can be drawn.
     *
     * {@code DrawableHelper.fill} is static and {@code fillGradient} is not, which is
     * vanilla's inconsistency rather than ours — and the alternative to this line is
     * every caller holding a DrawableHelper of its own to draw a border with.
     */
    private static final Panels DRAW = new Panels();

    private Panels() {}

    /** The tooltip frame's own colours, shared so every panel in the mod agrees. */
    public static final int PANEL_BG = 0xF0100010;
    public static final int PANEL_EDGE_TOP = 0x505000FF;
    public static final int PANEL_EDGE_BOTTOM = 0x5028007F;

    /**
     * Draws the frame around a content box, in vanilla's own proportions.
     *
     * The coordinates are the *content*, not the frame: the border is drawn outside
     * them, which is why every number here is x-3 or w+3 rather than an inset. Callers
     * lay out text against x and y and never have to know the border exists.
     */
    public static void vanillaPanel(MatrixStack m, int x, int y, int w, int h) {
        final int bg = PANEL_BG;
        final int edgeTop = PANEL_EDGE_TOP;
        final int edgeBottom = PANEL_EDGE_BOTTOM;

        fill(m, x - 3, y - 4, x + w + 3, y - 3, bg);
        fill(m, x - 3, y + h + 3, x + w + 3, y + h + 4, bg);
        fill(m, x - 3, y - 3, x + w + 3, y + h + 3, bg);
        fill(m, x - 4, y - 3, x - 3, y + h + 3, bg);
        fill(m, x + w + 3, y - 3, x + w + 4, y + h + 3, bg);

        DRAW.fillGradient(m, x - 3, y - 2, x - 2, y + h + 2, edgeTop, edgeBottom);
        DRAW.fillGradient(m, x + w + 2, y - 2, x + w + 3, y + h + 2, edgeTop, edgeBottom);
        DRAW.fillGradient(m, x - 3, y - 3, x + w + 3, y - 2, edgeTop, edgeTop);
        DRAW.fillGradient(m, x - 3, y + h + 2, x + w + 3, y + h + 3, edgeBottom, edgeBottom);
    }
}
