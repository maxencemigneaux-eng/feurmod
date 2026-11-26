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

        /** Fin connue précisément : lue au moment exact où le chrono du menu a changé (ou reprise du fichier). */
        boolean precise;
        /** Quand ce timer a été créé ou relancé (heure réelle). */
        long cycleStart;
        /** Dernière valeur lue dans le menu (secondes) et quand, pour repérer le moment où elle change. */

        /** Heure Minecraft du spawn, calculée une fois puis figée jusqu'au prochain cycle. -1 = pas encore. */
        public long frozenTick = -1;
        /** true si l'heure figée vient de l'horloge exacte de la zone (sinon estimation par le moment de la journée). */
        public boolean frozenExact;

        Timer(String name, long endMillis) {
            this.name = name;
            this.endMillis = endMillis;
            this.cycleStart = TickClock.now();
        }

        /** Nouveau cycle (le serveur a relancé le timer) : on oublie l'heure figée. */
        void restart(long newEnd, long now) {
            if (newEnd > endMillis) {
                warned = false;
                alerted = false;
            }
            endMillis = newEnd;
            precise = false;
            cycleStart = now;
            frozenTick = -1;
            frozenExact = false;
        }

        /** Assez sûr pour figer l'heure Minecraft : fin précise, ou lue depuis plus de 10 s. */
        public boolean settled() {
            return precise || TickClock.now() - cycleStart > 10_000;
        }

        /** Temps restant en millisecondes "temps serveur" (1 s = 20 ticks du serveur, comme le timer de /leg). */
        public long remainingMillis() {
            return endMillis - TickClock.now();
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
                // Le fichier garde la fin en temps réel ; en mémoire, elle est en "temps serveur"
                Timer t = new Timer(name, TickClock.fromReal(end));
                t.precise = true; // déjà affinée lors d'une session précédente
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

    /** Au-delà de cet écart, c'est un nouveau cycle (le serveur a relancé le timer), pas une imprécision de lecture. */
    private static final long RESTART_MS = 30_000;

    /**
     * Met à jour avec les valeurs lues dans le menu /leg (secondes restantes par zone). Appelé à chaque image tant que
     * le menu est ouvert. Comme avant la 1.0.46 : le timer se recale dès que l'écart avec /leg dépasse 2 s.
     */
    public void sync(Map<String, Long> secondsByZone) {
        long now = TickClock.now();
        boolean changed = false;

        // Nouvel ordre = ordre du menu ; on garde les zones connues absentes de la lecture.
        Map<String, Timer> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : secondsByZone.entrySet()) {
            long shown = e.getValue();
            long estimate = now + shown * 1000L;
            Timer t = timers.get(e.getKey());
            if (t == null) {
                t = new Timer(e.getKey(), estimate);
                changed = true;
            } else if (Math.abs(estimate - t.endMillis) > RESTART_MS) {
                t.restart(estimate, now);
                changed = true;
            } else if (Math.abs(estimate - t.endMillis) > 2000) {
                // Petit écart : même cycle, on recale la fin (l'heure de spawn figée ne bouge pas)
                if (estimate > t.endMillis) {
                    t.warned = false;
                    t.alerted = false;
                }
                t.endMillis = estimate;
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
        timers.values().forEach(t -> mine.put(t.name, TickClock.toReal(t.endMillis)));
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
