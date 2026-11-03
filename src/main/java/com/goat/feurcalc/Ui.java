package com.goat.feurcalc;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Petits outils de dessin partagés par l'écran et l'affichage en combat. */
public final class Ui {
    /** La planche d'icônes de types de Cobblemon (18 icônes de 36x36 côte à côte). */
    private static final ResourceLocation TYPE_ICONS = ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/gui/types.png");
    private static final int TYPE_ICON_SRC = 36;
    private static final int TYPE_ICON_SHEET_W = 648;

    private Ui() {
    }

    public static void typeIcon(GuiGraphics g, ElementalType type, int x, int y, int size) {
        int u = type.getTextureXMultiplier() * TYPE_ICON_SRC;
        RenderSystem.enableBlend();
        g.blit(TYPE_ICONS, x, y, size, size, u, 0, TYPE_ICON_SRC, TYPE_ICON_SRC, TYPE_ICON_SHEET_W, TYPE_ICON_SRC);
        RenderSystem.disableBlend();
    }

    /** Coupe le texte avec "…" s'il dépasse. */
    public static String ellipsize(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String dots = "…";
        int dotsW = font.width(dots);
        String cut = font.plainSubstrByWidth(text, Math.max(0, maxWidth - dotsW));
        return cut + dots;
    }

    public static int hpColor(int percent) {
        if (percent > 50) {
            return 0xFF4CD964;
        }
        if (percent > 20) {
            return 0xFFFFCC33;
        }
        return 0xFFFF5A4A;
    }
}
