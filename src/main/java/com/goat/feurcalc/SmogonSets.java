package com.goat.feurcalc;

import com.cobblemon.mod.common.pokemon.FormData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Les sets "tout faits" façon Showdex : les sets Smogon (analyses) et le set le plus joué sur Showdown
 * (statistiques d'utilisation), pour chaque format. Téléchargés pendant la compilation, rangés dans le .jar.
 */
public final class SmogonSets {
    private static final Logger LOG = LoggerFactory.getLogger("Feurcalc");

    public enum Status { NOT_STARTED, LOADING, READY, FAILED }

    public record Format(String id, String label, String date, int battles) {
    }

    /**
     * Un set. name vide = le set "Usage Showdown" (le plus joué), avec les pourcentages d'utilisation.
     * items : par ordre de préférence (le 1er objet que Cobblemon connaît est utilisé).
     */
    public record SmogonSet(Format format, String name, String ability, int abilityPct, List<String> items,
                            int[] itemPcts, String nature, int[] evs, int spreadPct, int[] ivs, List<String> moves,
                            int[] movePcts, String tera, double usage, int count) {
        public boolean isUsage() {
            return name.isEmpty();
        }

        public String displayName() {
            return isUsage() ? Names.tr("feurcalc.set.usage") : name;
        }

        public String fullLabel() {
            return format.label() + " · " + displayName();
        }

        /** Identifiant stable (pour retrouver le set choisi). */
        public String id() {
            return format.id() + "|" + name;
        }

        /** Le 1er objet du set qui existe dans Cobblemon (sinon le 1er tout court). */
        public String bestItem() {
            if (items.isEmpty()) {
                return "";
            }
            Map<String, ?> known = Names.itemsById();
            for (String item : items) {
                if (item.isEmpty() || known.containsKey(item)) {
                    return item;
                }
            }
            return items.get(0);
        }
    }

    private static volatile Status status = Status.NOT_STARTED;
    private static volatile Map<String, List<SmogonSet>> sets = Collections.emptyMap();
    private static volatile String dataDate = "";

    private SmogonSets() {
    }

    public static Status status() {
        return status;
    }

    public static String date() {
        return dataDate;
    }

    /** Lit le fichier des sets (≈ 2 Mo) en arrière-plan, une seule fois. */
    public static synchronized void load() {
        if (status != Status.NOT_STARTED) {
            return;
        }
        status = Status.LOADING;
        CompletableFuture.runAsync(SmogonSets::read, Util.backgroundExecutor());
    }

    private static void read() {
        long start = System.currentTimeMillis();
        try (InputStream in = SmogonSets.class.getResourceAsStream("/assets/feurcalc/sets/sets.json")) {
            if (in == null) {
                throw new IllegalStateException("sets.json introuvable dans le .jar");
            }
            JsonObject root;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            List<Format> formats = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("formats")) {
                JsonObject f = e.getAsJsonObject();
                formats.add(new Format(str(f, "id"), str(f, "label"), str(f, "date"), f.has("battles") ? f.get("battles").getAsInt() : 0));
            }
            Map<String, List<SmogonSet>> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("pokemon").entrySet()) {
                List<SmogonSet> list = new ArrayList<>();
                for (JsonElement el : e.getValue().getAsJsonArray()) {
                    try {
                        list.add(parse(el.getAsJsonObject(), formats));
                    } catch (Exception ex) {
                        LOG.debug("Set illisible pour {}", e.getKey(), ex);
                    }
                }
                map.put(e.getKey(), Collections.unmodifiableList(list));
            }
            sets = map;
            dataDate = str(root, "date");
            status = Status.READY;
            LOG.info("Sets Smogon chargés ({} Pokémon, données du {}) en {} ms", map.size(), dataDate,
                    System.currentTimeMillis() - start);
        } catch (Throwable t) {
            status = Status.FAILED;
            LOG.warn("Impossible de lire les sets Smogon", t);
        }
    }

    private static SmogonSet parse(JsonObject o, List<Format> formats) {
        Format format = formats.get(o.get("f").getAsInt());
        int[] evs = ints(o, "ev", 6, 0);
        int[] ivs = ints(o, "iv", 6, 31);
        return new SmogonSet(format, str(o, "n"), str(o, "a"), o.has("ap") ? o.get("ap").getAsInt() : -1,
                strings(o, "i"), ints(o, "ip", 0, -1), str(o, "na"), evs, o.has("sp") ? o.get("sp").getAsInt() : -1, ivs,
                strings(o, "m"), ints(o, "mp", 0, -1), str(o, "t"), o.has("u") ? o.get("u").getAsDouble() : 0,
                o.has("c") ? o.get("c").getAsInt() : 0);
    }

    // =======================================================================================
    // Sets d'un Pokémon

    /** Tous les sets connus pour la forme (sinon pour l'espèce), dans l'ordre d'affichage. */
    public static List<SmogonSet> forMon(MonState m) {
        if (m.isEmpty() || status != Status.READY) {
            return Collections.emptyList();
        }
        FormData form = m.form;
        List<SmogonSet> list = sets.get(Names.toId(form.showdownId()));
        if (list == null || list.isEmpty()) {
            list = sets.get(Names.toId(m.species.getStandardForm().showdownId()));
        }
        if (list == null || list.isEmpty()) {
            list = sets.get(Names.toId(m.species.getName()));
        }
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Le set "le plus joué" : le set d'usage du format où ce Pokémon est le plus utilisé
     * (en évitant les objets qui n'existent pas dans Cobblemon, comme les cristaux Z), sinon le 1er set Smogon.
     */
    @Nullable
    public static SmogonSet best(MonState m) {
        SmogonSet best = null;
        double bestScore = -1;
        Map<String, ?> known = Names.itemsById();
        for (SmogonSet set : forMon(m)) {
            double score = set.isUsage() ? 1 + set.count() : 0.5;
            String item = set.bestItem();
            if (!item.isEmpty() && !known.isEmpty() && !known.containsKey(item)) {
                score *= 0.25;
            }
            if (score > bestScore) {
                bestScore = score;
                best = set;
            }
        }
        return best;
    }

    public static boolean applyBest(MonState m) {
        SmogonSet set = best(m);
        if (set == null) {
            return false;
        }
        apply(m, set);
        return true;
    }

    /** Remplace le spread (talent, objet, nature, EV, IV, attaques, Téra) par celui du set. */
    public static void apply(MonState m, SmogonSet set) {
        if (!set.ability().isEmpty()) {
            m.ability = set.ability();
        }
        m.item = set.bestItem();
        if (!set.nature().isEmpty()) {
            m.nature = set.nature();
        }
        System.arraycopy(set.evs(), 0, m.evs, 0, 6);
        System.arraycopy(set.ivs(), 0, m.ivs, 0, 6);
        for (int i = 0; i < 4; i++) {
            m.moves[i] = i < set.moves().size() ? set.moves().get(i) : "";
        }
        if (!set.tera().isEmpty()) {
            m.teraType = set.tera();
        }
        m.setId = set.id();
        m.setLabel = set.fullLabel();
        m.setModified = false;
        m.autoSet = false;
    }

    // =======================================================================================
    // Infobulle d'un set

    public static List<Component> tooltip(SmogonSet set) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(set.fullLabel()).withStyle(ChatFormatting.YELLOW));
        if (set.isUsage()) {
            lines.add(Component.translatable("feurcalc.set.usage_line", percent(set.usage() * 100), set.count(),
                    set.format().date()).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("feurcalc.set.smogon_line").withStyle(ChatFormatting.GRAY));
        }
        if (!set.ability().isEmpty()) {
            lines.add(line("feurcalc.ability", Names.abilityName(set.ability()), set.abilityPct()));
        }
        if (!set.items().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < set.items().size(); i++) {
                String id = set.items().get(i);
                String name = id.isEmpty() ? Names.tr("feurcalc.none") : Names.itemName(id);
                parts.add(i < set.itemPcts().length && set.itemPcts()[i] >= 0 ? name + " " + set.itemPcts()[i] + "%" : name);
            }
            lines.add(Component.literal(Names.tr("feurcalc.item") + " : ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.join(", ", parts)).withStyle(ChatFormatting.WHITE)));
        }
        String spread = (set.nature().isEmpty() ? "" : Names.natureName(set.nature()) + " — ") + evText(set.evs());
        lines.add(line("feurcalc.nature", spread, set.spreadPct()));
        String ivs = ivText(set.ivs());
        if (!ivs.isEmpty()) {
            lines.add(Component.literal("IV : ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(ivs).withStyle(ChatFormatting.WHITE)));
        }
        if (!set.tera().isEmpty()) {
            lines.add(line("feurcalc.tera", Names.typeName(set.tera()), -1));
        }
        for (int i = 0; i < set.moves().size(); i++) {
            String move = Names.moveName(set.moves().get(i));
            int p = i < set.movePcts().length ? set.movePcts()[i] : -1;
            lines.add(Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(move).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(p >= 0 ? "  " + p + "%" : "").withStyle(ChatFormatting.GRAY)));
        }
        return lines;
    }

    private static Component line(String labelKey, String value, int pct) {
        return Component.literal(Names.tr(labelKey) + " : ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(pct >= 0 ? "  " + pct + "%" : "").withStyle(ChatFormatting.GRAY));
    }

    /** "252 Atq / 4 DéfSpé / 252 Vit" */
    public static String evText(int[] evs) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (evs[i] > 0) {
                parts.add(evs[i] + " " + Names.statLabel(i));
            }
        }
        return parts.isEmpty() ? "EV 0" : String.join(" / ", parts);
    }

    private static String ivText(int[] ivs) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (ivs[i] != 31) {
                parts.add(ivs[i] + " " + Names.statLabel(i));
            }
        }
        return String.join(" / ", parts);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, value >= 10 ? "%.0f" : "%.1f", value).replace('.', ',');
    }

    // =======================================================================================
    // JSON

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                out.add(e.isJsonPrimitive() ? e.getAsString() : "");
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static int[] ints(JsonObject o, String key, int size, int fallback) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            int[] out = new int[size];
            java.util.Arrays.fill(out, fallback);
            return out;
        }
        JsonArray a = o.getAsJsonArray(key);
        int[] out = new int[size > 0 ? size : a.size()];
        java.util.Arrays.fill(out, fallback);
        for (int i = 0; i < out.length && i < a.size(); i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }
}
