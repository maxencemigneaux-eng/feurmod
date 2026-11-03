package com.goat.cobblemonundo.client;

import com.goat.cobblemonundo.CobblemonUndo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Réglages côté client, dans .minecraft/config/cobblemon_undo.json
 * (le fichier est créé automatiquement au premier lancement).
 */
public final class UndoConfig {
    /**
     * Mode 100 % client (serveur sans le mod) : après ton choix, attendre que tu cliques sur
     * « Envoyer » (ou Entrée) avant de l'envoyer. Tant que ce n'est pas envoyé, tu peux « Annuler ».
     * false = comportement normal de Cobblemon (envoi immédiat).
     */
    public boolean confirmerAvantEnvoi = true;

    /** Mode 100 % client : demander aussi la confirmation contre les dresseurs PNJ et les Pokémon sauvages. */
    public boolean aussiContrePnjEtSauvages = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static UndoConfig instance = new UndoConfig();

    public static UndoConfig get() {
        return instance;
    }

    static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CobblemonUndo.MOD_ID + ".json");
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    UndoConfig loaded = GSON.fromJson(reader, UndoConfig.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                }
            }
            // On réécrit le fichier pour qu'il contienne toujours toutes les options
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            CobblemonUndo.LOGGER.warn("Impossible de lire/écrire la config {} : {}", file, e.toString());
        }
    }
}
