package com.goat.feurinfo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * La table des types (9e génération) : combien une attaque de chaque type fait contre ce Pokémon,
 * et ce que ses talents y changent (Lévitation, Absorb'Eau, Isograisse...).
 */
public final class TypeChart {
    /** Les 18 types, dans l'ordre habituel. */
    public static final String[] TYPES = {
            "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
            "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"};

    /** attaque → (défense → multiplicateur), seulement ce qui n'est pas ×1. */
    private static final Map<String, Map<String, Double>> CHART = new HashMap<>();

    static {
        row("normal", "rock", .5, "ghost", 0, "steel", .5);
        row("fire", "fire", .5, "water", .5, "grass", 2, "ice", 2, "bug", 2, "rock", .5, "dragon", .5, "steel", 2);
        row("water", "fire", 2, "water", .5, "grass", .5, "ground", 2, "rock", 2, "dragon", .5);
        row("electric", "water", 2, "electric", .5, "grass", .5, "ground", 0, "flying", 2, "dragon", .5);
        row("grass", "fire", .5, "water", 2, "grass", .5, "poison", .5, "ground", 2, "flying", .5, "bug", .5, "rock", 2,
                "dragon", .5, "steel", .5);
        row("ice", "fire", .5, "water", .5, "grass", 2, "ice", .5, "ground", 2, "flying", 2, "dragon", 2, "steel", .5);
        row("fighting", "normal", 2, "ice", 2, "poison", .5, "flying", .5, "psychic", .5, "bug", .5, "rock", 2, "ghost", 0,
                "dark", 2, "steel", 2, "fairy", .5);
        row("poison", "grass", 2, "poison", .5, "ground", .5, "rock", .5, "ghost", .5, "steel", 0, "fairy", 2);
        row("ground", "fire", 2, "electric", 2, "grass", .5, "poison", 2, "flying", 0, "bug", .5, "rock", 2, "steel", 2);
        row("flying", "electric", .5, "grass", 2, "fighting", 2, "bug", 2, "rock", .5, "steel", .5);
        row("psychic", "fighting", 2, "poison", 2, "psychic", .5, "dark", 0, "steel", .5);
        row("bug", "fire", .5, "grass", 2, "fighting", .5, "poison", .5, "flying", .5, "psychic", 2, "ghost", .5, "dark", 2,
                "steel", .5, "fairy", .5);
        row("rock", "fire", 2, "ice", 2, "fighting", .5, "ground", .5, "flying", 2, "bug", 2, "steel", .5);
        row("ghost", "normal", 0, "psychic", 2, "ghost", 2, "dark", .5);
        row("dragon", "dragon", 2, "steel", .5, "fairy", 0);
        row("dark", "fighting", .5, "psychic", 2, "ghost", 2, "dark", .5, "fairy", .5);
        row("steel", "fire", .5, "water", .5, "electric", .5, "ice", 2, "rock", 2, "steel", .5, "fairy", 2);
        row("fairy", "fire", .5, "fighting", 2, "poison", .5, "dragon", 2, "dark", 2, "steel", .5);
    }

    private static void row(String attack, Object... pairs) {
        Map<String, Double> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        CHART.put(attack, map);
    }

    private TypeChart() {
    }

    /** Efficacité d'une attaque de ce type contre ces types de défense. */
    public static double effectiveness(String attack, List<String> defense) {
        Map<String, Double> row = CHART.get(attack.toLowerCase(Locale.ROOT));
        double total = 1;
        if (row == null) {
            return total;
        }
        for (String type : defense) {
            total *= row.getOrDefault(type.toLowerCase(Locale.ROOT), 1.0);
        }
        return total;
    }

    /** Les 18 types d'attaque → multiplicateur contre ces types de défense. */
    public static Map<String, Double> defensive(List<String> defense) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String attack : TYPES) {
            out.put(attack, effectiveness(attack, defense));
        }
        return out;
    }

    /**
     * Le même tableau avec ce talent, ou null si le talent ne change rien aux types
     * (seulement les talents qui touchent les dégâts reçus selon le type de l'attaque).
     */
    public static Map<String, Double> withAbility(Map<String, Double> base, String abilityId) {
        Map<String, Double> out = new LinkedHashMap<>(base);
        switch (abilityId) {
            case "levitate", "eartheater" -> out.put("ground", 0.0);
            case "flashfire", "wellbakedbody" -> out.put("fire", 0.0);
            case "waterabsorb", "stormdrain" -> out.put("water", 0.0);
            case "dryskin" -> {
                out.put("water", 0.0);
                out.put("fire", base.get("fire") * 1.25);
            }
            case "voltabsorb", "lightningrod", "motordrive" -> out.put("electric", 0.0);
            case "sapsipper" -> out.put("grass", 0.0);
            case "thickfat" -> {
                out.put("fire", base.get("fire") * 0.5);
                out.put("ice", base.get("ice") * 0.5);
            }
            case "heatproof", "waterbubble" -> out.put("fire", base.get("fire") * 0.5);
            case "purifyingsalt" -> out.put("ghost", base.get("ghost") * 0.5);
            case "fluffy" -> out.put("fire", base.get("fire") * 2);
            case "filter", "solidrock", "prismarmor" -> {
                for (Map.Entry<String, Double> e : base.entrySet()) {
                    if (e.getValue() > 1) {
                        out.put(e.getKey(), e.getValue() * 0.75);
                    }
                }
            }
            case "wonderguard" -> {
                for (Map.Entry<String, Double> e : base.entrySet()) {
                    if (e.getValue() <= 1) {
                        out.put(e.getKey(), 0.0);
                    }
                }
            }
            default -> {
                return null;
            }
        }
        return out.equals(base) ? null : out;
    }

    /** "×4", "×2", "×1", "×½", "×¼", "×0", sinon "×1,25". */
    public static String label(double value) {
        if (value == 0) {
            return "×0";
        }
        if (Math.abs(value - 0.5) < 1e-6) {
            return "×½";
        }
        if (Math.abs(value - 0.25) < 1e-6) {
            return "×¼";
        }
        if (Math.abs(value - Math.rint(value)) < 1e-6) {
            return "×" + (int) Math.rint(value);
        }
        String s = String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        return "×" + s.replace('.', ',');
    }
}
