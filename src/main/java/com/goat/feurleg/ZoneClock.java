package com.goat.feurleg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Chaque zone (Johto, Unys...) a sa propre heure Minecraft.
 * On apprend l'heure d'une zone quand le joueur s'y trouve (repéré grâce au menu /leg,
 * ligne "Unys · nuit · pluie · 17"), puis on la fait avancer toute seule, à la vitesse mesurée.
 */
public final class ZoneClock {

    /** Heure connue d'une zone : timeOfDay au moment "millis", et vitesse (ticks par tick réel). */
    public static final class Clock {
        public long tod;
        /** Moment de la mesure, en temps réel (pour le fichier). */
        public long millis;
        /** Vitesse de l'heure de la zone, en ticks d'heure par tick du serveur (1.0 normalement). */
        public double rate = 1.0;
        /** Moment de la mesure en "temps serveur" (cette session seulement, pas enregistré). */
        transient long serverMillis = -1;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("feurleg-zones2.json");
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, Data>>() {}.getType();
    private static final Set<String> GENERIC_DIMENSIONS = Set.of(
        "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    /** Ce qui est sauvegardé pour un serveur. */
    private static final class Data {
        Map<String, Clock> clocks = new LinkedHashMap<>();
        Map<String, String> dimensionToZone = new HashMap<>();
        Set<String> ambiguousDimensions = new HashSet<>();
    }

    private static Data data = new Data();
    private static String serverKey;

    private static ClientLevel lastWorld;
    private static String currentZone;
    private static long anchorTod, anchorMillis, lastTod, lastMillis;
    private static boolean hasAnchor;
    /** La zone actuelle vient du HUD du serveur (sinon de /leg ou de la dimension). */
    private static boolean zoneFromHud;
    private static int tickCounter;
    private static long lastSave;

    private ZoneClock() {}

    public static void onJoin(String key) {
        serverKey = key;
        Map<String, Data> all = readFile();
        data = all.getOrDefault(key, new Data());
        if (data.clocks == null) data.clocks = new LinkedHashMap<>();
        if (data.dimensionToZone == null) data.dimensionToZone = new HashMap<>();
        if (data.ambiguousDimensions == null) data.ambiguousDimensions = new HashSet<>();
        // Les anciennes vitesses étaient mesurées en temps réel (faussées quand le serveur rame) : une vitesse proche
        // de 1 est en fait une heure normale, on repart de 1 (elle sera remesurée en temps serveur dans la zone).
        for (Clock c : data.clocks.values()) {
            if (c.rate > 0.85 && c.rate < 1.15) c.rate = 1.0;
        }
        lastWorld = null;
        currentZone = null;
        hasAnchor = false;
    }

    public static void onLeave() {
        save();
        serverKey = null;
        lastWorld = null;
        currentZone = null;
        hasAnchor = false;
    }

    /** Appelé quand le menu /leg indique dans quelle zone se trouve le joueur (null = aucune zone connue, ex. Spawn). */
    public static void onLegSeen(String zone, String word) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        lastWorld = client.level;
        // Vérification : l'heure du monde où on est doit correspondre au moment affiché pour la zone.
        // Sinon (ex. au spawn, heure figée), ce monde n'est pas vraiment la zone.
        if (zone != null && (word == null || !ZonePeriods.wordMatchesTick(word, client.level.getDayTime()))) {
            zone = null;
        }
        if (zone == null) {
            currentZone = null;
            hasAnchor = false;
            return;
        }
        if (!zone.equals(currentZone)) hasAnchor = false;
        currentZone = zone;
        zoneFromHud = false;

        String dim = client.level.dimension().location().toString();
        if (!GENERIC_DIMENSIONS.contains(dim) && !data.ambiguousDimensions.contains(dim)) {
            String known = data.dimensionToZone.get(dim);
            if (known == null) {
                data.dimensionToZone.put(dim, zone);
            } else if (!known.equals(zone)) {
                // Même dimension pour deux zones : on ne peut pas s'y fier.
                data.dimensionToZone.remove(dim);
                data.ambiguousDimensions.add(dim);
            }
        }
        sample(client.level, true);
    }

    public static void tick(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null || serverKey == null) return;

        if (world != lastWorld) {
            // Changement de monde : on retrouve la zone grâce à la dimension, si elle est fiable.
            lastWorld = world;
            hasAnchor = false;
            String dim = world.dimension().location().toString();
            currentZone = data.dimensionToZone.get(dim);
        }
        if (currentZone == null) return;
        if (++tickCounter % 20 != 0) return;
        sample(world, false);
    }

    private static void sample(ClientLevel world, boolean force) {
        long tod = world.getDayTime();
        // La vitesse se mesure en "temps serveur" : 1 tick d'heure par tick du serveur, même quand le serveur rame
        long now = TickClock.now();
        Clock c = data.clocks.computeIfAbsent(currentZone, z -> new Clock());

        if (!hasAnchor) {
            hasAnchor = true;
            anchorTod = tod;
            anchorMillis = now;
        } else {
            long realTicks = Math.max(1, (now - lastMillis) / 50);
            long delta = tod - lastTod;
            // Saut d'heure (/time set, nuit passée en dormant...) : on repart de zéro pour la vitesse.
            if (delta < 0 || delta > realTicks * 20 + 200) {
                anchorTod = tod;
                anchorMillis = now;
            } else if (now - anchorMillis >= 60_000) {
                // Mesure sur au moins 1 min : sur 10 s, l'heure du client (corrigée par le serveur 1 fois/s)
                // donnait une vitesse fausse de plusieurs %, donc une heure prévue décalée.
                double measured = (tod - anchorTod) / ((now - anchorMillis) / 50.0);
                c.rate = Math.max(0.0, Math.min(20.0, measured));
            }
        }
        lastTod = tod;
        lastMillis = now;
        c.tod = tod;
        c.millis = System.currentTimeMillis();
        c.serverMillis = now;

        if (force || System.currentTimeMillis() - lastSave > 30_000) save();
    }

    /**
     * Zone lue dans le HUD du serveur (nom du monde en haut de l'écran), null si aucun nom de zone n'est affiché.
     * Plus fiable que /leg : le joueur n'a rien à ouvrir, et c'est vérifié chaque seconde.
     */
    public static void onHudZone(String zone) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || serverKey == null) return;
        if (zone == null) {
            // Ne retire que ce que le HUD avait trouvé (un serveur sans nom de monde à l'écran garde /leg)
            if (zoneFromHud && currentZone != null) {
                currentZone = null;
                hasAnchor = false;
            }
            zoneFromHud = false;
            return;
        }
        lastWorld = client.level;
        zoneFromHud = true;
        if (zone.equals(currentZone)) return; // déjà connue : tick() la mesure chaque seconde
        currentZone = zone;
        hasAnchor = false;
        String dim = client.level.dimension().location().toString();
        if (!GENERIC_DIMENSIONS.contains(dim) && !data.ambiguousDimensions.contains(dim)) {
            String known = data.dimensionToZone.get(dim);
            if (known == null) {
                data.dimensionToZone.put(dim, zone);
            } else if (!known.equals(zone)) {
                data.dimensionToZone.remove(dim);
                data.ambiguousDimensions.add(dim);
            }
        }
        sample(client.level, true);
    }

    /**
     * L'heure de cette zone a-t-elle été mesurée pendant cette session ? Sinon elle vient du fichier et a pu changer
     * depuis (redémarrage du serveur, nuit passée en dormant...) : ce n'est qu'une estimation.
     */
    public static boolean isFresh(String zone) {
        Clock c = data.clocks.get(zone);
        return c != null && c.serverMillis >= 0;
    }

    /** Zone où se trouve le joueur (repérée avec /leg ou la dimension), ou null. */
    public static String currentZone() {
        return currentZone;
    }

    /**
     * Heure de la journée (0-23999) dans la zone, dans "millisFromNow" ms de temps serveur (= le temps restant d'un
     * timer : 1 s = 20 ticks). null si on ne connaît pas encore la zone.
     */
    public static Long predictTick(String zone, long millisFromNow) {
        Clock c = data.clocks.get(zone);
        if (c == null) return null;
        // Temps serveur écoulé depuis la mesure : exact si elle date de cette session, sinon estimé (temps réel)
        long since = c.serverMillis >= 0 ? TickClock.now() - c.serverMillis : System.currentTimeMillis() - c.millis;
        double ticks = c.rate * ((since + millisFromNow) / 50.0);
        return Math.floorMod(c.tod + Math.round(ticks), 24000L);
    }

    private static void save() {
        if (serverKey == null) return;
        lastSave = System.currentTimeMillis();
        Map<String, Data> all = readFile();
        all.put(serverKey, data);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(all, FILE_TYPE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FeurlegClient.LOGGER.warn("Impossible d'enregistrer feurleg-zones.json", e);
        }
    }

    private static Map<String, Data> readFile() {
        try {
            if (Files.exists(FILE)) {
                Map<String, Data> m = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), FILE_TYPE);
                if (m != null) return m;
            }
        } catch (Exception e) {
            FeurlegClient.LOGGER.warn("feurleg-zones.json illisible", e);
        }
        return new LinkedHashMap<>();
    }
}
