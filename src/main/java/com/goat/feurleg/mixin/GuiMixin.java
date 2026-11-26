package com.goat.feurleg.mixin;

import com.goat.feurleg.TextCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Délimite le rendu du HUD (hors menus) : de temps en temps, on y cherche le nom du monde où se trouve le joueur. */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void feurleg$hudBegin(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        TextCapture.beginHud();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void feurleg$hudEnd(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        TextCapture.endHud();
    }
}
