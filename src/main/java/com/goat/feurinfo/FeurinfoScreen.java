package com.goat.feurinfo;

import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.categories.DamageCategory;
import com.cobblemon.mod.common.api.pokemon.moves.Learnset;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility;
import com.goat.feurmod.ui.FlatButton;
import com.goat.feurmod.ui.Theme;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Écran /feurinfo :
 *  - à gauche : le Pokémon en 3D (Glisser pour tourner, clic = cri), sa forme (< >), un bouton shiny,
 *    puis ses talents (survol = description) et ses stats de base ;
 *  - à droite : ses attaques par onglet (Niveau, CT, Œufs, Tuteur, Évolution, Autres),
 *    survol d'une attaque = sa description ; dernier onglet "Spawn" = où il apparaît (biomes, conditions).
 */
public final class FeurinfoScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int TOP = 30;
    private static final int ROW_H = 16;
    /** Icônes rondes des types de Cobblemon : 18 icônes de 36 x 36 px côte à côte. */
    private static final ResourceLocation TYPE_ICONS = ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/gui/types.png");
    private static final int TYPE_ICON_SRC = 36;
    private static final int TYPE_ICON_SHEET_W = 648;
    private static final int TYPE_ICON = 14;
    private static final int TYPE_COL_W = 24;

    private enum Tab {
        LEVEL("level"), TM("tm"), EGG("egg"), TUTOR("tutor"), EVOLUTION("evolution"), OTHER("other"), WEAK("weak"), SPAWN("spawn");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private record Row(String level, MoveTemplate move) {
    }

    private record AbilityLine(AbilityTemplate template, boolean hidden) {
    }

    /** Ordre d'affichage des stats de base. */
    private static final Stats[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    private static final String[] STAT_KEYS = {"hp", "atk", "def", "spa", "spd", "spe"};
    private static final int INFO_ROW = 10;

    // --- État (conservé quand l'écran est reconstruit, ex. redimensionnement) ---
    private Species species;
    private final List<FormData> forms = new ArrayList<>();
    private int formIndex;
    private boolean shiny;
    private Tab tab = Tab.LEVEL;
    private final Map<Tab, List<Row>> rows = new EnumMap<>(Tab.class);
    private final List<AbilityLine> abilities = new ArrayList<>();
    private int scroll;
    private String moveFilterText = "";
    private final List<Row> filteredRows = new ArrayList<>();
    private String searchText = "";
    private Component searchError;
    private float rotation = 35F;
    private boolean draggingModel;
    private long lastFrameMs;
    // Onglet Spawn
    private List<SpawnView.Card> spawnCards = List.of();
    private String spawnKey = "";
    private List<LaidCard> laidCards = List.of();
    private int laidWidth = -1;
    private int spawnScroll;
    private int spawnContentH;

    private record LaidCard(SpawnView.Card card, List<FormattedCharSequence> lines, int height) {
    }

    private static final int SPAWN_LINE = 10;
    private static final int SPAWN_GAP = 4;

    // --- Widgets ---
    private EditBox search;
    @Nullable private FlatButton calcButton;
    private EditBox moveFilter;
    private ModelWidget model;
    private FlatButton shinyButton;
    private final Map<Tab, FlatButton> tabButtons = new EnumMap<>(Tab.class);

    // --- Mise en page ---
    private int leftX;
    private int leftW;
    private int rightX;
    private int rightW;
    private int panelY;
    private int panelH;
    private int modelX;
    private int modelY;
    private int modelW;
    private int modelH;
    private int filterY;
    private int formRowY;
    private int infoY;
    private int listTop;
    private int listBottom;
    private int searchX;
    private int searchW;
    private static final int SEARCH_Y = 7;

    public FeurinfoScreen(@Nullable Species species) {
        super(Component.translatable("feurinfo.title"));
        for (Tab t : Tab.values()) {
            rows.put(t, new ArrayList<>());
        }
        if (species != null) {
            setSpecies(species);
        }
    }

    // =======================================================================================
    // Données
    // =======================================================================================

    private void setSpecies(Species newSpecies) {
        species = newSpecies;
        forms.clear();
        FormData standard = newSpecies.getStandardForm();
        forms.add(standard);
        for (FormData form : newSpecies.getForms()) {
            if (form != standard && !form.getName().equalsIgnoreCase(standard.getName())) {
                forms.add(form);
            }
        }
        formIndex = 0;
        shiny = false;
        if (tab != Tab.SPAWN && tab != Tab.WEAK) {
            tab = Tab.LEVEL; // on reste sur "Spawn" ou "Faiblesses" si on y était
        }
        spawnScroll = 0;
        rotation = 35F;
        model = null;
        computeRows();
    }

    private FormData form() {
        return forms.get(formIndex);
    }

    private void computeRows() {
        for (Tab t : Tab.values()) {
            rows.get(t).clear();
        }
        scroll = 0;
        if (species == null) {
            return;
        }
        computeAbilities();
        Learnset learnset = form().getMoves(); // les formes sans attaques propres reprennent celles de l'espèce

        String evolutionLabel = I18n.get("feurinfo.level.evolution");
        Map<Integer, List<MoveTemplate>> byLevel = new TreeMap<>(learnset.getLevelUpMoves());
        for (Map.Entry<Integer, List<MoveTemplate>> entry : byLevel.entrySet()) {
            int level = entry.getKey();
            for (MoveTemplate move : entry.getValue()) {
                rows.get(Tab.LEVEL).add(new Row(level <= 0 ? evolutionLabel : String.valueOf(level), move));
            }
        }
        addSorted(Tab.TM, learnset.getTmMoves());
        addSorted(Tab.EGG, learnset.getEggMoves());
        addSorted(Tab.TUTOR, learnset.getTutorMoves());
        addSorted(Tab.EVOLUTION, learnset.getEvolutionMoves());
        List<MoveTemplate> other = new ArrayList<>();
        other.addAll(learnset.getLegacyMoves());
        other.addAll(learnset.getSpecialMoves());
        other.addAll(learnset.getFormChangeMoves());
        addSorted(Tab.OTHER, other);

        spawnScroll = 0;
        if (tab != Tab.SPAWN && tab != Tab.WEAK && rows.get(tab).isEmpty()) {
            for (Tab t : Tab.values()) {
                if (!rows.get(t).isEmpty()) {
                    tab = t;
                    break;
                }
            }
        }
        applyFilter();
    }

    /** Talents de la forme : les normaux d'abord, puis le talent caché. */
    private void computeAbilities() {
        abilities.clear();
        List<AbilityLine> hidden = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PotentialAbility potential : form().getAbilities()) {
            AbilityTemplate template = potential.getTemplate();
            if (!seen.add(template.getName())) {
                continue;
            }
            boolean isHidden = potential instanceof HiddenAbility;
            (isHidden ? hidden : abilities).add(new AbilityLine(template, isHidden));
        }
        abilities.addAll(hidden);
    }

    private static String abilityName(AbilityTemplate template) {
        String key = template.getDisplayName();
        return I18n.exists(key) ? I18n.get(key) : template.getName();
    }

    /** Hauteur du bloc talents + stats (sous le modèle). */
    private int infoHeight() {
        int abilityLines = Math.max(3, abilities.size());
        return INFO_ROW + abilityLines * INFO_ROW + 6 + INFO_ROW + 6 * INFO_ROW + INFO_ROW + 2;
    }

    // --- Recherche d'attaque dans la liste du Pokémon ---

    private boolean filtering() {
        return !SpeciesLookup.normalize(moveFilterText).isEmpty();
    }

    /** Quand on cherche, on regarde dans TOUTES les catégories (Niveau, CT, Œufs...). */
    private void applyFilter() {
        filteredRows.clear();
        String query = SpeciesLookup.normalize(moveFilterText);
        if (query.isEmpty()) {
            return;
        }
        for (Tab t : Tab.values()) {
            for (Row row : rows.get(t)) {
                MoveTemplate move = row.move();
                String local = SpeciesLookup.normalize(move.getDisplayName().getString());
                String english = SpeciesLookup.normalize(move.getName());
                String type = SpeciesLookup.normalize(move.getElementalType().getDisplayName().getString());
                if (local.contains(query) || english.contains(query) || type.equals(query)) {
                    filteredRows.add(new Row(methodLabel(t, row), move));
                }
            }
        }
    }

    private static String methodLabel(Tab t, Row row) {
        return switch (t) {
            case LEVEL -> row.level().chars().allMatch(Character::isDigit)
                    ? I18n.get("feurinfo.method.level", row.level())
                    : row.level();
            case TM -> I18n.get("feurinfo.method.tm");
            case EGG -> I18n.get("feurinfo.method.egg");
            case TUTOR -> I18n.get("feurinfo.method.tutor");
            case EVOLUTION -> I18n.get("feurinfo.method.evolution");
            case OTHER -> I18n.get("feurinfo.method.other");
            case SPAWN -> "";
        };
    }

    private List<Row> currentRows() {
        return filtering() ? filteredRows : rows.get(tab);
    }

    private void onMoveFilterChanged(String text) {
        moveFilterText = text;
        scroll = 0;
        applyFilter();
        updateTabButtons();
    }

    private void addSorted(Tab target, Collection<MoveTemplate> moves) {
        List<MoveTemplate> list = new ArrayList<>(new LinkedHashSet<>(moves));
        list.sort(Comparator.comparing(move -> move.getDisplayName().getString()));
        for (MoveTemplate move : list) {
            rows.get(target).add(new Row("", move));
        }
    }

    private List<Tab> visibleTabs() {
        List<Tab> visible = new ArrayList<>();
        for (Tab t : Tab.values()) {
            if (!rows.get(t).isEmpty()) {
                visible.add(t);
            }
        }
        visible.remove(Tab.SPAWN);
        visible.remove(Tab.WEAK);
        if (visible.isEmpty()) {
            visible.add(Tab.LEVEL);
        }
        visible.add(Tab.WEAK); // toujours là : faiblesses et résistances
        visible.add(Tab.SPAWN); // toujours là, en dernier
        return visible;
    }

    /** Nom de forme traduit par Cobblemon ("Forme d'Alola", "Méga-Dracaufeu X"...). */
    private static String formName(FormData form, boolean standard) {
        String key = "cobblemon.ui.pokedex.info.form." + form.showdownId();
        if (I18n.exists(key) && !I18n.get(key).isBlank()) {
            return I18n.get(key);
        }
        if (standard && I18n.exists("cobblemon.ui.pokedex.info.form.normal")) {
            return I18n.get("cobblemon.ui.pokedex.info.form.normal");
        }
        return form.getName();
    }

    // =======================================================================================
    // Construction de l'écran
    // =======================================================================================

    @Override
    protected void init() {
        tabButtons.clear();
        SpawnIndex.ensureLoaded();

        panelY = TOP;
        panelH = height - TOP - MARGIN;
        leftX = MARGIN;
        leftW = Mth.clamp(width * 34 / 100, 130, 210);
        rightX = leftX + leftW + 6;
        rightW = width - MARGIN - rightX;
        modelX = leftX + 5;
        modelY = panelY + 5;
        modelW = leftW - 10;
        // Sous le modèle : la ligne forme / shiny (24 px), puis les talents et les stats de base
        modelH = Math.max(64, panelH - 5 - 24 - 4 - (species == null ? 0 : infoHeight()) - 4);
        formRowY = modelY + modelH + 4;
        infoY = formRowY + 24 + 2;
        filterY = panelY + 30;
        listTop = panelY + 62;
        listBottom = panelY + panelH - 5;

        searchW = Math.min(170, width / 3);
        searchX = width - MARGIN - searchW;
        search = Theme.fieldBox(font, searchX, SEARCH_Y, searchW, 16, Component.translatable("feurinfo.search.hint"));
        search.setHint(Component.translatable("feurinfo.search.hint").withStyle(s -> s.withColor(Theme.MUTED)));
        search.setMaxLength(40);
        search.setValue(searchText);
        search.setResponder(this::onSearchChanged);
        addRenderableWidget(search);

        calcButton = null;
        if (species == null) {
            setInitialFocus(search);
            return;
        }

        // Ouvrir le calculateur de dégâts avec ce Pokémon en face
        Component calcLabel = Component.translatable("feurinfo.open_calc");
        int calcW = font.width(calcLabel) + 14;
        calcButton = addRenderableWidget(new FlatButton(searchX - calcW - 6, SEARCH_Y, calcW, 16, calcLabel, this::openCalc)
                .accent(0xFF2A4F86));
        calcButton.setTooltip(Tooltip.create(Component.translatable("feurinfo.open_calc.tip")));

        model = createModel();
        addRenderableWidget(model);

        int rowY = formRowY;
        if (forms.size() > 1) {
            addRenderableWidget(new FlatButton(leftX + 5, rowY + 2, 18, 18, Component.literal("◀"), () -> changeForm(-1)));
            addRenderableWidget(new FlatButton(leftX + leftW - 47, rowY + 2, 18, 18, Component.literal("▶"), () -> changeForm(1)));
        }
        // Petit bouton shiny à droite de la ligne des formes
        shinyButton = addRenderableWidget(new FlatButton(leftX + leftW - 25, rowY + 2, 20, 18, shinyLabel(), this::toggleShiny)
                .accent(0xFF8A6A1E));
        shinyButton.selected = shiny;
        shinyButton.setTooltip(shinyTooltip());

        // Onglets dessinés (Niveau, CT, Œufs, Tuteur, Évolution, Autres, Spawn)
        List<Tab> visible = visibleTabs();
        int gap = 2;
        int tabW = Math.max(30, (rightW - 10 - gap * (visible.size() - 1)) / visible.size());
        int tabX = rightX + 5;
        for (Tab t : visible) {
            FlatButton button = new FlatButton(tabX, panelY + 5, tabW, 16, Component.translatable("feurinfo.tab." + t.key),
                    () -> selectTab(t));
            tabButtons.put(t, addRenderableWidget(button));
            tabX += tabW + gap;
        }

        // Barre de recherche d'attaque (cherche dans toutes les catégories du Pokémon)
        moveFilter = Theme.fieldBox(font, rightX + 6, filterY, filterW(), 16, Component.translatable("feurinfo.filter.hint"));
        moveFilter.setHint(Component.translatable("feurinfo.filter.hint").withStyle(s -> s.withColor(Theme.MUTED)));
        moveFilter.setMaxLength(40);
        moveFilter.setValue(moveFilterText);
        moveFilter.setResponder(this::onMoveFilterChanged);
        addRenderableWidget(moveFilter);
        updateTabButtons();
    }

    private int filterW() {
        return rightW - 12 - 72;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private ModelWidget createModel() {
        Set<String> aspects = new HashSet<>(form().getAspects());
        if (shiny) {
            aspects.add("shiny");
        }
        RenderablePokemon renderable = new RenderablePokemon(species, aspects, ItemStack.EMPTY);
        // Cobblemon utilise une échelle de 2.7 et un décalage de -12 pour une zone de 118 x 100 px.
        // On garde ces proportions (le plus petit des deux rapports, pour ne pas couper le Pokémon)
        // et on centre verticalement dans la zone.
        float ratio = Math.min(modelH / 100F, modelW / 118F);
        double offsetY = -12.0 * ratio + (modelH - 100.0 * ratio) / 2.0;
        return new ModelWidget(modelX, modelY, modelW, modelH, renderable,
                2.7F * ratio, rotation, offsetY, true, false, 13);
    }

    private void replaceModel() {
        if (model != null) {
            removeWidget(model);
        }
        model = createModel();
        addRenderableWidget(model);
    }

    private Component shinyLabel() {
        return shiny
                ? Component.literal("★").withStyle(s -> s.withColor(Theme.GOLD))
                : Component.literal("☆").withStyle(s -> s.withColor(Theme.TEXT_2));
    }

    private Tooltip shinyTooltip() {
        return Tooltip.create(Component.translatable(shiny ? "feurinfo.shiny.on" : "feurinfo.shiny.off"));
    }

    // =======================================================================================
    // Actions
    // =======================================================================================

    private void changeForm(int delta) {
        formIndex = Math.floorMod(formIndex + delta, forms.size());
        computeRows();
        rebuildWidgets(); // onglets, talents et stats peuvent changer d'une forme à l'autre
    }

    private void openCalc() {
        if (species != null && minecraft != null) {
            com.goat.feurcalc.FeurcalcClient.openWith(minecraft, species, form(), this);
        }
    }

    private void toggleShiny() {
        shiny = !shiny;
        shinyButton.selected = shiny;
        shinyButton.setMessage(shinyLabel());
        shinyButton.setTooltip(shinyTooltip());
        replaceModel();
    }

    private void selectTab(Tab t) {
        tab = t;
        scroll = 0;
        spawnScroll = 0;
        if (moveFilter != null && filtering()) {
            moveFilter.setValue(""); // cliquer un onglet quitte la recherche
        }
        updateTabButtons();
    }

    private void updateTabButtons() {
        boolean searching = filtering();
        tabButtons.forEach((t, button) -> button.selected = !searching && t == tab);
        if (moveFilter != null) {
            moveFilter.visible = (tab != Tab.SPAWN && tab != Tab.WEAK) || searching; // pas de recherche d'attaque dans Spawn / Faiblesses
        }
    }

    private void onSearchChanged(String text) {
        searchText = text;
        searchError = null;
        search.setSuggestion(null);
        String typed = SpeciesLookup.normalize(text);
        if (typed.isEmpty()) {
            return;
        }
        for (Species s : SpeciesLookup.all()) {
            String name = SpeciesLookup.displayName(s);
            if (name.toLowerCase().startsWith(text.toLowerCase()) && name.length() > text.length()) {
                search.setSuggestion(name.substring(text.length()));
                return;
            }
        }
    }

    private void runSearch() {
        Species found = SpeciesLookup.find(search.getValue());
        if (found == null) {
            searchError = Component.translatable("feurinfo.not_found", search.getValue());
            return;
        }
        searchText = "";
        searchError = null;
        setSpecies(found);
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search != null && search.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                runSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                Species found = SpeciesLookup.find(search.getValue());
                if (found != null) {
                    search.setValue(SpeciesLookup.displayName(found));
                }
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && model != null && isInModel(mouseX, mouseY)) {
            draggingModel = true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingModel) {
            rotation += (float) dragX * 2.5F;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingModel = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (species != null && showingSpawns() && mouseX >= rightX && mouseX < rightX + rightW
                && mouseY >= spawnTop() && mouseY < listBottom) {
            spawnScroll -= (int) Math.signum(scrollY) * 24;
            spawnScroll = Mth.clamp(spawnScroll, 0, spawnMaxScroll());
            return true;
        }
        if (species != null && mouseX >= rightX && mouseX < rightX + rightW && mouseY >= listTop && mouseY < listBottom) {
            scroll -= (int) Math.signum(scrollY) * 3;
            scroll = Mth.clamp(scroll, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isInModel(double mouseX, double mouseY) {
        return mouseX >= modelX && mouseX < modelX + modelW && mouseY >= modelY && mouseY < modelY + modelH;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, currentRows().size() - visibleRows());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =======================================================================================
    // Affichage
    // =======================================================================================

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Theme.panel(g, leftX, panelY, leftW, panelH, 4, Theme.PANEL, Theme.PANEL_BORDER);
        Theme.panel(g, rightX, panelY, rightW, panelH, 4, Theme.PANEL, Theme.PANEL_BORDER);
        if (search != null) {
            Theme.field(g, searchX, SEARCH_Y, searchW, 16, search.isFocused(), inRect(mouseX, mouseY, searchX, SEARCH_Y, searchW, 16));
        }
        if (species == null) {
            return;
        }
        // Carte du modèle 3D, éclairée par la couleur du type principal
        int hue = 0x8899AA;
        for (ElementalType type : form().getTypes()) {
            hue = type.getHue();
            break;
        }
        Theme.rect(g, modelX, modelY, modelW, modelH, 3, Theme.CARD_ALT);
        g.fillGradient(modelX + 1, modelY + 1, modelX + modelW - 1, modelY + modelH - 1,
                Theme.withAlpha(hue, 0x58), Theme.withAlpha(hue, 0x06));
        // Bandeau des onglets
        Theme.rect(g, rightX + 1, panelY + 1, rightW - 2, 24, 3, Theme.HEADER);
        Theme.divider(g, rightX + 1, panelY + 25, rightW - 2);
        if (moveFilter != null && moveFilter.visible) {
            Theme.field(g, rightX + 6, filterY, filterW(), 16, moveFilter.isFocused(),
                    inRect(mouseX, mouseY, rightX + 6, filterY, filterW(), 16));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrameMs == 0 ? 0F : Math.min(0.1F, (now - lastFrameMs) / 1000F);
        lastFrameMs = now;
        if (model != null) {
            if (!draggingModel) {
                rotation += dt * 20F; // rotation automatique lente
            }
            model.getRotationVector().y = rotation;
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.flush();

        drawHeader(graphics);
        if (searchError != null) {
            int errorRight = calcButton != null ? calcButton.getX() - 8 : searchX - 8;
            graphics.drawString(font, searchError, errorRight - font.width(searchError), 11, Theme.BAD, false);
        }

        if (species == null) {
            int cx = rightX + rightW / 2;
            int cy = panelY + panelH / 2;
            Component empty = Component.translatable("feurinfo.empty");
            List<FormattedCharSequence> lines = font.split(empty, rightW - 40);
            int boxW = 0;
            for (FormattedCharSequence line : lines) {
                boxW = Math.max(boxW, font.width(line));
            }
            boxW += 24;
            int boxH = lines.size() * 10 + 14;
            Theme.panel(graphics, cx - boxW / 2, cy - boxH / 2, boxW, boxH, 4, Theme.CARD, Theme.PANEL_BORDER);
            graphics.flush();
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawCenteredString(font, lines.get(i), cx, cy - boxH / 2 + 8 + i * 10, Theme.TEXT_2);
            }
            return;
        }

        graphics.drawCenteredString(font, Component.translatable("feurinfo.drag_hint").withStyle(ChatFormatting.ITALIC),
                leftX + leftW / 2, modelY + modelH - 11, Theme.withAlpha(Theme.TEXT_2, 0xA0));
        int rowY = formRowY;
        String formLabel = formName(form(), formIndex == 0);
        int formLeft = leftX + (forms.size() > 1 ? 26 : 5);
        int formRight = leftX + leftW - (forms.size() > 1 ? 50 : 28);
        int maxFormW = formRight - formLeft;
        String counter = forms.size() > 1 ? (formIndex + 1) + "/" + forms.size() : "";
        formLabel = Theme.ellipsize(font, formLabel, maxFormW);
        int labelY = counter.isEmpty() || maxFormW < 40 ? rowY + 7 : rowY + 3;
        graphics.drawCenteredString(font, formLabel, (formLeft + formRight) / 2, labelY, Theme.TEXT);
        if (!counter.isEmpty() && maxFormW >= 40) {
            graphics.drawCenteredString(font, counter, (formLeft + formRight) / 2, rowY + 12, Theme.MUTED);
        }

        drawInfo(graphics, mouseX, mouseY);
        if (showingSpawns()) {
            drawSpawns(graphics, mouseX, mouseY);
        } else if (showingWeak()) {
            drawWeak(graphics, mouseX, mouseY);
        } else {
            drawMoves(graphics, mouseX, mouseY);
        }
    }

    /** "#0006  Dracaufeu" + les types en pastilles colorées. */
    private void drawHeader(GuiGraphics g) {
        int x = MARGIN;
        if (species == null) {
            g.drawString(font, title.copy().withStyle(ChatFormatting.BOLD), x, 11, Theme.TEXT, false);
            return;
        }
        String number = String.format("#%04d", species.getNationalPokedexNumber());
        g.drawString(font, number, x, 11, Theme.MUTED, false);
        x += font.width(number) + 6;
        Component name = species.getTranslatedName().copy().withStyle(ChatFormatting.BOLD);
        g.drawString(font, name, x, 11, Theme.TEXT, false);
        x += font.width(name) + 8;
        for (ElementalType type : form().getTypes()) {
            x += typePill(g, type, x, 8, 14) + 3;
        }
    }

    /** Pastille de type : icône + nom, sur la couleur du type. Renvoie sa largeur. */
    private int typePill(GuiGraphics g, ElementalType type, int x, int y, int h) {
        return typePill(g, type, x, y, h, type.getDisplayName().getString());
    }

    private int typePillWidth(String name, int h) {
        return 2 + (h - 4) + 3 + font.width(name) + 6;
    }

    private int typePill(GuiGraphics g, ElementalType type, int x, int y, int h, String name) {
        int icon = h - 4;
        int w = 2 + icon + 3 + font.width(name) + 6;
        Theme.rect(g, x, y, w, h, 3, Theme.typeBackground(type.getHue()));
        g.flush();
        drawTypeIcon(g, type, x + 2, y + 2, icon);
        g.drawString(font, name, x + 2 + icon + 3, y + (h - 8) / 2, 0xFFFFFFFF, false);
        return w;
    }

    /** Talents (survol = description) et stats de base, sous le modèle 3D. */
    private void drawInfo(GuiGraphics graphics, int mouseX, int mouseY) {
        int x0 = leftX + 7;
        int x1 = leftX + leftW - 7;
        int abilitiesY = infoY + INFO_ROW;
        int statsTitleY = infoY + INFO_ROW + Math.max(3, abilities.size()) * INFO_ROW + 6;
        int statsY = statsTitleY + INFO_ROW;
        Map<Stat, Integer> base = form().getBaseStats();

        int labelW = 0;
        for (String key : STAT_KEYS) {
            labelW = Math.max(labelW, font.width(I18n.get("feurinfo.stat." + key)));
        }
        int valueRight = x0 + labelW + 4 + font.width("255");
        int barX = valueRight + 5;
        int barW = Math.max(10, x1 - barX);

        // 1er passage : fonds, barres
        Theme.divider(graphics, x0 - 3, infoY - 3, x1 - x0 + 6);
        AbilityLine hoveredAbility = null;
        for (int i = 0; i < abilities.size(); i++) {
            AbilityLine line = abilities.get(i);
            int ry = abilitiesY + i * INFO_ROW;
            boolean hover = mouseX >= x0 - 3 && mouseX < x1 + 3 && mouseY >= ry - 1 && mouseY < ry + INFO_ROW - 1;
            if (hover) {
                hoveredAbility = line;
            }
            int bg = line.hidden() ? (hover ? 0xFF3B3060 : 0xFF2A2442) : (hover ? Theme.CARD_HOVER : Theme.CARD);
            Theme.rect(graphics, x0 - 3, ry - 1, x1 - x0 + 6, INFO_ROW - 1, 2, bg);
        }
        Theme.divider(graphics, x0 - 3, statsTitleY - 4, x1 - x0 + 6);
        int total = 0;
        for (int i = 0; i < STATS.length; i++) {
            int value = base.getOrDefault(STATS[i], 0);
            total += value;
            int ry = statsY + i * INFO_ROW;
            Theme.bar(graphics, barX, ry + 1, barW, 6, Math.min(1F, value / 200F), Theme.statColor(value), Theme.TRACK);
        }
        graphics.flush();

        // 2e passage : textes
        graphics.drawString(font, Component.translatable("feurinfo.abilities"), x0 - 1, infoY, Theme.LABEL, false);
        for (int i = 0; i < abilities.size(); i++) {
            AbilityLine line = abilities.get(i);
            int ry = abilitiesY + i * INFO_ROW;
            String suffix = line.hidden() ? I18n.get("feurinfo.ability.hidden") : "";
            int suffixW = suffix.isEmpty() ? 0 : font.width(suffix) + 4;
            String name = Theme.ellipsize(font, abilityName(line.template()), x1 - x0 - 2 - suffixW);
            graphics.drawString(font, name, x0 + 1, ry, line.hidden() ? Theme.PURPLE : Theme.TEXT, false);
            if (!suffix.isEmpty()) {
                graphics.drawString(font, suffix, x1 - font.width(suffix), ry, 0xFF8F7FBF, false);
            }
        }
        if (abilities.isEmpty()) {
            graphics.drawString(font, "—", x0 + 1, abilitiesY, Theme.MUTED, false);
        }

        graphics.drawString(font, Component.translatable("feurinfo.stats"), x0 - 1, statsTitleY, Theme.LABEL, false);
        for (int i = 0; i < STATS.length; i++) {
            int value = base.getOrDefault(STATS[i], 0);
            int ry = statsY + i * INFO_ROW;
            graphics.drawString(font, I18n.get("feurinfo.stat." + STAT_KEYS[i]), x0, ry, Theme.TEXT_2, false);
            drawRight(graphics, Component.literal(String.valueOf(value)), valueRight, ry, Theme.TEXT);
        }
        int totalY = statsY + STATS.length * INFO_ROW;
        graphics.drawString(font, Component.translatable("feurinfo.stat.total"), x0, totalY, Theme.TEXT_2, false);
        drawRight(graphics, Component.literal(String.valueOf(total)), valueRight, totalY, Theme.GOLD);

        if (hoveredAbility != null) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(Component.literal(abilityName(hoveredAbility.template())).withStyle(ChatFormatting.BOLD).getVisualOrderText());
            if (hoveredAbility.hidden()) {
                lines.add(Component.translatable("feurinfo.ability.hidden_long").withStyle(ChatFormatting.LIGHT_PURPLE).getVisualOrderText());
            }
            String descKey = hoveredAbility.template().getDescription();
            if (I18n.exists(descKey)) {
                lines.addAll(font.split(Component.literal(I18n.get(descKey)).withStyle(ChatFormatting.GRAY), 220));
            }
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void drawMoves(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Row> list = currentRows();
        boolean searching = filtering();
        int x0 = rightX + 7;
        int x1 = rightX + rightW - 9;
        boolean showLevel = searching || tab == Tab.LEVEL;
        boolean showCategory = rightW >= 330;

        // Colonnes, de droite à gauche
        int ppRight = x1 - 2;
        int accRight = ppRight - 24;
        int powRight = accRight - 34;
        int catX = powRight - 34 - 58;
        int typeX = (showCategory ? catX : powRight - 34) - TYPE_COL_W - 2;
        int nameX = x0 + 2 + (showLevel ? (searching ? 48 : 26) : 0);

        // Nombre d'attaques, à droite de la barre de recherche
        Component count = Component.translatable("feurinfo.moves_count", list.size());
        drawRight(graphics, count, x1, filterY + 4, searching ? Theme.GOLD : Theme.TEXT_2);

        int headerY = panelY + 50;
        int gray = Theme.LABEL;
        if (showLevel) {
            graphics.drawString(font, Component.translatable(searching ? "feurinfo.col.method" : "feurinfo.col.level"), x0 + 2, headerY, gray, false);
        }
        graphics.drawString(font, Component.translatable("feurinfo.col.name"), nameX, headerY, gray, false);
        graphics.drawString(font, Component.translatable("feurinfo.col.type"), typeX, headerY, gray, false);
        if (showCategory) {
            graphics.drawString(font, Component.translatable("feurinfo.col.category"), catX, headerY, gray, false);
        }
        drawRight(graphics, Component.translatable("feurinfo.col.power"), powRight, headerY, gray);
        drawRight(graphics, Component.translatable("feurinfo.col.accuracy"), accRight, headerY, gray);
        drawRight(graphics, Component.translatable("feurinfo.col.pp"), ppRight, headerY, gray);
        Theme.divider(graphics, x0 - 2, listTop - 3, x1 - x0 + 4);

        if (list.isEmpty()) {
            Component empty = searching
                    ? Component.translatable("feurinfo.no_match", moveFilterText.trim())
                    : Component.translatable("feurinfo.no_moves");
            graphics.drawCenteredString(font, empty, rightX + rightW / 2, listTop + 12, Theme.TEXT_2);
            return;
        }

        int visible = visibleRows();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        int shown = Math.min(visible, list.size() - scroll);
        Row hovered = null;

        graphics.enableScissor(rightX + 1, listTop, rightX + rightW - 1, listBottom);

        // 1er passage : les fonds (cartes des lignes, pastilles de catégorie)
        for (int i = 0; i < shown; i++) {
            Row row = list.get(scroll + i);
            int y = listTop + i * ROW_H;
            boolean hover = mouseX >= x0 - 2 && mouseX < x1 + 2 && mouseY >= y && mouseY < y + ROW_H && mouseY < listBottom;
            if (hover) {
                hovered = row;
            }
            int bg = hover ? Theme.CARD_HOVER : (scroll + i) % 2 == 0 ? Theme.CARD : Theme.CARD_ALT;
            Theme.rect(graphics, x0 - 2, y, x1 - x0 + 4, ROW_H - 1, 2, bg);
            int hue = 0xFF000000 | row.move().getElementalType().getHue();
            graphics.fill(x0 - 2, y + 2, x0, y + ROW_H - 3, hover ? hue : Theme.withAlpha(hue, 0x90));
            if (showCategory) {
                int color = categoryColor(row.move().getDamageCategory());
                Theme.rect(graphics, catX - 3, y + 3, 56, ROW_H - 7, 2, Theme.darken(color, 0.72F));
            }
        }
        // On dessine les fonds maintenant, pour que les icônes et le texte passent par-dessus
        graphics.flush();

        // Icônes de type (celles de Cobblemon)
        int iconX = typeX + (TYPE_COL_W - TYPE_ICON) / 2;
        for (int i = 0; i < shown; i++) {
            int y = listTop + i * ROW_H;
            drawTypeIcon(graphics, list.get(scroll + i).move().getElementalType(), iconX, y + (ROW_H - 1 - TYPE_ICON) / 2, TYPE_ICON);
        }

        // 2e passage : les textes
        for (int i = 0; i < shown; i++) {
            Row row = list.get(scroll + i);
            MoveTemplate move = row.move();
            int y = listTop + i * ROW_H;
            int textY = y + (ROW_H - 1 - 8) / 2;
            if (showLevel) {
                graphics.drawString(font, row.level(), x0 + 2, textY, Theme.GOLD, false);
            }
            String name = Theme.ellipsize(font, move.getDisplayName().getString(), typeX - nameX - 4);
            graphics.drawString(font, name, nameX, textY, Theme.TEXT, false);
            if (showCategory) {
                DamageCategory category = move.getDamageCategory();
                String cat = Theme.ellipsize(font, category.getDisplayName().getString(), 52);
                graphics.drawString(font, cat, catX - 3 + (56 - font.width(cat)) / 2, textY, categoryColor(category), false);
            }
            double power = move.getPower();
            double accuracy = move.getAccuracy();
            drawRight(graphics, Component.literal(power > 0 ? String.valueOf((int) power) : "—"), powRight, textY, power > 0 ? Theme.TEXT : Theme.MUTED);
            drawRight(graphics, Component.literal(accuracy > 0 && accuracy <= 100 ? (int) accuracy + "%" : "—"), accRight, textY,
                    accuracy > 0 && accuracy <= 100 ? Theme.TEXT_2 : Theme.MUTED);
            drawRight(graphics, Component.literal(String.valueOf(move.getPp())), ppRight, textY, Theme.TEXT_2);
        }
        graphics.disableScissor();

        // Barre de défilement
        if (list.size() > visible) {
            int trackH = listBottom - listTop;
            int barH = Math.max(12, trackH * visible / list.size());
            int barY = listTop + (trackH - barH) * scroll / Math.max(1, maxScroll());
            Theme.rect(graphics, x1 + 4, listTop, 3, trackH, 1, Theme.TRACK);
            Theme.rect(graphics, x1 + 4, barY, 3, barH, 1, Theme.lighten(Theme.TRACK, 0.4F));
        }

        if (hovered != null) {
            MoveTemplate move = hovered.move();
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(move.getDisplayName().copy().withStyle(ChatFormatting.BOLD).getVisualOrderText());
            int typeColor = move.getElementalType().getHue() & 0xFFFFFF;
            lines.add(Component.translatable("feurinfo.tooltip.type", move.getElementalType().getDisplayName())
                    .withStyle(style -> style.withColor(typeColor)).getVisualOrderText());
            if (!showCategory) {
                int color = categoryColor(move.getDamageCategory()) & 0xFFFFFF;
                lines.add(move.getDamageCategory().getDisplayName().copy()
                        .withStyle(style -> style.withColor(color)).getVisualOrderText());
            }
            lines.addAll(font.split(move.getDescription().copy().withStyle(ChatFormatting.GRAY), 220));
            if (move.getPriority() != 0) {
                String priority = (move.getPriority() > 0 ? "+" : "") + move.getPriority();
                lines.add(Component.translatable("feurinfo.tooltip.priority", priority).withStyle(ChatFormatting.DARK_AQUA).getVisualOrderText());
            }
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    // =======================================================================================
    // Onglet Faiblesses : dégâts reçus selon le type de l'attaque (et selon le talent)
    // =======================================================================================

    private boolean showingWeak() {
        return tab == Tab.WEAK && !filtering();
    }

    private static final double[] WEAK_GROUPS = {4, 2, 1, 0.5, 0.25, 0};
    private static final String[] WEAK_KEYS = {"x4", "x2", "x1", "half", "quarter", "immune"};
    private static final int[] WEAK_COLORS = {0xFFFF5F5F, 0xFFFF9A5A, 0xFFA9B5C1, 0xFF7FE08A, 0xFF4FD07A, 0xFF8AB4FF};

    private void drawWeak(GuiGraphics graphics, int mouseX, int mouseY) {
        int x0 = rightX + 8;
        int x1 = rightX + rightW - 8;
        int top = filterY;
        int pillH = 14;
        int line = pillH + 3;

        List<String> defense = new ArrayList<>();
        for (ElementalType type : form().getTypes()) {
            defense.add(type.getName());
        }
        Map<String, Double> base = TypeChart.defensive(defense);

        graphics.enableScissor(rightX + 1, top - 2, rightX + rightW - 1, listBottom);
        graphics.drawString(font, Component.translatable("feurinfo.weak.title"), x0, top, Theme.LABEL, false);
        int y = top + 14;

        // Largeur de la colonne des libellés ("×2  Faible")
        int labelW = 0;
        for (int i = 0; i < WEAK_GROUPS.length; i++) {
            labelW = Math.max(labelW, font.width(TypeChart.label(WEAK_GROUPS[i]) + "  " + I18n.get("feurinfo.weak." + WEAK_KEYS[i])));
        }
        labelW += 10;

        for (int i = 0; i < WEAK_GROUPS.length; i++) {
            List<ElementalType> types = new ArrayList<>();
            for (Map.Entry<String, Double> e : base.entrySet()) {
                if (Math.abs(e.getValue() - WEAK_GROUPS[i]) < 1e-6) {
                    ElementalType type = elementalType(e.getKey());
                    if (type != null) {
                        types.add(type);
                    }
                }
            }
            if (types.isEmpty()) {
                continue;
            }
            Theme.rect(graphics, x0 - 3, y - 1, 2, pillH + 2, 1, WEAK_COLORS[i]);
            graphics.flush();
            graphics.drawString(font, TypeChart.label(WEAK_GROUPS[i]), x0 + 2, y + 3, WEAK_COLORS[i], false);
            graphics.drawString(font, I18n.get("feurinfo.weak." + WEAK_KEYS[i]),
                    x0 + 2 + font.width(TypeChart.label(WEAK_GROUPS[i]) + "  "), y + 3, Theme.TEXT_2, false);
            int px = x0 + labelW;
            for (ElementalType type : types) {
                int pw = typePillWidth(type.getDisplayName().getString(), pillH);
                if (px + pw > x1 && px > x0 + labelW) {
                    px = x0 + labelW;
                    y += line;
                }
                px += typePill(graphics, type, px, y, pillH) + 4;
            }
            y += line + 4;
        }

        // Ce que ses talents y changent
        Theme.divider(graphics, x0 - 3, y, x1 - x0 + 6);
        y += 6;
        graphics.drawString(font, Component.translatable("feurinfo.weak.abilities"), x0, y, Theme.LABEL, false);
        y += 13;
        boolean any = false;
        for (AbilityLine ability : abilities) {
            Map<String, Double> changed = TypeChart.withAbility(base, ability.template().getName());
            if (changed == null) {
                continue;
            }
            any = true;
            String name = abilityName(ability.template()) + " :";
            graphics.drawString(font, name, x0, y + 3, ability.hidden() ? Theme.PURPLE : Theme.TEXT, false);
            int px = x0 + Math.max(labelW, font.width(name) + 8);
            for (Map.Entry<String, Double> e : changed.entrySet()) {
                double before = base.get(e.getKey());
                if (Math.abs(e.getValue() - before) < 1e-6) {
                    continue;
                }
                ElementalType type = elementalType(e.getKey());
                if (type == null) {
                    continue;
                }
                String text = type.getDisplayName().getString() + " " + TypeChart.label(before) + " → " + TypeChart.label(e.getValue());
                int pw = typePillWidth(text, pillH);
                if (px + pw > x1 && px > x0 + labelW) {
                    px = x0 + labelW;
                    y += line;
                }
                px += typePill(graphics, type, px, y, pillH, text) + 4;
            }
            y += line + 2;
        }
        if (!any) {
            graphics.drawString(font, Component.translatable("feurinfo.weak.no_ability"), x0, y + 2, Theme.MUTED, false);
        }
        graphics.disableScissor();
    }

    @Nullable
    private static ElementalType elementalType(String id) {
        try {
            return com.cobblemon.mod.common.api.types.ElementalTypes.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    // =======================================================================================
    // Onglet Spawn : où apparaît le Pokémon
    // =======================================================================================

    private boolean showingSpawns() {
        return tab == Tab.SPAWN && !filtering();
    }

    private int spawnTop() {
        return filterY + 26;
    }

    private int spawnMaxScroll() {
        return Math.max(0, spawnContentH - (listBottom - spawnTop()));
    }

    /** Refait les cartes si l'espèce, la forme ou les données ont changé, et les découpe en lignes à la bonne largeur. */
    private void prepareSpawns(int textW) {
        String key = species.getName() + "|" + formIndex + "|" + SpawnIndex.version();
        if (!key.equals(spawnKey)) {
            spawnKey = key;
            spawnCards = SpawnView.cards(species, forms, formIndex);
            laidWidth = -1;
        }
        if (laidWidth != textW) {
            laidWidth = textW;
            List<LaidCard> laid = new ArrayList<>();
            int total = 0;
            for (SpawnView.Card card : spawnCards) {
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (Component line : card.lines()) {
                    lines.addAll(font.split(line, textW));
                }
                int h = lines.size() * SPAWN_LINE + 5;
                laid.add(new LaidCard(card, lines, h));
                total += h + SPAWN_GAP;
            }
            laidCards = laid;
            spawnContentH = Math.max(0, total - SPAWN_GAP);
        }
    }

    private void drawSpawns(GuiGraphics graphics, int mouseX, int mouseY) {
        SpawnIndex.ensureLoaded(); // recharge si on a changé de monde
        int x0 = rightX + 6;
        int x1 = rightX + rightW - 8;
        int top = spawnTop();
        int bottom = listBottom;

        if (!SpawnIndex.isReady()) {
            graphics.drawCenteredString(font, Component.translatable("feurinfo.spawn.loading"), rightX + rightW / 2,
                    top + 12, Theme.TEXT_2);
            return;
        }
        prepareSpawns(x1 - x0 - 8);

        // En-tête : nombre d'apparitions + d'où viennent les données
        int n = laidCards.size();
        Component count = n == 1 ? Component.translatable("feurinfo.spawn.count_one")
                : Component.translatable("feurinfo.spawn.count", n);
        graphics.drawString(font, count, x0, filterY + 2, n == 0 ? Theme.TEXT_2 : Theme.GOLD, false);
        String sourceKey = SpawnIndex.source() == SpawnIndex.Source.WORLD ? "feurinfo.spawn.source.world" : "feurinfo.spawn.source.mods";
        String note = I18n.get(sourceKey);
        int noteMax = x1 - x0;
        if (font.width(note) > noteMax) {
            note = font.plainSubstrByWidth(note, Math.max(0, noteMax - font.width("…"))) + "…";
        }
        graphics.drawString(font, note, x0, filterY + 13, Theme.MUTED, false);
        boolean noteHovered = mouseX >= x0 && mouseX < x0 + font.width(note) && mouseY >= filterY + 12 && mouseY < filterY + 22;
        Theme.divider(graphics, x0 - 2, top - 3, x1 - x0 + 4);

        if (n == 0) {
            Component empty = Component.translatable(formIndex > 0 ? "feurinfo.spawn.none_form" : "feurinfo.spawn.none");
            List<FormattedCharSequence> lines = font.split(empty, x1 - x0 - 10);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawCenteredString(font, lines.get(i), rightX + rightW / 2, top + 10 + i * 10, Theme.TEXT_2);
            }
            if (noteHovered) {
                renderSourceTooltip(graphics, sourceKey, mouseX, mouseY);
            }
            return;
        }

        spawnScroll = Mth.clamp(spawnScroll, 0, spawnMaxScroll());
        boolean mouseInList = mouseX >= x0 - 2 && mouseX < x1 + 2 && mouseY >= top && mouseY < bottom;
        LaidCard hovered = null;

        graphics.enableScissor(rightX + 1, top, rightX + rightW - 1, bottom);
        // 1er passage : fonds des cartes + barre de couleur (rareté)
        int y = top - spawnScroll;
        for (LaidCard laid : laidCards) {
            if (y + laid.height() > top && y < bottom) {
                boolean hover = mouseInList && mouseY >= y && mouseY < y + laid.height();
                if (hover) {
                    hovered = laid;
                }
                Theme.rect(graphics, x0 - 2, y, x1 - x0 + 4, laid.height(), 3, hover ? Theme.CARD_HOVER : Theme.CARD);
                Theme.rect(graphics, x0 - 2, y + 2, 2, laid.height() - 4, 1, laid.card().color());
            }
            y += laid.height() + SPAWN_GAP;
        }
        graphics.flush();
        // 2e passage : textes
        y = top - spawnScroll;
        for (LaidCard laid : laidCards) {
            if (y + laid.height() > top && y < bottom) {
                int ty = y + 3;
                for (FormattedCharSequence line : laid.lines()) {
                    graphics.drawString(font, line, x0 + 4, ty, 0xFFFFFFFF, false);
                    ty += SPAWN_LINE;
                }
            }
            y += laid.height() + SPAWN_GAP;
        }
        graphics.disableScissor();

        // Barre de défilement
        int trackH = bottom - top;
        if (spawnContentH > trackH) {
            int barH = Math.max(10, trackH * trackH / spawnContentH);
            int barY = top + (trackH - barH) * spawnScroll / Math.max(1, spawnMaxScroll());
            Theme.rect(graphics, x1 + 4, top, 3, trackH, 1, Theme.TRACK);
            Theme.rect(graphics, x1 + 4, barY, 3, barH, 1, Theme.lighten(Theme.TRACK, 0.4F));
        }

        if (hovered != null && !hovered.card().tooltip().isEmpty()) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (Component line : hovered.card().tooltip()) {
                lines.addAll(font.split(line, 240));
            }
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        } else if (noteHovered) {
            renderSourceTooltip(graphics, sourceKey, mouseX, mouseY);
        }
    }

    private void renderSourceTooltip(GuiGraphics graphics, String sourceKey, int mouseX, int mouseY) {
        graphics.renderTooltip(font, font.split(Component.translatable(sourceKey + ".long"), 240), mouseX, mouseY);
    }

    private void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    /** Icône ronde du type, découpée dans la planche de Cobblemon (index = textureXMultiplier). */
    private static void drawTypeIcon(GuiGraphics graphics, ElementalType type, int x, int y, int size) {
        int u = type.getTextureXMultiplier() * TYPE_ICON_SRC;
        RenderSystem.enableBlend();
        graphics.blit(TYPE_ICONS, x, y, size, size, u, 0, TYPE_ICON_SRC, TYPE_ICON_SRC, TYPE_ICON_SHEET_W, TYPE_ICON_SRC);
        RenderSystem.disableBlend();
    }

    private static int categoryColor(DamageCategory category) {
        return switch (category.getName().toLowerCase()) {
            case "physical" -> 0xFFF08A4B;
            case "special" -> 0xFF7FA6FF;
            default -> 0xFFB8B8B8;
        };
    }
}
