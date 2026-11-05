package com.goat.feurcalc;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.goat.feurmod.ui.Theme;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Sur l'écran de combat de Cobblemon : un bouton "Feurcalc" en haut au centre, et juste en dessous
 * un résumé des dégâts de tes attaques contre l'adversaire en face (et sa meilleure attaque contre toi).
 */
public final class BattleHud {
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("feurcalc.json");
    private static final int BUTTON_H = 14;
    private static final int ROW = 12;
    private static final int SIDE_TILES = 146; // place prise par les cadres des Pokémon à gauche et à droite

    private static Boolean hudVisible;

    private BattleHud() {
    }

    public static void attach(Screen screen) {
        ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, tickDelta) -> render(s, graphics, mouseX, mouseY));
        ScreenMouseEvents.allowMouseClick(screen).register((s, mouseX, mouseY, button) -> {
            if (!overButton(s, mouseX, mouseY)) {
                return true;
            }
            click();
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                setHudVisible(!hudVisible());
            } else {
                FeurcalcClient.openScreen(Minecraft.getInstance(), s);
            }
            return false;
        });
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> {
            if (FeurcalcClient.openKey != null && FeurcalcClient.openKey.matches(key, scancode)) {
                FeurcalcClient.openScreen(Minecraft.getInstance(), s);
                return false;
            }
            return true;
        });
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static String buttonLabel() {
        String key = FeurcalcClient.openKey != null ? FeurcalcClient.openKey.getTranslatedKeyMessage().getString() : "J";
        return "Feurcalc [" + key + "]";
    }

    private static int buttonW(Font font) {
        return font.width(buttonLabel()) + 14;
    }

    private static boolean overButton(Screen s, double mx, double my) {
        Font font = Minecraft.getInstance().font;
        int w = buttonW(font);
        int x = (s.width - w) / 2;
        return mx >= x && mx < x + w && my >= 3 && my < 3 + BUTTON_H;
    }

    private static void render(Screen s, GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        CalcState state = CalcState.INSTANCE;
        List<Consumer<GuiGraphics>> icons = new ArrayList<>();
        List<Consumer<GuiGraphics>> texts = new ArrayList<>();

        g.pose().pushPose();
        g.pose().translate(0, 0, 200);

        // Bouton
        int bw = buttonW(font);
        int bx = (s.width - bw) / 2;
        boolean hover = overButton(s, mouseX, mouseY);
        Theme.rect(g, bx, 3, bw, BUTTON_H, 3, hover ? 0xF0283240 : 0xE0141A21);
        Theme.outline(g, bx, 3, bw, BUTTON_H, 2, hover ? Theme.ACCENT : Theme.withAlpha(Theme.ACCENT, 0x90));
        String label = buttonLabel();
        texts.add(gg -> gg.drawString(font, label, bx + 7, 6, Theme.GOLD, false));

        // Résumé
        int w = Math.min(190, s.width - 2 * SIDE_TILES);
        boolean show = hudVisible() && w >= 120 && state.inBattle && !state.a.isEmpty() && !state.b.isEmpty()
                && state.side(true) != null;
        if (show) {
            int x = (s.width - w) / 2;
            int y = 3 + BUTTON_H + 2;
            List<Integer> moveSlots = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (!state.a.moves[i].isEmpty()) {
                    moveSlots.add(i);
                }
            }
            JsonObject danger = bestMove(state, false);
            int rows = 1 + moveSlots.size() + (danger != null ? 1 : 0);
            int h = rows * ROW + 6;
            Theme.rect(g, x, y, w, h, 3, 0xE0111720);
            Theme.outline(g, x, y, w, h, 2, 0xFF29323E);

            // En-tête : l'adversaire et ses PV
            int ty = y + 3;
            String target = state.b.displayName.isEmpty() ? state.b.species.getTranslatedName().getString() : state.b.displayName;
            String header = Names.tr("feurcalc.hud.vs", target, state.b.hpPercent);
            final int hy = ty;
            texts.add(gg -> gg.drawString(font, Ui.ellipsize(font, header, w - 10), x + 5, hy + 1, 0xFFFF9AA8, false));
            Theme.bar(g, x + 5, ty + 10, w - 10, 2, state.b.hpPercent / 100F, Theme.hpColor(state.b.hpPercent), Theme.TRACK);
            ty += ROW + 1;

            for (int slot : moveSlots) {
                final int ry = ty;
                MoveTemplate template = Names.move(state.a.moves[slot]);
                if (template != null) {
                    ElementalType type = template.getElementalType();
                    icons.add(gg -> Ui.typeIcon(gg, type, x + 5, ry + 1, 8));
                }
                JsonObject r = state.move(true, slot);
                String result = resultText(r);
                int color = resultColor(r);
                int resultW = font.width(result);
                // petite barre de dégâts sous la ligne (100 % = toute la largeur)
                double max = CalcState.getDouble(r, "maxPct", 0);
                if (max > 0) {
                    int barW = (int) Math.round((w - 20) * Math.min(100, max) / 100.0);
                    Theme.rect(g, x + 15, ry + 9, Math.max(2, barW), 2, 1, Theme.withAlpha(color, 0xB0));
                }
                String name = Ui.ellipsize(font, Names.moveName(state.a.moves[slot]), w - 22 - resultW - 6);
                texts.add(gg -> {
                    gg.drawString(font, name, x + 15, ry, 0xFFFFFFFF, false);
                    gg.drawString(font, result, x + w - 5 - resultW, ry, color, false);
                });
                ty += ROW;
            }

            if (danger != null) {
                final int ry = ty;
                // l'attaque adverse la plus dangereuse, sur fond rouge
                Theme.rect(g, x + 2, ry - 2, w - 4, ROW + 1, 2, 0x50FF3B4E);
                String id = CalcState.getString(danger, "id");
                MoveTemplate template = Names.move(id);
                if (template != null) {
                    ElementalType type = template.getElementalType();
                    icons.add(gg -> Ui.typeIcon(gg, type, x + 5, ry, 8));
                }
                String result = resultText(danger);
                int color = resultColor(danger);
                int resultW = font.width(result);
                String name = Ui.ellipsize(font, "▼ " + Names.moveName(id), w - 22 - resultW - 6);
                texts.add(gg -> {
                    gg.drawString(font, name, x + 15, ry, 0xFFFFA0A8, false);
                    gg.drawString(font, result, x + w - 5 - resultW, ry, color, false);
                });
            }
        }

        g.flush();
        for (Consumer<GuiGraphics> op : icons) {
            op.accept(g);
        }
        g.flush();
        for (Consumer<GuiGraphics> op : texts) {
            op.accept(g);
        }
        g.flush();
        g.pose().popPose();

        if (hover) {
            List<net.minecraft.network.chat.Component> tip = List.of(
                    net.minecraft.network.chat.Component.translatable("feurcalc.hud.button.1"),
                    net.minecraft.network.chat.Component.translatable("feurcalc.hud.button.2"));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    /** L'attaque qui fait le plus de dégâts de ce côté (pour "attention à..."). */
    private static JsonObject bestMove(CalcState state, boolean sideA) {
        JsonObject best = null;
        double bestMax = 0;
        for (int i = 0; i < 4; i++) {
            JsonObject r = state.move(sideA, i);
            double max = CalcState.getDouble(r, "maxPct", 0);
            if (r != null && max > bestMax) {
                best = r;
                bestMax = max;
            }
        }
        return best;
    }

    private static String resultText(JsonObject r) {
        if (r == null) {
            return "…";
        }
        if (!r.has("known") || !r.get("known").getAsBoolean()) {
            return "?";
        }
        if ("Status".equals(CalcState.getString(r, "category"))) {
            return Names.tr("feurcalc.moves.status");
        }
        double min = CalcState.getDouble(r, "minPct", 0);
        double max = CalcState.getDouble(r, "maxPct", 0);
        if (max <= 0) {
            return "0%";
        }
        String ko = Names.koText(CalcState.getInt(r, "koN", 0), CalcState.getDouble(r, "koChance", 0));
        return String.format(Locale.ROOT, "%.0f–%.0f%%", min, max) + (ko.isEmpty() ? "" : " " + ko);
    }

    private static int resultColor(JsonObject r) {
        if (r == null || CalcState.getDouble(r, "maxPct", 0) <= 0) {
            return 0xFF8A949E;
        }
        return Names.koColor(CalcState.getInt(r, "koN", 0), CalcState.getDouble(r, "koChance", 0));
    }

    // ---------------------------------------------------------------------------------------
    // Préférence : résumé affiché ou non (clic droit sur le bouton)

    private static boolean hudVisible() {
        if (hudVisible == null) {
            hudVisible = true;
            try {
                if (Files.exists(CONFIG)) {
                    JsonObject o = JsonParser.parseString(Files.readString(CONFIG, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.has("resumeEnCombat")) {
                        hudVisible = o.get("resumeEnCombat").getAsBoolean();
                    }
                }
            } catch (Exception ignored) {
                // valeur par défaut
            }
        }
        return hudVisible;
    }

    private static void setHudVisible(boolean visible) {
        hudVisible = visible;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("resumeEnCombat", visible);
            Files.createDirectories(CONFIG.getParent());
            Files.writeString(CONFIG, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // pas grave
        }
    }
}
