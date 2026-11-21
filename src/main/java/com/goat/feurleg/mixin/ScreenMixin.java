package com.goat.feurleg.mixin;

import com.goat.feurleg.TextCapture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Délimite le rendu d'un menu : on capte le texte uniquement entre HEAD et RETURN. */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void feurleg$begin(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        TextCapture.begin((Screen) (Object) this);
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"))
    private void feurleg$end(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        TextCapture.end((Screen) (Object) this);
    }
}
