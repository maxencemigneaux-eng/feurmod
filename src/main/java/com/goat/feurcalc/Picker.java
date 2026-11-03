package com.goat.feurcalc;

import com.cobblemon.mod.common.api.types.ElementalType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Petite fenêtre de choix avec recherche (Pokémon, talent, objet, nature, attaque, type Téra, set). */
public final class Picker {
    /**
     * Une ligne. header = titre de groupe (non cliquable) ; group = nom du groupe, affiché à droite pendant une recherche ;
     * tip = infobulle au survol.
     */
    public record Entry(String id, String label, String detail, @Nullable ElementalType icon, ItemStack stack,
                        boolean dim, String search, boolean header, String group, @Nullable List<Component> tip) {
        public static Entry of(String id, String label, String detail, @Nullable ElementalType icon, boolean dim, String extraSearch) {
            return new Entry(id, label, detail, icon, ItemStack.EMPTY, dim,
                    Names.normalize(label) + " " + Names.normalize(id) + " " + Names.normalize(extraSearch), false, "", null);
        }

        public static Entry item(String id, String label, ItemStack stack) {
            return new Entry(id, label, "", null, stack, false, Names.normalize(label) + " " + Names.normalize(id),
                    false, "", null);
        }

        public static Entry header(String label) {
            return new Entry("", label, "", null, ItemStack.EMPTY, false, "", true, "", null);
        }

        public Entry withGroup(String groupName, @Nullable List<Component> tooltip) {
            return new Entry(id, label, detail, icon, stack, dim, search + " " + Names.normalize(groupName), header,
                    groupName, tooltip);
        }
    }

    private static final int ROW = 11;
    private static final int HEADER = 14;
    private static final int FILTER_H = 12;

    private final Font font;
    private final Component title;
    private final List<Entry> all;
    private final Consumer<Entry> onPick;
    private List<Entry> filtered;
    final EditBox filter;
    private final int x;
    private final int y;
    private final int w;
    private final int h;
    private int scroll;
    private int selected;
    private boolean filtering;

    public Picker(Font font, Component title, List<Entry> entries, int anchorX, int anchorY, int screenW, int screenH,
                  Consumer<Entry> onPick) {
        this(font, title, entries, anchorX, anchorY, screenW, screenH, 190, onPick);
    }

    public Picker(Font font, Component title, List<Entry> entries, int anchorX, int anchorY, int screenW, int screenH,
                  int width, Consumer<Entry> onPick) {
        this.font = font;
        this.title = title;
        this.all = entries;
        this.onPick = onPick;
        this.w = Math.min(width, screenW - 8);
        this.h = Math.min(screenH - 8, HEADER + FILTER_H + 4 + ROW * 16 + 4);
        this.x = Mth.clamp(anchorX, 4, screenW - w - 4);
        this.y = Mth.clamp(anchorY, 4, screenH - h - 4);
        this.filter = new EditBox(font, x + 4, y + HEADER, w - 8, FILTER_H, Component.translatable("feurcalc.picker.search"));
        this.filter.setHint(Component.translatable("feurcalc.picker.search"));
        this.filter.setMaxLength(40);
        this.filter.setResponder(this::refilter);
        this.filtered = new ArrayList<>(entries);
        this.selected = nextSelectable(0, 1);
    }

    /** Met en évidence (et fait défiler jusqu'à) l'entrée d'identifiant donné. */
    public void select(String id) {
        for (int i = 0; i < filtered.size(); i++) {
            Entry e = filtered.get(i);
            if (!e.header() && e.id().equals(id)) {
                selected = i;
                int rows = visibleRows();
                scroll = Mth.clamp(i - rows / 3, 0, Math.max(0, filtered.size() - rows));
                return;
            }
        }
    }

    /** Prochaine ligne cliquable (pas un titre) à partir de start, dans la direction donnée. */
    private int nextSelectable(int start, int direction) {
        for (int i = start; i >= 0 && i < filtered.size(); i += direction) {
            if (!filtered.get(i).header()) {
                return i;
            }
        }
        return Mth.clamp(selected, 0, Math.max(0, filtered.size() - 1));
    }

    private int listTop() {
        return y + HEADER + FILTER_H + 4;
    }

    private int visibleRows() {
        return Math.max(1, (y + h - 4 - listTop()) / ROW);
    }

    private void refilter(String text) {
        String q = Names.normalize(text);
        filtering = !q.isEmpty();
        if (q.isEmpty()) {
            filtered = new ArrayList<>(all);
        } else {
            List<Entry> starts = new ArrayList<>();
            List<Entry> contains = new ArrayList<>();
            for (Entry e : all) {
                if (e.header()) {
                    continue;
                }
                if (Names.normalize(e.label()).startsWith(q)) {
                    starts.add(e);
                } else if (e.search().contains(q)) {
                    contains.add(e);
                }
            }
            // Les entrées "principales" (non grisées) d'abord
            starts.sort((p, r) -> Boolean.compare(p.dim(), r.dim()));
            contains.sort((p, r) -> Boolean.compare(p.dim(), r.dim()));
            starts.addAll(contains);
            filtered = starts;
        }
        scroll = 0;
        selected = 0;
        selected = nextSelectable(0, 1);
    }

    public boolean isInside(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x, y, x + w, y + h, 0xF2141A20);
        g.renderOutline(x, y, w, h, 0xFF6A7A8A);
        g.flush();

        filter.render(g, mouseX, mouseY, partialTick);
        g.flush();

        int top = listTop();
        int rows = visibleRows();
        int hovered = hoveredIndex(mouseX, mouseY);
        for (int i = 0; i < rows && scroll + i < filtered.size(); i++) {
            int index = scroll + i;
            int ry = top + i * ROW;
            if (filtered.get(index).header()) {
                g.fill(x + 2, ry, x + w - 6, ry + ROW, 0x24FFFFFF);
            } else if (index == hovered || (hovered < 0 && index == selected)) {
                g.fill(x + 2, ry, x + w - 6, ry + ROW, 0x40FFFFFF);
            }
        }
        // Barre de défilement
        if (filtered.size() > rows) {
            int trackH = rows * ROW;
            int barH = Math.max(8, trackH * rows / filtered.size());
            int barY = top + (trackH - barH) * scroll / Math.max(1, filtered.size() - rows);
            g.fill(x + w - 4, top, x + w - 2, top + trackH, 0x30FFFFFF);
            g.fill(x + w - 4, barY, x + w - 2, barY + barH, 0xA0FFFFFF);
        }
        g.flush();

        for (int i = 0; i < rows && scroll + i < filtered.size(); i++) {
            Entry e = filtered.get(scroll + i);
            int ry = top + i * ROW;
            int tx = x + 5;
            if (e.icon() != null) {
                Ui.typeIcon(g, e.icon(), tx, ry + 1, 9);
                tx += 11;
            } else if (!e.stack().isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(tx, ry + 1, 0);
                g.pose().scale(0.5625F, 0.5625F, 1F);
                g.renderItem(e.stack(), 0, 0);
                g.pose().popPose();
                tx += 11;
            }
        }
        g.flush();

        g.drawString(font, title, x + 5, y + 4, 0xFFFFE08A, false);
        for (int i = 0; i < rows && scroll + i < filtered.size(); i++) {
            Entry e = filtered.get(scroll + i);
            int ry = top + i * ROW;
            if (e.header()) {
                g.drawString(font, Ui.ellipsize(font, e.label().toUpperCase(java.util.Locale.ROOT), w - 12), x + 5, ry + 2,
                        0xFF8FA3B5, false);
                continue;
            }
            int tx = x + 5 + (e.icon() != null || !e.stack().isEmpty() ? 11 : 0);
            String detail = filtering && !e.group().isEmpty() ? e.group() : e.detail();
            int detailW = detail.isEmpty() ? 0 : Math.min(font.width(detail), (w - 16) / 2) + 4;
            String label = Ui.ellipsize(font, e.label(), x + w - 8 - detailW - tx);
            g.drawString(font, label, tx, ry + 2, e.dim() ? 0xFF8A949E : 0xFFFFFFFF, false);
            if (detailW > 0) {
                String shown = Ui.ellipsize(font, detail, detailW - 4);
                g.drawString(font, shown, x + w - 7 - font.width(shown), ry + 2, 0xFF8FA3B5, false);
            }
        }
        if (filtered.isEmpty()) {
            g.drawString(font, Component.translatable("feurcalc.picker.empty"), x + 5, top + 2, 0xFF8A949E, false);
        }
        g.flush();
        g.pose().popPose();

        // Infobulle de la ligne survolée (ex. le détail d'un set)
        if (hovered >= 0 && filtered.get(hovered).tip() != null && !filtered.get(hovered).tip().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
            for (Component c : filtered.get(hovered).tip()) {
                lines.addAll(font.split(c, 240));
            }
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.renderTooltip(font, lines, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    private int hoveredIndex(double mx, double my) {
        int top = listTop();
        if (mx < x + 2 || mx >= x + w - 6 || my < top) {
            return -1;
        }
        int row = (int) ((my - top) / ROW);
        if (row >= visibleRows()) {
            return -1;
        }
        int index = scroll + row;
        return index < filtered.size() ? index : -1;
    }

    /** @return true si un choix a été fait (la fenêtre doit se fermer). */
    public boolean mouseClicked(double mx, double my, int button) {
        if (filter.isMouseOver(mx, my)) {
            filter.mouseClicked(mx, my, button);
            filter.setFocused(true);
            return false;
        }
        int index = hoveredIndex(mx, my);
        if (index >= 0 && !filtered.get(index).header()) {
            onPick.accept(filtered.get(index));
            return true;
        }
        return false;
    }

    public void mouseScrolled(double amount) {
        int max = Math.max(0, filtered.size() - visibleRows());
        scroll = Mth.clamp(scroll - (int) Math.signum(amount) * 3, 0, max);
    }

    /** @return true si un choix a été fait. */
    public boolean keyPressed(int key, int scancode, int modifiers) {
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (selected < filtered.size() && !filtered.get(selected).header()) {
                    onPick.accept(filtered.get(selected));
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                selected = nextSelectable(Math.min(filtered.size() - 1, selected + 1), 1);
                ensureVisible();
                return false;
            }
            case GLFW.GLFW_KEY_UP -> {
                selected = nextSelectable(Math.max(0, selected - 1), -1);
                ensureVisible();
                return false;
            }
            default -> {
                filter.keyPressed(key, scancode, modifiers);
                return false;
            }
        }
    }

    public void charTyped(char c, int modifiers) {
        filter.charTyped(c, modifiers);
    }

    private void ensureVisible() {
        int rows = visibleRows();
        if (selected < scroll) {
            scroll = selected;
        } else if (selected >= scroll + rows) {
            scroll = selected - rows + 1;
        }
    }
}
