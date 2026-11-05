package com.goat.feurcalc;

import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.abilities.Abilities;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.moves.Learnset;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.Species;
import com.goat.feurmod.ui.Theme;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Le calculateur façon Showdex : à gauche toi (A), à droite l'adversaire (B), le terrain en haut.
 * Tout est modifiable : Pokémon, niveau, PV, talent, objet, nature, Téra, statut, attaques, IV, EV, boosts.
 * Ligne "Set" : les sets Smogon et le set le plus joué sur Showdown (comme Showdex).
 */
public final class FeurcalcScreen extends Screen {
    // Couleurs : le thème commun de Feurmod (façon Showdex)
    private static final int C_LABEL = Theme.LABEL;
    private static final int C_TEXT = Theme.TEXT;
    private static final int C_DIM = Theme.MUTED;
    private static final int C_TITLE = Theme.GOLD;
    private static final int C_CHIP = Theme.CARD;
    private static final int C_HOVER = Theme.CARD_HOVER;
    private static final int C_ON = Theme.ON;
    private static final int C_PLUS = 0xFFFF9A8A;
    private static final int C_MINUS = 0xFF8AB4FF;

    // Colonnes du tableau des stats (relatives au bord intérieur du panneau), calculées dans init()
    private static final int COL_IV_W = 22;
    private static final int COL_EV_W = 28;
    private int colBaseRight = 66;
    private int colIv = 74;
    private int colEv = 101;
    private int colStatRight = 160;
    private int colBoost = 168;
    /** Boutons de boost : [−] [+1] [+] */
    private int boostStep = 11;
    private int boostGap = 2;

    // Espacements (un peu plus serrés sur les petits écrans)
    private static final int TOP_BAR_H = 14;
    private int topGap = 7;
    /** Hauteur des pastilles dans une ligne de rowH (le reste fait l'espace entre deux lignes). */
    private int chipH = 13;
    /** Largeur des étiquettes "Talent", "Nature"... et "Set" / "PV". */
    private int infoLabelW = 40;
    private int smallLabelW = 22;
    private int bandH = 23;

    @Nullable private final Screen parent;
    private final CalcState state = CalcState.INSTANCE;

    // Mise en page
    private int x0;
    private int contentW;
    private int topY;
    private int panelY;
    private int panelW;
    private int panelH;
    private int rowH;
    private final Layout[] layouts = {new Layout(), new Layout()};

    private static final class Layout {
        int panelX;
        int x;
        int w;
        int headerY;
        int setY;
        int hpY;
        int infoY;
        int movesY;
        int statsY;
        int sideY;
    }

    // Champs de saisie
    private final EditBox[][] ivBoxes = new EditBox[2][6];
    private final EditBox[][] evBoxes = new EditBox[2][6];
    private final EditBox[] levelBoxes = new EditBox[2];
    private final EditBox[] hpBoxes = new EditBox[2];
    private final List<NumberBox> numberBoxes = new ArrayList<>();
    private boolean syncingBoxes;

    private record NumberBox(EditBox box, IntSupplier getter, IntConsumer setter, int min, int max, int step, int side,
                             boolean spread, int x, int y, int w, int h) {
    }

    // Zones cliquables, refaites à chaque image
    private record Hit(int x, int y, int w, int h, Runnable left, @Nullable Runnable right) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Consumer<GuiGraphics>> iconOps = new ArrayList<>();
    private final List<Consumer<GuiGraphics>> textOps = new ArrayList<>();
    @Nullable private List<Component> tooltip;

    @Nullable private Picker picker;
    /** Panneau "Qui envoyer ?" ouvert. */
    private boolean teamOpen;
    private int teamX;
    private int teamY;
    private int teamW;
    private int teamHovered = -1;
    /** Le mode double ajoute une ligne (Coup d'Main, Garde-Ami) : la mise en page en dépend. */
    private boolean layoutDoubles;
    private static final int C_REVEALED = 0xFF8FE3A0;
    private final Map<String, FormData> pickerForms = new HashMap<>();

    public FeurcalcScreen(@Nullable Screen parent) {
        super(Component.translatable("feurcalc.title"));
        this.parent = parent;
        CalcEngine.warmUp();
        SmogonSets.load();
        state.tick();
    }

    private MonState mon(int side) {
        return side == 0 ? state.a : state.b;
    }

    private FieldState.Side fieldSide(int side) {
        return side == 0 ? state.field.a : state.field.b;
    }

    // =======================================================================================
    // Construction

    @Override
    protected void init() {
        numberBoxes.clear();
        picker = null;

        // Largeur : deux panneaux séparés par un vrai espace
        int panelGap = width >= 520 ? 10 : 6;
        contentW = Math.min(width - 12, 600);
        x0 = (width - contentW) / 2;
        panelW = (contentW - panelGap) / 2;
        int pad = panelW >= 260 ? 7 : 5;

        // Hauteur : version aérée si l'écran est assez grand, sinon version compacte
        layoutDoubles = state.field.doubles;
        int rows = layoutDoubles ? 18 : 17; // en double : une ligne de plus pour Coup d'Main / Garde-Ami
        boolean roomy = true;
        int fixed = 91; // tout ce qui n'est pas une ligne de rowH (marges, en-tête, Set, PV, séparateurs)
        topGap = 7;
        int avail = height - 8 - TOP_BAR_H - topGap;
        rowH = (avail - fixed) / rows;
        if (rowH < 12) {
            roomy = false;
            fixed = 68;
            topGap = 4;
            avail = height - 4 - TOP_BAR_H - topGap;
            rowH = (avail - fixed) / rows;
        }
        rowH = Mth.clamp(rowH, 10, 16);
        chipH = rowH - (rowH >= 13 ? 3 : 2);
        panelH = fixed + rows * rowH;

        // Centré verticalement
        int total = TOP_BAR_H + topGap + panelH;
        topY = Math.max(2, (height - total) / 2);
        panelY = topY + TOP_BAR_H + topGap;

        // Largeur des étiquettes : la plus longue + un espace
        infoLabelW = 0;
        for (String key : new String[]{"feurcalc.ability", "feurcalc.item", "feurcalc.nature", "feurcalc.tera",
                "feurcalc.status", "feurcalc.speed"}) {
            infoLabelW = Math.max(infoLabelW, font.width(Names.tr(key)));
        }
        infoLabelW += 6;
        smallLabelW = Math.max(font.width(Names.tr("feurcalc.set")), font.width(Names.tr("feurcalc.stat.hp"))) + 6;

        int sectionGap = roomy ? 8 : 5;
        bandH = roomy ? 23 : 19;
        for (int s = 0; s < 2; s++) {
            Layout l = layouts[s];
            l.panelX = x0 + s * (panelW + panelGap);
            l.x = l.panelX + pad;
            l.w = panelW - 2 * pad;
            l.headerY = panelY + (roomy ? 5 : 4);
            l.setY = l.headerY + (roomy ? 24 : 18);
            l.hpY = l.setY + (roomy ? 17 : 14);
            l.infoY = l.hpY + (roomy ? 18 : 15);
            l.movesY = l.infoY + 3 * rowH + sectionGap;
            l.statsY = l.movesY + 5 * rowH + sectionGap;
            l.sideY = l.statsY + 7 * rowH + sectionGap;
        }
        computeStatColumns(layouts[0].w);

        for (int s = 0; s < 2; s++) {
            Layout l = layouts[s];
            final int side = s;
            levelBoxes[s] = numberBox(side, l.x + l.w - 26, l.headerY + 1, 26, 12,
                    () -> mon(side).level, v -> mon(side).level = v, 1, 100, 1, false);
            hpBoxes[s] = numberBox(side, hpBoxX(l), l.hpY, 26, 12,
                    () -> mon(side).hpPercent, v -> mon(side).hpPercent = v, 0, 100, 5, false);
            for (int i = 0; i < 6; i++) {
                final int stat = i;
                int y = statRowY(l, i);
                ivBoxes[s][i] = numberBox(side, l.x + colIv, y, COL_IV_W, chipH,
                        () -> mon(side).ivs[stat], v -> mon(side).ivs[stat] = v, 0, 31, 1, true);
                evBoxes[s][i] = numberBox(side, l.x + colEv, y, COL_EV_W, chipH,
                        () -> mon(side).evs[stat], v -> mon(side).evs[stat] = v, 0, 252, 4, true);
            }
        }
        syncBoxes(true);
    }

    /** Colonnes du tableau des stats : le plus d'espace possible entre elles sans dépasser du panneau. */
    private void computeStatColumns(int lw) {
        int labelW = 0;
        for (int i = 0; i < 6; i++) {
            labelW = Math.max(labelW, font.width(Names.statLabel(i)));
        }
        labelW += font.width("−");
        int w3 = font.width("255");
        int w4 = font.width("1234");
        int evLeft = 8 + font.width("510"); // EV restants, tout à droite
        // 1er essai : on garde la place des EV restants ; sinon on s'en passe
        for (int pass = 0; pass < 2; pass++) {
            for (int gap = 11; gap >= 2; gap--) {
                boostStep = gap >= 5 ? 11 : 10;
                boostGap = gap >= 5 ? 2 : 1;
                colBaseRight = labelW + Math.max(3, gap - 2) + w3;
                colIv = colBaseRight + gap;
                colEv = colIv + COL_IV_W + Math.max(2, gap - 3);
                colStatRight = colEv + COL_EV_W + gap + w4;
                colBoost = colStatRight + gap;
                if (colBoost + boostWidth() + (pass == 0 ? evLeft : 0) <= lw) {
                    return;
                }
            }
        }
    }

    private int boostWidth() {
        return 2 * boostStep + 20 + 2 * boostGap;
    }

    /** Position X de la case "PV %". */
    private int hpBoxX(Layout l) {
        return l.x + l.w - font.width("999/999") - 10 - font.width("%") - 3 - 26;
    }

    /** Y du texte centré dans une pastille de hauteur h. */
    private static int textY(int y, int h) {
        return y + (h - 7) / 2;
    }

    private int statRowY(Layout l, int stat) {
        return l.statsY + rowH * (stat + 1);
    }

    private EditBox numberBox(int side, int x, int y, int w, int h, IntSupplier getter, IntConsumer setter,
                              int min, int max, int step, boolean spread) {
        EditBox box = Theme.fieldBox(font, x, y, w, h, Component.empty());
        box.setMaxLength(3);
        box.setFilter(s -> s.matches("\\d{0,3}"));
        NumberBox nb = new NumberBox(box, getter, setter, min, max, step, side, spread, x, y, w, h);
        box.setResponder(text -> {
            if (syncingBoxes) {
                return;
            }
            int value = text.isEmpty() ? min : Mth.clamp(Integer.parseInt(text), min, max);
            setter.accept(value);
            if (spread) {
                state.spreadEdited(mon(side));
            }
        });
        numberBoxes.add(nb);
        addRenderableWidget(box);
        return box;
    }

    /** Remet dans les cases les valeurs actuelles (sauf la case où tu es en train d'écrire). */
    private void syncBoxes(boolean force) {
        syncingBoxes = true;
        for (NumberBox nb : numberBoxes) {
            boolean empty = mon(nb.side()).isEmpty();
            nb.box().visible = !empty;
            if (!force && nb.box().isFocused()) {
                continue;
            }
            String expected = String.valueOf(nb.getter().getAsInt());
            if (!nb.box().getValue().equals(expected)) {
                nb.box().setValue(expected);
            }
        }
        syncingBoxes = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (state.field.doubles != layoutDoubles) {
            rebuildWidgets();
        }
        syncBoxes(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SavedSets.saveIfNeeded();
        Screen target = parent;
        if (target instanceof BattleGUI && CobblemonClient.INSTANCE.getBattle() == null) {
            target = null;
        }
        if (minecraft != null) {
            minecraft.setScreen(target);
        }
    }

    @Override
    public void removed() {
        SavedSets.saveIfNeeded();
        super.removed();
    }

    // =======================================================================================
    // Souris / clavier

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (teamOpen) {
            List<TeamMatchups.Row> rows = TeamMatchups.rows();
            int h = TeamPanel.height(rows.size());
            boolean inside = mx >= teamX && mx < teamX + teamW && my >= teamY && my < teamY + h;
            if (inside && teamHovered >= 0 && teamHovered < rows.size() && !rows.get(teamHovered).fainted()) {
                // Ce Pokémon passe à gauche (côté A)
                state.a.copyFrom(MonState.fromPokemon(rows.get(teamHovered).pokemon()));
                playClick();
                syncBoxes(true);
                teamOpen = false;
            } else if (!inside) {
                teamOpen = false;
            }
            return true;
        }
        if (picker != null) {
            if (picker.isInside(mx, my)) {
                if (picker.mouseClicked(mx, my, button)) {
                    picker = null;
                }
            } else {
                picker = null;
            }
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mx, my)) {
                Runnable action = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? hit.right() : hit.left();
                if (action != null && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                    setFocused(null);
                    playClick();
                    action.run();
                    syncBoxes(true);
                    return true;
                }
            }
        }
        boolean handled = super.mouseClicked(mx, my, button);
        if (!handled) {
            setFocused(null);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (teamOpen) {
            return true;
        }
        if (picker != null) {
            picker.mouseScrolled(scrollY);
            return true;
        }
        for (NumberBox nb : numberBoxes) {
            if (nb.box().visible && hover((int) mx, (int) my, nb.x(), nb.y(), nb.w(), nb.h())) {
                int value = nb.getter().getAsInt();
                if (hasShiftDown()) {
                    value = scrollY > 0 ? nb.max() : nb.min();
                } else {
                    value += (int) Math.signum(scrollY) * nb.step();
                }
                nb.setter().accept(Mth.clamp(value, nb.min(), nb.max()));
                if (nb.spread()) {
                    state.spreadEdited(mon(nb.side()));
                }
                syncBoxes(true);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (teamOpen && key == GLFW.GLFW_KEY_ESCAPE) {
            teamOpen = false;
            return true;
        }
        if (picker != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                picker = null;
                return true;
            }
            if (picker.keyPressed(key, scancode, modifiers)) {
                picker = null;
            }
            return true;
        }
        boolean typing = getFocused() instanceof EditBox box && box.isFocused();
        if (typing && (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            setFocused(null);
            syncBoxes(true);
            return true;
        }
        if (!typing && FeurcalcClient.openKey != null && FeurcalcClient.openKey.matches(key, scancode)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (picker != null) {
            picker.charTyped(c, modifiers);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    // =======================================================================================
    // Affichage

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        for (int s = 0; s < 2; s++) {
            Layout l = layouts[s];
            int accent = s == 0 ? Theme.ACCENT : Theme.ACCENT_RED;
            Theme.panel(g, l.panelX, panelY, panelW, panelH, 4, Theme.PANEL, Theme.mix(Theme.PANEL_BORDER, accent, 0.35F));
            // Bandeau du haut, teinté bleu (toi) ou rouge (l'adversaire)
            Theme.rect(g, l.panelX + 1, panelY + 1, panelW - 2, bandH, 3, Theme.mix(Theme.HEADER, accent, 0.16F));
            g.fill(l.panelX + 4, panelY + 1, l.panelX + panelW - 4, panelY + 2, Theme.withAlpha(accent, 0xC0));
            if (mon(s).isEmpty()) {
                continue;
            }
            // Cartes des attaques, bandes du tableau des stats
            for (int i = 0; i < 4; i++) {
                int y = l.movesY + rowH * (i + 1);
                Theme.rect(g, l.x - 3, y, l.w + 6, chipH, 2, Theme.CARD_ALT);
            }
            for (int i = 0; i < 6; i += 2) {
                int y = statRowY(l, i);
                Theme.rect(g, l.x - 3, y - 1, l.w + 6, chipH + 2, 2, Theme.CARD_ALT);
            }
            // Séparateurs au milieu de l'espace entre deux blocs
            int half = (l.statsY - (l.movesY + 5 * rowH)) / 2;
            Theme.divider(g, l.x, l.movesY - half - 1, l.w);
            Theme.divider(g, l.x, l.statsY - half - 1, l.w);
            Theme.divider(g, l.x, l.sideY - half - 1, l.w);
        }
        // Fonds des cases de saisie (niveau, PV, IV, EV)
        for (NumberBox nb : numberBoxes) {
            if (nb.box().visible) {
                Theme.field(g, nb.x(), nb.y(), nb.w(), nb.h(), nb.box().isFocused(),
                        picker == null && hover(mouseX, mouseY, nb.x(), nb.y(), nb.w(), nb.h()));
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.flush();

        hits.clear();
        iconOps.clear();
        textOps.clear();
        tooltip = null;
        boolean overlay = picker != null || teamOpen;
        int mx = overlay ? -10000 : mouseX;
        int my = overlay ? -10000 : mouseY;

        drawTopBar(g, mx, my);
        for (int s = 0; s < 2; s++) {
            drawPanel(g, s, mx, my);
        }
        g.flush();
        for (Consumer<GuiGraphics> op : iconOps) {
            op.accept(g);
        }
        g.flush();
        for (Consumer<GuiGraphics> op : textOps) {
            op.accept(g);
        }
        g.flush();

        if (teamOpen) {
            drawTeam(g, mouseX, mouseY);
        } else if (picker != null) {
            picker.render(g, mouseX, mouseY, partialTick);
        } else if (tooltip != null && !tooltip.isEmpty()) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (Component c : tooltip) {
                lines.addAll(font.split(c, 260));
            }
            g.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** Le panneau "Qui envoyer ?" : toute ton équipe contre le Pokémon de droite. Clic = il passe à gauche. */
    private void drawTeam(GuiGraphics g, int mouseX, int mouseY) {
        TeamMatchups.want();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(0, 0, width, height, 0x88000000);
        teamW = Math.min(contentW - 20, 340);
        teamX = (width - teamW) / 2;
        teamY = panelY + 8;
        TeamPanel.Result result = TeamPanel.draw(g, font, teamX, teamY, teamW, mouseX, mouseY, Theme.POPUP);
        teamHovered = result.hovered();
        g.flush();
        String hint = Names.tr("feurcalc.team.hint");
        g.drawString(font, hint, (width - font.width(hint)) / 2, teamY + result.height() + 5, Theme.MUTED, false);
        g.pose().popPose();
        List<TeamMatchups.Row> rows = TeamMatchups.rows();
        if (teamHovered >= 0 && teamHovered < rows.size()) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (Component c : TeamPanel.tooltip(rows.get(teamHovered), true)) {
                lines.addAll(font.split(c, 260));
            }
            g.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Outils de dessin

    private void text(String s, int x, int y, int color) {
        textOps.add(g -> g.drawString(font, s, x, y, color, false));
    }

    private void textRight(String s, int right, int y, int color) {
        text(s, right - font.width(s), y, color);
    }

    private void textCenter(String s, int center, int y, int color) {
        text(s, center - font.width(s) / 2, y, color);
    }

    private boolean hover(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Une "pastille" cliquable. */
    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, int color, boolean on, boolean center,
                      int mx, int my, @Nullable Runnable left, @Nullable Runnable right, @Nullable List<Component> tip) {
        boolean hovered = hover(mx, my, x, y, w, h);
        boolean clickable = left != null || right != null;
        int bg = on ? C_ON : C_CHIP;
        if (hovered && clickable) {
            bg = on ? Theme.lighten(C_ON, 0.12F) : C_HOVER;
        }
        Theme.rect(g, x, y, w, h, 2, bg);
        if (clickable && !on && h >= 8) {
            g.fill(x + 2, y, x + w - 2, y + 1, 0x12FFFFFF); // petit relief
        }
        String s = Ui.ellipsize(font, label, w - (center ? 4 : 8));
        int ty = textY(y, h);
        if (center) {
            textCenter(s, x + w / 2, ty, color);
        } else {
            text(s, x + 4, ty, color);
        }
        if (left != null || right != null) {
            hits.add(new Hit(x, y, w, h, left != null ? left : () -> { }, right));
        }
        if (hovered && tip != null) {
            tooltip = tip;
        }
    }

    private void tipArea(int mx, int my, int x, int y, int w, int h, List<Component> tip) {
        if (hover(mx, my, x, y, w, h)) {
            tooltip = tip;
        }
    }

    private static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    // ---------------------------------------------------------------------------------------
    // Barre du haut : terrain global

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        int y = topY;
        int h = TOP_BAR_H;
        FieldState f = state.field;

        // Libellés complets, ou courts si la barre ne tient pas dans la largeur (petits écrans)
        String[] full = {
                Names.tr("feurcalc.field.weather", Names.weatherName(f.weather)),
                Names.tr("feurcalc.field.terrain", Names.terrainName(f.terrain)),
                Names.tr("feurcalc.field.gravity"), Names.tr("feurcalc.field.crit"),
                Names.tr("feurcalc.field.doubles"), Names.tr("feurcalc.team.button")};
        String[] small = {
                Names.weatherName(f.weather), Names.terrainName(f.terrain),
                Names.tr("feurcalc.field.gravity.short"), Names.tr("feurcalc.field.crit.short"),
                Names.tr("feurcalc.field.doubles.short"), Names.tr("feurcalc.team.button")};
        int titleW = font.width("Feurcalc") + 10;
        int fixedW = 4 + 16 + 14 + 2; // ⇄ et ?
        boolean compact = barWidth(full, 12, 5) + titleW + fixedW + 10 > contentW;
        boolean showTitle = !compact || barWidth(small, 8, 3) + titleW + fixedW + 6 <= contentW;
        String[] labels = compact ? small : full;
        int gap = compact ? 3 : 5;
        int padW = compact ? 8 : 12;

        int x = x0 + 2;
        if (showTitle) {
            text("Feurcalc", x, textY(y, h), C_TITLE);
            x += titleW;
        }

        int w = font.width(labels[0]) + padW;
        chip(g, x, y, w, h, labels[0], C_TEXT, !f.weather.isEmpty(), true, mx, my,
                () -> f.weather = cycle(Names.WEATHERS, f.weather, 1), () -> f.weather = cycle(Names.WEATHERS, f.weather, -1),
                List.of(tr("feurcalc.tip.weather"), tr("feurcalc.tip.cycle")));
        x += w + gap;

        w = font.width(labels[1]) + padW;
        chip(g, x, y, w, h, labels[1], C_TEXT, !f.terrain.isEmpty(), true, mx, my,
                () -> f.terrain = cycle(Names.TERRAINS, f.terrain, 1), () -> f.terrain = cycle(Names.TERRAINS, f.terrain, -1),
                List.of(tr("feurcalc.tip.terrain"), tr("feurcalc.tip.cycle")));
        x += w + gap;

        w = font.width(labels[2]) + padW;
        chip(g, x, y, w, h, labels[2], C_TEXT, f.gravity, true, mx, my, () -> f.gravity = !f.gravity, null,
                List.of(tr("feurcalc.tip.gravity")));
        x += w + gap;

        w = font.width(labels[3]) + padW;
        chip(g, x, y, w, h, labels[3], C_TEXT, f.crit, true, mx, my, () -> f.crit = !f.crit, null,
                List.of(tr("feurcalc.tip.crit")));
        x += w + gap;

        w = font.width(labels[4]) + padW;
        chip(g, x, y, w, h, labels[4], C_TEXT, f.doubles, true, mx, my, () -> f.doubles = !f.doubles, null,
                List.of(tr("feurcalc.tip.doubles"), tr("feurcalc.tip.doubles2")));
        x += w + gap;

        w = font.width(labels[5]) + padW;
        chip(g, x, y, w, h, labels[5], C_TITLE, false, true, mx, my, () -> {
            teamOpen = true;
            teamHovered = -1;
            TeamMatchups.want();
        }, null, List.of(tr("feurcalc.tip.team"), tr("feurcalc.tip.team2")));
        x += w + gap;

        x += 4; // un peu plus d'air avant ⇄ et ?
        chip(g, x, y, 16, h, "⇄", C_TEXT, false, true, mx, my, state::swapSides, null, List.of(tr("feurcalc.tip.swap")));
        x += 16 + gap;
        chip(g, x, y, 14, h, "?", C_TITLE, false, true, mx, my, null, null, List.of(
                tr("feurcalc.help.1"), tr("feurcalc.help.2"), tr("feurcalc.help.3"), tr("feurcalc.help.4"), tr("feurcalc.help.5"),
                tr("feurcalc.help.6"), tr("feurcalc.help.7"), tr("feurcalc.help.8")));
        x += 14 + gap;

        // État du calculateur à droite
        String status;
        int color = C_DIM;
        List<Component> tip = null;
        switch (CalcEngine.status()) {
            case LOADING, NOT_STARTED -> status = Names.tr("feurcalc.engine.loading");
            case FAILED -> {
                status = Names.tr("feurcalc.engine.failed");
                color = 0xFFFF6A5A;
                tip = List.of(Component.literal(CalcEngine.error()));
            }
            default -> {
                if (state.lastError() != null) {
                    status = Names.tr("feurcalc.engine.error");
                    color = 0xFFFF6A5A;
                    tip = List.of(Component.literal(state.lastError()));
                } else if (state.isCalculating()) {
                    status = Names.tr("feurcalc.engine.calculating");
                } else {
                    status = state.inBattle ? Names.tr("feurcalc.engine.battle") : "";
                    color = 0xFF7FD08A;
                }
            }
        }
        int right = x0 + contentW;
        int sw = font.width(status);
        if (!status.isEmpty() && right - sw > x + 6) {
            textRight(status, right - 2, textY(y, h), color);
            if (tip != null) {
                tipArea(mx, my, right - sw, y, sw, h, tip);
            }
        }
    }

    private int barWidth(String[] labels, int padW, int gap) {
        int total = 0;
        for (String label : labels) {
            total += font.width(label) + padW + gap;
        }
        return total;
    }

    private static String cycle(String[] values, String current, int direction) {
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                index = i;
            }
        }
        return values[Math.floorMod(index + direction, values.length)];
    }

    // ---------------------------------------------------------------------------------------
    // Un panneau (A ou B)

    private void drawPanel(GuiGraphics g, int s, int mx, int my) {
        Layout l = layouts[s];
        MonState m = mon(s);
        if (m.isEmpty()) {
            drawEmptyPanel(g, s, mx, my);
            return;
        }
        drawHeader(g, s, l, m, mx, my);
        drawSet(g, s, l, m, mx, my);
        drawHp(g, s, l, m, mx, my);
        drawInfo(g, s, l, m, mx, my);
        drawMoves(g, s, l, m, mx, my);
        drawStats(g, s, l, m, mx, my);
        drawSide(g, s, l, mx, my);
    }

    private void drawEmptyPanel(GuiGraphics g, int s, int mx, int my) {
        Layout l = layouts[s];
        String label = Names.tr(s == 0 ? "feurcalc.side.a" : "feurcalc.side.b");
        text(label, l.x, textY(l.headerY, 14), s == 0 ? 0xFF8AC4FF : 0xFFFF9A9A);
        String choose = Names.tr("feurcalc.choose_pokemon");
        int w = font.width(choose) + 20;
        int x = l.panelX + (panelW - w) / 2;
        int y = panelY + panelH / 2 - 12;
        chip(g, x, y, w, 18, choose, C_TITLE, false, true, mx, my, () -> openSpeciesPicker(s, x, y + 18), null, null);
        if (s == 0 && !CalcState.party().isEmpty()) {
            String party = Names.tr("feurcalc.from_party");
            int pw = font.width(party) + 20;
            int px = l.panelX + (panelW - pw) / 2;
            chip(g, px, y + 24, pw, 15, party, C_TEXT, false, true, mx, my, () -> state.cycleParty(1), null, null);
        }
    }

    private void drawHeader(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.headerY;
        int h = 14;
        // Partie droite : flèches de l'équipe (A) + niveau
        int right = l.x + l.w;
        textRight(Names.tr("feurcalc.level"), right - 31, textY(y + 1, 12), C_LABEL);
        int arrowsRight = right - 31 - font.width(Names.tr("feurcalc.level")) - 8;
        int nameMax = arrowsRight - l.x - 36;
        if (s == 0 && !CalcState.party().isEmpty()) {
            chip(g, arrowsRight - 26, y + 1, 12, 12, "◀", C_TEXT, false, true, mx, my, () -> state.cycleParty(-1), null,
                    List.of(tr("feurcalc.tip.party")));
            chip(g, arrowsRight - 12, y + 1, 12, 12, "▶", C_TEXT, false, true, mx, my, () -> state.cycleParty(1), null,
                    List.of(tr("feurcalc.tip.party")));
            nameMax -= 32;
            arrowsRight -= 32;
        }
        // Combat double : lequel des Pokémon en jeu (1/2, 2/2) est utilisé ici
        int slots = s == 0 ? state.myActiveCount() : state.oppActiveCount();
        if (slots >= 2) {
            int slot = (s == 0 ? state.mySlot() : state.oppSlot()) + 1;
            chip(g, arrowsRight - 24, y + 1, 24, 12, slot + "/" + slots, C_TITLE, false, true, mx, my,
                    () -> {
                        if (s == 0) {
                            state.cycleMySlot();
                        } else {
                            state.cycleOppSlot();
                        }
                    }, null, List.of(tr(s == 0 ? "feurcalc.tip.slot_mine" : "feurcalc.tip.slot_theirs")));
            nameMax -= 30;
        }

        // Nom du Pokémon (clic : en choisir un autre ; clic droit : valeurs par défaut)
        String name = m.displayName.isEmpty() ? m.species.getTranslatedName().getString() : m.displayName;
        String formName = formLabel(m.form);
        if (!formName.isEmpty()) {
            name += " (" + formName + ")";
        }
        int nameW = Math.min(font.width(name) + 12, Math.max(40, nameMax));
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(m.species.getTranslatedName().getString()).withStyle(ChatFormatting.YELLOW));
        tip.add(tr(switch (m.source) {
            case PARTY -> "feurcalc.source.party";
            case BATTLE -> SavedSets.has(m.key()) ? "feurcalc.source.battle_saved" : "feurcalc.source.battle";
            default -> SavedSets.has(m.key()) ? "feurcalc.source.saved" : "feurcalc.source.custom";
        }).withStyle(ChatFormatting.GRAY));
        tip.add(tr("feurcalc.tip.species"));
        if (m.source != MonState.Source.PARTY) {
            tip.add(tr("feurcalc.tip.reset"));
        }
        int color = s == 0 ? 0xFF9AD0FF : 0xFFFFB0A0;
        chip(g, l.x, y, nameW, h, name, color, false, false, mx, my,
                () -> openSpeciesPicker(s, l.x, y + h),
                m.source == MonState.Source.PARTY ? null : () -> {
                    m.applyDefaults();
                    SavedSets.forget(m);
                }, tip);

        // Types (ou type Téra si activé)
        int tx = l.x + nameW + 6;
        List<String> types = m.tera && !m.teraType.isEmpty() ? List.of(m.teraType) : Names.formTypes(m.form);
        for (String type : types) {
            ElementalType et = Names.type(type);
            final int ix = tx;
            if (et != null) {
                Theme.rect(g, ix, y + 1, 12, 12, 2, Theme.typeBackground(et.getHue()));
                iconOps.add(gg -> Ui.typeIcon(gg, et, ix + 1, y + 2, 10));
                tipArea(mx, my, ix, y + 1, 12, 12, List.of(Component.literal(Names.typeName(type))));
                tx += 15;
            } else if (type.equalsIgnoreCase("stellar")) {
                text("✦", ix, textY(y, h), 0xFFFFE08A);
                tx += 10;
            }
        }
        if (m.tera) {
            text(Names.tr("feurcalc.tera.short"), tx + 2, textY(y, h), 0xFFFFE08A);
        }
    }

    private static String formLabel(@Nullable FormData form) {
        if (form == null || form == form.getSpecies().getStandardForm()
                || form.getName().equalsIgnoreCase(form.getSpecies().getStandardForm().getName())
                || form.getName().equalsIgnoreCase("normal")) {
            return "";
        }
        String key = "cobblemon.ui.pokedex.info.form." + form.showdownId();
        if (I18n.exists(key) && !I18n.get(key).isBlank()) {
            return I18n.get(key);
        }
        return form.getName();
    }

    // ---------------------------------------------------------------------------------------
    // Ligne "Set" : sets Smogon + set le plus joué sur Showdown (façon Showdex)

    private void drawSet(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.setY;
        int h = 12;
        text(Names.tr("feurcalc.set"), l.x, textY(y, h), C_LABEL);
        int x = l.x + smallLabelW;
        int w = l.w - smallLabelW - 2 * 13 - 4;
        List<SmogonSets.SmogonSet> sets = SmogonSets.forMon(m);
        SmogonSets.SmogonSet current = null;
        int index = -1;
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).id().equals(m.setId)) {
                current = sets.get(i);
                index = i;
                break;
            }
        }

        String label;
        int color;
        boolean loading = SmogonSets.status() == SmogonSets.Status.LOADING || SmogonSets.status() == SmogonSets.Status.NOT_STARTED;
        if (!m.setId.isEmpty() && !m.setId.equals(MonState.SAVED)) {
            label = (current != null ? current.fullLabel() : m.setLabel)
                    + (m.setModified ? " " + Names.tr("feurcalc.set.modified") : "");
            color = m.setModified ? 0xFFFFC870 : 0xFFFFE08A;
        } else if (m.setId.equals(MonState.SAVED)) {
            label = Names.tr("feurcalc.set.saved");
            color = C_TEXT;
        } else if (m.source == MonState.Source.PARTY) {
            label = Names.tr("feurcalc.set.party");
            color = 0xFF9AD0FF;
        } else if (loading || m.autoSet) {
            label = Names.tr("feurcalc.set.loading");
            color = C_DIM;
        } else if (sets.isEmpty()) {
            label = Names.tr(SmogonSets.status() == SmogonSets.Status.FAILED ? "feurcalc.set.failed" : "feurcalc.set.none");
            color = C_DIM;
        } else {
            label = Names.tr("feurcalc.set.default");
            color = C_DIM;
        }
        String counter = sets.isEmpty() ? "" : (index >= 0 ? (index + 1) + "/" + sets.size() : String.valueOf(sets.size()));

        List<Component> tip = new ArrayList<>();
        if (current != null) {
            tip.addAll(SmogonSets.tooltip(current));
            if (m.setModified) {
                tip.add(tr("feurcalc.set.modified_tip").withStyle(ChatFormatting.GOLD));
            }
        }
        tip.add(tr("feurcalc.tip.set").withStyle(ChatFormatting.DARK_GRAY));
        if (!SmogonSets.date().isEmpty()) {
            tip.add(tr("feurcalc.tip.set_data", SmogonSets.date(), sets.size()).withStyle(ChatFormatting.DARK_GRAY));
        }
        int counterW = counter.isEmpty() ? 0 : font.width(counter) + 6;
        if (counterW >= w / 3) {
            counterW = 0;
        }
        label = Ui.ellipsize(font, label, w - 8 - counterW);
        chip(g, x, y, w, h, label, color, false, false, mx, my, () -> openSetPicker(s, x, y + h), () -> stepSet(s, 1), tip);
        if (counterW > 0) {
            // petit compteur "3/12" à droite de la pastille
            g.fill(x + w - counterW, y, x + w, y + h, 0x30000000);
            textRight(counter, x + w - 3, textY(y, h), C_DIM);
        }
        chip(g, x + w + 4, y, 12, h, "◀", sets.isEmpty() ? C_DIM : C_TEXT, false, true, mx, my,
                sets.isEmpty() ? null : () -> stepSet(s, -1), null, List.of(tr("feurcalc.tip.set_prev")));
        chip(g, x + w + 18, y, 12, h, "▶", sets.isEmpty() ? C_DIM : C_TEXT, false, true, mx, my,
                sets.isEmpty() ? null : () -> stepSet(s, 1), null, List.of(tr("feurcalc.tip.set_next")));
    }

    /** Set suivant / précédent de la liste. */
    private void stepSet(int s, int direction) {
        MonState m = mon(s);
        List<SmogonSets.SmogonSet> sets = SmogonSets.forMon(m);
        if (sets.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).id().equals(m.setId)) {
                index = i;
            }
        }
        int next = index < 0 ? (direction > 0 ? 0 : sets.size() - 1) : Math.floorMod(index + direction, sets.size());
        applySet(s, sets.get(next));
    }

    private void applySet(int s, SmogonSets.SmogonSet set) {
        MonState m = mon(s);
        SmogonSets.apply(m, set);
        if (m.source != MonState.Source.PARTY) {
            SavedSets.remember(m); // retenu pour cette espèce, comme un spread réglé à la main
        }
    }

    private void openSetPicker(int s, int x, int y) {
        MonState m = mon(s);
        List<SmogonSets.SmogonSet> sets = SmogonSets.forMon(m);
        Map<String, SmogonSets.SmogonSet> byId = new HashMap<>();
        List<Picker.Entry> entries = new ArrayList<>();
        if (!sets.isEmpty()) {
            entries.add(Picker.Entry.of("@best", Names.tr("feurcalc.set.best"), "", null, false, "auto")
                    .withGroup("", List.of(tr("feurcalc.set.best_tip"))));
        } else {
            entries.add(Picker.Entry.of("@none", Names.tr(SmogonSets.status() == SmogonSets.Status.READY
                    ? "feurcalc.set.none" : "feurcalc.set.loading"), "", null, true, ""));
        }
        String lastFormat = null;
        for (SmogonSets.SmogonSet set : sets) {
            if (!set.format().id().equals(lastFormat)) {
                lastFormat = set.format().id();
                entries.add(Picker.Entry.header(set.format().label()));
            }
            String detail = set.isUsage() ? usagePercent(set.usage()) : "";
            entries.add(Picker.Entry.of(set.id(), set.displayName(), detail, null, false, set.format().label())
                    .withGroup(set.format().label(), SmogonSets.tooltip(set)));
            byId.put(set.id(), set);
        }
        setFocused(null);
        picker = new Picker(font, tr("feurcalc.picker.set"), entries, x, y, width, height, 210, e -> {
            if (e.id().equals("@best")) {
                SavedSets.forget(m);
                SmogonSets.applyBest(m);
            } else {
                SmogonSets.SmogonSet set = byId.get(e.id());
                if (set != null) {
                    applySet(s, set);
                }
            }
            syncBoxes(true);
        });
        picker.select(m.setId);
        picker.filter.setFocused(true);
    }

    private static String usagePercent(double usage) {
        double p = usage * 100;
        return (p >= 10 ? String.format(Locale.ROOT, "%.0f", p) : String.format(Locale.ROOT, "%.1f", p)).replace('.', ',') + "%";
    }

    private void drawHp(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.hpY;
        int h = 12;
        text(Names.tr("feurcalc.stat.hp"), l.x, textY(y, h), C_LABEL);
        int barX = l.x + smallLabelW;
        int boxX = hpBoxX(l);
        int barRight = boxX - 7;
        int barW = Math.max(10, barRight - barX);
        Theme.bar(g, barX, y + (h - 5) / 2, barW, 5, Mth.clamp(m.hpPercent, 0, 100) / 100F, Theme.hpColor(m.hpPercent), Theme.TRACK);
        text("%", boxX + 26 + 3, textY(y, h), C_LABEL);
        JsonObject summary = state.side(s == 0);
        if (summary != null) {
            String hp = CalcState.getInt(summary, "curHP", 0) + "/" + CalcState.getInt(summary, "maxHP", 0);
            textRight(hp, l.x + l.w, textY(y, h), C_TEXT);
        }
        tipArea(mx, my, barX, y, barW, h, List.of(tr("feurcalc.tip.hp")));
    }

    private void drawInfo(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int colGap = 10;
        int colW = (l.w - colGap) / 2;
        int labelW = infoLabelW;
        int left = l.x;
        int rightCol = l.x + colW + colGap;
        int h = chipH;

        // Ligne 1 : talent | objet
        final int y = l.infoY;
        text(Names.tr("feurcalc.ability"), left, textY(y, h), C_LABEL);
        List<Component> abilityTip = new ArrayList<>();
        String desc = Names.abilityDescription(m.ability);
        if (!desc.isEmpty()) {
            abilityTip.add(Component.literal(desc));
        }
        if (m.abilityRevealed()) {
            abilityTip.add(tr("feurcalc.tip.revealed").withStyle(ChatFormatting.GREEN));
        }
        abilityTip.add(tr("feurcalc.tip.ability"));
        chip(g, left + labelW, y, colW - labelW, h, Names.abilityName(m.ability), m.abilityRevealed() ? C_REVEALED : C_TEXT,
                false, false, mx, my,
                () -> openAbilityPicker(s, left + labelW, y + h), () -> cycleAbility(s), abilityTip);

        text(Names.tr("feurcalc.item"), rightCol, textY(y, h), C_LABEL);
        ItemStack stack = Names.itemStack(m.item);
        int itemX = rightCol + labelW;
        int iconSpace = stack.isEmpty() ? 0 : 10;
        if (!stack.isEmpty()) {
            iconOps.add(gg -> {
                gg.pose().pushPose();
                gg.pose().translate(itemX + 3, y + (h - 9) / 2F, 0);
                gg.pose().scale(0.5625F, 0.5625F, 1F);
                gg.renderItem(stack, 0, 0);
                gg.pose().popPose();
            });
        }
        boolean lost = m.item.isEmpty() && !m.lostItem.isEmpty();
        String itemName = lost ? Names.tr("feurcalc.item.lost", Names.itemName(m.lostItem))
                : m.item.isEmpty() ? Names.tr("feurcalc.none") : Names.itemName(m.item);
        List<Component> itemTip = new ArrayList<>();
        if (lost) {
            itemTip.add(tr("feurcalc.tip.item_lost", Names.itemName(m.lostItem)).withStyle(ChatFormatting.GOLD));
        } else if (m.itemRevealed()) {
            itemTip.add(tr("feurcalc.tip.revealed").withStyle(ChatFormatting.GREEN));
        }
        itemTip.add(tr("feurcalc.tip.item"));
        int itemColor = m.itemRevealed() && !m.item.isEmpty() ? C_REVEALED : m.item.isEmpty() ? C_DIM : C_TEXT;
        chip(g, itemX, y, colW - labelW, h, (iconSpace > 0 ? "   " : "") + itemName, itemColor,
                false, false, mx, my, () -> openItemPicker(s, itemX, y + h), () -> {
                    m.item = "";
                    state.spreadEdited(m);
                }, itemTip);

        // Ligne 2 : nature | Téra
        final int y2 = l.infoY + rowH;
        text(Names.tr("feurcalc.nature"), left, textY(y2, h), C_LABEL);
        Nature nature = Names.nature(m.nature);
        chip(g, left + labelW, y2, colW - labelW, h, Names.natureName(m.nature), C_TEXT, false, false, mx, my,
                () -> openNaturePicker(s, left + labelW, y2 + h), null,
                List.of(Component.literal(Names.natureEffect(nature)), tr("feurcalc.tip.nature")));

        text(Names.tr("feurcalc.tera"), rightCol, textY(y2, h), C_LABEL);
        int toggleW = 24;
        int teraW = colW - labelW - toggleW - 4;
        int teraX = rightCol + labelW;
        ElementalType teraIcon = Names.type(m.teraType);
        if (teraIcon != null) {
            final int iy = y2;
            iconOps.add(gg -> Ui.typeIcon(gg, teraIcon, teraX + 3, iy + (h - 9) / 2, 9));
        }
        chip(g, teraX, y2, teraW, h, (teraIcon != null ? "   " : "") + Names.typeName(m.teraType), C_TEXT, false, false,
                mx, my, () -> openTeraPicker(s, teraX, y2 + h), null, List.of(tr("feurcalc.tip.tera_type")));
        final int ty = y2;
        chip(g, teraX + teraW + 4, ty, toggleW, h, m.tera ? "ON" : "off", m.tera ? C_TEXT : C_DIM, m.tera, true, mx, my,
                () -> m.tera = !m.tera, null, List.of(tr("feurcalc.tip.tera")));

        // Ligne 3 : statut | vitesse
        final int y3 = l.infoY + 2 * rowH;
        text(Names.tr("feurcalc.status"), left, textY(y3, h), C_LABEL);
        chip(g, left + labelW, y3, colW - labelW, h, Names.statusName(m.status), m.status.isEmpty() ? C_DIM : 0xFFFFB070,
                false, false, mx, my, () -> m.status = cycle(Names.STATUSES, m.status, 1),
                () -> m.status = cycle(Names.STATUSES, m.status, -1), List.of(tr("feurcalc.tip.cycle")));

        text(Names.tr("feurcalc.speed"), rightCol, textY(y3, h), C_LABEL);
        JsonObject mine = state.side(s == 0);
        JsonObject theirs = state.side(s != 0);
        if (mine != null && theirs != null) {
            int sp = CalcState.getInt(mine, "speed", 0);
            int other = CalcState.getInt(theirs, "speed", 0);
            String arrow;
            int color;
            String key;
            if (sp > other) {
                arrow = " ▲";
                color = 0xFF7FE08A;
                key = "feurcalc.speed.faster";
            } else if (sp < other) {
                arrow = " ▼";
                color = 0xFFFF8A7A;
                key = "feurcalc.speed.slower";
            } else {
                arrow = " =";
                color = 0xFFFFE07A;
                key = "feurcalc.speed.tie";
            }
            String label = sp + arrow;
            text(label, rightCol + labelW + 4, textY(y3, h), color);
            tipArea(mx, my, rightCol, y3, colW, h, List.of(tr(key, sp, other), tr("feurcalc.tip.speed")));
        }
    }

    private void drawMoves(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.movesY;
        int koRight = l.x + l.w;
        int koColW = 56;
        int dmgRight = koRight - koColW;
        int barW = 64;
        int barX = dmgRight - barW;
        int hy = textY(y, chipH) + (rowH - chipH) / 2;
        MonState target = mon(1 - s);
        String vs = Names.tr("feurcalc.moves.vs", target.isEmpty() ? "?" : shortName(target));
        text(Ui.ellipsize(font, vs, barX - 8 - l.x), l.x, hy, C_LABEL);
        textCenter(Names.tr("feurcalc.moves.damage"), barX + barW / 2, hy, C_LABEL);
        textCenter(Names.tr("feurcalc.moves.ko"), koRight - (koColW - 6) / 2, hy, C_LABEL);

        for (int i = 0; i < 4; i++) {
            final int slot = i;
            int ry = y + rowH * (i + 1);
            int h = chipH;
            String id = m.moves[i];
            MoveTemplate template = Names.move(id);
            if (template != null) {
                ElementalType type = template.getElementalType();
                iconOps.add(gg -> Ui.typeIcon(gg, type, l.x + 1, ry + (h - 9) / 2, 9));
            }
            int nameX = l.x + 14;
            int nameW = barX - 6 - nameX;
            String label = id.isEmpty() ? Names.tr("feurcalc.moves.empty") : Names.moveName(id);
            JsonObject r = id.isEmpty() || target.isEmpty() ? null : state.move(s == 0, i);
            List<Component> tip = new ArrayList<>();
            if (template != null) {
                tip.add(template.getDisplayName().copy().withStyle(ChatFormatting.YELLOW));
                String cat = template.getDamageCategory().getDisplayName().getString();
                String power = template.getPower() > 0 ? String.valueOf((int) template.getPower()) : "—";
                tip.add(tr("feurcalc.tip.move_info", Names.typeName(template.getElementalType().getName()), cat, power)
                        .withStyle(ChatFormatting.GRAY));
            }
            if (r != null && !CalcState.getString(r, "desc").isEmpty()) {
                tip.add(Component.literal(CalcState.getString(r, "desc")).withStyle(ChatFormatting.WHITE));
            }
            boolean seen = !id.isEmpty() && m.revealedMoves.contains(id);
            if (seen) {
                tip.add(tr("feurcalc.tip.revealed_move").withStyle(ChatFormatting.GREEN));
            }
            tip.add(tr("feurcalc.tip.move").withStyle(ChatFormatting.DARK_GRAY));
            chip(g, nameX, ry, nameW, h, label, id.isEmpty() ? C_DIM : seen ? C_REVEALED : C_TEXT, false, false, mx, my,
                    () -> openMovePicker(s, slot, nameX, ry + h), () -> {
                        m.moves[slot] = "";
                        state.spreadEdited(m);
                    }, tip);

            if (template != null) {
                // liseré de la couleur du type à gauche de la ligne
                g.fill(l.x - 3, ry + 2, l.x - 2, ry + h - 2, 0xFF000000 | template.getElementalType().getHue());
            }
            if (id.isEmpty() || target.isEmpty()) {
                continue;
            }
            int ty = textY(ry, h);
            Theme.rect(g, barX, ry + 1, barW, h - 2, 2, Theme.FIELD);
            if (r == null) {
                textCenter("…", barX + barW / 2, ty, C_DIM);
                continue;
            }
            if (!r.has("known") || !r.get("known").getAsBoolean()) {
                textCenter("?", barX + barW / 2, ty, C_DIM);
                continue;
            }
            if ("Status".equals(CalcState.getString(r, "category"))) {
                textCenter(Names.tr("feurcalc.moves.status"), barX + barW / 2, ty, C_DIM);
                continue;
            }
            double min = CalcState.getDouble(r, "minPct", 0);
            double max = CalcState.getDouble(r, "maxPct", 0);
            int n = CalcState.getInt(r, "koN", 0);
            double chance = CalcState.getDouble(r, "koChance", 0);
            int color = max <= 0 ? C_DIM : Names.koColor(n, chance);
            // Barre de dégâts : foncé = dégâts minimum, clair = jusqu'au maximum (100 % = barre pleine)
            int maxW = (int) Math.round(barW * Math.min(100, max) / 100.0);
            int minW = (int) Math.round(barW * Math.min(100, min) / 100.0);
            if (maxW > 0) {
                Theme.rect(g, barX, ry + 1, Math.max(2, maxW), h - 2, 2, Theme.withAlpha(color, 0x38));
            }
            if (minW > 0) {
                Theme.rect(g, barX, ry + 1, Math.max(2, minW), h - 2, 2, Theme.withAlpha(color, 0x6A));
            }
            String dmg = max <= 0 ? "0%" : String.format(Locale.ROOT, "%.1f–%.1f%%", min, max);
            if (font.width(dmg) > barW - 6) {
                dmg = String.format(Locale.ROOT, "%.0f–%.0f%%", min, max);
            }
            textCenter(dmg, barX + barW / 2, ty, max <= 0 ? C_DIM : Theme.lighten(color, 0.35F));
            String ko = Names.koText(n, chance);
            if (!ko.isEmpty()) {
                int koW = Math.min(koColW - 6, font.width(ko) + 8);
                int koCenter = koRight - (koColW - 6) / 2;
                Theme.rect(g, koCenter - koW / 2, ry + 1, koW, h - 2, 2, Theme.darken(color, 0.62F));
                textCenter(Ui.ellipsize(font, ko, koW - 4), koCenter, ty, color);
            }
            tipArea(mx, my, barX, ry, koRight - barX, h, tip);
        }
    }

    private String shortName(MonState m) {
        return m.displayName.isEmpty() ? m.species.getTranslatedName().getString() : m.displayName;
    }

    private void drawStats(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.statsY;
        int ty = textY(y, chipH) + (rowH - chipH) / 2;
        textRight(Names.tr("feurcalc.col.base"), l.x + colBaseRight, ty, C_LABEL);
        textCenter("IV", l.x + colIv + COL_IV_W / 2, ty, C_LABEL);
        int evTotal = m.evTotal();
        String ev = "EV";
        textCenter(ev, l.x + colEv + COL_EV_W / 2, ty, evTotal > 510 ? 0xFFFF6A5A : C_LABEL);
        tipArea(mx, my, l.x + colEv, y, COL_EV_W, rowH,
                List.of(tr("feurcalc.tip.ev_total", evTotal), tr("feurcalc.tip.scroll")));
        textRight(Names.tr("feurcalc.col.stat"), l.x + colStatRight, ty, C_LABEL);
        textCenter(Names.tr("feurcalc.col.boost"), l.x + colBoost + boostWidth() / 2, ty, C_LABEL);
        int remaining = Math.max(0, 510 - evTotal);
        if (colBoost + boostWidth() + 8 + font.width("510") <= l.w) {
            textRight(String.valueOf(remaining), l.x + l.w, ty, evTotal > 510 ? 0xFFFF6A5A : 0xFF6A7480);
            tipArea(mx, my, l.x + l.w - 20, y, 20, rowH, List.of(tr("feurcalc.tip.ev_left", remaining)));
        }

        JsonObject summary = state.side(s == 0);
        JsonObject stats = summary != null && summary.has("stats") ? summary.getAsJsonObject("stats") : null;
        Map<com.cobblemon.mod.common.api.pokemon.stats.Stat, Integer> base = m.form.getBaseStats();
        for (int i = 0; i < 6; i++) {
            final int stat = i;
            int ry = statRowY(l, i);
            int rty = textY(ry, chipH);
            int nat = Names.natureModifier(m.nature, i);
            String label = Names.statLabel(i) + (nat > 0 ? "+" : nat < 0 ? "−" : "");
            text(label, l.x, rty, nat > 0 ? C_PLUS : nat < 0 ? C_MINUS : C_LABEL);
            Integer b = base.get(Names.STATS[i]);
            textRight(b == null ? "?" : String.valueOf(b), l.x + colBaseRight, rty, b == null ? C_DIM : Theme.statColor(b));

            if (stats != null && stats.has(Names.STAT_IDS[i])) {
                int raw = stats.get(Names.STAT_IDS[i]).getAsInt();
                int boost = i == 0 ? 0 : m.boosts[i];
                int value = boosted(raw, boost);
                textRight(String.valueOf(value), l.x + colStatRight, rty, boost > 0 ? 0xFF7FE08A : boost < 0 ? 0xFFFF8A7A : C_TEXT);
                if (boost != 0) {
                    tipArea(mx, my, l.x + colStatRight - 30, ry, 30, chipH,
                            List.of(tr("feurcalc.tip.boosted", raw, value)));
                }
            }
            if (i == 0) {
                continue;
            }
            int bx = l.x + colBoost;
            int h = chipH;
            chip(g, bx, ry, boostStep, h, "−", C_TEXT, false, true, mx, my,
                    () -> m.boosts[stat] = Math.max(-6, m.boosts[stat] - 1), null, null);
            int boost = m.boosts[i];
            String bt = boost > 0 ? "+" + boost : String.valueOf(boost);
            int vx = bx + boostStep + boostGap;
            chip(g, vx, ry, 20, h, bt, boost > 0 ? 0xFF7FE08A : boost < 0 ? 0xFFFF8A7A : C_DIM, false, true, mx, my,
                    null, () -> m.boosts[stat] = 0, null);
            chip(g, vx + 20 + boostGap, ry, boostStep, h, "+", C_TEXT, false, true, mx, my,
                    () -> m.boosts[stat] = Math.min(6, m.boosts[stat] + 1), null, null);
        }
    }

    private static int boosted(int raw, int boost) {
        if (boost >= 0) {
            return raw * (2 + boost) / 2;
        }
        return raw * 2 / (2 - boost);
    }

    private void drawSide(GuiGraphics g, int s, Layout l, int mx, int my) {
        FieldState.Side side = fieldSide(s);
        String who = shortName(mon(s));
        int gap = 5;
        int w = (l.w - 2 * gap) / 3;
        int h = chipH;
        int y = l.sideY;
        chip(g, l.x, y, w, h, Names.tr("feurcalc.side.reflect"), C_TEXT, side.reflect, true, mx, my,
                () -> side.reflect = !side.reflect, null, List.of(tr("feurcalc.tip.reflect", who)));
        chip(g, l.x + w + gap, y, w, h, Names.tr("feurcalc.side.light_screen"), C_TEXT, side.lightScreen, true, mx, my,
                () -> side.lightScreen = !side.lightScreen, null, List.of(tr("feurcalc.tip.light_screen", who)));
        chip(g, l.x + l.w - w, y, w, h, Names.tr("feurcalc.side.aurora_veil"), C_TEXT, side.auroraVeil, true, mx, my,
                () -> side.auroraVeil = !side.auroraVeil, null, List.of(tr("feurcalc.tip.aurora_veil", who)));
        y += rowH;
        chip(g, l.x, y, w, h, Names.tr("feurcalc.side.stealth_rock"), C_TEXT, side.stealthRock, true, mx, my,
                () -> side.stealthRock = !side.stealthRock, null, List.of(tr("feurcalc.tip.hazards", who)));
        chip(g, l.x + w + gap, y, w, h, Names.tr("feurcalc.side.spikes", side.spikes), C_TEXT, side.spikes > 0, true, mx, my,
                () -> side.spikes = (side.spikes + 1) % 4, () -> side.spikes = Math.floorMod(side.spikes - 1, 4),
                List.of(tr("feurcalc.tip.hazards", who), tr("feurcalc.tip.cycle")));
        chip(g, l.x + l.w - w, y, w, h, Names.tr("feurcalc.side.tailwind"), C_TEXT, side.tailwind, true, mx, my,
                () -> side.tailwind = !side.tailwind, null, List.of(tr("feurcalc.tip.tailwind", who)));
        if (layoutDoubles && state.field.doubles) {
            // Le partenaire en combat double
            y += rowH;
            int half = (l.w - gap) / 2;
            chip(g, l.x, y, half, h, Names.tr("feurcalc.side.helping_hand"), C_TEXT, side.helpingHand, true, mx, my,
                    () -> side.helpingHand = !side.helpingHand, null, List.of(tr("feurcalc.tip.helping_hand", who)));
            chip(g, l.x + l.w - half, y, half, h, Names.tr("feurcalc.side.friend_guard"), C_TEXT, side.friendGuard, true, mx, my,
                    () -> side.friendGuard = !side.friendGuard, null, List.of(tr("feurcalc.tip.friend_guard", who)));
        }
    }

    // =======================================================================================
    // Fenêtres de choix

    private void openPicker(Component title, List<Picker.Entry> entries, int x, int y, Consumer<Picker.Entry> onPick) {
        setFocused(null);
        picker = new Picker(font, title, entries, x, y, width, height, e -> {
            onPick.accept(e);
            syncBoxes(true);
        });
        picker.filter.setFocused(true);
    }

    private void openSpeciesPicker(int s, int x, int y) {
        pickerForms.clear();
        List<Species> all = new ArrayList<>(PokemonSpecies.getSpecies());
        all.sort(Comparator.comparingInt(Species::getNationalPokedexNumber));
        List<Picker.Entry> entries = new ArrayList<>();
        for (Species species : all) {
            String name = species.getTranslatedName().getString();
            String number = "#" + species.getNationalPokedexNumber();
            FormData standard = species.getStandardForm();
            Set<FormData> forms = new LinkedHashSet<>();
            forms.add(standard);
            forms.addAll(species.getForms());
            for (FormData form : forms) {
                String id = form.showdownId() + "@" + species.getResourceIdentifier();
                if (pickerForms.containsKey(id)) {
                    continue;
                }
                pickerForms.put(id, form);
                String form1 = form == standard ? "" : formLabel(form);
                String label = form1.isEmpty() ? name : name + " (" + form1 + ")";
                entries.add(Picker.Entry.of(id, label, number, null, !species.getImplemented(), species.getName() + " " + form.getName()));
            }
        }
        openPicker(tr("feurcalc.picker.species"), entries, x, y, e -> {
            FormData form = pickerForms.get(e.id());
            if (form == null) {
                return;
            }
            MonState m = mon(s);
            int level = m.isEmpty() ? 50 : m.level;
            MonState fresh = MonState.fromForm(form.getSpecies(), form, level);
            if (!SavedSets.apply(fresh)) {
                fresh.autoSet = true; // le set le plus joué, dès que les sets sont chargés
            }
            m.copyFrom(fresh);
            state.tick();
        });
    }

    private void openAbilityPicker(int s, int x, int y) {
        MonState m = mon(s);
        List<String> own = MonState.formAbilities(m.form);
        List<Picker.Entry> entries = new ArrayList<>();
        for (String id : own) {
            entries.add(Picker.Entry.of(id, Names.abilityName(id), "", null, false, ""));
        }
        List<Picker.Entry> others = new ArrayList<>();
        for (AbilityTemplate template : Abilities.all()) {
            String id = template.getName();
            if (own.contains(id) || id.equals("dummy")) {
                continue;
            }
            others.add(Picker.Entry.of(id, Names.abilityName(id), "", null, true, ""));
        }
        others.sort(Comparator.comparing(e -> Names.normalize(e.label())));
        entries.addAll(others);
        openPicker(tr("feurcalc.picker.ability"), entries, x, y, e -> {
            m.ability = e.id();
            state.spreadEdited(m);
        });
    }

    private void cycleAbility(int s) {
        MonState m = mon(s);
        List<String> own = MonState.formAbilities(m.form);
        if (own.isEmpty()) {
            return;
        }
        int index = own.indexOf(m.ability);
        m.ability = own.get((index + 1) % own.size());
        state.spreadEdited(m);
    }

    private void openItemPicker(int s, int x, int y) {
        MonState m = mon(s);
        List<Picker.Entry> entries = new ArrayList<>();
        entries.add(Picker.Entry.of("", Names.tr("feurcalc.none"), "", null, false, "aucun none"));
        for (Map.Entry<String, Item> e : Names.itemsById().entrySet()) {
            ItemStack stack = new ItemStack(e.getValue());
            entries.add(Picker.Entry.item(e.getKey(), stack.getHoverName().getString(), stack));
        }
        openPicker(tr("feurcalc.picker.item"), entries, x, y, e -> {
            m.item = e.id();
            state.spreadEdited(m);
        });
    }

    private void openNaturePicker(int s, int x, int y) {
        MonState m = mon(s);
        List<Picker.Entry> entries = new ArrayList<>();
        List<Nature> natures = new ArrayList<>(Natures.all());
        natures.sort(Comparator.comparing(n -> Names.normalize(I18n.get(n.getDisplayName()))));
        for (Nature nature : natures) {
            String id = nature.getName().getPath();
            entries.add(Picker.Entry.of(id, I18n.get(nature.getDisplayName()), Names.natureEffect(nature), null, false, ""));
        }
        openPicker(tr("feurcalc.picker.nature"), entries, x, y, e -> {
            m.nature = e.id();
            state.spreadEdited(m);
        });
    }

    private void openTeraPicker(int s, int x, int y) {
        MonState m = mon(s);
        List<Picker.Entry> entries = new ArrayList<>();
        for (ElementalType type : ElementalTypes.all()) {
            entries.add(Picker.Entry.of(type.getName(), type.getDisplayName().getString(), "", type, false, ""));
        }
        entries.add(Picker.Entry.of("stellar", Names.tr("feurcalc.type.stellar"), "", null, false, "stellar"));
        openPicker(tr("feurcalc.picker.tera"), entries, x, y, e -> {
            m.teraType = e.id();
            state.spreadEdited(m);
        });
    }

    private void openMovePicker(int s, int slot, int x, int y) {
        MonState m = mon(s);
        Set<String> learnable = new LinkedHashSet<>();
        if (m.form != null) {
            Learnset learnset = m.form.getMoves();
            for (List<MoveTemplate> list : learnset.getLevelUpMoves().values()) {
                addNames(learnable, list);
            }
            addNames(learnable, learnset.getTmMoves());
            addNames(learnable, learnset.getEggMoves());
            addNames(learnable, learnset.getTutorMoves());
            addNames(learnable, learnset.getEvolutionMoves());
            addNames(learnable, learnset.getLegacyMoves());
            addNames(learnable, learnset.getSpecialMoves());
            addNames(learnable, learnset.getFormChangeMoves());
        }
        List<Picker.Entry> main = new ArrayList<>();
        List<Picker.Entry> others = new ArrayList<>();
        for (MoveTemplate template : Moves.all()) {
            String id = template.getName();
            boolean learns = learnable.contains(id);
            Picker.Entry entry = Picker.Entry.of(id, template.getDisplayName().getString(), moveDetail(template),
                    template.getElementalType(), !learns, "");
            (learns ? main : others).add(entry);
        }
        main.sort(Comparator.comparing(e -> Names.normalize(e.label())));
        others.sort(Comparator.comparing(e -> Names.normalize(e.label())));
        List<Picker.Entry> entries = new ArrayList<>();
        entries.add(Picker.Entry.of("", Names.tr("feurcalc.moves.none"), "", null, false, "aucune none"));
        entries.addAll(main);
        entries.addAll(others);
        openPicker(tr("feurcalc.picker.move", slot + 1), entries, x, y, e -> {
            m.moves[slot] = e.id();
            state.spreadEdited(m);
        });
    }

    private static void addNames(Set<String> target, Collection<MoveTemplate> moves) {
        for (MoveTemplate template : moves) {
            target.add(template.getName());
        }
    }

    private static String moveDetail(MoveTemplate template) {
        String category = template.getDamageCategory().getName().toLowerCase(Locale.ROOT);
        String shortCat = Names.tr("feurcalc.category." + category);
        if (template.getPower() > 0) {
            return shortCat + " " + (int) template.getPower();
        }
        return shortCat;
    }
}
