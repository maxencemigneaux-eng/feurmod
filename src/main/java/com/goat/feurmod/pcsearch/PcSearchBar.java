package com.goat.feurmod.pcsearch;

import com.cobblemon.mod.common.api.storage.party.PartyPosition;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.goat.feurmod.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Barre de recherche ajoutée au-dessus du PC de Cobblemon.
 * Taper un nom (ou "shiny", "6iv", une nature...) : ◀ ▶ ou Entrée pour passer d'un résultat à l'autre,
 * le PC saute à la bonne boîte et sélectionne le Pokémon.
 */
public final class PcSearchBar {

    // Taille du PC de Cobblemon (PCGUI.BASE_WIDTH / BASE_HEIGHT)
    private static final int PC_W = 349;
    private static final int PC_H = 205;
    private static final int BOX_W = 130;
    private static final int H = 14;

    private static String lastQuery = "";

    private final PCGUI gui;
    private final Font font;
    private final EditBox box;
    private List<PcSearch.Hit> hits = new ArrayList<>();
    private int index = -1;
    private int x0, barY;

    private PcSearchBar(PCGUI gui) {
        this.gui = gui;
        this.font = Minecraft.getInstance().font;
        this.box = new EditBox(font, 0, 0, BOX_W, H, Component.literal("Recherche"));
        box.setHint(Component.literal("Chercher dans le PC…").withColor(Theme.MUTED));
        box.setMaxLength(60);
        box.setValue(lastQuery);
        box.setResponder(this::search);
        layout();
        if (!lastQuery.isBlank()) {
            hits = PcSearch.find(lastQuery, gui.getParty(), gui.getPc());
        }
    }

    /** Appelé pour chaque écran ouvert : n'agit que sur le PC de Cobblemon. */
    public static void attach(Screen screen) {
        if (!(screen instanceof PCGUI pc)) return;
        PcSearchBar bar = new PcSearchBar(pc);
        ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> bar.render(g, mx, my, delta));
        ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> bar.click(mx, my, button));
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, mods) -> bar.key(key, scancode, mods));
    }

    private void layout() {
        x0 = (gui.width - PC_W) / 2;
        int y0 = (gui.height - PC_H) / 2;
        barY = y0 >= H + 6 ? y0 - H - 4 : y0 + PC_H + 4;
        box.setX(x0 + 2);
        box.setY(barY);
    }

    private void search(String query) {
        lastQuery = query;
        hits = PcSearch.find(query, gui.getParty(), gui.getPc());
        index = -1;
        if (!hits.isEmpty()) show(0);
    }

    private void step(int dir) {
        if (hits.isEmpty()) return;
        show(Math.floorMod(index + dir, hits.size()));
    }

    private void show(int i) {
        index = i;
        PcSearch.Hit h = hits.get(i);
        try {
            if (h.inParty()) {
                gui.getStorage().setSelectedPosition(new PartyPosition(h.slot()));
            } else {
                gui.getStorage().setBox(h.page());
                gui.getStorage().setSelectedPosition(new PCPosition(h.page(), h.slot()));
            }
            gui.setPreviewPokemon(h.pokemon(), false);
        } catch (Throwable ignored) {
            // Une autre version du PC : on affiche quand même l'emplacement en texte.
        }
    }

    private int prevX() { return x0 + 2 + BOX_W + 4; }
    private int counterX() { return prevX() + H + 2; }
    private int nextX() { return counterX() + 34; }
    private int infoX() { return nextX() + H + 6; }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void render(GuiGraphics g, int mx, int my, float delta) {
        box.render(g, mx, my, delta);
        arrow(g, prevX(), "◀", in(mx, my, prevX(), barY, H, H));
        arrow(g, nextX(), "▶", in(mx, my, nextX(), barY, H, H));

        String counter = hits.isEmpty() ? "0/0" : (index + 1) + "/" + hits.size();
        int cw = font.width(counter);
        g.drawString(font, counter, counterX() + (34 - cw) / 2, barY + 3, hits.isEmpty() ? Theme.MUTED : Theme.TEXT);

        String info;
        int color = Theme.TEXT_2;
        if (lastQuery.isBlank()) {
            info = "nom, shiny, 6iv, nature, niv>50…";
            color = Theme.MUTED;
        } else if (hits.isEmpty()) {
            info = "Aucun résultat";
            color = Theme.BAD;
        } else if (index >= 0) {
            PcSearch.Hit h = hits.get(index);
            info = PcSearch.where(h) + (h.pokemon().getShiny() ? " ★" : "") + " · IV " + PcSearch.ivLine(h.pokemon());
        } else {
            info = hits.size() + " résultat(s) : Entrée pour voir";
        }
        int maxW = x0 + PC_W - infoX();
        g.drawString(font, font.plainSubstrByWidth(info, maxW), infoX(), barY + 3, color);
    }

    private void arrow(GuiGraphics g, int x, String s, boolean hover) {
        Theme.rect(g, x, barY, H, H, 2, hover ? Theme.CARD_HOVER : Theme.CARD);
        g.drawString(font, s, x + (H - font.width(s)) / 2, barY + 3, hits.isEmpty() ? Theme.MUTED : Theme.TEXT);
    }

    /** true = laisser le PC gérer le clic. */
    private boolean click(double mx, double my, int button) {
        if (in(mx, my, box.getX(), box.getY(), box.getWidth(), box.getHeight())) {
            box.setFocused(true);
            gui.setFocused(box);
            if (button == 1) box.setValue(""); // clic droit : vider
            else box.mouseClicked(mx, my, button);
            return false;
        }
        if (in(mx, my, prevX(), barY, H, H)) { step(-1); return false; }
        if (in(mx, my, nextX(), barY, H, H)) { step(1); return false; }
        if (box.isFocused()) {
            box.setFocused(false);
            gui.setFocused(null);
        }
        return true;
    }

    /** true = laisser le PC gérer la touche (sinon taper "e" fermerait le PC). */
    private boolean key(int key, int scancode, int mods) {
        if (!box.isFocused()) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            box.setFocused(false);
            gui.setFocused(null);
            return false;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            step((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            return false;
        }
        box.keyPressed(key, scancode, mods);
        return false;
    }
}
