package com.goat.feurleg;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;

import java.util.List;

/** Le petit panneau toujours affiché dans un coin de l'écran. */
public final class TimerHud {

    private static final int PAD = 4;
    private static final int LINE = 11;
    private static final int GAP = 10;

    private static final int COLOR_TITLE = 0xFFFF5555;
    private static final int COLOR_NAME = 0xFFDDDDDD;
    private static final int COLOR_BLUE = 0xFF5599FF;
    private static final int COLOR_YELLOW = 0xFFFFFF55;
    private static final int COLOR_PURPLE = 0xFFCC66FF;
    private static final int COLOR_RED = 0xFFFF5555;
    private static final int COLOR_STALE = 0xFF888888;
    private static final int COLOR_MC_DAY = 0xFFFFE0A0;
    private static final int COLOR_MC_NIGHT = 0xFF9FC4E8;

    private TimerHud() {}

    public static void render(GuiGraphics ctx, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        FeurlegConfig cfg = FeurlegConfig.get();
        if (!cfg.enabled || client.player == null || client.options.hideGui) return;
        if (client.getDebugOverlay().showDebugScreen()) return;

        List<TimerStore.Timer> timers = TimerStore.get().list();
        Font tr = client.font;
        String title = "Feur-Leg";
        String hint = "ouvre /leg pour charger les timers";

        // Préparer les lignes et mesurer.
        int n = timers.size();
        String[] times = new String[n];
        int[] colors = new int[n];
        String[] mcTimes = new String[n];
        int[] mcColors = new int[n];
        int nameW = 0, timeW = 0, mcW = 0;
        boolean showMc = cfg.showMinecraftTime && client.level != null;
        boolean showCd = cfg.showCountdown || !showMc;
        for (int i = 0; i < n; i++) {
            TimerStore.Timer t = timers.get(i);
            long left = t.remainingMillis();
            if (left <= -cfg.staleAfterSeconds * 1000L) {
                times[i] = "/leg ?";
                colors[i] = COLOR_STALE;
            } else if (left <= 0) {
                times[i] = "0:00";
                colors[i] = COLOR_RED;
            } else {
                times[i] = format(left);
                colors[i] = countdownColor(left, cfg);
            }
            if (showMc) {
                Long exact = left > 0 ? ZoneClock.predictTick(t.name, left) : null;
                ZonePeriods.Prediction guess = (left > 0 && exact == null) ? ZonePeriods.predict(t.name, left) : null;
                if (left <= 0) {
                    mcTimes[i] = "";
                } else if (exact != null) {
                    mcTimes[i] = clock(exact) + " " + ZonePeriods.nameOfTick(exact);
                    mcColors[i] = isNight(exact) ? COLOR_MC_NIGHT : COLOR_MC_DAY;
                } else if (guess != null && guess.tick() != null) {
                    mcTimes[i] = "~" + clock(guess.tick()) + " " + ZonePeriods.nameOfTick(guess.tick());
                    mcColors[i] = guess.night() ? COLOR_MC_NIGHT : COLOR_MC_DAY;
                } else if (guess != null) {
                    mcTimes[i] = guess.word();
                    mcColors[i] = guess.night() ? COLOR_MC_NIGHT : COLOR_MC_DAY;
                } else {
                    // Moment de cette zone pas encore connu : survole-la dans /leg.
                    mcTimes[i] = "--:--";
                    mcColors[i] = COLOR_STALE;
                }
                mcW = Math.max(mcW, tr.width(mcTimes[i]));
            }
            if (!showCd && left > 0) times[i] = "";
            nameW = Math.max(nameW, tr.width(t.name));
            timeW = Math.max(timeW, tr.width(times[i]));
        }
        int mcCol = showMc ? mcW + GAP : 0;

        int innerW = n == 0 ? tr.width(hint) : Math.max(tr.width(title), nameW + GAP + mcCol + timeW);
        int w = innerW + PAD * 2;
        int h = PAD * 2 + LINE + (n == 0 ? LINE : n * LINE);

        float scale = Math.max(0.25f, cfg.scale);
        int screenW = ctx.guiWidth();
        int screenH = ctx.guiHeight();
        float sw = w * scale, sh = h * scale;
        String corner = cfg.corner == null ? "" : cfg.corner.toLowerCase();
        float x = corner.contains("droite") ? screenW - sw - cfg.offsetX : cfg.offsetX;
        float y = corner.contains("bas") ? screenH - sh - cfg.offsetY : cfg.offsetY;

        ctx.pose().pushPose();
        ctx.pose().translate(x, y, 0);
        ctx.pose().scale(scale, scale, 1f);

        int alpha = Math.max(0, Math.min(255, cfg.backgroundAlpha));
        ctx.fill(0, 0, w, h, (alpha << 24) | 0x101010);
        ctx.fill(0, 0, w, 1, 0xFFCC3333);

        ctx.drawString(tr, title, PAD, PAD, COLOR_TITLE);
        int ly = PAD + LINE;
        if (n == 0) {
            ctx.drawString(tr, hint, PAD, ly, COLOR_STALE);
        }
        for (int i = 0; i < n; i++) {
            ctx.drawString(tr, timers.get(i).name, PAD, ly, COLOR_NAME);
            if (showMc && !mcTimes[i].isEmpty()) {
                int mx = showCd ? w - PAD - timeW - GAP - tr.width(mcTimes[i]) : w - PAD - tr.width(mcTimes[i]);
                ctx.drawString(tr, mcTimes[i], mx, ly, mcColors[i]);
            }
            if (!times[i].isEmpty()) {
                ctx.drawString(tr, times[i], w - PAD - tr.width(times[i]), ly, colors[i]);
            }
            ly += LINE;
        }

        ctx.pose().popPose();
    }

    /** Bleu ≥ 10 min, jaune entre 5 et 10, violet < 5, rouge < 2 (réglable). */
    private static int countdownColor(long millis, FeurlegConfig cfg) {
        long min = 60_000L;
        if (millis < cfg.colorRedUnderMinutes * min) return COLOR_RED;
        if (millis < cfg.colorPurpleUnderMinutes * min) return COLOR_PURPLE;
        if (millis >= cfg.colorBlueFromMinutes * min) return COLOR_BLUE;
        return COLOR_YELLOW;
    }

    /** Tick (0-23999) -> "21:30". Le tick 0 = 6:00. */
    private static String clock(long tick) {
        long hours = (tick / 1000 + 6) % 24;
        long minutes = (tick % 1000) * 60 / 1000;
        return String.format("%02d:%02d", hours, minutes);
    }

    private static boolean isNight(long tick) {
        return tick >= 12542 && tick < 23460;
    }

    /** 41:03 ou 1:05:09 */
    public static String format(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%d:%02d", m, sec);
    }
}
