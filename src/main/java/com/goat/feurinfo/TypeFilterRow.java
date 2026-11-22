package com.goat.feurinfo;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.goat.feurmod.ui.Theme;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rangée des 18 icônes de type pour filtrer une liste de Pokémon : clic = choisir (2 max, le Pokémon doit avoir
 * les deux), re-clic = enlever, clic droit = tous les types.
 */
final class TypeFilterRow {

    final Set<String> selected = new LinkedHashSet<>();
    private int[] xs = new int[0];
    private int y;
    private int size = 14;

    /** Le Pokémon a-t-il tous les types choisis ? */
    boolean matches(List<ElementalType> types) {
        for (String t : selected) {
            boolean has = false;
            for (ElementalType type : types) {
                if (type.getName().equalsIgnoreCase(t)) has = true;
            }
            if (!has) return false;
        }
        return true;
    }

    /** Dessine la rangée de x0 à x1 ; renvoie le nom du type survolé (pour l'info-bulle) ou null. */
    String draw(GuiGraphics g, int x0, int x1, int y, int mx, int my) {
        this.y = y;
        int n = TypeChart.TYPES.length;
        size = Mth.clamp((x1 - x0 - (n - 1) * 4) / n, 10, 16);
        xs = new int[n];
        String hovered = null;
        int x = x0;
        for (int i = 0; i < n; i++) {
            xs[i] = x;
            String id = TypeChart.TYPES[i];
            ElementalType type = FeurinfoScreen.elementalType(id);
            if (selected.contains(id)) {
                Theme.rect(g, x - 2, y - 2, size + 4, size + 4, 3, Theme.withAlpha(Theme.ACCENT, 0xC0));
                g.flush();
            }
            if (type != null) {
                if (!selected.isEmpty() && !selected.contains(id)) {
                    RenderSystem.setShaderColor(1F, 1F, 1F, 0.4F);
                }
                FeurinfoScreen.drawTypeIcon(g, type, x, y, size);
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                if (mx >= x && mx < x + size && my >= y && my < y + size) hovered = type.getDisplayName().getString();
            }
            x += size + 4;
        }
        return hovered;
    }

    static void tooltip(GuiGraphics g, Font font, String type, int mx, int my) {
        g.renderTooltip(font, List.of(Component.literal(type).getVisualOrderText(),
                Component.literal("Clic : filtrer (2 types max) · Clic droit : tous").withStyle(ChatFormatting.GRAY).getVisualOrderText()),
                mx, my);
    }

    /** true si le clic était sur un type (le filtre a changé). */
    boolean click(double mx, double my, int button) {
        if (my < y || my >= y + size) return false;
        for (int i = 0; i < xs.length; i++) {
            if (mx >= xs[i] && mx < xs[i] + size) {
                String id = TypeChart.TYPES[i];
                if (button == 1) {
                    selected.clear();
                } else if (!selected.remove(id)) {
                    if (selected.size() >= 2) selected.remove(selected.iterator().next());
                    selected.add(id);
                }
                return true;
            }
        }
        return false;
    }
}
