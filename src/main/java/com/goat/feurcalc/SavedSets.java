package com.goat.feurcalc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Les spreads que tu as réglés à la main, retenus par espèce/forme dans config/feurcalc_sets.json.
 * La prochaine fois que tu croises ce Pokémon, le calculateur reprend ton spread.
 */
public final class SavedSets {
    private static final Logger LOG = LoggerFactory.getLogger("Feurcalc");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("feurcalc_sets.json");

    private static JsonObject sets;
    private static boolean dirty;

    private SavedSets() {
    }

    private static JsonObject sets() {
        if (sets == null) {
            sets = new JsonObject();
            try {
                if (Files.exists(FILE)) {
                    JsonElement parsed = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8));
                    if (parsed.isJsonObject()) {
                        sets = parsed.getAsJsonObject();
                    }
                }
            } catch (Exception e) {
                LOG.warn("Impossible de lire {}", FILE, e);
            }
        }
        return sets;
    }

    public static boolean has(String key) {
        return !key.isEmpty() && sets().has(key) && sets().get(key).isJsonObject();
    }

    /** Applique le spread sauvegardé (s'il existe). */
    public static boolean apply(MonState mon) {
        String key = mon.key();
        if (!has(key)) {
            return false;
        }
        try {
            mon.applySpread(sets().getAsJsonObject(key));
            return true;
        } catch (Exception e) {
            LOG.warn("Spread illisible pour {}", key, e);
            return false;
        }
    }

    public static void remember(MonState mon) {
        String key = mon.key();
        if (key.isEmpty()) {
            return;
        }
        sets().add(key, mon.spreadToJson());
        dirty = true;
    }

    public static void forget(MonState mon) {
        String key = mon.key();
        if (sets().has(key)) {
            sets().remove(key);
            dirty = true;
        }
    }

    public static void saveIfNeeded() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject sorted = new JsonObject();
            sets().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> sorted.add(e.getKey(), e.getValue()));
            Files.writeString(FILE, GSON.toJson(sorted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Impossible d'écrire {}", FILE, e);
        }
    }
}
