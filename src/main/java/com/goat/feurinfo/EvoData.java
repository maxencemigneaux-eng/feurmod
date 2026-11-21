package com.goat.feurinfo;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chaînes d'évolution pour l'onglet « Évos » de Feurinfo.
 *  - Pokémon officiels : table tirée de Pokémon Showdown (assets/feurinfo/data/evos.json, voir calc/evos.js).
 *  - Fakemon / Pokémon ajoutés par des datapacks : lus directement dans Cobblemon quand c'est possible.
 */
public final class EvoData {

    /** Une étape : l'espèce (null si inconnue de Cobblemon), son nom affiché et comment on l'obtient. */
    public record Node(@Nullable Species species, String name, String how) {}

    private record Entry(String base, String form, String prevo, List<String> evos,
                         String method, int level, String item, String move, String cond) {}

    private static Map<String, Entry> table;
    private static Map<String, Item> itemsById;

    private EvoData() {}

    /** Toutes les lignées passant par cette espèce : chaque liste va de la forme de base à une évolution finale. */
    public static List<List<Node>> chains(Species species) {
        load();
        String id = SpeciesLookup.normalize(species.getName());
        if (table.containsKey(id)) {
            String root = id;
            for (int guard = 0; guard < 5 && table.get(root).prevo() != null && table.containsKey(table.get(root).prevo()); guard++) {
                root = table.get(root).prevo();
            }
            List<List<Node>> out = new ArrayList<>();
            walk(root, new ArrayList<>(), out);
            return out;
        }
        return fromCobblemon(species);
    }

    private static void walk(String id, List<Node> path, List<List<Node>> out) {
        Entry e = table.get(id);
        if (e == null) return;
        path.add(node(id, e));
        List<String> next = e.evos().stream().filter(table::containsKey).toList();
        if (next.isEmpty()) {
            out.add(new ArrayList<>(path));
        } else {
            for (String n : next) walk(n, path, out);
        }
        path.remove(path.size() - 1);
    }

    private static Node node(String id, Entry e) {
        Species s = PokemonSpecies.getByName(e.base());
        String name = s != null ? SpeciesLookup.displayName(s) : capitalize(e.base());
        if (e.form() != null) name += " (" + formLabel(e.form()) + ")";
        return new Node(s, name, e.prevo() == null ? "" : howTo(e));
    }

    // ---------------------------------------------------------------------------------------
    // Texte de la méthode d'évolution

    private static String howTo(Entry e) {
        String item = e.item() != null ? itemName(e.item()) : null;
        String text = switch (e.method()) {
            case "level" -> e.level() > 0 ? "Niv. " + e.level() : "Montée de niveau";
            case "useItem" -> item != null ? item : "Objet";
            case "trade" -> item != null ? "Échange en tenant " + item : "Échange";
            case "levelFriendship" -> "Bonheur";
            case "levelHold" -> "Niveau en tenant " + (item != null ? item : "un objet");
            case "levelMove" -> "Niveau en connaissant " + (e.move() != null ? moveName(e.move()) : "une attaque");
            case "levelExtra" -> e.level() > 0 ? "Niv. " + e.level() : "Montée de niveau";
            default -> e.level() > 0 ? "Niv. " + e.level() : "Spécial";
        };
        if (e.cond() != null) text += " · " + condition(e.cond());
        return text;
    }

    private static String condition(String c) {
        return switch (c) {
            case "at night" -> "la nuit";
            case "during the day" -> "le jour";
            case "during rain" -> "sous la pluie";
            case "with an Atk stat > its Def stat" -> "Attaque > Défense";
            case "with an Atk stat < its Def stat" -> "Attaque < Défense";
            case "with an Atk stat equal to its Def stat" -> "Attaque = Défense";
            case "with a Fairy-type move and two levels of Affection" -> "avec une attaque Fée et de l'affection";
            case "near a special magnetic field" -> "près d'un champ magnétique";
            case "with a Dark-type in the party" -> "avec un type Ténèbres dans l'équipe";
            case "with a Karrablast" -> "contre Carabing";
            case "with a Shelmet" -> "contre Escargaume";
            case "with a Remoraid in party" -> "avec Rémoraid dans l'équipe";
            case "from a special Rockruff during the evening" -> "le soir (Rocabot spécial)";
            case "Land 3 critical hits in 1 battle" -> "3 coups critiques en un combat";
            case "Use a Scroll of Darkness" -> "Rouleau des Ténèbres";
            case "Use a Scroll of Waters" -> "Rouleau de l'Eau";
            default -> c;
        };
    }

    private static String formLabel(String forme) {
        return switch (forme) {
            case "Alola" -> "Alola";
            case "Galar" -> "Galar";
            case "Hisui" -> "Hisui";
            case "Paldea" -> "Paldea";
            default -> forme;
        };
    }

    /** "waterstone" → "Pierre Eau" (nom traduit de l'objet Cobblemon ou Minecraft). */
    private static String itemName(String id) {
        if (itemsById == null) {
            itemsById = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                String norm = key.getPath().replace("_", "");
                if (key.getNamespace().equals("cobblemon") || !itemsById.containsKey(norm)) {
                    itemsById.put(norm, item);
                }
            }
        }
        Item item = itemsById.get(id);
        return item != null ? item.getDescription().getString() : capitalize(id);
    }

    private static String moveName(String id) {
        MoveTemplate move = Moves.getByName(id);
        return move != null ? move.getDisplayName().getString() : capitalize(id);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------------------------------
    // Fakemon : on demande à Cobblemon (par réflexion, pour ne pas casser si son code change)

    private static List<List<Node>> fromCobblemon(Species species) {
        List<List<Node>> out = new ArrayList<>();
        try {
            Species root = species;
            for (int guard = 0; guard < 5; guard++) {
                Species pre = preEvolution(root);
                if (pre == null) break;
                root = pre;
            }
            walkCobblemon(root, "", new ArrayList<>(), out, 0);
        } catch (Throwable ignored) {
            // Pas d'info : l'onglet affichera "information non disponible"
        }
        if (out.size() == 1 && out.get(0).size() == 1) out.clear(); // pas d'évolution du tout
        return out;
    }

    private static void walkCobblemon(Species s, String how, List<Node> path, List<List<Node>> out, int depth) throws Exception {
        path.add(new Node(s, SpeciesLookup.displayName(s), how));
        List<Node> next = new ArrayList<>();
        Object evolutions = call(s, "getEvolutions");
        if (evolutions instanceof Collection<?> evos && depth < 4) {
            for (Object evo : evos) {
                Object result = call(evo, "getResult");
                Object name = result == null ? null : call(result, "getSpecies");
                Species target = name == null ? null : PokemonSpecies.getByName(String.valueOf(name).toLowerCase());
                if (target != null && target != s) next.add(new Node(target, "", methodOf(evo)));
            }
        }
        if (next.isEmpty()) {
            out.add(new ArrayList<>(path));
        } else {
            for (Node n : next) walkCobblemon(n.species(), n.how(), path, out, depth + 1);
        }
        path.remove(path.size() - 1);
    }

    private static Species preEvolution(Species s) throws Exception {
        Object pre = call(s, "getPreEvolution");
        Object sp = pre == null ? null : call(pre, "getSpecies");
        return sp instanceof Species species ? species : null;
    }

    private static String methodOf(Object evolution) {
        String name = evolution.getClass().getSimpleName();
        String base = switch (name) {
            case "LevelUpEvolution" -> "Montée de niveau";
            case "ItemInteractionEvolution" -> "Utiliser un objet";
            case "TradeEvolution" -> "Échange";
            case "BlockClickEvolution" -> "Clic sur un bloc";
            default -> "Évolution";
        };
        try {
            Object reqs = call(evolution, "getRequirements");
            if (reqs instanceof Collection<?> list) {
                for (Object r : list) {
                    if (r.getClass().getSimpleName().equals("LevelRequirement")) {
                        Object min = call(r, "getMinLevel");
                        if (min instanceof Integer lvl && lvl > 1) return "Niv. " + lvl;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return base;
    }

    @Nullable
    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    // ---------------------------------------------------------------------------------------

    private static void load() {
        if (table != null) return;
        table = new HashMap<>();
        try (InputStream in = EvoData.class.getResourceAsStream("/assets/feurinfo/data/evos.json")) {
            if (in == null) return;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                List<String> evos = new ArrayList<>();
                if (o.has("e")) o.getAsJsonArray("e").forEach(x -> evos.add(x.getAsString()));
                table.put(e.getKey(), new Entry(
                    str(o, "b"), str(o, "f"), str(o, "p"), evos,
                    o.has("m") ? o.get("m").getAsString() : "level",
                    o.has("l") ? o.get("l").getAsInt() : 0,
                    str(o, "i"), str(o, "mv"), str(o, "c")));
            }
        } catch (Exception ignored) {
            // table vide : on se rabat sur Cobblemon
        }
    }

    @Nullable
    private static String str(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsString() : null;
    }
}
