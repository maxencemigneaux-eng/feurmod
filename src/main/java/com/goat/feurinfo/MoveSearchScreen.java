package com.goat.feurinfo;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.moves.Learnset;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import com.goat.feurmod.ui.FlatButton;
import com.goat.feurmod.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * FeurWiki · Qui apprend une attaque ? Taper une attaque (ex. "Distorsion") : tous les Pokémon qui peuvent
 * l'apprendre, avec la méthode (Niv. 30, CT, Œufs, Tuteur...), leurs types et leurs stats. Clic = ouvrir la fiche.
 */
public final class MoveSearchScreen extends Screen {

    private record Entry(Species species, String name, List<ElementalType> types, String method, int sortLevel,
                         int[] stats, int total) {}

    private static final Stats[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    private static final String[] STAT_LABELS = {"PV", "Atq", "Déf", "A.Spé", "D.Spé", "Vit", "Total"};
    private static final int ROW = 16;
    private static final int MARGIN = 8;
    private static final int DROP_MAX = 8;
    private static final int DROP_ROW = 13;

    @Nullable private final Screen parent;
    @Nullable private MoveTemplate move;
    private String query = "";
    private final List<MoveTemplate> suggestions = new ArrayList<>();
    private int suggestionSel;
    private final TypeFilterRow typeRow = new TypeFilterRow();
    @Nullable private String hoveredType;
    private final List<Entry> shown = new ArrayList<>();
    private List<Entry> all = new ArrayList<>();
    private int sortCol = -2; // -2 = méthode, -1 = n°, 0 = nom, 1..7 = stats
    private boolean sortDesc;
    private int scroll;

    private EditBox box;
    private int panelX, panelY, panelW, panelH, boxX, boxY, boxW, headerY, listTop, listBottom;
    private int nameX, typesX, methodX, statsX, statW;
    @Nullable private Entry hovered;

    public MoveSearchScreen(@Nullable Screen parent, @Nullable MoveTemplate move) {
        super(Component.literal("FeurWiki · Qui apprend une attaque ?"));
        this.parent = parent;
        if (move != null) {
            setMove(move);
        }
    }

    @Override
    protected void init() {
        panelX = MARGIN;
        panelY = 30;
        panelW = width - 2 * MARGIN;
        panelH = height - panelY - MARGIN;
        boxX = panelX + 10;
        boxY = panelY + 8;
        boxW = Math.min(220, panelW / 3);
        headerY = boxY + 44;
        listTop = headerY + 13;
        listBottom = panelY + panelH - 5;

        addRenderableWidget(new FlatButton(width - MARGIN - 16, 7, 16, 16, Component.literal("✕"), this::onClose));
        Component back = Component.literal("Retour");
        int bw = font.width(back) + 12;
        addRenderableWidget(new FlatButton(width - MARGIN - 16 - 4 - bw, 7, bw, 16, back, this::onClose));

        box = Theme.fieldBox(font, boxX, boxY, boxW, 16, Component.literal("Attaque"));
        box.setHint(Component.literal("Chercher une attaque… (ex. Distorsion)").withStyle(s -> s.withColor(Theme.MUTED)));
        box.setMaxLength(40);
        box.setValue(query);
        box.setResponder(this::onQuery);
        addRenderableWidget(box);
        if (move == null) {
            setInitialFocus(box);
        }
    }

    private void onQuery(String text) {
        query = text;
        suggestions.clear();
        suggestionSel = 0;
        String q = SpeciesLookup.normalize(text);
        if (q.isEmpty()) return;
        List<MoveTemplate> contains = new ArrayList<>();
        List<MoveTemplate> sorted = new ArrayList<>(Moves.all());
        sorted.sort(Comparator.comparing(m -> m.getDisplayName().getString()));
        for (MoveTemplate m : sorted) {
            String local = SpeciesLookup.normalize(m.getDisplayName().getString());
            String english = SpeciesLookup.normalize(m.getName());
            if (local.startsWith(q) || english.startsWith(q)) suggestions.add(m);
            else if (local.contains(q) || english.contains(q)) contains.add(m);
        }
        suggestions.addAll(contains);
        if (suggestions.size() > DROP_MAX) suggestions.subList(DROP_MAX, suggestions.size()).clear();
    }

    private void setMove(MoveTemplate m) {
        move = m;
        query = m.getDisplayName().getString();
        suggestions.clear();
        if (box != null) {
            box.setValue(query);
            suggestions.clear();
            box.setFocused(false);
            setFocused(null);
        }
        build();
        refilter();
    }

    /** Tous les Pokémon (et formes avec d'autres types) qui apprennent l'attaque, avec la méthode. */
    private void build() {
        all = new ArrayList<>();
        if (move == null) return;
        String id = move.getName();
        for (Species sp : SpeciesLookup.all()) {
            FormData standard = sp.getStandardForm();
            List<FormData> forms = new ArrayList<>();
            forms.add(standard);
            forms.addAll(sp.getForms());
            List<String> seen = new ArrayList<>();
            for (FormData f : forms) {
                String method = method(f.getMoves(), id);
                if (method == null) continue;
                List<ElementalType> types = new ArrayList<>();
                f.getTypes().forEach(types::add);
                String key = types + "|" + method;
                if (seen.contains(key)) continue;
                seen.add(key);
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
                all.add(new Entry(sp, name, types, method, sortLevel, stats, total));
            }
        }
    }

    private int sortLevel;

    /** "Niv. 30, CT" ou null si la forme ne l'apprend pas. */
    @Nullable
    private String method(Learnset ls, String id) {
        List<String> parts = new ArrayList<>();
        TreeSet<Integer> levels = new TreeSet<>();
        for (Map.Entry<Integer, List<MoveTemplate>> e : ls.getLevelUpMoves().entrySet()) {
            for (MoveTemplate m : e.getValue()) {
                if (m.getName().equals(id)) levels.add(Math.max(0, e.getKey()));
            }
        }
        if (!levels.isEmpty()) {
            List<String> nums = new ArrayList<>();
            for (int l : levels) nums.add(l <= 0 ? I18n.get("feurinfo.level.evolution") : String.valueOf(l));
            parts.add(I18n.get("feurinfo.method.level", String.join("/", nums)));
        }
        if (has(ls.getTmMoves(), id)) parts.add(I18n.get("feurinfo.method.tm"));
        if (has(ls.getEggMoves(), id)) parts.add(I18n.get("feurinfo.method.egg"));
        if (has(ls.getTutorMoves(), id)) parts.add(I18n.get("feurinfo.method.tutor"));
        if (has(ls.getEvolutionMoves(), id)) parts.add(I18n.get("feurinfo.method.evolution"));
        if (has(ls.getLegacyMoves(), id) || has(ls.getSpecialMoves(), id) || has(ls.getFormChangeMoves(), id)) {
            parts.add(I18n.get("feurinfo.method.other"));
        }
        sortLevel = levels.isEmpty() ? 1000 : levels.first();
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static boolean has(Iterable<MoveTemplate> moves, String id) {
        for (MoveTemplate m : moves) {
            if (m.getName().equals(id)) return true;
        }
        return false;
    }

    private void refilter() {
        shown.clear();
        for (Entry e : all) {
            if (!typeRow.matches(e.types())) continue;
            shown.add(e);
        }
        Comparator<Entry> cmp = switch (sortCol) {
            case 0 -> Comparator.comparing(Entry::name);
            case -1 -> Comparator.comparingInt(e -> e.species().getNationalPokedexNumber());
            case 7 -> Comparator.comparingInt(Entry::total);
            case 1, 2, 3, 4, 5, 6 -> Comparator.comparingInt(e -> e.stats()[sortCol - 1]);
            default -> Comparator.comparingInt(Entry::sortLevel)
                    .thenComparingInt(e -> e.species().getNationalPokedexNumber());
        };
        shown.sort(sortDesc ? cmp.reversed() : cmp);
        scroll = 0;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Theme.screenTitle(g, font, "FeurWiki", "Qui apprend une attaque ?", MARGIN, 11);

        Theme.panel(g, panelX, panelY, panelW, panelH, 4, Theme.PANEL, Theme.PANEL_BORDER);
        g.flush();

        int x0 = panelX + 10;
        int x1 = panelX + panelW - 14;

        // Fiche de l'attaque choisie, à droite de la recherche
        if (move != null) {
            int cx = boxX + boxW + 12;
            ElementalType t = move.getElementalType();
            FeurinfoScreen.drawTypeIcon(g, t, cx, boxY + 1, 14);
            g.drawString(font, move.getDisplayName().copy().withStyle(ChatFormatting.BOLD), cx + 18, boxY + 4, Theme.TEXT, false);
            String count = shown.size() + " Pokémon";
            g.drawString(font, count, x1 - font.width(count), boxY + 4, Theme.GOLD, false);
            // Filtre par type des Pokémon
            hoveredType = typeRow.draw(g, x0, x1, boxY + 24, mx, my);
        } else {
            g.drawString(font, "Tape une attaque, puis choisis-la dans la liste.", boxX + boxW + 12, boxY + 4, Theme.MUTED, false);
        }

        if (move != null) {
            statW = Mth.clamp((x1 - x0) / 16, 24, 40);
            statsX = x1 - statW * 7;
            // Largeur de la colonne d'après le texte le plus long (« Niv. 1/36, CT, Tuteur »)
            int methodW = font.width("Méthode ▲") + 12;
            for (Entry e : shown) methodW = Math.max(methodW, font.width(e.method()) + 12);
            methodX = statsX - Mth.clamp(methodW, 60, 170);
            typesX = methodX - 40;
            nameX = x0 + font.width("#0000") + 8;
            Theme.headerBand(g, x0 - 2, headerY, x1 - x0 + 4);
        header(g, "N°", -1, x0, false, mx, my);
            header(g, "Pokémon", 0, nameX, false, mx, my);
            g.drawString(font, "Types", typesX, headerY, Theme.LABEL, false);
            header(g, "Méthode", -2, methodX, false, mx, my);
            for (int i = 0; i < 7; i++) header(g, STAT_LABELS[i], i + 1, statsX + (i + 1) * statW - 4, true, mx, my);

            int visible = Math.max(1, (listBottom - listTop) / ROW);
            scroll = Mth.clamp(scroll, 0, Math.max(0, shown.size() - visible));
            hovered = null;
            g.enableScissor(panelX + 1, listTop, panelX + panelW - 1, listBottom);
            for (int i = 0; i < visible && scroll + i < shown.size(); i++) {
                int y = listTop + i * ROW;
                boolean hover = !dropOpen() && mx >= x0 - 2 && mx < x1 + 2 && my >= y && my < y + ROW && my < listBottom;
                if (hover) hovered = shown.get(scroll + i);
                Theme.rect(g, x0 - 2, y, x1 - x0 + 4, ROW - 1, 2, hover ? Theme.CARD_HOVER : (scroll + i) % 2 == 0 ? Theme.CARD : Theme.CARD_ALT);
            }
            g.flush();
            for (int i = 0; i < visible && scroll + i < shown.size(); i++) {
                Entry e = shown.get(scroll + i);
                int y = listTop + i * ROW;
                int ty = y + (ROW - 1 - 8) / 2;
                g.drawString(font, String.format("#%04d", e.species().getNationalPokedexNumber()), x0, ty, Theme.MUTED, false);
                g.drawString(font, Theme.ellipsize(font, e.name(), typesX - nameX - 6), nameX, ty,
                        Theme.TEXT, false);
                int ix = typesX;
                for (ElementalType t : e.types()) {
                    FeurinfoScreen.drawTypeIcon(g, t, ix, y + 1, 13);
                    ix += 16;
                }
                g.drawString(font, Theme.ellipsize(font, e.method(), statsX - methodX - 8), methodX, ty, Theme.GOLD, false);
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
                int barY = listTop + (trackH - barH) * scroll / Math.max(1, shown.size() - visible);
                Theme.rect(g, x1 + 5, listTop, 3, trackH, 1, Theme.TRACK);
                Theme.rect(g, x1 + 5, barY, 3, barH, 1, Theme.lighten(Theme.TRACK, 0.4F));
            }
            if (shown.isEmpty()) {
                g.drawCenteredString(font, "Aucun Pokémon n'apprend cette attaque", panelX + panelW / 2, listTop + 20, Theme.TEXT_2);
            }
        }

        if (move == null) hoveredType = null;
        drawDropdown(g, mx, my);
        if (hoveredType != null && !dropOpen()) {
            TypeFilterRow.tooltip(g, font, hoveredType, mx, my);
        } else if (hovered != null) {
            g.renderTooltip(font, Component.literal("Clic : ouvrir sa fiche").withStyle(ChatFormatting.DARK_AQUA), mx, my);
        }
    }

    private boolean dropOpen() {
        return box != null && box.isFocused() && !suggestions.isEmpty();
    }

    private void drawDropdown(GuiGraphics g, int mx, int my) {
        if (!dropOpen()) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        int y0 = boxY + 18;
        Theme.panel(g, boxX, y0, boxW, suggestions.size() * DROP_ROW + 4, 3, Theme.POPUP, Theme.lighten(Theme.PANEL_BORDER, 0.3F));
        g.flush();
        for (int i = 0; i < suggestions.size(); i++) {
            MoveTemplate m = suggestions.get(i);
            int ry = y0 + 2 + i * DROP_ROW;
            if (mx >= boxX && mx < boxX + boxW && my >= ry && my < ry + DROP_ROW) suggestionSel = i;
            if (i == suggestionSel) {
                Theme.rect(g, boxX + 2, ry, boxW - 4, DROP_ROW, 2, Theme.withAlpha(Theme.ACCENT, 0x70));
                g.flush();
            }
            FeurinfoScreen.drawTypeIcon(g, m.getElementalType(), boxX + 5, ry + 1, 11);
            g.drawString(font, Theme.ellipsize(font, m.getDisplayName().getString(), boxW - 26), boxX + 20, ry + 3, Theme.TEXT, false);
        }
        g.pose().popPose();
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
        if (dropOpen()) {
            int y0 = boxY + 18;
            if (mx >= boxX && mx < boxX + boxW && my >= y0 && my < y0 + suggestions.size() * DROP_ROW + 4) {
                int i = (int) ((my - y0 - 2) / DROP_ROW);
                if (i >= 0 && i < suggestions.size()) setMove(suggestions.get(i));
                return true;
            }
        }
        if (move != null && !dropOpen() && typeRow.click(mx, my, button)) {
            refilter();
            return true;
        }
        if (move != null && my >= headerY - 2 && my < headerY + 10) {
            int col = Integer.MIN_VALUE;
            if (mx < nameX - 2) col = -1;
            else if (mx < typesX - 2) col = 0;
            else if (mx >= methodX && mx < statsX) col = -2;
            else if (mx >= statsX) col = 1 + Mth.clamp((int) ((mx - statsX) / statW), 0, 6);
            if (col != Integer.MIN_VALUE) {
                if (sortCol != col) {
                    sortCol = col;
                    sortDesc = col >= 1;
                } else {
                    sortDesc = !sortDesc;
                }
                refilter();
                return true;
            }
        }
        if (button == 0 && hovered != null) {
            minecraft.setScreen(new FeurinfoScreen(hovered.species()));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        if (box != null && box.isFocused()) {
            if (!suggestions.isEmpty() && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
                suggestionSel = Math.floorMod(suggestionSel + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), suggestions.size());
                return true;
            }
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !suggestions.isEmpty()) {
                setMove(suggestions.get(Mth.clamp(suggestionSel, 0, suggestions.size() - 1)));
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll += 10; return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP) { scroll -= 10; return true; }
        return super.keyPressed(key, scancode, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (int) Math.signum(sy) * 3;
        return true;
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
