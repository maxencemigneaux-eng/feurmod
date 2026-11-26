package com.goat.feurinfo;

import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokemon.moves.Learnset;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * /feurwiki export vol → config/feurwiki-vol.csv : tous les Pokémon (formes, Méga et fusions du serveur compris)
 * qui ont ce type, avec types, talents, stats et attaques. Le .csv s'ouvre directement dans Excel.
 */
final class Exporter {
    private static final Stats[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};

    private Exporter() {}

    /** Le fichier écrit, ou null si le type n'existe pas. */
    static Path export(String typeQuery) throws Exception {
        return export(List.of(typeQuery));
    }

    /** Les Pokémon qui ont tous ces types (un ou deux). */
    static Path export(Collection<String> typeQueries) throws Exception {
        List<String> wanted = new ArrayList<>();
        for (String t : typeQueries) wanted.add(SpeciesLookup.normalize(t));
        if (wanted.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("﻿"); // BOM : Excel lit les accents
        sb.append(String.join(";", "N°", "Pokémon", "Forme", "Catégorie", "Type 1", "Type 2", "Talent 1", "Talent 2", "Talent caché",
                "PV", "Atq", "Déf", "Atq.Spé", "Déf.Spé", "Vit", "Total",
                "Attaques par niveau", "CT", "Œuf", "Tuteur")).append("\r\n");
        int rows = 0;
        for (Species sp : SpeciesLookup.all()) {
            FormData standard = sp.getStandardForm();
            List<FormData> forms = new ArrayList<>();
            forms.add(standard);
            forms.addAll(sp.getForms());
            Set<String> seen = new HashSet<>();
            for (FormData f : forms) {
                List<String> types = new ArrayList<>();
                Set<String> has = new HashSet<>();
                for (ElementalType t : f.getTypes()) {
                    String shown = t.getDisplayName().getString();
                    types.add(shown);
                    has.add(SpeciesLookup.normalize(t.getName()));
                    has.add(SpeciesLookup.normalize(shown));
                }
                if (!has.containsAll(wanted)) continue;

                List<String> normal = new ArrayList<>();
                String hidden = "";
                for (PotentialAbility p : f.getAbilities()) {
                    String name = FeurinfoScreen.abilityLabel(p.getTemplate());
                    if (p instanceof HiddenAbility) hidden = name;
                    else if (!normal.contains(name)) normal.add(name);
                }
                Map<Stat, Integer> base = f.getBaseStats();
                int[] st = new int[6];
                int total = 0;
                for (int i = 0; i < 6; i++) {
                    st[i] = base.getOrDefault(STATS[i], 0);
                    total += st[i];
                }
                // Formes purement cosmétiques (motifs de Prismillon...) : une seule ligne
                String sig = types + "|" + normal + "|" + hidden + "|" + java.util.Arrays.toString(st);
                if (!seen.add(sig)) continue;

                String formName = f == standard || f.getName().equalsIgnoreCase(standard.getName()) ? "" : f.getName();
                String n = f.getName().toLowerCase(Locale.ROOT);
                String category = n.contains("mega") ? "Méga" : n.contains("primal") ? "Primo" : n.contains("gmax") ? "Gigamax"
                        : !sp.getResourceIdentifier().getNamespace().equals("cobblemon") || sp.getNationalPokedexNumber() > 1025
                        || sp.getNationalPokedexNumber() <= 0 ? "Custom / fusion" : "";

                Learnset ls = f.getMoves();
                List<String> level = new ArrayList<>();
                for (Map.Entry<Integer, List<MoveTemplate>> e : new TreeMap<>(ls.getLevelUpMoves()).entrySet()) {
                    for (MoveTemplate m : e.getValue()) {
                        level.add(m.getDisplayName().getString() + " (" + Math.max(1, e.getKey()) + ")");
                    }
                }
                row(sb, String.valueOf(sp.getNationalPokedexNumber()), SpeciesLookup.displayName(sp), formName, category,
                        types.get(0), types.size() > 1 ? types.get(1) : "",
                        normal.size() > 0 ? normal.get(0) : "", normal.size() > 1 ? normal.get(1) : "", hidden,
                        String.valueOf(st[0]), String.valueOf(st[1]), String.valueOf(st[2]), String.valueOf(st[3]),
                        String.valueOf(st[4]), String.valueOf(st[5]), String.valueOf(total),
                        String.join(", ", new LinkedHashSet<>(level)), names(ls.getTmMoves()), names(ls.getEggMoves()), names(ls.getTutorMoves()));
                rows++;
            }
        }
        if (rows == 0) return null;
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config")
                .resolve("feurwiki-" + String.join("-", wanted).replaceAll("[^a-z0-9-]", "") + ".csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String names(Collection<MoveTemplate> moves) {
        Set<String> out = new LinkedHashSet<>();
        for (MoveTemplate m : moves) out.add(m.getDisplayName().getString());
        return String.join(", ", out);
    }

    private static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(';');
            sb.append('"').append(cells[i].replace("\"", "\"\"")).append('"');
        }
        sb.append("\r\n");
    }
}
