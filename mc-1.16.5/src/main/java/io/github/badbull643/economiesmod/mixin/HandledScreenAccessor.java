package io.github.badbull643.economiesmod.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads where a container screen actually drew its panel.
 *
 * The inventory's left edge is not a constant and not a formula we could safely
 * duplicate: it comes from RecipeBookWidget.findLeftEdge and it moves when the recipe
 * book opens. Vanilla repositions its own button when that happens and nothing else,
 * so anything anchored to the panel has to ask for the current value each frame rather
 * than remember one from init().
 *
 * An accessor rather than an injection: this only reads a field vanilla already keeps.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {

    @Accessor("x")
    int getPanelX();

    /**
     * The panel's top edge, for anything anchored beside it rather than inside it.
     *
     * Does not move when the recipe book opens — only x does — but it is asked for the
     * same way and in the same breath, and a caller that reads one from the screen and
     * derives the other from a formula is one vanilla layout change from drawing in the
     * wrong place.
     */
    @Accessor("y")
    int getPanelY();

    /** How wide vanilla drew it, so a panel beside it knows where "beside" is. */
    @Accessor("backgroundWidth")
    int getPanelWidth();
}
