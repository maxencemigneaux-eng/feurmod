package com.goat.feurcalc;

import com.goat.feurmod.ui.Theme;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Le tableau "Qui envoyer ?" (utilisé en combat dans le menu des switchs et dans le calculateur) :
 * une ligne par Pokémon de ton équipe, avec ce que tu fais à l'adversaire, ce que tu prends, et qui est plus rapide.
 */
public final class TeamPanel {
    public static final int TITLE_H = 13;
    public static final int HEADER_H = 11;
    public static final int ROW_H = 14;

    /** Hauteur dessinée et ligne survolée (-1 = aucune). */
    public record Result(int height, int hovered) {
    }

    private TeamPanel() {
    }

    public static int height(int rows) {
        return TITLE_H + HEADER_H + Math.max(1, rows) * ROW_H + 5;
    }

    /** Dessine le tableau (fond compris). */
    public static Result draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, int background) {
        List<TeamMatchups.Row> rows = TeamMatchups.rows();
        int h = height(rows.size());
        Theme.rect(g, x, y, w, h, 3, background);
        Theme.outline(g, x, y, w, h, 2, Theme.withAlpha(Theme.GOLD, 0x70));

        int pad = 6;
        int speedW = 12;
        int colW = Math.max(58, Math.min(92, (w - 2 * pad) / 4));
        int speedX = x + w - pad - speedW;
        int theirsX = speedX - 4 - colW;
        int mineX = theirsX - 6 - colW;
        int nameX = x + pad;
        int nameW = mineX - 6 - nameX;

        int best = TeamMatchups.bestIndex(rows);
        int foeSpeed = TeamMatchups.foeSpeed();
        int hovered = -1;
        int top = y + TITLE_H + HEADER_H;

        // Fonds des lignes
        for (int i = 0; i < rows.size(); i++) {
            TeamMatchups.Row row = rows.get(i);
            int ry = top + i * ROW_H;
            boolean hover = mx >= x + 2 && mx < x + w - 2 && my >= ry && my < ry + ROW_H - 1;
            if (hover) {
                hovered = i;
            }
            int bg = hover ? Theme.CARD_HOVER : (i % 2 == 0 ? Theme.CARD : Theme.CARD_ALT);
            if (row.fainted()) {
                bg = Theme.CARD_DISABLED;
            }
            Theme.rect(g, x + 3, ry, w - 6, ROW_H - 1, 2, bg);
            if (i == best) {
                g.fill(x + 3, ry + 2, x + 4, ry + ROW_H - 3, Theme.GOLD);
            }
            cell(g, row.mine(), mineX, ry + 2, colW, ROW_H - 5, false);
            cell(g, row.theirs(), theirsX, ry + 2, colW, ROW_H - 5, true);
        }
        g.flush();

        // Textes
        String vs = Names.tr("feurcalc.team.title", TeamMatchups.foeName().isEmpty() ? "?" : TeamMatchups.foeName());
        if (TeamMatchups.calculating()) {
            vs += " …";
        }
        g.drawString(font, Ui.ellipsize(font, vs, w - 2 * pad), x + pad, y + 3, Theme.GOLD, false);
        int hy = y + TITLE_H;
        g.drawString(font, Names.tr("feurcalc.team.col.mon"), nameX, hy, Theme.LABEL, false);
        center(g, font, Names.tr("feurcalc.team.col.mine"), mineX + colW / 2, hy, Theme.LABEL);
        center(g, font, Names.tr("feurcalc.team.col.theirs"), theirsX + colW / 2, hy, Theme.LABEL);
        center(g, font, Names.tr("feurcalc.team.col.speed"), speedX + speedW / 2, hy, Theme.LABEL);

        if (rows.isEmpty()) {
            g.drawString(font, Names.tr(TeamMatchups.calculating() ? "feurcalc.team.loading" : "feurcalc.team.empty"),
                    nameX, top + 3, Theme.MUTED, false);
        }
        for (int i = 0; i < rows.size(); i++) {
            TeamMatchups.Row row = rows.get(i);
            int ry = top + i * ROW_H;
            int ty = ry + (ROW_H - 1 - 7) / 2;
            String prefix = i == best ? "★ " : row.active() ? "● " : "";
            int color = row.fainted() ? Theme.MUTED : i == best ? Theme.GOLD : row.active() ? 0xFF9AD0FF : Theme.TEXT;
            String name = row.fainted() ? Names.tr("feurcalc.team.fainted", shortName(row.mon())) : prefix + shortName(row.mon());
            g.drawString(font, Ui.ellipsize(font, name, nameW), nameX + 2, ty, color, false);
            if (row.fainted()) {
                continue;
            }
            cellText(g, font, row.mine(), mineX, ty, colW);
            cellText(g, font, row.theirs(), theirsX, ty, colW);
            String arrow;
            int arrowColor;
            if (row.speed() > foeSpeed) {
                arrow = "▲";
                arrowColor = 0xFF7FE08A;
            } else if (row.speed() < foeSpeed) {
                arrow = "▼";
                arrowColor = 0xFFFF8A7A;
            } else {
                arrow = "=";
                arrowColor = 0xFFFFE07A;
            }
            center(g, font, arrow, speedX + speedW / 2, ty, arrowColor);
        }
        return new Result(h, hovered);
    }

    private static void cell(GuiGraphics g, @Nullable JsonObject r, int x, int y, int w, int h, boolean danger) {
        double max = CalcState.getDouble(r, "maxPct", 0);
        if (r == null || max <= 0) {
            Theme.rect(g, x, y, w, h, 2, Theme.FIELD);
            return;
        }
        int color = Names.koColor(CalcState.getInt(r, "koN", 0), CalcState.getDouble(r, "koChance", 0));
        Theme.rect(g, x, y, w, h, 2, Theme.darken(color, danger ? 0.7F : 0.62F));
        int fillW = (int) Math.round(w * Math.min(100, max) / 100.0);
        if (fillW > 0) {
            Theme.rect(g, x, y, Math.max(2, fillW), h, 2, Theme.withAlpha(color, 0x40));
        }
    }

    private static void cellText(GuiGraphics g, Font font, @Nullable JsonObject r, int x, int ty, int w) {
        String text = shortResult(r);
        int color = r == null || CalcState.getDouble(r, "maxPct", 0) <= 0 ? Theme.MUTED
                : Theme.lighten(Names.koColor(CalcState.getInt(r, "koN", 0), CalcState.getDouble(r, "koChance", 0)), 0.25F);
        center(g, font, Ui.ellipsize(font, text, w - 4), x + w / 2, ty, color);
    }

    /** "OHKO", "56% 2HKO" ou "34–40%". */
    private static String shortResult(@Nullable JsonObject r) {
        if (r == null) {
            return "—";
        }
        double min = CalcState.getDouble(r, "minPct", 0);
        double max = CalcState.getDouble(r, "maxPct", 0);
        if (max <= 0) {
            return "0%";
        }
        String ko = Names.koText(CalcState.getInt(r, "koN", 0), CalcState.getDouble(r, "koChance", 0));
        if (!ko.isEmpty() && CalcState.getInt(r, "koN", 0) <= 3) {
            return ko;
        }
        return String.format(Locale.ROOT, "%.0f–%.0f%%", min, max);
    }

    private static String longResult(@Nullable JsonObject r) {
        if (r == null) {
            return Names.tr("feurcalc.team.no_attack");
        }
        String move = Names.moveName(CalcState.getString(r, "id"));
        String range = String.format(Locale.ROOT, "%.1f–%.1f%%", CalcState.getDouble(r, "minPct", 0),
                CalcState.getDouble(r, "maxPct", 0)).replace('.', ',');
        String ko = Names.koText(CalcState.getInt(r, "koN", 0), CalcState.getDouble(r, "koChance", 0));
        return move + " : " + range + (ko.isEmpty() ? "" : " (" + ko + ")");
    }

    public static List<Component> tooltip(TeamMatchups.Row row, boolean clickable) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(shortName(row.mon())).withStyle(ChatFormatting.YELLOW));
        if (row.fainted()) {
            lines.add(Component.translatable("feurcalc.team.tip.fainted").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(Component.translatable("feurcalc.team.tip.mine", longResult(row.mine())).withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("feurcalc.team.tip.theirs", longResult(row.theirs())).withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("feurcalc.team.tip.speed", row.speed(), TeamMatchups.foeSpeed()).withStyle(ChatFormatting.GRAY));
        if (row.active()) {
            lines.add(Component.translatable("feurcalc.team.tip.active").withStyle(ChatFormatting.AQUA));
        }
        lines.add(Component.translatable("feurcalc.team.tip.star").withStyle(ChatFormatting.DARK_GRAY));
        if (clickable) {
            lines.add(Component.translatable("feurcalc.team.tip.click").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private static String shortName(MonState m) {
        if (!m.displayName.isEmpty()) {
            return m.displayName;
        }
        return m.species == null ? "?" : m.species.getTranslatedName().getString();
    }

    private static void center(GuiGraphics g, Font font, String s, int cx, int y, int color) {
        g.drawString(font, s, cx - font.width(s) / 2, y, color, false);
    }
}
