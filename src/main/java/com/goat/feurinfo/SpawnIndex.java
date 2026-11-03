package com.goat.feurinfo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Où apparaissent les Pokémon : on lit les fichiers d'apparition de Cobblemon (data/xxx/spawn_pool_world).
 *
 * - En solo : ceux chargés par ton monde (datapacks compris).
 * - Sur un serveur : ceux des mods installés chez toi (le serveur ne les envoie pas au client).
 */
public final class SpawnIndex {
    private static final Logger LOG = LoggerFactory.getLogger("Feurinfo");

    public enum Source { NONE, MODS, WORLD }

    public enum Role { SOLO, LEADER, FOLLOWER }

    /** Une façon d'apparaître pour une espèce. */
    public record Entry(String species, List<String> aspects, boolean alpha, Role role, String leader, boolean leaderAlpha,
                        int herdSize, String bucket, String level, String position, JsonObject condition,
                        JsonObject anticondition, List<String> presets, List<JsonObject> multipliers, double weight,
                        String id, boolean snackOnly) {
    }

    private static volatile Map<String, List<Entry>> index = Collections.emptyMap();
    private static volatile Source loadedSource = Source.NONE;
    /** Le monde solo pour lequel les données ont été lues (pour recharger si on change de monde). */
    private static volatile WeakReference<MinecraftServer> loadedServer = new WeakReference<>(null);
    private static volatile boolean loading;
    private static volatile int version;

    private SpawnIndex() {
    }

    public static boolean isReady() {
        return loadedSource != Source.NONE && !loading;
    }

    public static Source source() {
        return loadedSource;
    }

    /** Change à chaque rechargement (pour savoir quand refaire l'affichage). */
    public static int version() {
        return version;
    }

    public static List<Entry> get(String speciesId) {
        return index.getOrDefault(toId(speciesId), Collections.emptyList());
    }

    /** Charge (ou recharge si on a changé de monde / de serveur) les données d'apparition, en arrière-plan. */
    public static synchronized void ensureLoaded() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        Source wanted = server != null ? Source.WORLD : Source.MODS;
        if (loading) {
            return;
        }
        if (loadedSource == wanted && (wanted == Source.MODS || loadedServer.get() == server)) {
            return;
        }
        loading = true;
        CompletableFuture.runAsync(() -> {
            long start = System.currentTimeMillis();
            Map<String, List<Entry>> built = Collections.emptyMap();
            try {
                Map<String, JsonObject> files = wanted == Source.WORLD ? readFromServer(server) : readFromMods();
                built = build(files);
                LOG.info("Apparitions chargées ({} fichiers, source {}) en {} ms", files.size(), wanted,
                        System.currentTimeMillis() - start);
            } catch (Throwable t) {
                LOG.warn("Impossible de lire les données d'apparition", t);
            } finally {
                synchronized (SpawnIndex.class) {
                    index = built;
                    loadedServer = new WeakReference<>(server);
                    loadedSource = wanted;
                    version++;
                    loading = false;
                }
            }
        }, Util.backgroundExecutor());
    }

    // =======================================================================================
    // Lecture des fichiers

    private static Map<String, JsonObject> readFromServer(MinecraftServer server) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> found = server.getResourceManager()
                .listResources("spawn_pool_world", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> e : found.entrySet()) {
            try (Reader reader = e.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (json.isJsonObject()) {
                    out.put(e.getKey().toString(), json.getAsJsonObject());
                }
            } catch (Exception ex) {
                LOG.debug("Fichier d'apparition illisible : {}", e.getKey(), ex);
            }
        }
        return out;
    }

    private static Map<String, JsonObject> readFromMods() throws Exception {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        // Cobblemon d'abord : les addons qui remplacent ses fichiers passent après
        mods.sort((a, b) -> Boolean.compare(!"cobblemon".equals(a.getMetadata().getId()), !"cobblemon".equals(b.getMetadata().getId())));
        for (ModContainer mod : mods) {
            for (Path root : mod.getRootPaths()) {
                Path data = root.resolve("data");
                if (!Files.isDirectory(data)) {
                    continue;
                }
                List<Path> namespaces;
                try (Stream<Path> list = Files.list(data)) {
                    namespaces = list.filter(Files::isDirectory).toList();
                }
                for (Path ns : namespaces) {
                    Path pool = ns.resolve("spawn_pool_world");
                    if (!Files.isDirectory(pool)) {
                        continue;
                    }
                    String namespace = ns.getFileName().toString().replace("/", "").replace("\\", "");
                    List<Path> jsons;
                    try (Stream<Path> walk = Files.walk(pool)) {
                        jsons = walk.filter(p -> p.toString().endsWith(".json")).toList();
                    }
                    for (Path p : jsons) {
                        String key = namespace + ":" + pool.relativize(p).toString().replace('\\', '/');
                        try {
                            JsonElement json = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
                            if (json.isJsonObject()) {
                                out.put(key, json.getAsJsonObject());
                            }
                        } catch (Exception ex) {
                            LOG.debug("Fichier d'apparition illisible : {}", key, ex);
                        }
                    }
                }
            }
        }
        return out;
    }

    // =======================================================================================
    // Construction de l'index par espèce

    private static Map<String, List<Entry>> build(Map<String, JsonObject> files) {
        Map<String, List<Entry>> map = new HashMap<>();
        for (JsonObject file : files.values()) {
            if (file.has("enabled") && !file.get("enabled").getAsBoolean()) {
                continue;
            }
            if (!modsOk(file)) {
                continue;
            }
            JsonArray spawns = file.has("spawns") && file.get("spawns").isJsonArray() ? file.getAsJsonArray("spawns") : null;
            if (spawns == null) {
                continue;
            }
            for (JsonElement element : spawns) {
                if (!element.isJsonObject()) {
                    continue;
                }
                try {
                    addSpawn(map, element.getAsJsonObject());
                } catch (Exception ex) {
                    LOG.debug("Apparition ignorée", ex);
                }
            }
        }
        Map<String, List<Entry>> cleaned = new HashMap<>();
        map.forEach((species, entries) -> cleaned.put(species, Collections.unmodifiableList(mergeSnackTwins(entries))));
        return cleaned;
    }

    private static boolean modsOk(JsonObject file) {
        for (String mod : strings(file, "neededInstalledMods")) {
            if (!FabricLoader.getInstance().isModLoaded(mod)) {
                return false;
            }
        }
        for (String mod : strings(file, "neededUninstalledMods")) {
            if (FabricLoader.getInstance().isModLoaded(mod)) {
                return false;
            }
        }
        return true;
    }

    private record Props(String species, List<String> aspects, boolean alpha) {
    }

    /** "vulpix alolan" → vulpix + [alolan] ; "venusaur held_item=... alpha=true" → venusaur, alpha. */
    private static Props parseProps(String text) {
        String[] tokens = text.trim().split("\\s+");
        String species = "";
        List<String> aspects = new ArrayList<>();
        boolean alpha = false;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq > 0) {
                String key = token.substring(0, eq);
                String value = token.substring(eq + 1);
                switch (key) {
                    case "species" -> species = value;
                    case "alpha" -> alpha = !value.equals("false");
                    case "held_item", "level", "lvl", "shiny", "gender", "nature", "ability" -> {
                        // propriétés qui ne changent pas la forme
                    }
                    default -> {
                        // "sea=east" → east-sea, "striped=blue" → bluestriped, "bull_breed=aqua" → aqua-breed...
                        String dashed = key.replace('_', '-');
                        String last = dashed.substring(dashed.lastIndexOf('-') + 1);
                        aspects.add(value);
                        aspects.add(dashed + "-" + value);
                        aspects.add(value + "-" + dashed);
                        aspects.add(value + "-" + last);
                        aspects.add(value + key);
                    }
                }
            } else if (i == 0 && species.isEmpty()) {
                species = token;
            } else if (token.equals("alpha")) {
                alpha = true;
            } else {
                aspects.add(token);
                if (token.endsWith("bias") && token.length() > 4) { // "hisuibias" → region-bias-hisui
                    aspects.add("region-bias-" + token.substring(0, token.length() - 4));
                }
            }
        }
        if (species.contains(":")) {
            species = species.substring(species.indexOf(':') + 1);
        }
        return new Props(toId(species), aspects, alpha);
    }

    private static void addSpawn(Map<String, List<Entry>> map, JsonObject s) {
        String type = string(s, "type");
        String bucket = string(s, "bucket");
        String position = string(s, "spawnablePositionType");
        JsonObject condition = object(s, "condition");
        JsonObject anticondition = object(s, "anticondition");
        List<String> presets = strings(s, "presets");
        List<JsonObject> multipliers = new ArrayList<>();
        if (s.has("weightMultipliers") && s.get("weightMultipliers").isJsonArray()) {
            for (JsonElement m : s.getAsJsonArray("weightMultipliers")) {
                if (m.isJsonObject()) {
                    multipliers.add(m.getAsJsonObject());
                }
            }
        }
        if (s.has("weightMultiplier") && s.get("weightMultiplier").isJsonObject()) {
            multipliers.add(s.getAsJsonObject("weightMultiplier"));
        }
        double weight = s.has("weight") && s.get("weight").isJsonPrimitive() ? s.get("weight").getAsDouble() : 0;
        String id = string(s, "id");
        boolean snack = condition.has("isPokeSnack") && condition.get("isPokeSnack").isJsonPrimitive()
                && condition.get("isPokeSnack").getAsBoolean();

        if (type.equals("pokemon")) {
            Props p = parseProps(string(s, "pokemon"));
            if (!p.species().isEmpty()) {
                add(map, new Entry(p.species(), p.aspects(), p.alpha(), Role.SOLO, "", false, 0, bucket,
                        string(s, "level"), position, condition, anticondition, presets, multipliers, weight, id, snack));
            }
        } else if (type.equals("pokemon-herd") && s.has("herdablePokemon") && s.get("herdablePokemon").isJsonArray()) {
            JsonArray herd = s.getAsJsonArray("herdablePokemon");
            int herdSize = s.has("maxHerdSize") && s.get("maxHerdSize").isJsonPrimitive() ? s.get("maxHerdSize").getAsInt() : 0;
            String leader = "";
            boolean leaderAlpha = false;
            for (JsonElement el : herd) {
                JsonObject h = el.getAsJsonObject();
                if (h.has("isLeader") && h.get("isLeader").getAsBoolean()) {
                    Props lp = parseProps(string(h, "pokemon"));
                    leader = lp.species();
                    leaderAlpha = lp.alpha();
                }
            }
            for (JsonElement el : herd) {
                JsonObject h = el.getAsJsonObject();
                Props p = parseProps(string(h, "pokemon"));
                if (p.species().isEmpty()) {
                    continue;
                }
                boolean isLeader = h.has("isLeader") && h.get("isLeader").getAsBoolean();
                // Le chef suit le niveau du groupe ; les autres ont leur propre fourchette
                String level = isLeader
                        ? firstNonEmpty(string(s, "levelRange"), string(h, "levelRange"))
                        : firstNonEmpty(string(h, "levelRange"), string(s, "levelRange"));
                add(map, new Entry(p.species(), p.aspects(), p.alpha(), isLeader ? Role.LEADER : Role.FOLLOWER, leader,
                        leaderAlpha, herdSize, bucket, level, position, condition, anticondition, presets, multipliers,
                        weight, id, snack));
            }
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return a.isEmpty() ? b : a;
    }

    private static void add(Map<String, List<Entry>> map, Entry entry) {
        map.computeIfAbsent(entry.species(), k -> new ArrayList<>()).add(entry);
    }

    /** Cobblemon double souvent une apparition : une normale, une "avec Poké Snack". On n'en garde qu'une. */
    private static List<Entry> mergeSnackTwins(List<Entry> entries) {
        Map<String, Boolean> plain = new HashMap<>();
        for (Entry e : entries) {
            if (!e.snackOnly()) {
                plain.put(twinKey(e), true);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.snackOnly() && plain.containsKey(twinKey(e))) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static String twinKey(Entry e) {
        JsonObject cond = e.condition().deepCopy();
        cond.remove("isPokeSnack");
        return e.species() + "|" + e.aspects() + "|" + e.role() + "|" + e.bucket() + "|" + e.level() + "|" + e.position()
                + "|" + cond + "|" + e.anticondition() + "|" + e.presets();
    }

    // =======================================================================================
    // Outils JSON

    public static String toId(@Nullable String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    static JsonObject object(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key)) {
            return out;
        }
        JsonElement e = o.get(key);
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive()) {
                    out.add(x.getAsString());
                }
            }
        } else if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
        }
        return out;
    }
}
