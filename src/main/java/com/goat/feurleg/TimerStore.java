package com.goat.feurleg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Garde l'heure de fin de chaque timer (heure réelle), par serveur.
 * Les timers continuent donc de tourner même après une déconnexion ou un redémarrage du jeu.
 */
public final class TimerStore {

    public static final class Timer {
        public final String name;
        public long endMillis;
        boolean warned;
        boolean alerted;

        Timer(String name, long endMillis) {
            this.name = name;
            this.endMillis = endMillis;
        }

        public long remainingMillis() {
            return endMillis - System.currentTimeMillis();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("feurleg-timers.json");
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, Long>>>() {}.getType();
    private static final TimerStore INSTANCE = new TimerStore();

    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private String serverKey = null;

    public static TimerStore get() {
        return INSTANCE;
    }

    public List<Timer> list() {
        return new ArrayList<>(timers.values());
    }

    public boolean isEmpty() {
        return timers.isEmpty();
    }

    /** Appelé à la connexion : charge les timers enregistrés pour ce serveur. */
    public void onJoin(String key) {
        serverKey = key;
        timers.clear();
        Map<String, LinkedHashMap<String, Long>> all = readFile();
        Map<String, Long> saved = all.get(key);
        if (saved != null) {
            long now = System.currentTimeMillis();
            int stale = FeurlegConfig.get().staleAfterSeconds;
            saved.forEach((name, end) -> {
                Timer t = new Timer(name, end);
                // Ne pas re-sonner pour des timers déjà passés pendant qu'on était absent.
                if (end - now <= FeurlegConfig.get().warnSeconds * 1000L) t.warned = true;
                if (end - now <= 0) t.alerted = true;
                if (now - end < stale * 1000L) timers.put(name, t);
            });
        }
    }

    public void onLeave() {
        serverKey = null;
        timers.clear();
    }

    /** Met à jour avec les valeurs lues dans le menu /leg (secondes restantes par zone). */
    public void sync(Map<String, Long> secondsByZone) {
        long now = System.currentTimeMillis();
        boolean changed = false;

        // Nouvel ordre = ordre du menu ; on garde les zones connues absentes de la lecture.
        Map<String, Timer> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : secondsByZone.entrySet()) {
            long newEnd = now + e.getValue() * 1000L;
            Timer t = timers.get(e.getKey());
            if (t == null) {
                t = new Timer(e.getKey(), newEnd);
                changed = true;
            } else if (Math.abs(t.endMillis - newEnd) > 2000) {
                // Écart de plus de 2 s : le serveur a relancé ou corrigé ce timer.
                if (newEnd > t.endMillis) {
                    t.warned = false;
                    t.alerted = false;
                }
                t.endMillis = newEnd;
                changed = true;
            }
            reordered.put(t.name, t);
        }
        for (Timer t : timers.values()) reordered.putIfAbsent(t.name, t);
        if (!reordered.keySet().equals(timers.keySet())) changed = true;
        timers.clear();
        timers.putAll(reordered);

        if (changed) save();
    }

    private void save() {
        if (serverKey == null) return;
        Map<String, LinkedHashMap<String, Long>> all = readFile();
        LinkedHashMap<String, Long> mine = new LinkedHashMap<>();
        timers.values().forEach(t -> mine.put(t.name, t.endMillis));
        all.put(serverKey, mine);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(all, FILE_TYPE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FeurlegClient.LOGGER.warn("Impossible d'enregistrer les timers", e);
        }
    }

    private Map<String, LinkedHashMap<String, Long>> readFile() {
        try {
            if (Files.exists(FILE)) {
                Map<String, LinkedHashMap<String, Long>> m = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), FILE_TYPE);
                if (m != null) return m;
            }
        } catch (Exception e) {
            FeurlegClient.LOGGER.warn("feurleg-timers.json illisible", e);
        }
        return new LinkedHashMap<>();
    }
}
