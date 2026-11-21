package com.goat.feurmod;

import com.goat.feurmod.ui.Theme;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Réglages d'apparence communs à tous les menus de Feurmod (config/feurmod.json). */
public final class FeurmodConfig {

    /** Couleurs d'accent proposées dans le menu Réglages. */
    public static final String[] ACCENT_NAMES = {"Bleu", "Violet", "Vert", "Orange", "Rose", "Rouge", "Cyan"};
    public static final int[] ACCENT_COLORS = {0xFF3D8BFF, 0xFF9B6BFF, 0xFF33C27A, 0xFFFF9A3D, 0xFFFF5FA8, 0xFFFF4F5E, 0xFF2EC4D6};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("feurmod.json");
    private static FeurmodConfig instance;

    /** Index dans ACCENT_COLORS. */
    public int accent = 0;
    /** Calculateur : mettre tous les Pokémon au niveau 100 (sinon leur vrai niveau). Toujours modifiable à la main. */
    public boolean calcLevel100 = true;
    /** Opacité du fond des menus, en % (40 à 100). */
    public int panelOpacity = 100;

    public static FeurmodConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                instance = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), FeurmodConfig.class);
            }
        } catch (Exception ignored) {
            // fichier abîmé : valeurs par défaut
        }
        if (instance == null) instance = new FeurmodConfig();
        apply();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Applique les réglages au thème (couleur d'accent, opacité du fond). */
    public static void apply() {
        FeurmodConfig c = get();
        c.accent = Math.floorMod(c.accent, ACCENT_COLORS.length);
        c.panelOpacity = Math.max(40, Math.min(100, c.panelOpacity));
        Theme.ACCENT = ACCENT_COLORS[c.accent];
        int alpha = Math.round(255 * c.panelOpacity / 100f);
        Theme.PANEL = (alpha << 24) | 0x141920;
    }
}
