package com.goat.feurleg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Le menu /leg affiche le moment de la journée de chaque zone ("matin", "nuit", "minuit"...).
 * Un mot = une plage d'heures possibles. À chaque observation, on garde seulement les heures
 * compatibles avec tout ce qu'on a vu (en faisant avancer le temps entre deux observations).
 * Plus on observe (surtout au moment où le mot change), plus l'heure devient précise.
 */
public final class ZonePeriods {

    public static final int DAY = 24000;

    /** Mot (sans accents, minuscules) -> ticks possibles. L'ordre sert à nommer une heure. */
    private static final Map<String, BitSet> WORDS = new LinkedHashMap<>();
    private static final Map<String, String> DISPLAY = new HashMap<>();

    /** État d'une zone : heures possibles au moment refMillis. */
    private static final class State {
        long[] bits;
        long refMillis;
        transient BitSet set;
        transient String lastWord;
        transient long lastObs;
    }

    public record Prediction(Long tick, String word, boolean approx, boolean night) {}

    private static final Gson GSON = new GsonBuilder().create();
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("feurleg-periods2.json");
    }
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, State>>>() {}.getType();

    private static Map<String, State> states = new LinkedHashMap<>();
    private static String serverKey;
    private static long lastSave;
    private static boolean dirty;

    private ZonePeriods() {}

    /** Charge la table des moments (modifiable dans config/feurleg.json). */
    public static void loadTable(Map<String, String> table) {
        WORDS.clear();
        DISPLAY.clear();
        table.forEach((word, ranges) -> {
            BitSet set = parseRanges(ranges);
            if (!set.isEmpty()) {
                WORDS.put(norm(word), set);
                DISPLAY.put(norm(word), word);
            }
        });
    }

    public static Map<String, String> defaultTable() {
        // Plages de Cobblemon (tick 0 = 6:00). Ordre = priorité pour nommer une heure.
        Map<String, String> t = new LinkedHashMap<>();
        t.put("minuit", "17000-18999");
        t.put("midi", "5000-6999");
        t.put("aube", "23000-23999");
        t.put("crépuscule", "12000-12999");
        t.put("matin", "0-4999");
        t.put("après-midi", "7000-11999");
        t.put("nuit", "13000-16999,19000-22999");
        t.put("soir", "12000-12999");
        t.put("jour", "0-12541,23460-23999");
        return t;
    }

    public static String norm(String s) {
        String n = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    /** Renvoie le mot de moment trouvé dans le texte "4 joueurs · nuit · temps clair", ou null. */
    public static String findWord(String text) {
        for (String part : text.split("·")) {
            String w = norm(part);
            if (WORDS.containsKey(w)) return w;
        }
        return null;
    }

    public static boolean wordMatchesTick(String word, long tick) {
        BitSet set = WORDS.get(word);
        return set != null && set.get((int) Math.floorMod(tick, (long) DAY));
    }

    public static void onJoin(String key) {
        serverKey = key;
        Map<String, LinkedHashMap<String, State>> all = readFile();
        states = all.getOrDefault(key, new LinkedHashMap<>());
        for (State s : states.values()) {
            s.set = s.bits != null ? BitSet.valueOf(s.bits) : new BitSet(DAY);
        }
    }

    public static void onLeave() {
        save(true);
        serverKey = null;
        states = new LinkedHashMap<>();
    }

    /** Le menu montre que la zone est actuellement au moment "word". */
    public static void observe(String zone, String word) {
        BitSet allowed = WORDS.get(word);
        if (allowed == null || zone == null) return;
        long now = System.currentTimeMillis();
        State s = states.get(zone);
        if (s != null && word.equals(s.lastWord) && now - s.lastObs < 2000) return;

        if (s == null || s.set == null || s.set.isEmpty()) {
            s = new State();
            s.set = (BitSet) allowed.clone();
        } else {
            BitSet shifted = shift(s.set, ticksSince(s.refMillis, now));
            shifted.and(allowed);
            // Contradiction (heure changée, nuit passée en dormant...) : on repart du mot actuel.
            s.set = shifted.isEmpty() ? (BitSet) allowed.clone() : shifted;
        }
        s.refMillis = now;
        s.lastWord = word;
        s.lastObs = now;
        states.put(zone, s);
        dirty = true;
        save(false);
    }

    /** Prévision pour la zone dans "millisFromNow" ms. null si la zone n'a jamais été observée. */
    public static Prediction predict(String zone, long millisFromNow) {
        State s = states.get(zone);
        if (s == null || s.set == null || s.set.isEmpty()) return null;
        long now = System.currentTimeMillis();
        BitSet c = shift(s.set, ticksSince(s.refMillis, now + millisFromNow));

        // Plus petit arc (circulaire) contenant toutes les heures possibles.
        int first = c.nextSetBit(0);
        int prev = first, bestGap = 0, arcStart = first;
        for (int i = c.nextSetBit(first + 1); i >= 0; i = c.nextSetBit(i + 1)) {
            if (i - prev > bestGap) { bestGap = i - prev; arcStart = i; }
            prev = i;
        }
        int wrapGap = first + DAY - prev;
        if (wrapGap >= bestGap) { bestGap = wrapGap; arcStart = first; }
        int span = DAY - bestGap;
        long center = Math.floorMod((long) arcStart + span / 2, (long) DAY);

        String centerWord = nameOf(center);
        boolean night = center >= 12542 && center < 23460;
        if (span <= 1500) return new Prediction(center, centerWord, true, night);

        // Sinon : le moment du spawn, s'il est le même pour toutes les heures possibles.
        Set<String> words = new LinkedHashSet<>();
        for (int i = c.nextSetBit(0); i >= 0; i = c.nextSetBit(i + 50)) {
            words.add(nameOf(i));
            if (words.size() > 1) break;
        }
        String w = words.size() == 1 ? centerWord : centerWord + "?";
        return new Prediction(null, w, false, night);
    }

    /** Ordre préféré pour nommer une heure (ex. "soir" plutôt que "crépuscule"). */
    private static final String[] DISPLAY_ORDER = {
        "minuit", "midi", "aube", "crepuscule", "matin", "apres-midi", "nuit", "soir", "jour"};

    /** Nom du moment de la journée pour un tick (0-23999), ex. "matin". */
    public static String nameOfTick(long tick) {
        return nameOf(Math.floorMod(tick, (long) DAY));
    }

    private static String nameOf(long tick) {
        for (String w : DISPLAY_ORDER) {
            BitSet set = WORDS.get(w);
            if (set != null && set.get((int) tick)) return DISPLAY.getOrDefault(w, w);
        }
        for (Map.Entry<String, BitSet> e : WORDS.entrySet()) {
            if (e.getValue().get((int) tick)) return DISPLAY.getOrDefault(e.getKey(), e.getKey());
        }
        return "?";
    }

    private static int ticksSince(long fromMillis, long toMillis) {
        return (int) Math.floorMod((toMillis - fromMillis) / 50, (long) DAY);
    }

    private static BitSet shift(BitSet set, int d) {
        BitSet out = new BitSet(DAY);
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
            out.set((i + d) % DAY);
        }
        return out;
    }

    private static BitSet parseRanges(String ranges) {
        BitSet set = new BitSet(DAY);
        for (String part : ranges.split(",")) {
            String[] ab = part.trim().split("-");
            if (ab.length != 2) continue;
            try {
                int a = Integer.parseInt(ab[0].trim()), b = Integer.parseInt(ab[1].trim());
                if (a <= b) set.set(Math.max(0, a), Math.min(DAY, b + 1));
                else { set.set(Math.max(0, a), DAY); set.set(0, Math.min(DAY, b + 1)); }
            } catch (NumberFormatException ignored) {
            }
        }
        return set;
    }

    private static void save(boolean force) {
        if (serverKey == null || !dirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSave < 5000) return;
        lastSave = now;
        dirty = false;
        for (State s : states.values()) s.bits = s.set.toLongArray();
        Map<String, LinkedHashMap<String, State>> all = readFile();
        all.put(serverKey, new LinkedHashMap<>(states));
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(all, FILE_TYPE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FeurlegClient.LOGGER.warn("Impossible d'enregistrer feurleg-periods.json", e);
        }
    }

    public static void tickSave() {
        save(false);
    }

    private static Map<String, LinkedHashMap<String, State>> readFile() {
        try {
            if (Files.exists(file())) {
                Map<String, LinkedHashMap<String, State>> m = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), FILE_TYPE);
                if (m != null) return m;
            }
        } catch (Exception e) {
            FeurlegClient.LOGGER.warn("feurleg-periods.json illisible", e);
        }
        return new LinkedHashMap<>();
    }
}
