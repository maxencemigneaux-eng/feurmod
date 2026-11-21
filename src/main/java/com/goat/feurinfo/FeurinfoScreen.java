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
        MOVES("moves"), WEAK("weak"), EVOS("evos"), SET("set"), SPAWN("spawn");

        /** Onglet qui affiche la liste d'attaques. */
        boolean moves() {
            return this == MOVES;
        }

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    /**
     * Une attaque du Pokémon, avec toutes ses façons de l'apprendre ("Niv. 1, CT, Tuteur").
     * sortLevel : 1er niveau d'apprentissage (Integer.MAX_VALUE si pas par niveau) ; methods : masque de METHOD_*.
     */
    private record Row(String level, MoveTemplate move, int sortLevel, int methods) {
    }

    // Façons d'apprendre une attaque (filtres "Niveau", "CT"...)
    private static final int M_LEVEL = 1, M_TM = 2, M_EGG = 4, M_TUTOR = 8, M_EVO = 16, M_OTHER = 32;
    private static final int[] METHOD_BITS = {M_LEVEL, M_TM, M_EGG, M_TUTOR, M_EVO, M_OTHER};
    private static final String[] METHOD_KEYS = {"level", "tm", "egg", "tutor", "evolution", "other"};

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
    /** UltraShiny (variante du serveur, mod "ultrashiny-client") : par-dessus le shiny. */
    private boolean ultraShiny;
    private Tab tab = Tab.MOVES;
    private final List<Row> allRows = new ArrayList<>();
    /** Filtres de la liste d'attaques (0 / vide = tout). */
    private int methodFilter;
    private int categoryFilter; // 1 = physique, 2 = spéciale, 4 = statut
    private final Set<String> typeFilter = new HashSet<>();
    private final Map<Integer, FlatButton> methodButtons = new java.util.HashMap<>();
    private final FlatButton[] categoryButtons = new FlatButton[3];
    private int typeRowY;
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
        ultraShiny = false;
        methodFilter = 0;
        categoryFilter = 0;
        typeFilter.clear();
        setIndex = 0;
        spawnScroll = 0;
        evoScroll = 0;
        evoChains = null;
        rotation = 35F;
        model = null;
        computeRows();
    }

    private FormData form() {
        return forms.get(formIndex);
    }

    private void computeRows() {
        allRows.clear();
        scroll = 0;
        if (species == null) {
            return;
        }
        computeAbilities();
        Learnset learnset = form().getMoves(); // les formes sans attaques propres reprennent celles de l'espèce

        // Une ligne par attaque, avec toutes ses méthodes d'apprentissage
        Map<String, MoveTemplate> moves = new java.util.LinkedHashMap<>();
        Map<String, java.util.TreeSet<Integer>> levels = new java.util.HashMap<>();
        Map<String, Integer> masks = new java.util.HashMap<>();
        java.util.function.BiConsumer<MoveTemplate, Integer> add = (move, bit) -> {
            moves.putIfAbsent(move.getName(), move);
            masks.merge(move.getName(), bit, (x, y) -> x | y);
        };
        for (Map.Entry<Integer, List<MoveTemplate>> entry : new TreeMap<>(learnset.getLevelUpMoves()).entrySet()) {
            for (MoveTemplate move : entry.getValue()) {
                add.accept(move, M_LEVEL);
                levels.computeIfAbsent(move.getName(), k -> new java.util.TreeSet<>()).add(Math.max(0, entry.getKey()));
            }
        }
        learnset.getTmMoves().forEach(m -> add.accept(m, M_TM));
        learnset.getEggMoves().forEach(m -> add.accept(m, M_EGG));
        learnset.getTutorMoves().forEach(m -> add.accept(m, M_TUTOR));
        learnset.getEvolutionMoves().forEach(m -> add.accept(m, M_EVO));
        learnset.getLegacyMoves().forEach(m -> add.accept(m, M_OTHER));
        learnset.getSpecialMoves().forEach(m -> add.accept(m, M_OTHER));
        learnset.getFormChangeMoves().forEach(m -> add.accept(m, M_OTHER));

        for (Map.Entry<String, MoveTemplate> e : moves.entrySet()) {
            int mask = masks.getOrDefault(e.getKey(), 0);
            java.util.TreeSet<Integer> lv = levels.get(e.getKey());
            List<String> parts = new ArrayList<>();
            if (lv != null && !lv.isEmpty()) {
                List<String> nums = new ArrayList<>();
                for (int l : lv) {
                    nums.add(l <= 0 ? I18n.get("feurinfo.level.evolution") : String.valueOf(l));
                }
                parts.add(I18n.get("feurinfo.method.level", String.join("/", nums)));
            }
            for (int i = 1; i < METHOD_BITS.length; i++) {
                if ((mask & METHOD_BITS[i]) != 0) {
                    parts.add(I18n.get("feurinfo.method." + METHOD_KEYS[i]));
                }
            }
            int sortLevel = lv == null || lv.isEmpty() ? Integer.MAX_VALUE : lv.first();
            allRows.add(new Row(String.join(", ", parts), e.getValue(), sortLevel, mask));
        }
        // Par niveau d'abord, puis le reste par ordre alphabétique
        allRows.sort(Comparator.comparingInt(Row::sortLevel)
                .thenComparing(r -> r.move().getDisplayName().getString()));
        spawnScroll = 0;
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
        int weak = weakGroups().size();
        return INFO_ROW + abilityLines * INFO_ROW + 6 + INFO_ROW + 6 * INFO_ROW + INFO_ROW + 2
                + (weak == 0 ? 0 : 6 + INFO_ROW + weak * (INFO_ROW + 2));
    }

    /** Résumé des faiblesses (sans talent) : multiplicateur → types, du pire au meilleur. */
    private List<Map.Entry<Double, List<String>>> weakGroups() {
        List<Map.Entry<Double, List<String>>> out = new ArrayList<>();
        if (species == null) return out;
        List<String> defense = new ArrayList<>();
        for (ElementalType t : form().getTypes()) {
            defense.add(t.getName().toLowerCase());
        }
        Map<String, Double> chart = TypeChart.defensive(defense);
        for (double mult : new double[]{4, 2, 0.5, 0.25, 0}) {
            List<String> types = new ArrayList<>();
            for (Map.Entry<String, Double> e : chart.entrySet()) {
                if (Math.abs(e.getValue() - mult) < 0.001) types.add(e.getKey());
            }
            if (!types.isEmpty()) out.add(Map.entry(mult, types));
        }
        return out;
    }

    // --- Recherche d'attaque dans la liste du Pokémon ---

    private boolean filtering() {
        return !SpeciesLookup.normalize(moveFilterText).isEmpty();
    }


    /** Recherche + filtres (méthode, catégorie, type). */
    private void applyFilter() {
        filteredRows.clear();
        String query = SpeciesLookup.normalize(moveFilterText);
        for (Row row : allRows) {
            MoveTemplate move = row.move();
            if (methodFilter != 0 && (row.methods() & methodFilter) == 0) continue;
            if (categoryFilter != 0 && (categoryBit(move.getDamageCategory()) & categoryFilter) == 0) continue;
            if (!typeFilter.isEmpty() && !typeFilter.contains(move.getElementalType().getName().toLowerCase())) continue;
            if (!query.isEmpty()) {
                String local = SpeciesLookup.normalize(move.getDisplayName().getString());
                String english = SpeciesLookup.normalize(move.getName());
                String type = SpeciesLookup.normalize(move.getElementalType().getDisplayName().getString());
                String category = SpeciesLookup.normalize(move.getDamageCategory().getDisplayName().getString());
                if (!(local.contains(query) || english.contains(query) || type.equals(query) || category.equals(query))) continue;
            }
            filteredRows.add(row);
        }
    }

    private static int categoryBit(DamageCategory category) {
        return switch (category.getName().toLowerCase()) {
            case "physical" -> 1;
            case "special" -> 2;
            default -> 4;
        };
    }

    private List<Row> currentRows() {
        return filteredRows;
    }

    private void onMoveFilterChanged(String text) {
        moveFilterText = text;
        scroll = 0;
        applyFilter();
        updateTabButtons();
    }

    private List<Tab> visibleTabs() {
        return List.of(Tab.values());
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
        typeRowY = panelY + 50;
        listTop = panelY + 82;
        listBottom = panelY + panelH - 5;

        // En haut à droite : recherche, Réglages ⚙, Fermer ✕
        int closeX = width - MARGIN - 16;
        int gearX = closeX - 4 - 16;
        addRenderableWidget(new FlatButton(closeX, SEARCH_Y, 16, 16, Component.literal("✕"), this::onClose));
        FlatButton gear = addRenderableWidget(new FlatButton(gearX, SEARCH_Y, 16, 16, Component.literal("⚙"),
                () -> minecraft.setScreen(new com.goat.feurmod.ui.SettingsScreen(this))));
        gear.setTooltip(Tooltip.create(Component.literal("Réglages Feurmod")));
        searchW = Math.min(170, width / 3);
        searchX = gearX - 6 - searchW;
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
            addRenderableWidget(new FlatButton(leftX + leftW - 23, rowY + 2, 18, 18, Component.literal("▶"), () -> changeForm(1)));
        }
        // Normal / Shiny : onglets en haut du modèle 3D
        Component normalLabel = Component.literal("Normal");
        Component shinyText = Component.literal("Shiny");
        int nw = font.width(normalLabel) + 10;
        int sw = font.width(shinyText) + 10;
        FlatButton normalBtn = addRenderableWidget(new FlatButton(modelX + 4, modelY + 4, nw, 13, normalLabel, () -> setVariant(false, false)));
        shinyButton = addRenderableWidget(new FlatButton(modelX + 4 + nw + 2, modelY + 4, sw, 13, shinyText, () -> setVariant(true, false))
                .accent(0xFF8A6A1E));
        // UltraShiny : textures du serveur (aspect "ultrashiny", posé sur le shiny)
        Component ultraText = Component.literal("UltraShiny");
        int uw = font.width(ultraText) + 10;
        ultraButton = addRenderableWidget(new FlatButton(modelX + 4 + nw + 2 + sw + 2, modelY + 4, uw, 13, ultraText,
                () -> setVariant(true, true)).accent(0xFF7A2E8A));
        ultraButton.setTooltip(Tooltip.create(Component.literal(
                "UltraShiny (textures du serveur)\nSans variante UltraShiny pour ce Pokémon, c'est le shiny qui s'affiche.")));
        shinyNormalButton = normalBtn;
        updateVariantButtons();

        java.util.Arrays.fill(categoryButtons, null);
        // Filtres par catégorie (physique / spéciale / statut), à droite de la barre d'onglets
        String[] catLabels = {"", "", ""}; // icônes de catégorie de Cobblemon, dessinées par-dessus
        String[] catTips = {"Physique", "Spéciale", "Statut"};
        int[] catColors = {0xFF8A4A2A, 0xFF34508A, 0xFF5A5A5A};
        int catW = 30;
        int catTotal = rightW >= 360 ? catW * 3 + 4 + 8 : 0;
        if (catTotal > 0) {
            int cx = rightX + rightW - 5 - catW * 3 - 4;
            for (int i = 0; i < 3; i++) {
                final int bit = 1 << i;
                FlatButton b = new FlatButton(cx + i * (catW + 2), panelY + 5, catW, 16, Component.literal(catLabels[i]), () -> {
                    categoryFilter ^= bit;
                    onFiltersChanged();
                }).accent(catColors[i]);
                b.setTooltip(Tooltip.create(Component.literal(catTips[i] + " : ne garder que ces attaques (re-clic : enlever)")));
                categoryButtons[i] = addRenderableWidget(b);
            }
        }

        // Onglets : Attaques, Faiblesses, Évolutions, Set courant, Spawn
        List<Tab> visible = visibleTabs();
        int gap = 2;
        int tabsW = rightW - 10 - catTotal;
        int tabW = Math.max(30, (tabsW - gap * (visible.size() - 1)) / visible.size());
        int tabX = rightX + 5;
        for (Tab t : visible) {
            FlatButton button = new FlatButton(tabX, panelY + 5, tabW, 16, Component.translatable("feurinfo.tab." + t.key),
                    () -> selectTab(t));
            tabButtons.put(t, addRenderableWidget(button));
            tabX += tabW + gap;
        }

        // Barre de recherche d'attaque, puis les filtres de méthode (Niveau, CT, Tuteur...) présents chez ce Pokémon
        moveFilter = Theme.fieldBox(font, rightX + 6, filterY, filterW(), 16, Component.translatable("feurinfo.filter.hint"));
        moveFilter.setHint(Component.translatable("feurinfo.filter.hint").withStyle(s -> s.withColor(Theme.MUTED)));
        moveFilter.setMaxLength(40);
        moveFilter.setValue(moveFilterText);
        moveFilter.setResponder(this::onMoveFilterChanged);
        addRenderableWidget(moveFilter);

        methodButtons.clear();
        int present = 0;
        for (Row r : allRows) {
            present |= r.methods();
        }
        int mx = rightX + 6 + filterW() + 6;
        int countW = 72;
        for (int i = 0; i < METHOD_BITS.length; i++) {
            final int bit = METHOD_BITS[i];
            if ((present & bit) == 0) {
                continue;
            }
            Component label = Component.translatable("feurinfo.tab." + METHOD_KEYS[i]);
            int w = font.width(label) + 10;
            if (mx + w > rightX + rightW - countW) {
                break;
            }
            FlatButton b = new FlatButton(mx, filterY, w, 16, label, () -> {
                methodFilter ^= bit;
                onFiltersChanged();
            });
            b.setTooltip(Tooltip.create(Component.literal("Ne garder que les attaques apprises comme ça (re-clic : enlever)")));
            methodButtons.put(bit, addRenderableWidget(b));
            mx += w + 3;
        }
        updateTabButtons();
    }

    private int filterW() {
        return Mth.clamp(rightW * 30 / 100, 90, 170);
    }

    @Nullable private FlatButton shinyNormalButton;
    @Nullable private FlatButton ultraButton;

    private void onFiltersChanged() {
        scroll = 0;
        applyFilter();
        updateTabButtons();
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private ModelWidget createModel() {
        Set<String> aspects = new HashSet<>(form().getAspects());
        if (shiny) {
            aspects.add("shiny");
        }
        if (ultraShiny) {
            aspects.add("ultrashiny");
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

    private void setVariant(boolean shinyValue, boolean ultra) {
        shiny = shinyValue;
        ultraShiny = ultra;
        updateVariantButtons();
        replaceModel();
    }

    private void updateVariantButtons() {
        if (shinyNormalButton != null) shinyNormalButton.selected = !shiny;
        if (shinyButton != null) shinyButton.selected = shiny && !ultraShiny;
        if (ultraButton != null) ultraButton.selected = ultraShiny;
    }

    private void selectTab(Tab t) {
        tab = t;
        scroll = 0;
        spawnScroll = 0;
        evoScroll = 0;
        if (moveFilter != null && filtering()) {
            moveFilter.setValue(""); // cliquer un onglet quitte la recherche
        }
        updateTabButtons();
    }

    private void updateTabButtons() {
        boolean searching = filtering();
        tabButtons.forEach((t, button) -> button.selected = !searching && t == tab);
        boolean moves = tab.moves() || searching;
        if (moveFilter != null) {
            moveFilter.visible = moves; // pas de recherche d'attaque dans Spawn / Faiblesses...
        }
        methodButtons.forEach((bit, b) -> {
            b.visible = moves;
            b.selected = (methodFilter & bit) != 0;
        });
        for (int i = 0; i < 3; i++) {
            if (categoryButtons[i] != null) {
                categoryButtons[i].visible = moves;
                categoryButtons[i].selected = (categoryFilter & (1 << i)) != 0;
            }
        }
    }

    // Liste déroulante sous la recherche : "#0025  Pikachu" (clic, ↑ ↓ + Entrée)
    private static final int DROP_MAX = 8;
    private static final int DROP_ROW = 12;
    private final List<Species> searchResults = new ArrayList<>();
    private int searchSel;

    private void onSearchChanged(String text) {
        searchText = text;
        searchError = null;
        search.setSuggestion(null);
        searchResults.clear();
        searchSel = 0;
        String typed = SpeciesLookup.normalize(text);
        if (typed.isEmpty()) {
            return;
        }
        List<Species> contains = new ArrayList<>();
        boolean number = typed.chars().allMatch(Character::isDigit);
        for (Species sp : SpeciesLookup.all()) {
            String local = SpeciesLookup.normalize(SpeciesLookup.displayName(sp));
            String english = SpeciesLookup.normalize(sp.getName());
            if (number ? String.valueOf(sp.getNationalPokedexNumber()).startsWith(typed.replaceFirst("^0+(?=.)", ""))
                    : local.startsWith(typed) || english.startsWith(typed)) {
                searchResults.add(sp);
            } else if (!number && (local.contains(typed) || english.contains(typed))) {
                contains.add(sp);
            }
            if (searchResults.size() >= DROP_MAX) break;
        }
        for (Species sp : contains) {
            if (searchResults.size() >= DROP_MAX) break;
            searchResults.add(sp);
        }
    }

    private boolean dropdownOpen() {
        return search != null && search.isFocused() && !searchResults.isEmpty();
    }

    private int dropY() {
        return SEARCH_Y + 18;
    }

    private void openSpecies(Species sp) {
        searchText = "";
        searchError = null;
        searchResults.clear();
        setSpecies(sp);
        rebuildWidgets();
    }

    private void drawDropdown(GuiGraphics g, int mouseX, int mouseY) {
        if (!dropdownOpen()) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        int h = searchResults.size() * DROP_ROW + 4;
        Theme.panel(g, searchX, dropY(), searchW, h, 3, Theme.POPUP, Theme.lighten(Theme.PANEL_BORDER, 0.3F));
        g.flush();
        int numW = font.width("#00000") + 6;
        for (int i = 0; i < searchResults.size(); i++) {
            Species sp = searchResults.get(i);
            int ry = dropY() + 2 + i * DROP_ROW;
            boolean hover = inRect(mouseX, mouseY, searchX, ry, searchW, DROP_ROW);
            if (hover) searchSel = i;
            if (i == searchSel) {
                Theme.rect(g, searchX + 2, ry, searchW - 4, DROP_ROW, 2, Theme.withAlpha(Theme.ACCENT, 0x70));
                g.flush();
            }
            g.drawString(font, String.format("#%04d", sp.getNationalPokedexNumber()), searchX + 6, ry + 2, Theme.MUTED, false);
            g.drawString(font, Theme.ellipsize(font, SpeciesLookup.displayName(sp), searchW - numW - 10), searchX + 6 + numW, ry + 2,
                    Theme.TEXT, false);
        }
        g.pose().popPose();
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
            if (!searchResults.isEmpty() && (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP)) {
                searchSel = Math.floorMod(searchSel + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1), searchResults.size());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (!searchResults.isEmpty()) {
                    openSpecies(searchResults.get(Mth.clamp(searchSel, 0, searchResults.size() - 1)));
                } else {
                    runSearch();
                }
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
        if (dropdownOpen()) {
            int h = searchResults.size() * DROP_ROW + 4;
            if (inRect(mouseX, mouseY, searchX, dropY(), searchW, h)) {
                int i = (int) ((mouseY - dropY() - 2) / DROP_ROW);
                if (i >= 0 && i < searchResults.size()) {
                    openSpecies(searchResults.get(i));
                }
                return true;
            }
        }
        if (button == 0 && model != null && isInModel(mouseX, mouseY)) {
            draggingModel = true;
        }
        if (button == 0 && species != null && showingEvos() && evoHovered != null && evoHovered.species() != null) {
            if (evoHovered.species() != species) {
                setSpecies(evoHovered.species());
                rebuildWidgets();
            }
            return true;
        }
        if (species != null && showingMoves() && clickMoveTable(mouseX, mouseY, button)) {
            return true;
        }
        if (species != null && showingSet() && clickSet(mouseX, mouseY, button)) {
            return true;
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
        if (species != null && showingEvos() && mouseX >= rightX && mouseX < rightX + rightW && mouseY >= filterY && mouseY < listBottom) {
            evoScroll = Math.max(0, evoScroll - (int) Math.signum(scrollY) * 20);
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
            drawDropdown(graphics, mouseX, mouseY);
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
        } else if (showingEvos()) {
            drawEvos(graphics, mouseX, mouseY);
        } else if (showingSet()) {
            drawSet(graphics, mouseX, mouseY);
        } else if (showingWeak()) {
            drawWeak(graphics, mouseX, mouseY);
        } else {
            drawMoves(graphics, mouseX, mouseY);
        }
        drawCategoryIcons(graphics);
        drawDropdown(graphics, mouseX, mouseY);
    }

    /** "#0006  Dracaufeu" + les types en pastilles colorées. */
    private void drawHeader(GuiGraphics g) {
        int x = MARGIN;
        if (species == null) {
            g.drawString(font, title.copy().withStyle(ChatFormatting.BOLD), x, 11, Theme.TEXT, false);
            return;
        }
        g.drawString(font, title.copy().withStyle(ChatFormatting.BOLD), x, 11, Theme.ACCENT, false);
        x += font.width(title.copy().withStyle(ChatFormatting.BOLD)) + 8;
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

        // Faiblesses en bref (le détail avec les talents est dans l'onglet Faiblesses)
        List<Map.Entry<Double, List<String>>> weak = weakGroups();
        if (!weak.isEmpty()) {
            int wy = totalY + INFO_ROW + 6;
            Theme.divider(graphics, x0 - 3, wy - 4, x1 - x0 + 6);
            graphics.drawString(font, Component.translatable("feurinfo.tab.weak"), x0 - 1, wy, Theme.LABEL, false);
            wy += INFO_ROW;
            for (Map.Entry<Double, List<String>> group : weak) {
                double mult = group.getKey();
                String label = mult == 0 ? "×0" : mult == 0.5 ? "×½" : mult == 0.25 ? "×¼" : "×" + (int) mult;
                int color = mult >= 4 ? 0xFFFF5F5F : mult >= 2 ? 0xFFFF9A5A : mult == 0 ? 0xFF8AB4FF : 0xFF7FE08A;
                graphics.drawString(font, label, x0, wy + 1, color, false);
                int ix = x0 + 18;
                for (String id : group.getValue()) {
                    ElementalType type = elementalType(id);
                    if (type != null && ix + 10 <= x1) {
                        drawTypeIcon(graphics, type, ix, wy, 10);
                        ix += 12;
                    }
                }
                wy += INFO_ROW + 2;
            }
        }

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
        List<Row> list = displayedRows();
        boolean searching = filtering();
        int x0 = rightX + 7;
        int x1 = rightX + rightW - 9;
        boolean showLevel = true;
        boolean showCategory = rightW >= 330;

        // Filtre par type : une icône par type (re-clic : enlever)
        drawTypeFilter(graphics, x0, x1, mouseX, mouseY);

        // Colonnes, de droite à gauche
        int prioRight = x1 - 2;
        int ppRight = prioRight - 28;
        int accRight = ppRight - 24;
        int powRight = accRight - 34;
        int catX = powRight - 34 - 58;
        int typeX = (showCategory ? catX : powRight - 34) - TYPE_COL_W - 2;
        int methodW = Mth.clamp((typeX - x0) * 36 / 100, 50, 110);
        int nameX = x0 + 2 + methodW;

        // Nombre d'attaques, à droite de la barre de recherche
        Component count = Component.translatable("feurinfo.moves_count", list.size());
        drawRight(graphics, count, x1, filterY + 4, searching ? Theme.GOLD : Theme.TEXT_2);

        int headerY = panelY + 70;
        // Géométrie des colonnes, pour les clics (tri, filtre par type / catégorie)
        hdrY = headerY;
        hdrX0 = x0;
        hdrNameX = nameX;
        hdrTypeX = typeX;
        hdrCatX = showCategory ? catX : -1000;
        hdrPowRight = powRight;
        hdrAccRight = accRight;
        hdrPpRight = ppRight;
        hdrPrioRight = prioRight;
        hdrShowLevel = showLevel;
        if (showLevel) {
            header(graphics, Component.translatable("feurinfo.col.method"), SortCol.LEVEL, x0 + 2, headerY, false, mouseX, mouseY);
        }
        header(graphics, Component.translatable("feurinfo.col.name"), SortCol.NAME, nameX, headerY, false, mouseX, mouseY);
        header(graphics, Component.translatable("feurinfo.col.type"), SortCol.TYPE, typeX, headerY, false, mouseX, mouseY);
        if (showCategory) {
            header(graphics, Component.translatable("feurinfo.col.category"), SortCol.CAT, catX, headerY, false, mouseX, mouseY);
        }
        header(graphics, Component.translatable("feurinfo.col.power"), SortCol.POW, powRight, headerY, true, mouseX, mouseY);
        header(graphics, Component.translatable("feurinfo.col.accuracy"), SortCol.ACC, accRight, headerY, true, mouseX, mouseY);
        header(graphics, Component.translatable("feurinfo.col.pp"), SortCol.PP, ppRight, headerY, true, mouseX, mouseY);
        header(graphics, Component.literal("Prio"), SortCol.PRIO, prioRight, headerY, true, mouseX, mouseY);
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
                graphics.drawString(font, Theme.ellipsize(font, row.level(), methodW - 6), x0 + 2, textY, Theme.GOLD, false);
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
            int prio = move.getPriority();
            if (prio != 0) {
                drawRight(graphics, Component.literal((prio > 0 ? "+" : "") + prio), prioRight, textY, prio > 0 ? Theme.GOOD : Theme.BAD);
            }
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

    // =======================================================================================
    // Tableau d'attaques : tri par colonne (clic sur l'en-tête) et filtres rapides
    // =======================================================================================

    private enum SortCol { NONE, LEVEL, NAME, TYPE, CAT, POW, ACC, PP, PRIO }

    private SortCol sortCol = SortCol.NONE;
    private boolean sortDesc;
    private int hdrY = -1000, hdrX0, hdrNameX, hdrTypeX, hdrCatX, hdrPowRight, hdrAccRight, hdrPpRight, hdrPrioRight;
    private boolean hdrShowLevel;

    /** Lignes affichées : celles de l'onglet (ou de la recherche), triées si une colonne est choisie. */
    private List<Row> displayedRows() {
        List<Row> base = currentRows();
        if (sortCol == SortCol.NONE) {
            return base;
        }
        Comparator<Row> cmp = switch (sortCol) {
            case LEVEL -> Comparator.comparingInt(Row::sortLevel);
            case NAME -> Comparator.comparing(r -> r.move().getDisplayName().getString());
            case TYPE -> Comparator.comparing(r -> r.move().getElementalType().getDisplayName().getString());
            case CAT -> Comparator.comparing(r -> r.move().getDamageCategory().getDisplayName().getString());
            case POW -> Comparator.comparingDouble(r -> r.move().getPower());
            case ACC -> Comparator.comparingDouble(r -> r.move().getAccuracy() <= 0 ? 101 : r.move().getAccuracy());
            case PP -> Comparator.comparingInt(r -> r.move().getPp());
            case PRIO -> Comparator.comparingInt(r -> r.move().getPriority());
            case NONE -> null;
        };
        List<Row> sorted = new ArrayList<>(base);
        sorted.sort(sortDesc ? cmp.reversed() : cmp);
        return sorted;
    }

    private static int levelNumber(String level) {
        String digits = level.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits.length() > 4 ? digits.substring(0, 4) : digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** En-tête de colonne cliquable, avec ▲ / ▼ sur la colonne triée. */
    private void header(GuiGraphics g, Component label, SortCol col, int x, int y, boolean alignRight, int mouseX, int mouseY) {
        String text = label.getString() + (sortCol == col ? (sortDesc ? " ▼" : " ▲") : "");
        int w = font.width(text);
        int left = alignRight ? x - w : x;
        boolean hover = mouseX >= left - 1 && mouseX < left + w + 1 && mouseY >= y - 2 && mouseY < y + 10;
        int color = sortCol == col ? Theme.GOLD : hover ? Theme.TEXT : Theme.LABEL;
        g.drawString(font, text, left, y, color, false);
        if (hover) {
            g.renderTooltip(font, Component.literal("Clic : trier (croissant ▲ / décroissant ▼ / par défaut)"), mouseX, mouseY + 14);
        }
    }

    /** Clic dans le tableau : en-tête = tri ; icône de type / catégorie d'une ligne = filtre. */
    private boolean clickMoveTable(double mx, double my, int button) {
        if (hdrY < 0) return false;
        if (my >= typeRowY && my < typeRowY + typeIconSize + 2) {
            for (int i = 0; i < typeIconX.length; i++) {
                if (mx >= typeIconX[i] && mx < typeIconX[i] + typeIconSize) {
                    String id = TypeChart.TYPES[i];
                    if (button == 1 || (typeFilter.size() == 1 && typeFilter.contains(id))) {
                        typeFilter.clear(); // clic droit (ou re-clic) : tous les types
                    } else if (!typeFilter.remove(id)) {
                        typeFilter.add(id);
                    }
                    onFiltersChanged();
                    return true;
                }
            }
        }
        if (my >= hdrY - 2 && my < hdrY + 10) {
            SortCol col = null;
            if (hdrShowLevel && mx >= hdrX0 && mx < hdrNameX - 2) col = SortCol.LEVEL;
            else if (mx >= hdrNameX && mx < hdrTypeX - 2) col = SortCol.NAME;
            else if (mx >= hdrTypeX && mx < hdrTypeX + TYPE_COL_W + 4) col = SortCol.TYPE;
            else if (hdrCatX > 0 && mx >= hdrCatX - 3 && mx < hdrCatX + 56) col = SortCol.CAT;
            else if (mx >= hdrPowRight - 30 && mx <= hdrPowRight) col = SortCol.POW;
            else if (mx >= hdrAccRight - 30 && mx <= hdrAccRight) col = SortCol.ACC;
            else if (mx >= hdrPpRight - 20 && mx <= hdrPpRight + 2) col = SortCol.PP;
            else if (mx >= hdrPrioRight - 24 && mx <= hdrPrioRight + 2) col = SortCol.PRIO;
            if (col == null) return false;
            if (sortCol != col) {
                sortCol = col;
                sortDesc = false;
            } else if (!sortDesc) {
                sortDesc = true;
            } else {
                sortCol = SortCol.NONE;
            }
            scroll = 0;
            return true;
        }
        if (button != 0 || my < listTop || my >= listBottom || moveFilter == null) return false;
        int index = scroll + (int) ((my - listTop) / ROW_H);
        List<Row> list = displayedRows();
        if (index < 0 || index >= list.size()) return false;
        MoveTemplate move = list.get(index).move();
        if (mx >= hdrTypeX && mx < hdrTypeX + TYPE_COL_W) {
            String id = move.getElementalType().getName().toLowerCase();
            if (!typeFilter.remove(id)) {
                typeFilter.add(id);
            }
            onFiltersChanged();
            return true;
        }
        if (hdrCatX > 0 && mx >= hdrCatX - 3 && mx < hdrCatX + 53) {
            categoryFilter ^= categoryBit(move.getDamageCategory());
            onFiltersChanged();
            return true;
        }
        return false;
    }

    // =======================================================================================
    // Onglet Set courant : les sets Smogon / les plus joués sur Showdown pour ce Pokémon
    // =======================================================================================

    private int setIndex;
    private List<com.goat.feurcalc.SmogonSets.SmogonSet> setList = List.of();
    private String setKey = "";
    private int setPrevX, setNextX, setArrowY, setCalcX, setCalcW;

    private boolean showingSet() {
        return tab == Tab.SET && !filtering();
    }

    private List<com.goat.feurcalc.SmogonSets.SmogonSet> sets() {
        String key = species.getResourceIdentifier() + "/" + form().showdownId() + "/" + com.goat.feurcalc.SmogonSets.status();
        if (!key.equals(setKey)) {
            setKey = key;
            com.goat.feurcalc.MonState m = com.goat.feurcalc.MonState.fromForm(species, form(), 100);
            setList = com.goat.feurcalc.SmogonSets.forMon(m);
            com.goat.feurcalc.SmogonSets.SmogonSet best = com.goat.feurcalc.SmogonSets.best(m);
            setIndex = best == null ? 0 : Math.max(0, setList.indexOf(best));
        }
        return setList;
    }

    private void drawSet(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = rightX + 7;
        int x1 = rightX + rightW - 9;
        int top = filterY;
        if (com.goat.feurcalc.SmogonSets.status() != com.goat.feurcalc.SmogonSets.Status.READY) {
            g.drawCenteredString(font, "Chargement des sets…", rightX + rightW / 2, top + 20, Theme.TEXT_2);
            return;
        }
        List<com.goat.feurcalc.SmogonSets.SmogonSet> list = sets();
        if (list.isEmpty()) {
            g.drawCenteredString(font, "Aucun set connu pour ce Pokémon (pas joué sur Showdown)", rightX + rightW / 2, top + 20, Theme.TEXT_2);
            return;
        }
        setIndex = Math.floorMod(setIndex, list.size());
        com.goat.feurcalc.SmogonSets.SmogonSet set = list.get(setIndex);

        // Sélecteur : ◀ OU · Usage Showdown (3/12) ▶            [Ouvrir dans Feurcalc ⚔]
        setArrowY = top;
        setPrevX = x0;
        String calcLabel = "Ouvrir dans Feurcalc ⚔";
        setCalcW = font.width(calcLabel) + 12;
        setCalcX = x1 - setCalcW;
        setNextX = setCalcX - 8 - 16;
        boolean hp = inRect(mouseX, mouseY, setPrevX, top, 16, 16);
        boolean hn = inRect(mouseX, mouseY, setNextX, top, 16, 16);
        boolean hc = inRect(mouseX, mouseY, setCalcX, top, setCalcW, 16);
        Theme.rect(g, setPrevX, top, 16, 16, 3, hp ? Theme.CARD_HOVER : Theme.CARD);
        Theme.rect(g, setNextX, top, 16, 16, 3, hn ? Theme.CARD_HOVER : Theme.CARD);
        Theme.rect(g, setPrevX + 20, top, setNextX - setPrevX - 24, 16, 3, Theme.FIELD);
        Theme.rect(g, setCalcX, top, setCalcW, 16, 3, hc ? Theme.lighten(0xFF2A4F86, 0.12F) : 0xFF2A4F86);
        g.flush();
        g.drawCenteredString(font, "◀", setPrevX + 8, top + 4, Theme.TEXT);
        g.drawCenteredString(font, "▶", setNextX + 8, top + 4, Theme.TEXT);
        String label = set.fullLabel() + "  (" + (setIndex + 1) + "/" + list.size() + ")";
        int labelW = setNextX - setPrevX - 32;
        g.drawString(font, Theme.ellipsize(font, label, labelW), setPrevX + 24, top + 4, set.isUsage() ? Theme.GOLD : Theme.TEXT, false);
        g.drawCenteredString(font, calcLabel, setCalcX + setCalcW / 2, top + 4, 0xFFFFFFFF);

        // Le set : talent, objet, nature + EV, Téra (à gauche) et attaques (à droite), puis les autres attaques jouées
        List<Component> lines = com.goat.feurcalc.SmogonSets.tooltip(set);
        int cardY = top + 22;
        int colW = (x1 - x0 - 6) / 2;
        List<Component> info = new ArrayList<>();
        List<Component> moves = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            (lines.get(i).getString().startsWith("• ") ? moves : info).add(lines.get(i));
        }
        int cardH = Math.max(info.size(), moves.size() + 1) * 12 + 12;
        Theme.panel(g, x0, cardY, colW, cardH, 4, Theme.CARD, Theme.PANEL_BORDER);
        Theme.panel(g, x0 + colW + 6, cardY, colW, cardH, 4, Theme.CARD, Theme.PANEL_BORDER);
        g.flush();
        int y = cardY + 6;
        for (Component c : info) {
            for (FormattedCharSequence part : font.split(c, colW - 12)) {
                g.drawString(font, part, x0 + 6, y, 0xFFFFFFFF, false);
                y += 11;
            }
        }
        y = cardY + 6;
        g.drawString(font, "Attaques", x0 + colW + 12, y, Theme.LABEL, false);
        y += 12;
        for (Component c : moves) {
            g.drawString(font, Theme.ellipsize(font, c.getString(), colW - 12), x0 + colW + 12, y, Theme.TEXT, false);
            y += 12;
        }

        if (!set.moreMoves().isEmpty()) {
            int my = cardY + cardH + 8;
            g.drawString(font, "Autres attaques jouées", x0, my, Theme.LABEL, false);
            my += 12;
            int cols = Math.max(1, (x1 - x0) / 130);
            int cw = (x1 - x0) / cols;
            for (int i = 0; i < set.moreMoves().size(); i++) {
                int cx = x0 + (i % cols) * cw;
                int cy = my + (i / cols) * 11;
                if (cy > listBottom - 10) break;
                int pct = i < set.moreMovePcts().length ? set.moreMovePcts()[i] : -1;
                String name = com.goat.feurcalc.Names.moveName(set.moreMoves().get(i));
                g.drawString(font, Theme.ellipsize(font, name, cw - 40), cx, cy, Theme.TEXT_2, false);
                if (pct >= 0) {
                    drawRight(g, Component.literal(pct + " %"), cx + cw - 8, cy, Theme.MUTED);
                }
            }
        }
    }

    private boolean clickSet(double mx, double my, int button) {
        if (setList.isEmpty()) return false;
        if (inRect(mx, my, setPrevX, setArrowY, 16, 16)) {
            setIndex = Math.floorMod(setIndex - 1, setList.size());
            return true;
        }
        if (inRect(mx, my, setNextX, setArrowY, 16, 16)) {
            setIndex = Math.floorMod(setIndex + 1, setList.size());
            return true;
        }
        if (inRect(mx, my, setCalcX, setArrowY, setCalcW, 16)) {
            com.goat.feurcalc.FeurcalcClient.openWithSet(minecraft, species, form(), setList.get(Math.floorMod(setIndex, setList.size())), this);
            return true;
        }
        return false;
    }

    private int[] typeIconX = new int[0];
    private int typeIconSize = 14;

    /** La rangée d'icônes de type au-dessus du tableau : clic = filtrer (plusieurs possibles), clic droit = tout. */
    private void drawTypeFilter(GuiGraphics g, int x0, int x1, int mouseX, int mouseY) {
        int n = TypeChart.TYPES.length;
        int avail = x1 - x0;
        int size = Mth.clamp((avail - (n - 1) * 3) / n, 9, 14);
        int gap = Math.min(4, Math.max(1, (avail - n * size) / Math.max(1, n - 1)));
        typeIconSize = size;
        typeIconX = new int[n];
        String hovered = null;
        int x = x0;
        for (int i = 0; i < n; i++) {
            typeIconX[i] = x;
            String id = TypeChart.TYPES[i];
            boolean on = typeFilter.isEmpty() || typeFilter.contains(id);
            boolean hover = mouseX >= x && mouseX < x + size && mouseY >= typeRowY && mouseY < typeRowY + size;
            ElementalType type = elementalType(id);
            if (typeFilter.contains(id)) {
                Theme.rect(g, x - 2, typeRowY - 2, size + 4, size + 4, 3, Theme.withAlpha(Theme.ACCENT, 0xC0));
            }
            if (type != null) {
                g.flush();
                if (!on) {
                    RenderSystem.setShaderColor(1F, 1F, 1F, 0.3F);
                }
                drawTypeIcon(g, type, x, typeRowY, size);
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                if (hover) hovered = type.getDisplayName().getString();
            }
            x += size + gap;
        }
        if (hovered != null) {
            g.renderTooltip(font, List.of(Component.literal(hovered).getVisualOrderText(),
                    Component.literal("Clic : filtrer · Clic droit : tous les types").withStyle(ChatFormatting.GRAY).getVisualOrderText()),
                    mouseX, mouseY + 12);
        }
    }

    // =======================================================================================
    // Onglet Évos : chaîne d'évolution (clic sur un Pokémon = ouvrir sa fiche)
    // =======================================================================================

    private List<List<EvoData.Node>> evoChains;
    private int evoScroll;
    @Nullable private EvoData.Node evoHovered;

    private boolean showingMoves() {
        return tab.moves() || filtering();
    }

    private boolean showingEvos() {
        return tab == Tab.EVOS && !filtering();
    }

    private void drawEvos(GuiGraphics g, int mouseX, int mouseY) {
        if (evoChains == null) {
            evoChains = EvoData.chains(species);
        }
        evoHovered = null;
        int x0 = rightX + 7;
        int x1 = rightX + rightW - 9;
        int top = filterY;
        if (evoChains.isEmpty()) {
            g.drawCenteredString(font, Component.literal("N'évolue pas et n'a pas de pré-évolution"), rightX + rightW / 2, top + 20, Theme.TEXT_2);
            return;
        }
        final int cardH = 30;
        final int arrowW = 14;
        final int rowGap = 6;
        int totalH = evoChains.size() * (cardH + rowGap);
        int maxScroll = Math.max(0, totalH - (listBottom - top));
        evoScroll = Mth.clamp(evoScroll, 0, maxScroll);

        g.enableScissor(rightX + 1, top, rightX + rightW - 1, listBottom);
        List<FormattedCharSequence> tip = null;
        for (int r = 0; r < evoChains.size(); r++) {
            List<EvoData.Node> chain = evoChains.get(r);
            int y = top + r * (cardH + rowGap) - evoScroll;
            if (y + cardH < top || y > listBottom) continue;
            int n = chain.size();
            int cardW = Math.min(130, (x1 - x0 - arrowW * (n - 1)) / Math.max(1, n));
            int x = x0;
            for (int i = 0; i < n; i++) {
                EvoData.Node node = chain.get(i);
                boolean current = node.species() == species;
                boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH
                        && mouseY >= top && mouseY < listBottom;
                int bg = hover && node.species() != null ? Theme.CARD_HOVER : Theme.CARD;
                Theme.panel(g, x, y, cardW, cardH, 3, bg, current ? Theme.ACCENT : Theme.PANEL_BORDER);
                g.drawString(font, Theme.ellipsize(font, node.name(), cardW - 8), x + 4, y + 5,
                        current ? Theme.GOLD : node.species() != null ? Theme.TEXT : Theme.MUTED, false);
                String how = i == 0 ? "Forme de base" : node.how();
                g.drawString(font, Theme.ellipsize(font, how, cardW - 8), x + 4, y + 17, i == 0 ? Theme.MUTED : Theme.TEXT_2, false);
                if (hover) {
                    evoHovered = node;
                    tip = new ArrayList<>();
                    tip.add(Component.literal(node.name()).withStyle(ChatFormatting.BOLD).getVisualOrderText());
                    if (i > 0) tip.addAll(font.split(Component.literal(node.how()).withStyle(ChatFormatting.GRAY), 220));
                    if (node.species() != null && !current) {
                        tip.add(Component.literal("Clic : ouvrir sa fiche").withStyle(ChatFormatting.DARK_AQUA).getVisualOrderText());
                    }
                }
                x += cardW;
                if (i < n - 1) {
                    g.drawCenteredString(font, "→", x + arrowW / 2, y + cardH / 2 - 4, Theme.MUTED);
                    x += arrowW;
                }
            }
        }
        g.disableScissor();
        if (tip != null) {
            g.renderTooltip(font, tip, mouseX, mouseY);
        }
    }

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
    /** Icônes de catégorie de Cobblemon : 3 icônes de 24 x 16 l'une sous l'autre (physique, spéciale, statut). */
    private static final ResourceLocation CATEGORY_ICONS = ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/gui/categories.png");

    private static void drawCategoryIcon(GuiGraphics graphics, int index, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        graphics.blit(CATEGORY_ICONS, x, y, w, h, 0, index * 16, 24, 16, 24, 48);
        RenderSystem.disableBlend();
    }

    /** Les icônes sur les filtres Physique / Spéciale / Statut. */
    private void drawCategoryIcons(GuiGraphics g) {
        for (int i = 0; i < 3; i++) {
            FlatButton b = categoryButtons[i];
            if (b == null || !b.visible) continue;
            int w = 18, h = 12;
            if (categoryFilter != 0 && (categoryFilter & (1 << i)) == 0) {
                RenderSystem.setShaderColor(1F, 1F, 1F, 0.35F);
            }
            drawCategoryIcon(g, i, b.getX() + (b.getWidth() - w) / 2, b.getY() + (b.getHeight() - h) / 2, w, h);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }
    }

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
