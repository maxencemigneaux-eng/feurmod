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
 */
public final class FeurcalcScreen extends Screen {
    private static final int C_PANEL = 0xC0101418;
    private static final int C_OUTLINE = 0xFF3A4450;
    private static final int C_STRIPE = 0x14FFFFFF;
    private static final int C_LABEL = 0xFF8FA3B5;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_DIM = 0xFF8A949E;
    private static final int C_TITLE = 0xFFFFE08A;
    private static final int C_CHIP = 0x22FFFFFF;
    private static final int C_HOVER = 0x50FFFFFF;
    private static final int C_ON = 0x9030A050;
    private static final int C_PLUS = 0xFFFF9A8A;
    private static final int C_MINUS = 0xFF8AB4FF;

    // Colonnes du tableau des stats (relatives au bord intérieur du panneau)
    private static final int COL_BASE_RIGHT = 60;
    private static final int COL_IV = 64;
    private static final int COL_IV_W = 22;
    private static final int COL_EV = 89;
    private static final int COL_EV_W = 28;
    private static final int COL_STAT_RIGHT = 148;
    private static final int COL_BOOST = 153;

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
                             boolean spread) {
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
    private final Map<String, FormData> pickerForms = new HashMap<>();

    public FeurcalcScreen(@Nullable Screen parent) {
        super(Component.translatable("feurcalc.title"));
        this.parent = parent;
        CalcEngine.warmUp();
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

        contentW = Math.min(width - 8, 560);
        x0 = (width - contentW) / 2;
        topY = 4;
        panelY = topY + 17;
        panelW = (contentW - 6) / 2;
        int maxPanelH = height - panelY - 4;
        rowH = Mth.clamp((maxPanelH - 49) / 17, 10, 15);
        panelH = Math.min(maxPanelH, 49 + 17 * rowH);

        for (int s = 0; s < 2; s++) {
            Layout l = layouts[s];
            l.panelX = x0 + s * (panelW + 6);
            l.x = l.panelX + 4;
            l.w = panelW - 8;
            l.headerY = panelY + 4;
            l.hpY = l.headerY + 15;
            l.infoY = l.hpY + 13;
            l.movesY = l.infoY + 3 * rowH + 3;
            l.statsY = l.movesY + 5 * rowH + 3;
            l.sideY = l.statsY + 7 * rowH + 3;

            final int side = s;
            levelBoxes[s] = numberBox(side, l.x + l.w - 26, l.headerY + 1, 26, 12,
                    () -> mon(side).level, v -> mon(side).level = v, 1, 100, 1, false);
            hpBoxes[s] = numberBox(side, l.x + l.w - 80, l.hpY, 26, 11,
                    () -> mon(side).hpPercent, v -> mon(side).hpPercent = v, 0, 100, 5, false);
            for (int i = 0; i < 6; i++) {
                final int stat = i;
                int y = statRowY(l, i);
                ivBoxes[s][i] = numberBox(side, l.x + COL_IV, y, COL_IV_W, rowH - 1,
                        () -> mon(side).ivs[stat], v -> mon(side).ivs[stat] = v, 0, 31, 1, true);
                evBoxes[s][i] = numberBox(side, l.x + COL_EV, y, COL_EV_W, rowH - 1,
                        () -> mon(side).evs[stat], v -> mon(side).evs[stat] = v, 0, 252, 4, true);
            }
        }
        syncBoxes(true);
    }

    private int statRowY(Layout l, int stat) {
        return l.statsY + rowH * (stat + 1);
    }

    private EditBox numberBox(int side, int x, int y, int w, int h, IntSupplier getter, IntConsumer setter,
                              int min, int max, int step, boolean spread) {
        EditBox box = new EditBox(font, x, y, w, h, Component.empty());
        box.setMaxLength(3);
        box.setFilter(s -> s.matches("\\d{0,3}"));
        NumberBox nb = new NumberBox(box, getter, setter, min, max, step, side, spread);
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
        if (picker != null) {
            picker.mouseScrolled(scrollY);
            return true;
        }
        for (NumberBox nb : numberBoxes) {
            if (nb.box().visible && nb.box().isMouseOver(mx, my)) {
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
            g.fill(l.panelX, panelY, l.panelX + panelW, panelY + panelH, C_PANEL);
            g.renderOutline(l.panelX, panelY, panelW, panelH, s == 0 ? 0xFF3F6A8A : 0xFF8A4A4A);
            if (mon(s).isEmpty()) {
                continue;
            }
            // Bandes du tableau des stats et des attaques
            for (int i = 0; i < 6; i += 2) {
                int y = statRowY(l, i);
                g.fill(l.x - 2, y - 1, l.x + l.w + 2, y + rowH - 1, C_STRIPE);
            }
            for (int i = 0; i < 4; i += 2) {
                int y = l.movesY + rowH * (i + 1);
                g.fill(l.x - 2, y, l.x + l.w + 2, y + rowH, C_STRIPE);
            }
            g.fill(l.x, l.movesY - 2, l.x + l.w, l.movesY - 1, 0x30FFFFFF);
            g.fill(l.x, l.statsY - 2, l.x + l.w, l.statsY - 1, 0x30FFFFFF);
            g.fill(l.x, l.sideY - 2, l.x + l.w, l.sideY - 1, 0x30FFFFFF);
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
        int mx = picker == null ? mouseX : -10000;
        int my = picker == null ? mouseY : -10000;

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

        if (picker != null) {
            picker.render(g, mouseX, mouseY, partialTick);
        } else if (tooltip != null && !tooltip.isEmpty()) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (Component c : tooltip) {
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
        int bg = on ? C_ON : C_CHIP;
        if (hovered && (left != null || right != null)) {
            bg = on ? 0xB040C060 : C_HOVER;
        }
        g.fill(x, y, x + w, y + h, bg);
        String s = Ui.ellipsize(font, label, w - 4);
        int ty = y + (h - 8) / 2;
        if (center) {
            textCenter(s, x + w / 2, ty, color);
        } else {
            text(s, x + 3, ty, color);
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
        int h = 13;
        text("Feurcalc", x0, y + 3, C_TITLE);
        int x = x0 + font.width("Feurcalc") + 8;
        FieldState f = state.field;

        String weather = Names.tr("feurcalc.field.weather", Names.weatherName(f.weather));
        int w = font.width(weather) + 8;
        chip(g, x, y, w, h, weather, C_TEXT, !f.weather.isEmpty(), true, mx, my,
                () -> f.weather = cycle(Names.WEATHERS, f.weather, 1), () -> f.weather = cycle(Names.WEATHERS, f.weather, -1),
                List.of(tr("feurcalc.tip.cycle")));
        x += w + 3;

        String terrain = Names.tr("feurcalc.field.terrain", Names.terrainName(f.terrain));
        w = font.width(terrain) + 8;
        chip(g, x, y, w, h, terrain, C_TEXT, !f.terrain.isEmpty(), true, mx, my,
                () -> f.terrain = cycle(Names.TERRAINS, f.terrain, 1), () -> f.terrain = cycle(Names.TERRAINS, f.terrain, -1),
                List.of(tr("feurcalc.tip.cycle")));
        x += w + 3;

        String gravity = Names.tr("feurcalc.field.gravity");
        w = font.width(gravity) + 8;
        chip(g, x, y, w, h, gravity, C_TEXT, f.gravity, true, mx, my, () -> f.gravity = !f.gravity, null, null);
        x += w + 3;

        String crit = Names.tr("feurcalc.field.crit");
        w = font.width(crit) + 8;
        chip(g, x, y, w, h, crit, C_TEXT, f.crit, true, mx, my, () -> f.crit = !f.crit, null,
                List.of(tr("feurcalc.tip.crit")));
        x += w + 3;

        chip(g, x, y, 14, h, "⇄", C_TEXT, false, true, mx, my, state::swapSides, null, List.of(tr("feurcalc.tip.swap")));
        x += 17;
        chip(g, x, y, 12, h, "?", C_TITLE, false, true, mx, my, null, null, List.of(
                tr("feurcalc.help.1"), tr("feurcalc.help.2"), tr("feurcalc.help.3"), tr("feurcalc.help.4"), tr("feurcalc.help.5")));
        x += 15;

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
        if (!status.isEmpty() && right - sw > x + 4) {
            textRight(status, right, y + 3, color);
            if (tip != null) {
                tipArea(mx, my, right - sw, y, sw, h, tip);
            }
        }
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
        drawHp(g, s, l, m, mx, my);
        drawInfo(g, s, l, m, mx, my);
        drawMoves(g, s, l, m, mx, my);
        drawStats(g, s, l, m, mx, my);
        drawSide(g, s, l, mx, my);
    }

    private void drawEmptyPanel(GuiGraphics g, int s, int mx, int my) {
        Layout l = layouts[s];
        String label = Names.tr(s == 0 ? "feurcalc.side.a" : "feurcalc.side.b");
        text(label, l.x, l.headerY + 2, s == 0 ? 0xFF8AC4FF : 0xFFFF9A9A);
        String choose = Names.tr("feurcalc.choose_pokemon");
        int w = font.width(choose) + 12;
        int x = l.panelX + (panelW - w) / 2;
        int y = panelY + panelH / 2 - 8;
        chip(g, x, y, w, 16, choose, C_TITLE, false, true, mx, my, () -> openSpeciesPicker(s, x, y + 16), null, null);
        if (s == 0 && !CalcState.party().isEmpty()) {
            String party = Names.tr("feurcalc.from_party");
            int pw = font.width(party) + 12;
            int px = l.panelX + (panelW - pw) / 2;
            chip(g, px, y + 20, pw, 14, party, C_TEXT, false, true, mx, my, () -> state.cycleParty(1), null, null);
        }
    }

    private void drawHeader(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.headerY;
        int h = 14;
        // Partie droite : flèches de l'équipe (A) + niveau
        int right = l.x + l.w;
        textRight(Names.tr("feurcalc.level"), right - 29, y + 3, C_LABEL);
        int arrowsRight = right - 31 - font.width(Names.tr("feurcalc.level"));
        int nameMax = arrowsRight - l.x - 30;
        if (s == 0 && !CalcState.party().isEmpty()) {
            chip(g, arrowsRight - 24, y + 1, 11, 12, "◀", C_TEXT, false, true, mx, my, () -> state.cycleParty(-1), null,
                    List.of(tr("feurcalc.tip.party")));
            chip(g, arrowsRight - 12, y + 1, 11, 12, "▶", C_TEXT, false, true, mx, my, () -> state.cycleParty(1), null,
                    List.of(tr("feurcalc.tip.party")));
            nameMax -= 26;
        }

        // Nom du Pokémon (clic : en choisir un autre ; clic droit : valeurs par défaut)
        String name = m.displayName.isEmpty() ? m.species.getTranslatedName().getString() : m.displayName;
        String formName = formLabel(m.form);
        if (!formName.isEmpty()) {
            name += " (" + formName + ")";
        }
        int nameW = Math.min(font.width(name) + 8, Math.max(40, nameMax));
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
        int tx = l.x + nameW + 3;
        List<String> types = m.tera && !m.teraType.isEmpty() ? List.of(m.teraType) : Names.formTypes(m.form);
        for (String type : types) {
            ElementalType et = Names.type(type);
            final int ix = tx;
            if (et != null) {
                iconOps.add(gg -> Ui.typeIcon(gg, et, ix, y + 2, 10));
                tipArea(mx, my, ix, y + 2, 10, 10, List.of(Component.literal(Names.typeName(type))));
                tx += 12;
            } else if (type.equalsIgnoreCase("stellar")) {
                text("✦", ix, y + 3, 0xFFFFE08A);
                tx += 9;
            }
        }
        if (m.tera) {
            text(Names.tr("feurcalc.tera.short"), tx + 1, y + 3, 0xFFFFE08A);
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

    private void drawHp(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.hpY;
        text(Names.tr("feurcalc.stat.hp"), l.x, y + 2, C_LABEL);
        int barX = l.x + 18;
        int barRight = l.x + l.w - 84;
        int barW = Math.max(10, barRight - barX);
        g.fill(barX, y + 3, barX + barW, y + 8, 0xFF2A2F36);
        int filled = Math.round(barW * Mth.clamp(m.hpPercent, 0, 100) / 100F);
        g.fill(barX, y + 3, barX + filled, y + 8, Ui.hpColor(m.hpPercent));
        text("%", l.x + l.w - 52, y + 2, C_LABEL);
        JsonObject summary = state.side(s == 0);
        if (summary != null) {
            String hp = CalcState.getInt(summary, "curHP", 0) + "/" + CalcState.getInt(summary, "maxHP", 0);
            textRight(hp, l.x + l.w, y + 2, C_TEXT);
        }
        tipArea(mx, my, barX, y, barW, 11, List.of(tr("feurcalc.tip.hp")));
    }

    private void drawInfo(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int colW = (l.w - 4) / 2;
        int labelW = 34;
        int left = l.x;
        int rightCol = l.x + colW + 4;
        int h = rowH - 1;

        // Ligne 1 : talent | objet
        final int y = l.infoY;
        text(Names.tr("feurcalc.ability"), left, y + (h - 8) / 2, C_LABEL);
        List<Component> abilityTip = new ArrayList<>();
        String desc = Names.abilityDescription(m.ability);
        if (!desc.isEmpty()) {
            abilityTip.add(Component.literal(desc));
        }
        abilityTip.add(tr("feurcalc.tip.ability"));
        chip(g, left + labelW, y, colW - labelW, h, Names.abilityName(m.ability), C_TEXT, false, false, mx, my,
                () -> openAbilityPicker(s, left + labelW, y + h), () -> cycleAbility(s), abilityTip);

        text(Names.tr("feurcalc.item"), rightCol, y + (h - 8) / 2, C_LABEL);
        ItemStack stack = Names.itemStack(m.item);
        int itemX = rightCol + labelW;
        int iconSpace = stack.isEmpty() ? 0 : 10;
        if (!stack.isEmpty()) {
            iconOps.add(gg -> {
                gg.pose().pushPose();
                gg.pose().translate(itemX + 1, y + (h - 9) / 2F, 0);
                gg.pose().scale(0.5625F, 0.5625F, 1F);
                gg.renderItem(stack, 0, 0);
                gg.pose().popPose();
            });
        }
        String itemName = m.item.isEmpty() ? Names.tr("feurcalc.none") : Names.itemName(m.item);
        chip(g, itemX, y, colW - labelW, h, (iconSpace > 0 ? "   " : "") + itemName, m.item.isEmpty() ? C_DIM : C_TEXT,
                false, false, mx, my, () -> openItemPicker(s, itemX, y + h), () -> {
                    m.item = "";
                    state.spreadEdited(m);
                }, List.of(tr("feurcalc.tip.item")));

        // Ligne 2 : nature | Téra
        final int y2 = l.infoY + rowH;
        text(Names.tr("feurcalc.nature"), left, y2 + (h - 8) / 2, C_LABEL);
        Nature nature = Names.nature(m.nature);
        chip(g, left + labelW, y2, colW - labelW, h, Names.natureName(m.nature), C_TEXT, false, false, mx, my,
                () -> openNaturePicker(s, left + labelW, y2 + h), null,
                List.of(Component.literal(Names.natureEffect(nature)), tr("feurcalc.tip.nature")));

        text(Names.tr("feurcalc.tera"), rightCol, y2 + (h - 8) / 2, C_LABEL);
        int toggleW = 22;
        int teraW = colW - labelW - toggleW - 2;
        int teraX = rightCol + labelW;
        ElementalType teraIcon = Names.type(m.teraType);
        if (teraIcon != null) {
            final int iy = y2;
            iconOps.add(gg -> Ui.typeIcon(gg, teraIcon, teraX + 2, iy + (h - 9) / 2, 9));
        }
        chip(g, teraX, y2, teraW, h, (teraIcon != null ? "   " : "") + Names.typeName(m.teraType), C_TEXT, false, false,
                mx, my, () -> openTeraPicker(s, teraX, y2 + h), null, List.of(tr("feurcalc.tip.tera_type")));
        final int ty = y2;
        chip(g, teraX + teraW + 2, ty, toggleW, h, m.tera ? "ON" : "off", m.tera ? C_TEXT : C_DIM, m.tera, true, mx, my,
                () -> m.tera = !m.tera, null, List.of(tr("feurcalc.tip.tera")));

        // Ligne 3 : statut | vitesse
        final int y3 = l.infoY + 2 * rowH;
        text(Names.tr("feurcalc.status"), left, y3 + (h - 8) / 2, C_LABEL);
        chip(g, left + labelW, y3, colW - labelW, h, Names.statusName(m.status), m.status.isEmpty() ? C_DIM : 0xFFFFB070,
                false, false, mx, my, () -> m.status = cycle(Names.STATUSES, m.status, 1),
                () -> m.status = cycle(Names.STATUSES, m.status, -1), List.of(tr("feurcalc.tip.cycle")));

        text(Names.tr("feurcalc.speed"), rightCol, y3 + (h - 8) / 2, C_LABEL);
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
            text(label, rightCol + labelW + 3, y3 + (h - 8) / 2, color);
            tipArea(mx, my, rightCol, y3, colW, h, List.of(tr(key, sp, other), tr("feurcalc.tip.speed")));
        }
    }

    private void drawMoves(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.movesY;
        int koRight = l.x + l.w;
        int dmgRight = koRight - 50;
        MonState target = mon(1 - s);
        String vs = Names.tr("feurcalc.moves.vs", target.isEmpty() ? "?" : shortName(target));
        text(Ui.ellipsize(font, vs, dmgRight - 66 - l.x), l.x, y + (rowH - 8) / 2, C_LABEL);
        textRight(Names.tr("feurcalc.moves.damage"), dmgRight, y + (rowH - 8) / 2, C_LABEL);
        textRight(Names.tr("feurcalc.moves.ko"), koRight, y + (rowH - 8) / 2, C_LABEL);

        for (int i = 0; i < 4; i++) {
            final int slot = i;
            int ry = y + rowH * (i + 1);
            int h = rowH - 1;
            String id = m.moves[i];
            MoveTemplate template = Names.move(id);
            if (template != null) {
                ElementalType type = template.getElementalType();
                iconOps.add(gg -> Ui.typeIcon(gg, type, l.x, ry + (h - 9) / 2, 9));
            }
            int nameX = l.x + 11;
            int nameW = dmgRight - 64 - nameX;
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
            tip.add(tr("feurcalc.tip.move").withStyle(ChatFormatting.DARK_GRAY));
            chip(g, nameX, ry, nameW, h, label, id.isEmpty() ? C_DIM : C_TEXT, false, false, mx, my,
                    () -> openMovePicker(s, slot, nameX, ry + h), () -> {
                        m.moves[slot] = "";
                        state.spreadEdited(m);
                    }, tip);

            if (id.isEmpty() || target.isEmpty()) {
                continue;
            }
            int ty = ry + (h - 8) / 2;
            if (r == null) {
                textRight("…", dmgRight, ty, C_DIM);
                continue;
            }
            if (!r.has("known") || !r.get("known").getAsBoolean()) {
                textRight("?", dmgRight, ty, C_DIM);
                continue;
            }
            if ("Status".equals(CalcState.getString(r, "category"))) {
                textRight(Names.tr("feurcalc.moves.status"), dmgRight, ty, C_DIM);
                continue;
            }
            double min = CalcState.getDouble(r, "minPct", 0);
            double max = CalcState.getDouble(r, "maxPct", 0);
            int n = CalcState.getInt(r, "koN", 0);
            double chance = CalcState.getDouble(r, "koChance", 0);
            int color = max <= 0 ? C_DIM : Names.koColor(n, chance);
            String dmg = max <= 0 ? "0%" : String.format(Locale.ROOT, "%.1f–%.1f%%", min, max);
            textRight(dmg, dmgRight, ty, color);
            textRight(Names.koText(n, chance), koRight, ty, color);
            tipArea(mx, my, dmgRight - 64, ry, koRight - (dmgRight - 64), h, tip);
        }
    }

    private String shortName(MonState m) {
        return m.displayName.isEmpty() ? m.species.getTranslatedName().getString() : m.displayName;
    }

    private void drawStats(GuiGraphics g, int s, Layout l, MonState m, int mx, int my) {
        int y = l.statsY;
        int ty = y + (rowH - 8) / 2;
        textRight(Names.tr("feurcalc.col.base"), l.x + COL_BASE_RIGHT, ty, C_LABEL);
        textCenter("IV", l.x + COL_IV + COL_IV_W / 2, ty, C_LABEL);
        int evTotal = m.evTotal();
        String ev = "EV";
        textCenter(ev, l.x + COL_EV + COL_EV_W / 2, ty, evTotal > 510 ? 0xFFFF6A5A : C_LABEL);
        tipArea(mx, my, l.x + COL_EV, y, COL_EV_W, rowH,
                List.of(tr("feurcalc.tip.ev_total", evTotal), tr("feurcalc.tip.scroll")));
        textRight(Names.tr("feurcalc.col.stat"), l.x + COL_STAT_RIGHT, ty, C_LABEL);
        textCenter(Names.tr("feurcalc.col.boost"), l.x + COL_BOOST + 21, ty, C_LABEL);
        int remaining = Math.max(0, 510 - evTotal);
        if (l.x + COL_BOOST + 46 + font.width("510") < l.x + l.w) {
            textRight(String.valueOf(remaining), l.x + l.w, ty, evTotal > 510 ? 0xFFFF6A5A : 0xFF6A7480);
            tipArea(mx, my, l.x + l.w - 20, y, 20, rowH, List.of(tr("feurcalc.tip.ev_left", remaining)));
        }

        JsonObject summary = state.side(s == 0);
        JsonObject stats = summary != null && summary.has("stats") ? summary.getAsJsonObject("stats") : null;
        Map<com.cobblemon.mod.common.api.pokemon.stats.Stat, Integer> base = m.form.getBaseStats();
        for (int i = 0; i < 6; i++) {
            final int stat = i;
            int ry = statRowY(l, i);
            int rty = ry + (rowH - 1 - 8) / 2;
            int nat = Names.natureModifier(m.nature, i);
            String label = Names.statLabel(i) + (nat > 0 ? "+" : nat < 0 ? "−" : "");
            text(label, l.x, rty, nat > 0 ? C_PLUS : nat < 0 ? C_MINUS : C_LABEL);
            Integer b = base.get(Names.STATS[i]);
            textRight(b == null ? "?" : String.valueOf(b), l.x + COL_BASE_RIGHT, rty, 0xFFB8C4CF);

            if (stats != null && stats.has(Names.STAT_IDS[i])) {
                int raw = stats.get(Names.STAT_IDS[i]).getAsInt();
                int boost = i == 0 ? 0 : m.boosts[i];
                int value = boosted(raw, boost);
                textRight(String.valueOf(value), l.x + COL_STAT_RIGHT, rty, boost > 0 ? 0xFF7FE08A : boost < 0 ? 0xFFFF8A7A : C_TEXT);
                if (boost != 0) {
                    tipArea(mx, my, l.x + COL_STAT_RIGHT - 30, ry, 30, rowH - 1,
                            List.of(tr("feurcalc.tip.boosted", raw, value)));
                }
            }
            if (i == 0) {
                continue;
            }
            int bx = l.x + COL_BOOST;
            int h = rowH - 1;
            chip(g, bx, ry, 10, h, "−", C_TEXT, false, true, mx, my,
                    () -> m.boosts[stat] = Math.max(-6, m.boosts[stat] - 1), null, null);
            int boost = m.boosts[i];
            String bt = boost > 0 ? "+" + boost : String.valueOf(boost);
            chip(g, bx + 11, ry, 20, h, bt, boost > 0 ? 0xFF7FE08A : boost < 0 ? 0xFFFF8A7A : C_DIM, false, true, mx, my,
                    null, () -> m.boosts[stat] = 0, null);
            chip(g, bx + 32, ry, 10, h, "+", C_TEXT, false, true, mx, my,
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
        int w = (l.w - 4) / 3;
        int h = rowH - 1;
        int y = l.sideY;
        chip(g, l.x, y, w, h, Names.tr("feurcalc.side.reflect"), C_TEXT, side.reflect, true, mx, my,
                () -> side.reflect = !side.reflect, null, List.of(tr("feurcalc.tip.reflect", who)));
        chip(g, l.x + w + 2, y, w, h, Names.tr("feurcalc.side.light_screen"), C_TEXT, side.lightScreen, true, mx, my,
                () -> side.lightScreen = !side.lightScreen, null, List.of(tr("feurcalc.tip.light_screen", who)));
        chip(g, l.x + 2 * (w + 2), y, w, h, Names.tr("feurcalc.side.aurora_veil"), C_TEXT, side.auroraVeil, true, mx, my,
                () -> side.auroraVeil = !side.auroraVeil, null, List.of(tr("feurcalc.tip.aurora_veil", who)));
        y += rowH;
        chip(g, l.x, y, w, h, Names.tr("feurcalc.side.stealth_rock"), C_TEXT, side.stealthRock, true, mx, my,
                () -> side.stealthRock = !side.stealthRock, null, List.of(tr("feurcalc.tip.hazards", who)));
        chip(g, l.x + w + 2, y, w, h, Names.tr("feurcalc.side.spikes", side.spikes), C_TEXT, side.spikes > 0, true, mx, my,
                () -> side.spikes = (side.spikes + 1) % 4, () -> side.spikes = Math.floorMod(side.spikes - 1, 4),
                List.of(tr("feurcalc.tip.hazards", who), tr("feurcalc.tip.cycle")));
        chip(g, l.x + 2 * (w + 2), y, w, h, Names.tr("feurcalc.side.tailwind"), C_TEXT, side.tailwind, true, mx, my,
                () -> side.tailwind = !side.tailwind, null, List.of(tr("feurcalc.tip.tailwind", who)));
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
            SavedSets.apply(fresh);
            m.copyFrom(fresh);
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
