package com.goat.feurleg.mixin;

import com.goat.feurleg.TextCapture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tout le texte dessiné par Minecraft passe par l'une de ces deux méthodes.
 * Pendant qu'un menu est affiché, on note chaque texte et sa position à l'écran.
 */
@Mixin(Font.class)
public abstract class TextRendererMixin {

    @Inject(
        method = "drawInternal(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I",
        at = @At("HEAD"))
    private void feurleg$captureString(String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                                       MultiBufferSource consumers, Font.DisplayMode layer,
                                       int background, int light, boolean mirror,
                                       CallbackInfoReturnable<Integer> cir) {
        if (TextCapture.isCapturing() && text != null) {
            TextCapture.record(text, x, y, matrix);
        }
    }

    @Inject(
        method = "drawInternal(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
        at = @At("HEAD"))
    private void feurleg$captureOrdered(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                                        MultiBufferSource consumers, Font.DisplayMode layer,
                                        int background, int light,
                                        CallbackInfoReturnable<Integer> cir) {
        if (TextCapture.isCapturing() && text != null) {
            StringBuilder sb = new StringBuilder();
            text.accept((index, style, codePoint) -> {
                sb.appendCodePoint(codePoint);
                return true;
            });
            TextCapture.record(sb.toString(), x, y, matrix);
        }
    }
}
