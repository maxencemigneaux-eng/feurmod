package com.goat.feurinfo;

import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import com.goat.feurmod.ui.FlatButton;
import com.goat.feurmod.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FeurWiki · Pokémon par type : choisir un type (ou deux) et voir tous les Pokémon qui l'ont,
 * avec leurs stats de base. Clic sur un en-tête = trier ; clic sur un Pokémon = ouvrir sa fiche.
 */
public final class TypeBrowserScreen extends Screen {

    private record Entry(Species species, FormData form, String name, List<ElementalType> types, int[] stats, int total) {}

    private static final Stats[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    private static final String[] STAT_LABELS = {"PV", "Atq", "Déf", "A.Spé", "D.Spé", "Vit", "Total"};
    private static final int ROW = 16;
    private static final int MARGIN = 8;

    @Nullable private final Screen parent;
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean onlyImplemented = false; // tous les Pokémon sont dans le jeu sur le serveur
    private List<Entry> all;
    private final List<Entry> shown = new ArrayList<>();
    private int sortCol = -1; // -1 = n° Pokédex, 0 = nom, 1..7 = stats
    private boolean sortDesc;
    private int scroll;

    // Géométrie
    private int panelX, panelY, panelW, panelH, iconsY, headerY, listTop, listBottom, iconSize;
    private int[] iconX = new int[0];
    private int nameX, typesX, statsX, statW;
    @Nullable private Entry hovered;

    public TypeBrowserScreen(@Nullable Screen parent, @Nullable String type) {
        super(Component.literal("FeurWiki · Pokémon par type"));
        this.parent = parent;
        if (type != null) {
            selected.add(type);
        }
    }

    @Override
    protected void init() {
        if (all == null) {
            build();
        }
        panelX = MARGIN;
        panelY = 30;
        panelW = width - 2 * MARGIN;
        panelH = height - panelY - MARGIN;
        iconsY = panelY + 8;
        headerY = iconsY + 26;
        listTop = headerY + 13;
        listBottom = panelY + panelH - 5;

        addRenderableWidget(new FlatButton(width - MARGIN - 16, 7, 16, 16, Component.literal("✕"), this::onClose));
        Component back = Component.literal("Retour");
        int bw = font.width(back) + 12;
        addRenderableWidget(new FlatButton(width - MARGIN - 16 - 4 - bw, 7, bw, 16, back, this::onClose));
        refilter();
    }

    /** Toutes les espèces, et leurs formes qui n'ont pas les mêmes types (Alola, Galar...). */
    private void build() {
        all = new ArrayList<>();
        for (Species sp : SpeciesLookup.all()) {
            FormData standard = sp.getStandardForm();
            Set<String> seenTypes = new LinkedHashSet<>();
            List<FormData> forms = new ArrayList<>();
            forms.add(standard);
            forms.addAll(sp.getForms());
            for (FormData f : forms) {
                List<ElementalType> types = new ArrayList<>();
                f.getTypes().forEach(types::add);
                String key = types.toString();
                if (!seenTypes.add(key)) {
                    continue; // même types qu'une forme déjà listée
                }
                String name = SpeciesLookup.displayName(sp);
                if (f != standard && !f.getName().equalsIgnoreCase(standard.getName())) {
                    name += " (" + f.getName() + ")";
                }
                Map<Stat, Integer> base = f.getBaseStats();
                int[] stats = new int[6];
                int total = 0;
                for (int i = 0; i < 6; i++) {
                    stats[i] = base.getOrDefault(STATS[i], 0);
                    total += stats[i];
                }
                all.add(new Entry(sp, f, name, types, stats, total));
            }
        }
    }

    private void refilter() {
        shown.clear();
        for (Entry e : all) {
            if (onlyImplemented && !e.species().getImplemented()) {
                continue;
            }
            boolean ok = true;
            for (String t : selected) {
                boolean has = false;
                for (ElementalType type : e.types()) {
                    if (type.getName().equalsIgnoreCase(t)) has = true;
                }
                ok &= has;
            }
            if (ok) {
                shown.add(e);
            }
        }
        Comparator<Entry> cmp = switch (sortCol) {
            case 0 -> Comparator.comparing(Entry::name);
            case 7 -> Comparator.comparingInt(Entry::total);
            case 1, 2, 3, 4, 5, 6 -> Comparator.comparingInt(e -> e.stats()[sortCol - 1]);
            default -> Comparator.comparingInt(e -> e.species().getNationalPokedexNumber());
        };
        shown.sort(sortDesc ? cmp.reversed() : cmp);
        scroll = 0;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        g.drawString(font, Component.literal("FeurWiki").withStyle(ChatFormatting.BOLD), MARGIN, 11, Theme.ACCENT, false);
        String sub = selected.isEmpty() ? "Pokémon par type — choisis un ou deux types" : "Pokémon de type " + typeNames();
        g.drawString(font, sub, MARGIN + font.width(Component.literal("FeurWiki").withStyle(ChatFormatting.BOLD)) + 14, 11, Theme.TEXT, false);

        Theme.panel(g, panelX, panelY, panelW, panelH, 4, Theme.PANEL, Theme.PANEL_BORDER);
        g.flush();

        // Rangée des types : clic = choisir (2 max), re-clic = enlever, clic droit = tout enlever
        int n = TypeChart.TYPES.length;
        iconSize = Mth.clamp((panelW - 20 - (n - 1) * 4) / n, 10, 18);
        int gap = 4;
        int x = panelX + 10;
        iconX = new int[n];
        String hoverType = null;
        for (int i = 0; i < n; i++) {
            iconX[i] = x;
            String id = TypeChart.TYPES[i];
            ElementalType type = FeurinfoScreen.elementalType(id);
            if (selected.contains(id)) {
                Theme.rect(g, x - 2, iconsY - 2, iconSize + 4, iconSize + 4, 3, Theme.withAlpha(Theme.ACCENT, 0xC0));
                g.flush();
            }
            if (type != null) {
                if (!selected.isEmpty() && !selected.contains(id)) {
                    com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1F, 1F, 1F, 0.4F);
                }
                FeurinfoScreen.drawTypeIcon(g, type, x, iconsY, iconSize);
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                if (mx >= x && mx < x + iconSize && my >= iconsY && my < iconsY + iconSize) {
                    hoverType = type.getDisplayName().getString();
                }
            }
            x += iconSize + gap;
        }
        String count = shown.size() + " Pokémon";
        g.drawString(font, count, panelX + panelW - 10 - font.width(count), iconsY + (iconSize - 8) / 2, Theme.TEXT_2, false);

        // Colonnes
        int x0 = panelX + 10;
        int x1 = panelX + panelW - 14;
        statW = Mth.clamp((x1 - x0) / 14, 26, 44);
        statsX = x1 - statW * 7;
        typesX = statsX - 44;
        nameX = x0 + font.width("#0000") + 8;
        header(g, "N°", -1, x0, false, mx, my);
        header(g, "Pokémon", 0, nameX, false, mx, my);
        g.drawString(font, "Types", typesX, headerY, Theme.LABEL, false);
        for (int i = 0; i < 7; i++) {
            header(g, STAT_LABELS[i], i + 1, statsX + (i + 1) * statW - 4, true, mx, my);
        }
        Theme.divider(g, x0 - 2, listTop - 3, x1 - x0 + 4);

        // Lignes
        int visible = Math.max(1, (listBottom - listTop) / ROW);
        scroll = Mth.clamp(scroll, 0, Math.max(0, shown.size() - visible));
        hovered = null;
        g.enableScissor(panelX + 1, listTop, panelX + panelW - 1, listBottom);
        for (int i = 0; i < visible && scroll + i < shown.size(); i++) {
            Entry e = shown.get(scroll + i);
            int y = listTop + i * ROW;
            boolean hover = mx >= x0 - 2 && mx < x1 + 2 && my >= y && my < y + ROW && my < listBottom;
            if (hover) hovered = e;
            Theme.rect(g, x0 - 2, y, x1 - x0 + 4, ROW - 1, 2, hover ? Theme.CARD_HOVER : (scroll + i) % 2 == 0 ? Theme.CARD : Theme.CARD_ALT);
        }
        g.flush();
        for (int i = 0; i < visible && scroll + i < shown.size(); i++) {
            Entry e = shown.get(scroll + i);
            int y = listTop + i * ROW;
            int ty = y + (ROW - 1 - 8) / 2;
            boolean dim = !e.species().getImplemented();
            g.drawString(font, String.format("#%04d", e.species().getNationalPokedexNumber()), x0, ty, Theme.MUTED, false);
            g.drawString(font, Theme.ellipsize(font, e.name(), typesX - nameX - 6), nameX, ty, Theme.TEXT, false);
            int ix = typesX;
            for (ElementalType t : e.types()) {
                FeurinfoScreen.drawTypeIcon(g, t, ix, y + 1, 13);
                ix += 16;
            }
            for (int s = 0; s < 6; s++) {
                String v = String.valueOf(e.stats()[s]);
                g.drawString(font, v, statsX + (s + 1) * statW - 4 - font.width(v), ty, Theme.statColor(e.stats()[s]), false);
            }
            String tot = String.valueOf(e.total());
            g.drawString(font, tot, statsX + 7 * statW - 4 - font.width(tot), ty, Theme.GOLD, false);
        }
        g.disableScissor();

        if (shown.size() > visible) {
            int trackH = listBottom - listTop;
            int barH = Math.max(12, trackH * visible / shown.size());
            int maxScroll = Math.max(1, shown.size() - visible);
            int barY = listTop + (trackH - barH) * scroll / maxScroll;
            Theme.rect(g, x1 + 5, listTop, 3, trackH, 1, Theme.TRACK);
            Theme.rect(g, x1 + 5, barY, 3, barH, 1, Theme.lighten(Theme.TRACK, 0.4F));
        }
        if (hoverType != null) {
            g.renderTooltip(font, List.of(Component.literal(hoverType).getVisualOrderText(),
                    Component.literal("Clic : choisir (2 max) · Clic droit : tout enlever").withStyle(ChatFormatting.GRAY).getVisualOrderText()), mx, my);
        } else if (hovered != null) {
            g.renderTooltip(font, Component.literal("Clic : ouvrir sa fiche").withStyle(ChatFormatting.DARK_AQUA), mx, my);
        }
    }

    private String typeNames() {
        List<String> names = new ArrayList<>();
        for (String id : selected) {
            ElementalType t = FeurinfoScreen.elementalType(id);
            names.add(t != null ? t.getDisplayName().getString() : id);
        }
        return String.join(" + ", names);
    }

    private void header(GuiGraphics g, String label, int col, int x, boolean right, int mx, int my) {
        String text = label + (sortCol == col ? (sortDesc ? " ▼" : " ▲") : "");
        int w = font.width(text);
        int left = right ? x - w : x;
        boolean hover = mx >= left - 1 && mx < left + w + 1 && my >= headerY - 2 && my < headerY + 10;
        g.drawString(font, text, left, headerY, sortCol == col ? Theme.GOLD : hover ? Theme.TEXT : Theme.LABEL, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Types
        if (my >= iconsY && my < iconsY + iconSize) {
            for (int i = 0; i < iconX.length; i++) {
                if (mx >= iconX[i] && mx < iconX[i] + iconSize) {
                    String id = TypeChart.TYPES[i];
                    if (button == 1) {
                        selected.clear();
                    } else if (!selected.remove(id)) {
                        if (selected.size() >= 2) {
                            selected.remove(selected.iterator().next());
                        }
                        selected.add(id);
                    }
                    refilter();
                    return true;
                }
            }
        }
        // Tri
        if (my >= headerY - 2 && my < headerY + 10) {
            int col = Integer.MIN_VALUE;
            if (mx < nameX - 2) col = -1;
            else if (mx < typesX - 2) col = 0;
            else if (mx >= statsX) col = 1 + Mth.clamp((int) ((mx - statsX) / statW), 0, 6);
            if (col != Integer.MIN_VALUE) {
                if (sortCol != col) {
                    sortCol = col;
                    sortDesc = col >= 1; // stats : du plus grand au plus petit d'abord
                } else {
                    sortDesc = !sortDesc;
                }
                refilter();
                return true;
            }
        }
        // Ouvrir une fiche
        if (button == 0 && hovered != null) {
            minecraft.setScreen(new FeurinfoScreen(hovered.species()));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (int) Math.signum(sy) * 3;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll += 10; return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP) { scroll -= 10; return true; }
        return super.keyPressed(key, scancode, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
