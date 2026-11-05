package com.goat.feurmod.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Le thème commun des menus de Feurmod, façon Showdex : fond sombre, cartes arrondies, pastilles,
 * barres de couleur. Toutes les couleurs sont en ARGB.
 */
public final class Theme {
    // Fonds
    public static final int PANEL = 0xFF141920;
    public static final int PANEL_BORDER = 0xFF29323E;
    public static final int HEADER = 0xFF1A212A;
    public static final int CARD = 0xFF1D242E;
    public static final int CARD_ALT = 0xFF181E26;
    public static final int CARD_HOVER = 0xFF283240;
    public static final int CARD_DISABLED = 0xFF161B21;
    public static final int FIELD = 0xFF0E1217;
    public static final int FIELD_BORDER = 0xFF2E3946;
    public static final int TRACK = 0xFF262D37;
    public static final int DIVIDER = 0xFF252D38;
    public static final int POPUP = 0xFF161C24;

    // Textes
    public static final int TEXT = 0xFFE9EEF3;
    public static final int TEXT_2 = 0xFFA9B5C1;
    public static final int MUTED = 0xFF6E7A87;
    public static final int LABEL = 0xFF8796A5;

    // Accents
    public static final int ACCENT = 0xFF3D8BFF;
    public static final int ACCENT_RED = 0xFFFF5470;
    public static final int GOLD = 0xFFFFCF5C;
    public static final int GOOD = 0xFF45D483;
    public static final int WARN = 0xFFFFB347;
    public static final int BAD = 0xFFFF5F5F;
    public static final int PURPLE = 0xFFB794FF;
    public static final int ON = 0xFF2C7A52;

    /** Coins arrondis : nombre de pixels retirés sur chaque ligne du haut (et du bas), par rayon. */
    private static final int[][] CORNERS = {{}, {1}, {2, 1}, {3, 1, 1}, {4, 2, 1, 1}};

    private Theme() {
    }

    // =======================================================================================
    // Formes

    /** Rectangle aux coins arrondis (rayon 0 à 4). Marche aussi avec des couleurs transparentes. */
    public static void rect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        r = Math.max(0, Math.min(Math.min(r, 4), Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        int[] inset = CORNERS[r];
        for (int i = 0; i < r; i++) {
            g.fill(x + inset[i], y + i, x + w - inset[i], y + i + 1, color);
            g.fill(x + inset[i], y + h - 1 - i, x + w - inset[i], y + h - i, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    /** Carte opaque avec une bordure de 1 px. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int border) {
        rect(g, x, y, w, h, r, border);
        rect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    /** Contour de 1 px (coins coupés en biais, rayon 0 à 2) : pour les cartes transparentes. */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(2, r));
        g.fill(x + r, y, x + w - r, y + 1, color);
        g.fill(x + r, y + h - 1, x + w - r, y + h, color);
        g.fill(x, y + r, x + 1, y + h - r, color);
        g.fill(x + w - 1, y + r, x + w, y + h - r, color);
        if (r == 2) {
            g.fill(x + 1, y + 1, x + 2, y + 2, color);
            g.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
            g.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
            g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
        }
    }

    /** Ombre douce sous une fenêtre. */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h, int r) {
        rect(g, x - 2, y, w + 4, h + 4, r + 2, 0x22000000);
        rect(g, x - 1, y + 1, w + 2, h + 2, r + 1, 0x33000000);
    }

    /** Barre de progression arrondie (fraction de 0 à 1). */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float fraction, int color, int track) {
        int r = Math.min(2, h / 2);
        rect(g, x, y, w, h, r, track);
        int filled = Math.round(w * Math.max(0F, Math.min(1F, fraction)));
        if (filled > 0) {
            rect(g, x, y, Math.max(filled, Math.min(w, 2 * r)), h, r, color);
        }
    }

    /** Fond d'un champ de saisie (la case elle-même est dessinée sans bordure par-dessus). */
    public static void field(GuiGraphics g, int x, int y, int w, int h, boolean focused, boolean hovered) {
        panel(g, x, y, w, h, 2, FIELD, focused ? ACCENT : hovered ? lighten(FIELD_BORDER, 0.2F) : FIELD_BORDER);
    }

    /**
     * Case de saisie sans bordure de Minecraft, placée dans le rectangle (x, y, w, h) : on dessine son fond
     * avec {@link #field}. Le texte est centré verticalement.
     */
    public static EditBox fieldBox(Font font, int x, int y, int w, int h, Component message) {
        int top = (h - 7) / 2; // même centrage que le texte des pastilles
        EditBox box = new EditBox(font, x + 4, y + top, Math.max(1, w - 8), h - top, message);
        box.setBordered(false);
        box.setTextColor(TEXT);
        return box;
    }

    /** Ligne de séparation horizontale. */
    public static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, DIVIDER);
    }

    // =======================================================================================
    // Couleurs

    public static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
    }

    /** Mélange de deux couleurs (t = 0 → a, t = 1 → b). L'alpha vient de a. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0F, Math.min(1F, t));
        int ra = (a >> 16) & 0xFF;
        int ga = (a >> 8) & 0xFF;
        int ba = a & 0xFF;
        int rb = (b >> 16) & 0xFF;
        int gb = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = Math.round(ra + (rb - ra) * t);
        int gg = Math.round(ga + (gb - ga) * t);
        int bl = Math.round(ba + (bb - ba) * t);
        return (a & 0xFF000000) | (r << 16) | (gg << 8) | bl;
    }

    public static int lighten(int color, float amount) {
        return mix(color, 0xFFFFFFFF, amount);
    }

    public static int darken(int color, float amount) {
        return mix(color, 0xFF000000, amount);
    }

    /** Fond d'une pastille de type : la couleur du type, assombrie, opaque. */
    public static int typeBackground(int hue) {
        return 0xFF000000 | (darken(0xFF000000 | hue, 0.45F) & 0xFFFFFF);
    }

    // =======================================================================================
    // Texte

    /** Coupe le texte avec "…" s'il dépasse. */
    public static String ellipsize(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String dots = "…";
        String cut = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(dots)));
        return cut + dots;
    }

    /** Couleurs des barres de stats, comme sur Showdown. */
    public static int statColor(int value) {
        if (value < 30) return 0xFFF34444;
        if (value < 60) return 0xFFFF7F0F;
        if (value < 90) return 0xFFFFDD57;
        if (value < 120) return 0xFFA0E515;
        if (value < 150) return 0xFF23CD5E;
        return 0xFF00C2B8;
    }

    public static int hpColor(int percent) {
        if (percent > 50) {
            return 0xFF45D483;
        }
        if (percent > 20) {
            return 0xFFFFC94A;
        }
        return 0xFFFF5F5F;
    }
}
