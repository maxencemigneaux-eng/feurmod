package com.goat.feurleg;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Récupère tout le texte affiché par un menu ouvert, puis y cherche des timers
 * (ex. "41:03") avec, juste au-dessus, le nom de la zone (ex. "Johto").
 * Ça marche sans connaître le fonctionnement interne du menu /leg du serveur.
 */
public final class TextCapture {

    /** Un texte dessiné, avec sa position à l'écran (en pixels GUI). */
    public record Entry(String text, float x, float y, float scale) {}

    private static final Pattern TIMER = Pattern.compile("^(\\d{1,3}):(\\d{2})(?::(\\d{2}))?$");
    private static final Pattern FORMAT_CODES = Pattern.compile("§.");

    private static boolean capturing = false;
    private static final List<Entry> frame = new ArrayList<>();

    // HUD (hors menus) : une image sur 10, pour y lire le nom du monde où se trouve le joueur
    private static boolean hudCapturing = false;
    private static int hudFrameCounter;
    private static int hudMisses;
    /** Mis à true pendant que le FeurHUD se dessine : ses propres noms de zones ne comptent pas. */
    public static boolean hudSuppressed = false;
    private static final List<Entry> hudFrame = new ArrayList<>();

    private static Screen lastScreen = null;
    private static boolean autoDumpedThisScreen = false;
    private static boolean dumpRequested = false;

    private TextCapture() {}

    public static boolean isCapturing() {
        return capturing;
    }

    /** Un texte dessiné maintenant doit-il être noté (menu ouvert, ou image du HUD analysée) ? */
    public static boolean isRecording() {
        return capturing || (hudCapturing && !hudSuppressed);
    }

    public static void beginHud() {
        hudCapturing = !capturing && ++hudFrameCounter % 10 == 0 && !TimerStore.get().isEmpty();
        hudFrame.clear();
    }

    /**
     * Le serveur affiche le nom du monde où tu es en haut de l'écran (ex. « Ressources-02 »). Si un texte du HUD est
     * le nom d'une zone de /leg, c'est la zone actuelle : son heure devient exacte et sa ligne passe en gras.
     * Le texte le plus haut gagne, et le chat (moitié basse de l'écran) est ignoré.
     */
    public static void endHud() {
        if (!hudCapturing) return;
        hudCapturing = false;
        String found = null;
        float foundY = Float.MAX_VALUE;
        java.util.Map<String, String> zones = new java.util.HashMap<>();
        for (TimerStore.Timer t : TimerStore.get().list()) {
            zones.put(zoneKey(t.name), t.name);
        }
        // Seulement la moitié haute de l'écran : le chat (en bas) peut citer une zone sans que tu y sois
        float half = Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2f;
        for (Entry e : hudFrame) {
            if (e.y() > half) continue;
            String zone = zoneIn(e.text(), zones);
            if (zone != null && e.y() < foundY) {
                found = zone;
                foundY = e.y();
            }
        }
        hudFrame.clear();
        if (found != null) {
            hudMisses = 0;
            ZoneClock.onHudZone(found);
        } else if (++hudMisses == 5) {
            ZoneClock.onHudZone(null); // plus de nom de zone affiché depuis 5 s : hors des zones à légendaires
        }
    }

    /** "Ressources-02", "Ressources 2", "ressources02" → "ressources2" ; Construction01..03 = Johto, Hoenn, Unys. */
    static String zoneKey(String s) {
        String t = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        Matcher m = Pattern.compile("^([a-z]+)0*(\\d+)$").matcher(t);
        if (m.matches()) {
            t = m.group(1) + m.group(2);
        }
        switch (t) {
            case "construction1": return "johto";
            case "construction2": return "hoenn";
            case "construction3": return "unys";
            default: return t;
        }
    }

    /** La zone nommée dans ce texte (un mot, ou deux mots qui se suivent comme « Ressources 2 »), sinon null. */
    private static String zoneIn(String text, java.util.Map<String, String> zones) {
        String[] words = text.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String z = zones.get(zoneKey(words[i]));
            if (z == null && i + 1 < words.length) z = zones.get(zoneKey(words[i] + words[i + 1]));
            if (z != null && !zoneKey(words[i]).isEmpty()) return z;
        }
        return null;
    }

    public static void requestDump() {
        dumpRequested = true;
    }

    public static void begin(Screen screen) {
        frame.clear();
        capturing = true;
        if (screen != lastScreen) {
            lastScreen = screen;
            autoDumpedThisScreen = false;
        }
    }

    public static void record(String raw, float x, float y, Matrix4f matrix) {
        String text = FORMAT_CODES.matcher(raw).replaceAll("").trim();
        if (text.isEmpty()) return;
        Vector3f pos = matrix.transformPosition(x, y, 0f, new Vector3f());
        float scale = Math.abs(matrix.m00());
        (capturing ? frame : hudFrame).add(new Entry(text, pos.x, pos.y, scale));
    }

    public static void end(Screen screen) {
        capturing = false;
        if (frame.isEmpty()) return;

        List<Entry> entries = new ArrayList<>(frame);
        frame.clear();

        Map<String, Long> found = findTimers(entries);
        if (found.size() >= 2 && isLegScreen(entries)) {
            TimerStore.get().sync(found);
            detectCurrentZone(entries, found);
            if (!autoDumpedThisScreen) {
                autoDumpedThisScreen = true;
                writeDump(entries, screen, found, false);
            }
        }
        if (dumpRequested) {
            dumpRequested = false;
            writeDump(entries, screen, found, true);
        }
    }

    /**
     * Seul le menu /leg a la ligne « zone · moment · météo » (deux « · » et un moment de la journée) :
     * les autres menus avec des chronos (ventes, quêtes...) ne créent donc plus de faux timers.
     */
    private static boolean isLegScreen(List<Entry> entries) {
        for (Entry e : entries) {
            String t = e.text().replace('•', '·').replace('∙', '·');
            if (t.chars().filter(ch -> ch == '·').count() >= 2 && ZonePeriods.findWord(t) != null) {
                return true;
            }
        }
        return false;
    }

    /** Associe chaque timer au texte placé juste au-dessus (le nom de la zone). */
    static Map<String, Long> findTimers(List<Entry> entries) {
        return findTimers(entries, FeurlegConfig.get());
    }

    static Map<String, Long> findTimers(List<Entry> entries, FeurlegConfig cfg) {
        List<Entry> timers = new ArrayList<>();
        List<Entry> labels = new ArrayList<>();
        for (Entry e : entries) {
            if (TIMER.matcher(e.text()).matches()) timers.add(e);
            else if (looksLikeLabel(e.text())) labels.add(e);
        }

        // Ordre d'affichage : de gauche à droite, puis de haut en bas.
        timers.sort(Comparator.comparingDouble((Entry e) -> Math.round(e.y() / 10f)).thenComparingDouble(Entry::x));

        Map<String, Long> result = new LinkedHashMap<>();
        for (Entry t : timers) {
            Entry best = null;
            for (Entry l : labels) {
                float dy = t.y() - l.y();
                float dx = Math.abs(t.x() - l.x());
                if (dy > 0 && dy <= cfg.labelMaxDistanceY && dx <= cfg.labelMaxDistanceX) {
                    if (best == null || l.y() > best.y()) best = l;
                }
            }
            if (best == null || result.containsKey(best.text())) continue;
            Long seconds = toSeconds(t.text(), cfg);
            if (seconds != null) result.put(best.text(), seconds);
        }
        return result;
    }

    /**
     * Le menu /leg affiche :
     *  - la zone où se trouve le joueur : "Johto · matin · temps clair · 27"
     *  - au survol d'une zone, une info-bulle "Hoenn" puis "4 joueurs · nuit · temps clair".
     * On en tire le moment de la journée de chaque zone, et la zone actuelle du joueur.
     */
    private static void detectCurrentZone(List<Entry> entries, Map<String, Long> found) {
        boolean sawHeader = false;
        boolean headerDone = false;
        for (Entry e : entries) {
            String t = e.text().replace('•', '·').replace('∙', '·');
            int dot = t.indexOf('·');
            if (dot < 0) continue;
            String word = ZonePeriods.findWord(t);
            String first = t.substring(0, dot).trim();

            if (dot > 0 && found.containsKey(first)) {
                // Ligne de la zone actuelle.
                if (word != null) ZonePeriods.observe(first, word);
                if (!headerDone) {
                    ZoneClock.onLegSeen(first, word);
                    headerDone = true;
                }
                continue;
            }
            if (t.chars().filter(ch -> ch == '·').count() >= 2 && dot > 0) sawHeader = true;

            if (word != null) {
                // Info-bulle : le nom de la zone est écrit juste au-dessus, aligné à gauche.
                Entry title = null;
                for (Entry z : entries) {
                    if (!found.containsKey(z.text())) continue;
                    float dy = e.y() - z.y();
                    if (Math.abs(z.x() - e.x()) <= 3 && dy > 0 && dy <= 100) {
                        if (title == null || z.y() > title.y()) title = z;
                    }
                }
                if (title != null) ZonePeriods.observe(title.text(), word);
            }
        }
        // Ligne "Spawn · ..." par ex. : le joueur n'est dans aucune zone à légendaires.
        if (!headerDone && sawHeader) ZoneClock.onLegSeen(null, null);
    }

    private static boolean looksLikeLabel(String s) {
        if (s.length() < 2 || s.length() > 32) return false;
        if (s.contains("%") || s.contains(":")) return false;
        return s.chars().anyMatch(Character::isLetter);
    }

    static Long toSeconds(String s, FeurlegConfig cfg) {
        Matcher m = TIMER.matcher(s);
        if (!m.matches()) return null;
        long a = Long.parseLong(m.group(1));
        long b = Long.parseLong(m.group(2));
        if (m.group(3) != null) {
            return a * 3600 + b * 60 + Long.parseLong(m.group(3));
        }
        if ("h:mm".equalsIgnoreCase(cfg.timerFormat)) {
            return a * 3600 + b * 60;
        }
        return a * 60 + b;
    }

    private static void writeDump(List<Entry> entries, Screen screen, Map<String, Long> found, boolean manual) {
        Minecraft client = Minecraft.getInstance();
        Path file = client.gameDirectory.toPath().resolve("config").resolve("feurleg-dump.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("Feurleg dump - ").append(LocalDateTime.now()).append('\n');
        sb.append("Menu : ").append(screen.getClass().getName()).append('\n');
        sb.append("Titre : ").append(screen.getTitle().getString()).append('\n');
        sb.append("Timers reconnus : ").append(found).append("\n\n");
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingDouble(Entry::y).thenComparingDouble(Entry::x));
        for (Entry e : sorted) {
            sb.append(String.format("y=%7.1f  x=%7.1f  scale=%4.2f  | %s%n", e.y(), e.x(), e.scale(), e.text()));
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            if (manual && client.player != null) {
                client.player.displayClientMessage(Component.literal("§6[Feurleg]§r Texte du menu enregistré dans config/feurleg-dump.txt"), false);
            }
        } catch (IOException ex) {
            FeurlegClient.LOGGER.warn("Impossible d'écrire le dump", ex);
        }
    }
}
