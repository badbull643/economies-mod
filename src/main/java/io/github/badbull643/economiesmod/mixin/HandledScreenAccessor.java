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
}
