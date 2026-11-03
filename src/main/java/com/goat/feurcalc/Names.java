package com.goat.feurcalc;

import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.abilities.Abilities;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.helditem.CobblemonHeldItemManager;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Traductions et identifiants : tout ce qui fait le lien entre Cobblemon (FR) et Showdown (ids). */
public final class Names {
    /** Ordre Showdown : hp, atk, def, spa, spd, spe. */
    public static final Stats[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    public static final String[] STAT_IDS = {"hp", "atk", "def", "spa", "spd", "spe"};

    public static final String[] STATUSES = {"", "brn", "par", "psn", "tox", "slp", "frz"};
    public static final String[] WEATHERS = {"", "Sun", "Rain", "Sand", "Snow"};
    public static final String[] TERRAINS = {"", "Electric", "Grassy", "Psychic", "Misty"};

    private Names() {
    }

    /** Comme toID de Showdown : "Choice Band" / "choice_band" → "choiceband". */
    public static String toId(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Pour la recherche : minuscules, sans accents ni ponctuation. */
    public static String normalize(String text) {
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    // ---------------------------------------------------------------------------------------
    // Stats

    public static String statLabel(int index) {
        return tr("feurcalc.stat." + STAT_IDS[index]);
    }

    public static int statIndex(@Nullable Stat stat) {
        if (stat == null) {
            return -1;
        }
        for (int i = 0; i < STATS.length; i++) {
            if (STATS[i] == stat) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------
    // Attaques

    @Nullable
    public static MoveTemplate move(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return Moves.getByName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String moveName(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        MoveTemplate template = move(id);
        return template != null ? template.getDisplayName().getString() : id;
    }

    // ---------------------------------------------------------------------------------------
    // Talents

    @Nullable
    public static AbilityTemplate ability(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return Abilities.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String abilityName(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return "—";
        }
        AbilityTemplate template = ability(id);
        if (template == null) {
            return id;
        }
        String key = template.getDisplayName();
        return I18n.exists(key) ? I18n.get(key) : id;
    }

    public static String abilityDescription(@Nullable String id) {
        AbilityTemplate template = ability(id);
        if (template == null) {
            return "";
        }
        String key = template.getDescription();
        return I18n.exists(key) ? I18n.get(key) : "";
    }

    // ---------------------------------------------------------------------------------------
    // Natures

    @Nullable
    public static Nature nature(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Nature nature : Natures.all()) {
            if (nature.getName().getPath().equals(id)) {
                return nature;
            }
        }
        return null;
    }

    public static String natureName(@Nullable String id) {
        Nature nature = nature(id);
        if (nature == null) {
            return id == null ? "" : id;
        }
        return I18n.get(nature.getDisplayName());
    }

    /** "+Atq −Vit", ou "neutre". */
    public static String natureEffect(@Nullable Nature nature) {
        if (nature == null) {
            return "";
        }
        int plus = statIndex(nature.getIncreasedStat());
        int minus = statIndex(nature.getDecreasedStat());
        if (plus < 0 || minus < 0 || plus == minus) {
            return tr("feurcalc.nature.neutral");
        }
        return "+" + statLabel(plus) + " −" + statLabel(minus);
    }

    /** +1 si la nature augmente la stat, -1 si elle la baisse, 0 sinon. */
    public static int natureModifier(@Nullable String natureId, int statIndex) {
        Nature nature = nature(natureId);
        if (nature == null) {
            return 0;
        }
        int plus = statIndex(nature.getIncreasedStat());
        int minus = statIndex(nature.getDecreasedStat());
        if (plus == minus) {
            return 0;
        }
        return statIndex == plus ? 1 : statIndex == minus ? -1 : 0;
    }

    // ---------------------------------------------------------------------------------------
    // Types

    @Nullable
    public static ElementalType type(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return ElementalTypes.get(id.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    public static String typeName(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return "—";
        }
        if (id.equalsIgnoreCase("stellar")) {
            return tr("feurcalc.type.stellar");
        }
        ElementalType type = type(id);
        return type != null ? type.getDisplayName().getString() : id;
    }

    public static List<String> formTypes(FormData form) {
        List<String> list = new ArrayList<>();
        for (ElementalType type : form.getTypes()) {
            list.add(type.getName());
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------
    // Objets

    private static Map<String, Item> itemsById;
    private static boolean itemsBuiltWithEngine;

    /** Identifiant Showdown de l'objet tenu ("" si aucun / inconnu de Showdown). */
    public static String itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            String id = CobblemonHeldItemManager.INSTANCE.showdownId(stack);
            if (id != null && !id.isEmpty()) {
                return toId(id);
            }
        } catch (Throwable ignored) {
            // on tente avec le nom de l'objet
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return toId(key.getPath());
    }

    /** Tous les objets du jeu que Showdown connaît : id Showdown → objet Minecraft (pour le nom et l'icône). */
    public static synchronized Map<String, Item> itemsById() {
        Set<String> known = CalcEngine.knownItems();
        if (itemsById != null && (itemsBuiltWithEngine || known.isEmpty())) {
            return itemsById;
        }
        itemsBuiltWithEngine = !known.isEmpty();
        Map<String, Item> map = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id;
            try {
                id = itemId(new ItemStack(item));
            } catch (Throwable t) {
                continue;
            }
            if (id.isEmpty() || map.containsKey(id)) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            boolean cobblemon = key.getNamespace().equals("cobblemon");
            if (known.isEmpty() ? cobblemon : known.contains(id)) {
                map.put(id, item);
            }
        }
        // Tri alphabétique sur le nom affiché
        List<Map.Entry<String, Item>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(e -> normalize(new ItemStack(e.getValue()).getHoverName().getString())));
        Map<String, Item> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Item> e : entries) {
            sorted.put(e.getKey(), e.getValue());
        }
        itemsById = sorted;
        return itemsById;
    }

    public static String itemName(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return tr("feurcalc.none");
        }
        Item item = itemsById().get(id);
        if (item != null) {
            return new ItemStack(item).getHoverName().getString();
        }
        try {
            Component name = CobblemonHeldItemManager.INSTANCE.nameOf(id);
            if (name != null) {
                return name.getString();
            }
        } catch (Throwable ignored) {
            // nom brut
        }
        return id;
    }

    public static ItemStack itemStack(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Item item = itemsById().get(id);
        return item != null ? new ItemStack(item) : ItemStack.EMPTY;
    }

    // ---------------------------------------------------------------------------------------
    // Statuts, météo, champ

    public static String statusName(String status) {
        return tr("feurcalc.status." + (status.isEmpty() ? "none" : status));
    }

    public static String weatherName(String weather) {
        return tr("feurcalc.weather." + (weather.isEmpty() ? "none" : weather.toLowerCase(Locale.ROOT)));
    }

    public static String terrainName(String terrain) {
        return tr("feurcalc.terrain." + (terrain.isEmpty() ? "none" : terrain.toLowerCase(Locale.ROOT)));
    }

    // ---------------------------------------------------------------------------------------
    // KO

    /** "OHKO", "2HKO", "38% 2HKO", "~4HKO" (possible), ou "" si rien. */
    public static String koText(int n, double chance) {
        if (n <= 0) {
            return "";
        }
        String base = n == 1 ? "OHKO" : n + "HKO";
        if (chance >= 0.999) {
            return base;
        }
        if (chance > 0) {
            long pct = Math.max(1, Math.round(chance * 100));
            if (pct >= 100) {
                pct = 99;
            }
            return pct + "% " + base;
        }
        return "~" + base;
    }

    public static int koColor(int n, double chance) {
        if (n == 1) {
            return chance >= 0.999 ? 0xFFFF5A5A : 0xFFFF9A4A;
        }
        if (n == 2) {
            return chance >= 0.999 ? 0xFFFFC84A : 0xFFFFE07A;
        }
        if (n == 3) {
            return 0xFFE8E8A0;
        }
        return 0xFFB8BEC6;
    }
}
