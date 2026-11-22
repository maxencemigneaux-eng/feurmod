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

}
