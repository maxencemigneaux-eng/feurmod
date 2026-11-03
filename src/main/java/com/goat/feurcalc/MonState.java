package com.goat.feurcalc;

import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.status.PersistentStatus;
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Un côté du calcul : le Pokémon et tout son "spread" (talent, nature, objet, IV, EV, boosts...). */
public final class MonState {
    public enum Source { NONE, PARTY, BATTLE, CUSTOM }

    public Source source = Source.NONE;
    @Nullable public UUID uuid;
    @Nullable public Species species;
    @Nullable public FormData form;
    public Set<String> aspects = Collections.emptySet();
    public String displayName = "";

    public int level = 50;
    public String ability = "";
    public String item = "";
    public String nature = "hardy";
    public String teraType = "";
    public boolean tera = false;
    public final int[] ivs = {31, 31, 31, 31, 31, 31};
    public final int[] evs = {0, 0, 0, 0, 0, 0};
    /** Index 0 (PV) inutilisé. */
    public final int[] boosts = {0, 0, 0, 0, 0, 0};
    public String status = "";
    public int hpPercent = 100;
    public final String[] moves = {"", "", "", ""};

    public boolean isEmpty() {
        return form == null || species == null;
    }

    /** Clé de sauvegarde du spread : l'identifiant Showdown de la forme ("garchomp", "vulpixalola"...). */
    public String key() {
        return form == null ? "" : form.showdownId();
    }

    public MonState copy() {
        MonState m = new MonState();
        m.copyFrom(this);
        return m;
    }

    public void copyFrom(MonState o) {
        source = o.source;
        uuid = o.uuid;
        species = o.species;
        form = o.form;
        aspects = o.aspects;
        displayName = o.displayName;
        level = o.level;
        ability = o.ability;
        item = o.item;
        nature = o.nature;
        teraType = o.teraType;
        tera = o.tera;
        System.arraycopy(o.ivs, 0, ivs, 0, 6);
        System.arraycopy(o.evs, 0, evs, 0, 6);
        System.arraycopy(o.boosts, 0, boosts, 0, 6);
        status = o.status;
        hpPercent = o.hpPercent;
        System.arraycopy(o.moves, 0, moves, 0, 4);
    }

    public int evTotal() {
        int total = 0;
        for (int ev : evs) {
            total += ev;
        }
        return total;
    }

    // =======================================================================================
    // Construction

    /** Un Pokémon de ton équipe : on connaît tout (stats, IV, EV, nature, talent, objet, attaques). */
    public static MonState fromPokemon(Pokemon pokemon) {
        MonState m = new MonState();
        m.source = Source.PARTY;
        m.uuid = pokemon.getUuid();
        m.species = pokemon.getSpecies();
        m.form = pokemon.getForm();
        m.aspects = new HashSet<>(pokemon.getAspects());
        m.displayName = pokemon.getDisplayName(false).getString();
        m.level = pokemon.getLevel();
        try {
            m.ability = pokemon.getAbility().getName();
        } catch (Throwable ignored) {
            m.ability = defaultAbility(m.form);
        }
        m.item = Names.itemId(pokemon.heldItem());
        m.nature = pokemon.getEffectiveNature().getName().getPath();
        try {
            m.teraType = pokemon.getTeraType().showdownId();
        } catch (Throwable ignored) {
            m.teraType = firstType(m.form);
        }
        for (int i = 0; i < 6; i++) {
            Stat stat = Names.STATS[i];
            m.ivs[i] = pokemon.getIvs().getEffectiveBattleIV(stat);
            m.evs[i] = pokemon.getEvs().getOrDefault(stat);
        }
        int slot = 0;
        for (Move move : pokemon.getMoveSet().getMoves()) {
            if (slot >= 4) {
                break;
            }
            m.moves[slot++] = move.getTemplate().getName();
        }
        PersistentStatusContainer status = pokemon.getStatus();
        m.status = status != null && status.getStatus() != null ? status.getStatus().getShowdownName() : "";
        int max = Math.max(1, pokemon.getMaxHealth());
        m.hpPercent = Math.round(pokemon.getCurrentHealth() * 100F / max);
        return m;
    }

    /** Une espèce choisie à la main : valeurs par défaut (puis le spread sauvegardé s'il existe). */
    public static MonState fromForm(Species species, FormData form, int level) {
        MonState m = new MonState();
        m.source = Source.CUSTOM;
        m.species = species;
        m.form = form;
        m.aspects = new HashSet<>(form.getAspects());
        m.displayName = species.getTranslatedName().getString();
        m.level = level;
        m.applyDefaults();
        return m;
    }

    /** Un Pokémon vu en combat (l'adversaire) : espèce, forme, niveau, PV, statut et boosts. */
    public static MonState fromBattle(ClientBattlePokemon battlePokemon) {
        Species species = battlePokemon.getSpecies();
        Set<String> aspects = battleAspects(battlePokemon);
        FormData form = species.getForm(aspects);
        MonState m = fromForm(species, form, battlePokemon.getLevel());
        m.source = Source.BATTLE;
        m.uuid = battlePokemon.getUuid();
        m.aspects = aspects;
        m.displayName = battlePokemon.getDisplayName().getString();
        m.updateFromBattle(battlePokemon);
        return m;
    }

    public static Set<String> battleAspects(ClientBattlePokemon battlePokemon) {
        try {
            Set<String> aspects = battlePokemon.getState().getCurrentAspects();
            if (aspects != null && !aspects.isEmpty()) {
                return new HashSet<>(aspects);
            }
        } catch (Throwable ignored) {
            // on se rabat sur les propriétés
        }
        try {
            return new HashSet<>(battlePokemon.getProperties().getAspects());
        } catch (Throwable ignored) {
            return new HashSet<>();
        }
    }

    /** Ce qui change pendant le combat : PV, statut, boosts. */
    public void updateFromBattle(ClientBattlePokemon battlePokemon) {
        float value = battlePokemon.getHpValue();
        float max = battlePokemon.getMaxHp();
        if (battlePokemon.isHpFlat()) {
            hpPercent = max > 0 ? Math.round(value * 100F / max) : 100;
        } else {
            hpPercent = Math.round(value <= 1.0001F ? value * 100F : (max > 0 ? value * 100F / max : 100F));
        }
        hpPercent = Math.max(0, Math.min(100, hpPercent));
        PersistentStatus persistent = battlePokemon.getStatus();
        status = persistent != null ? persistent.getShowdownName() : "";
        for (int i = 1; i < 6; i++) {
            boosts[i] = 0;
        }
        for (Map.Entry<Stat, Integer> e : battlePokemon.getStatChanges().entrySet()) {
            int index = Names.statIndex(e.getKey());
            if (index > 0 && e.getValue() != null) {
                boosts[index] = Math.max(-6, Math.min(6, e.getValue()));
            }
        }
    }

    /** Signature de l'état de combat, pour ne resynchroniser que quand quelque chose a bougé. */
    public static String battleSignature(ClientBattlePokemon bp) {
        PersistentStatus persistent = bp.getStatus();
        return bp.getHpValue() + "/" + bp.getMaxHp() + "/" + (persistent == null ? "" : persistent.getShowdownName())
                + "/" + new TreeMap<>(stringKeys(bp.getStatChanges()));
    }

    private static Map<String, Integer> stringKeys(Map<Stat, Integer> map) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<Stat, Integer> e : map.entrySet()) {
            out.put(e.getKey().getShowdownId(), e.getValue());
        }
        return out;
    }

    // =======================================================================================
    // Valeurs par défaut

    public void applyDefaults() {
        ability = defaultAbility(form);
        item = "";
        nature = "hardy";
        teraType = firstType(form);
        tera = false;
        for (int i = 0; i < 6; i++) {
            ivs[i] = 31;
            evs[i] = 0;
        }
        List<String> learned = defaultMoves(form, level);
        for (int i = 0; i < 4; i++) {
            moves[i] = i < learned.size() ? learned.get(i) : "";
        }
    }

    public static String firstType(@Nullable FormData form) {
        if (form == null) {
            return "normal";
        }
        List<String> types = Names.formTypes(form);
        return types.isEmpty() ? "normal" : types.get(0);
    }

    /** Premier talent "normal" (pas caché) de la forme. */
    public static String defaultAbility(@Nullable FormData form) {
        if (form == null) {
            return "";
        }
        String hidden = "";
        for (PotentialAbility potential : form.getAbilities()) {
            String name = potential.getTemplate().getName();
            if (potential.getClass().getSimpleName().toLowerCase().contains("hidden")) {
                if (hidden.isEmpty()) {
                    hidden = name;
                }
                continue;
            }
            return name;
        }
        return hidden;
    }

    /** Talents possibles de la forme (normaux puis cachés). */
    public static List<String> formAbilities(@Nullable FormData form) {
        List<String> normal = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        if (form != null) {
            for (PotentialAbility potential : form.getAbilities()) {
                String name = potential.getTemplate().getName();
                List<String> target = potential.getClass().getSimpleName().toLowerCase().contains("hidden") ? hidden : normal;
                if (!normal.contains(name) && !hidden.contains(name)) {
                    target.add(name);
                }
            }
        }
        normal.addAll(hidden);
        return normal;
    }

    /** Comme Cobblemon pour les sauvages : les 4 dernières attaques apprises par niveau. */
    public static List<String> defaultMoves(@Nullable FormData form, int level) {
        List<String> out = new ArrayList<>();
        if (form == null) {
            return out;
        }
        TreeMap<Integer, List<MoveTemplate>> byLevel = new TreeMap<>(form.getMoves().getLevelUpMoves());
        for (Map.Entry<Integer, List<MoveTemplate>> e : byLevel.entrySet()) {
            if (e.getKey() > level) {
                break;
            }
            for (MoveTemplate template : e.getValue()) {
                String name = template.getName();
                out.remove(name);
                out.add(name);
            }
        }
        return new ArrayList<>(out.subList(Math.max(0, out.size() - 4), out.size()));
    }

    // =======================================================================================
    // Sauvegarde du spread

    public JsonObject spreadToJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ability", ability);
        o.addProperty("item", item);
        o.addProperty("nature", nature);
        o.addProperty("teraType", teraType);
        o.add("ivs", intArray(ivs));
        o.add("evs", intArray(evs));
        JsonArray mv = new JsonArray();
        for (String move : moves) {
            mv.add(move);
        }
        o.add("moves", mv);
        return o;
    }

    public void applySpread(JsonObject o) {
        if (o.has("ability")) ability = o.get("ability").getAsString();
        if (o.has("item")) item = o.get("item").getAsString();
        if (o.has("nature")) nature = o.get("nature").getAsString();
        if (o.has("teraType")) teraType = o.get("teraType").getAsString();
        readInts(o, "ivs", ivs, 0, 31);
        readInts(o, "evs", evs, 0, 252);
        if (o.has("moves")) {
            JsonArray mv = o.getAsJsonArray("moves");
            for (int i = 0; i < 4; i++) {
                moves[i] = i < mv.size() ? mv.get(i).getAsString() : "";
            }
        }
    }

    private static JsonArray intArray(int[] values) {
        JsonArray a = new JsonArray();
        for (int v : values) {
            a.add(v);
        }
        return a;
    }

    private static void readInts(JsonObject o, String key, int[] target, int min, int max) {
        if (!o.has(key)) {
            return;
        }
        JsonArray a = o.getAsJsonArray(key);
        for (int i = 0; i < target.length && i < a.size(); i++) {
            target[i] = Math.max(min, Math.min(max, a.get(i).getAsInt()));
        }
    }

    // =======================================================================================
    // Requête pour le calculateur

    public JsonObject toRequest() {
        JsonObject o = new JsonObject();
        if (form == null || species == null) {
            return o;
        }
        o.addProperty("species", form.showdownId());
        o.addProperty("name", displayName);
        o.addProperty("level", level);
        o.addProperty("ability", ability);
        o.addProperty("item", item);
        o.addProperty("nature", nature);
        o.addProperty("teraType", teraType);
        o.addProperty("tera", tera);
        o.addProperty("status", status);
        o.addProperty("hpPercent", hpPercent);
        o.add("ivs", statObject(ivs));
        o.add("evs", statObject(evs));
        o.add("boosts", statObject(boosts));
        JsonArray mv = new JsonArray();
        for (String move : moves) {
            mv.add(move);
        }
        o.add("moves", mv);

        // Données Cobblemon (formes, datapacks) prioritaires sur celles de Showdown
        JsonObject base = new JsonObject();
        Map<Stat, Integer> baseStats = form.getBaseStats();
        for (int i = 0; i < 6; i++) {
            Integer v = baseStats.get(Names.STATS[i]);
            if (v != null) {
                base.addProperty(Names.STAT_IDS[i], v);
            }
        }
        if (base.size() == 6) {
            o.add("baseStats", base);
        }
        JsonArray types = new JsonArray();
        for (String type : Names.formTypes(form)) {
            types.add(type);
        }
        o.add("types", types);
        if (form.getWeight() > 0) {
            o.addProperty("weightkg", form.getWeight() / 10F);
        }
        return o;
    }

    private static JsonObject statObject(int[] values) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < 6; i++) {
            o.addProperty(Names.STAT_IDS[i], values[i]);
        }
        return o;
    }
}
