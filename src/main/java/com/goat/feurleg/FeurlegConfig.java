package com.goat.feurleg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Réglages, modifiables dans config/feurleg.json (relancer le jeu après modif). */
public final class FeurlegConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("feurleg.json");
    private static FeurlegConfig instance;

    /** HUD visible ou non (touche K par défaut). */
    public boolean enabled = true;
    /** Coin de l'écran : "haut-gauche", "haut-droite", "bas-gauche", "bas-droite". */
    public String corner = "haut-gauche";
    /** Décalage depuis le coin choisi, en pixels. */
    public int offsetX = 4;
    public int offsetY = 4;
    /** Taille du HUD (1.0 = normal). */
    public float scale = 1.0f;
    /** Opacité du fond (0 = transparent, 255 = opaque). */
    public int backgroundAlpha = 150;

    /** Afficher l'heure Minecraft du spawn (ex. "21:30"). */
    public boolean showMinecraftTime = true;
    /** Afficher le compte à rebours (ex. "25:50"). */
    public boolean showCountdown = true;

    /** Couleurs du compte à rebours selon les minutes restantes. */
    public int colorBlueFromMinutes = 10;
    public int colorPurpleUnderMinutes = 5;
    public int colorRedUnderMinutes = 2;

    /** Moments de la journée affichés par /leg -> plages de ticks (tick 0 = 6:00). */
    public java.util.Map<String, String> periods = ZonePeriods.defaultTable();
    /** Version de la table : si elle change, la table par défaut remplace l'ancienne. */
    public int periodsVersion = 0;
    private static final int PERIODS_VERSION = 3;

    /** Format des timers du serveur : "mm:ss" (minutes:secondes) ou "h:mm" (heures:minutes). */
    public String timerFormat = "mm:ss";

    /** Prévenir quand il reste moins de X secondes (0 = jamais). */
    public int warnSeconds = 60;
    public boolean alertSound = true;
    /** Son de l'alerte : "cloche", "damn" (Damn les gens) ou "damn_n" (Damn N). */
    public String alertSoundChoice = "cloche";
    /** Volume de l'alerte en % (0 à 300). Au-dessus de 100 %, le son est joué plusieurs fois en même temps. */
    public int alertVolume = 100;
    public boolean alertMessage = true;

    /** Après combien de secondes passées à 0 le timer est considéré comme périmé (rouvre /leg). */
    public int staleAfterSeconds = 300;

    /** Distance max entre un timer et le nom de zone au-dessus (pixels GUI). Pour le debug. */
    public float labelMaxDistanceY = 45f;
    public float labelMaxDistanceX = 20f;

    public static FeurlegConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                instance = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), FeurlegConfig.class);
            }
        } catch (Exception e) {
            FeurlegClient.LOGGER.warn("config/feurleg.json illisible, réglages par défaut utilisés", e);
        }
        if (instance == null) instance = new FeurlegConfig();
        if (instance.periods == null || instance.periods.isEmpty() || instance.periodsVersion < PERIODS_VERSION) {
            instance.periods = ZonePeriods.defaultTable();
            instance.periodsVersion = PERIODS_VERSION;
        }
        ZonePeriods.loadTable(instance.periods);
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FeurlegClient.LOGGER.warn("Impossible d'enregistrer config/feurleg.json", e);
        }
    }
}
