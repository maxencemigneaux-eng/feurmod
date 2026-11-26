package com.goat.feurleg;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.List;

/**
 * "FeurHUD" : le panneau des timers, toujours affiché dans un coin de l'écran.
 *
 * <pre>
 * FeurHUD
 * Hoenn » 00h 01m 39s [18:16]   ← monde où tu es : tout en gras (sauf le »)
 * Ressources01 » 00h 04m 28s [21:47]
 * </pre>
 *
 * Code couleur repris de Legend HUD : chrono en couleur vive, nom de la zone dans la même couleur en plus sombre,
 * « » » et crochets gris foncé, heure Minecraft gris clair, zones sans timer en gris bleuté.
 * Vert &gt; 10 min, jaune de 10:00 à 5:01, orange de 5:00 à 0:31, rouge de 0:30 à 0.
 * Entre crochets : l'heure Minecraft du spawn, calculée une fois puis figée jusqu'au cycle suivant.
 */
public final class TimerHud {

    private static final int PAD = 4;
    private static final int LINE = 11;

    // Couleurs relevées sur Legend HUD
    private static final int COLOR_TITLE = 0xFFF0F0F0;
    private static final int COLOR_TITLE_HUD = 0xFFA80000;
    /** Chrono (couleur vive) et nom de la zone (même couleur, plus sombre), par état. */
    private static final int[] TIME_COLORS = {0xFF3BCF27, 0xFFCFC127, 0xFFCF6827, 0xFFCF2727};
    // Noms éclaircis par rapport à Legend HUD : trop sombres pour être lus sur le décor
    private static final int[] NAME_COLORS = {0xFF7CF06A, 0xFFF2E66A, 0xFFF59A5C, 0xFFF26A6A};
    private static final int COLOR_SEP = 0xFF8A8A8A;
    private static final int COLOR_BRACKET = 0xFF8A8A8A;
    private static final int COLOR_MC = 0xFFA8A8A8;
    /** Zone sans timer valable (N/A) : nom et texte en gris bleuté. */
    private static final int COLOR_STALE_NAME = 0xFFA4AAB4;
    private static final int COLOR_STALE = 0xFF9197A1;

    private static final String SEP = " » ";
    private static final String NA = "N/A";

    private TimerHud() {}

    public static void render(GuiGraphics ctx, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        FeurlegConfig cfg = FeurlegConfig.get();
        if (!cfg.enabled || client.player == null || client.options.hideGui) return;
        if (client.getDebugOverlay().showDebugScreen()) return;

        List<TimerStore.Timer> timers = new java.util.ArrayList<>(TimerStore.get().list());
        // Le plus proche en haut, le plus lointain en bas ; les timers périmés tout en bas
        long staleMs = cfg.staleAfterSeconds * 1000L;
        timers.sort(java.util.Comparator
                .comparing((TimerStore.Timer t) -> t.remainingMillis() <= -staleMs)
                .thenComparingLong(t -> t.endMillis));
        Font font = client.font;
        boolean showMc = cfg.showMinecraftTime;
        boolean showCd = cfg.showCountdown || !showMc;
        String here = ZoneClock.currentZone();

        // Préparer les lignes et mesurer.
        int n = timers.size();
        Component[] rows = new Component[n];
        int rowsW = 0;
        for (int i = 0; i < n; i++) {
            TimerStore.Timer t = timers.get(i);
            long left = t.remainingMillis();
            boolean stale = left <= -staleMs;
            int level = colorLevel(left);
            int timeColor = stale ? COLOR_STALE : TIME_COLORS[level];
            String time = stale ? NA : format(left);
            String mc = stale || left <= 0 ? NA : minecraftTime(t, left);
            int mcColor = NA.equals(mc) ? COLOR_STALE : COLOR_MC;
            int nameColor = stale ? COLOR_STALE_NAME : NAME_COLORS[level];
            // Comme Legend HUD : une ligne qui se suit (pas de colonnes) ; le monde où tu es tout en gras, sauf le »
            boolean bold = t.name.equals(here);
            net.minecraft.network.chat.MutableComponent row = Component.literal(displayName(t.name, cfg))
                    .withStyle(style(nameColor, bold));
            row.append(Component.literal(SEP).withStyle(style(COLOR_SEP, false)));
            if (showCd) {
                row.append(Component.literal(time).withStyle(style(timeColor, bold)));
            }
            if (showMc) {
                if (showCd) row.append(Component.literal(" "));
                row.append(Component.literal("[").withStyle(style(COLOR_BRACKET, bold)));
                row.append(Component.literal(mc).withStyle(style(mcColor, bold)));
                row.append(Component.literal("]").withStyle(style(COLOR_BRACKET, bold)));
            }
            rows[i] = row;
            rowsW = Math.max(rowsW, font.width(row));
        }

        Component title = Component.literal("Feur").withStyle(Style.EMPTY.withColor(COLOR_TITLE & 0xFFFFFF).withBold(true))
                .append(Component.literal("HUD").withStyle(Style.EMPTY.withColor(COLOR_TITLE_HUD & 0xFFFFFF).withBold(true)));
        String hint = "Ouvre /leg pour charger les timers";

        int innerW = Math.max(font.width(title), n == 0 ? font.width(hint) : rowsW);
        int w = innerW + PAD * 2;
        int h = PAD * 2 + LINE + (n == 0 ? LINE : n * LINE);

        float scale = Math.max(0.25f, cfg.scale);
        float sw = w * scale, sh = h * scale;
        String corner = cfg.corner == null ? "" : cfg.corner.toLowerCase();
        float x = corner.contains("droite") ? ctx.guiWidth() - sw - cfg.offsetX : cfg.offsetX;
        float y = corner.contains("bas") ? ctx.guiHeight() - sh - cfg.offsetY : cfg.offsetY;

        ctx.pose().pushPose();
        ctx.pose().translate(x, y, 0);
        ctx.pose().scale(scale, scale, 1f);

        int alpha = Math.max(0, Math.min(255, cfg.hudBackgroundAlpha));
        if (alpha > 0) {
            ctx.fill(0, 0, w, h, (alpha << 24) | 0x101010);
        }

        ctx.drawString(font, title, PAD, PAD, COLOR_TITLE, true);
        int ly = PAD + LINE;
        if (n == 0) {
            ctx.drawString(font, hint, PAD, ly, COLOR_STALE, true);
        }
        for (int i = 0; i < n; i++) {
            ctx.drawString(font, rows[i], PAD, ly, 0xFFFFFFFF, true);
            ly += LINE;
        }

        ctx.pose().popPose();
    }

    /**
     * Heure Minecraft du spawn ("18:16 nuit"), recalculée à chaque image comme avant la 1.0.46 : elle se corrige toute
     * seule en approchant du spawn. "~" = estimation (horloge de la zone pas encore mesurée, moment vu dans /leg).
     */
    private static String minecraftTime(TimerStore.Timer t, long left) {
        Long exact = ZoneClock.predictTick(t.name, left);
        if (exact != null) {
            return clock(exact);
        }
        ZonePeriods.Prediction p = ZonePeriods.predict(t.name, left);
        return p == null ? NA : "~" + clock(p.tick());
    }

    private static Style style(int color, boolean bold) {
        return Style.EMPTY.withColor(color & 0xFFFFFF).withBold(bold);
    }

    private static final java.util.regex.Pattern NUMBERED =
            java.util.regex.Pattern.compile("(?i)^\\s*(ressources?|construction)\\s*0*(\\d{1,2})\\s*$");
    private static final String[] REGIONS = {"Johto", "Hoenn", "Unys"};

    /**
     * Nom affiché : "Ressources 1" → "Ressources01", et selon /feurmod Johto / Hoenn / Unys ou
     * Construction01 / 02 / 03 (dans les deux sens, quel que soit le nom envoyé par le serveur).
     */
    static String displayName(String zone, FeurlegConfig cfg) {
        boolean construction = "construction".equals(cfg.zoneNames);
        java.util.regex.Matcher m = NUMBERED.matcher(zone);
        if (m.matches()) {
            String base = m.group(1).toLowerCase(java.util.Locale.ROOT);
            int num = Integer.parseInt(m.group(2));
            if (base.equals("construction") && !construction && num >= 1 && num <= REGIONS.length) {
                return REGIONS[num - 1];
            }
            return Character.toUpperCase(base.charAt(0)) + base.substring(1) + String.format("%02d", num);
        }
        if (construction) {
            for (int i = 0; i < REGIONS.length; i++) {
                if (REGIONS[i].equalsIgnoreCase(zone.trim())) {
                    return String.format("Construction%02d", i + 1);
                }
            }
        }
        return zone;
    }

    /**
     * Couleur selon les secondes affichées : vert au-dessus de 10:00, jaune de 10:00 à 5:01,
     * orange de 5:00 à 0:31, rouge de 0:30 à 0:00.
     */
    private static int colorLevel(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        if (s > 600) return 0; // vert
        if (s > 300) return 1; // jaune
        if (s > 30) return 2;  // orange
        return 3;              // rouge
    }

    /** Tick (0-23999) -> "21:30". Le tick 0 = 6:00. */
    private static String clock(long tick) {
        long tod = Math.floorMod(tick, 24000L);
        long hours = (tod / 1000 + 6) % 24;
        long minutes = (tod % 1000) * 60 / 1000;
        String time = String.format("%02d:%02d", hours, minutes);
        return FeurlegConfig.get().showPeriodName ? time + " " + ZonePeriods.nameOfTick(tick) : time;
    }

    /** "00h 01m 39s" */
    public static String format(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return String.format("%02dh %02dm %02ds", h, m, sec);
    }
}
