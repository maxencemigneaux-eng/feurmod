package com.goat.feurmod.ui;

import com.goat.cobblemonundo.client.UndoConfig;
import com.goat.feurleg.FeurlegConfig;
import com.goat.feurmod.FeurmodConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Menu Réglages de Feurmod (/feurmod ou la touche dans Options › Commandes › Feurmod).
 * Clic gauche : valeur suivante · clic droit : valeur précédente. Tout est enregistré tout de suite.
 */
public final class SettingsScreen extends Screen {

    private record Option(String label, Supplier<String> value, Runnable next, Runnable prev, String tip, Supplier<Boolean> on) {}

    private record Section(String title, List<Option> options) {}

    private static final String[] CORNERS = {"haut-gauche", "haut-droite", "bas-gauche", "bas-droite"};
    private static final String[] CORNER_LABELS = {"Haut gauche", "Haut droite", "Bas gauche", "Bas droite"};
    private static final int[] WARN_STEPS = {0, 30, 60, 120, 300};

    @Nullable private final Screen parent;
    private final List<Section> sections = new ArrayList<>();
    private int scroll;
    private int contentH;
    private static final int ROW = 20;
    private static final int HEAD = 22;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.literal("Réglages Feurmod"));
        this.parent = parent;
        build();
    }

    private void build() {
        FeurmodConfig fm = FeurmodConfig.get();
        sections.add(new Section("Apparence des menus", List.of(
            new Option("Couleur d'accent", () -> FeurmodConfig.ACCENT_NAMES[fm.accent],
                () -> { fm.accent++; themeChanged(); }, () -> { fm.accent--; themeChanged(); },
                "Couleur des onglets, boutons actifs et cadres sélectionnés", () -> true),
            new Option("Opacité du fond", () -> fm.panelOpacity + " %",
                () -> { fm.panelOpacity = fm.panelOpacity >= 100 ? 40 : fm.panelOpacity + 10; themeChanged(); },
                () -> { fm.panelOpacity = fm.panelOpacity <= 40 ? 100 : fm.panelOpacity - 10; themeChanged(); },
                "Transparence du fond des menus Feurinfo / Feurcalc (40 à 100 %)", () -> true))));

        UndoConfig undo = UndoConfig.get();
        sections.add(new Section("Combat : Annuler / Envoyer", List.of(
            toggle("Confirmer avant d'envoyer", () -> undo.confirmerAvantEnvoi, v -> undo.confirmerAvantEnvoi = v,
                "Ton choix d'attaque attend que tu cliques sur Envoyer (ou Entrée). Non = envoi immédiat comme dans Cobblemon.",
                UndoConfig::save),
            toggle("Aussi contre PNJ / sauvages", () -> undo.aussiContrePnjEtSauvages, v -> undo.aussiContrePnjEtSauvages = v,
                "Non : seulement contre d'autres joueurs. Les touches Annuler / Envoyer se changent dans Options › Commandes › Feurmod.",
                UndoConfig::save))));

        FeurlegConfig leg = FeurlegConfig.get();
        sections.add(new Section("Timers des légendaires", List.of(
            toggle("Afficher les timers", () -> leg.enabled, v -> leg.enabled = v, "Aussi avec la touche K", FeurlegConfig::save),
            new Option("Coin de l'écran", () -> CORNER_LABELS[cornerIndex(leg.corner)],
                () -> { leg.corner = CORNERS[Math.floorMod(cornerIndex(leg.corner) + 1, 4)]; FeurlegConfig.save(); },
                () -> { leg.corner = CORNERS[Math.floorMod(cornerIndex(leg.corner) - 1, 4)]; FeurlegConfig.save(); },
                "Où afficher le panneau", () -> true),
            new Option("Taille", () -> Math.round(leg.scale * 100) + " %",
                () -> { leg.scale = leg.scale >= 2f ? 0.5f : leg.scale + 0.25f; FeurlegConfig.save(); },
                () -> { leg.scale = leg.scale <= 0.5f ? 2f : leg.scale - 0.25f; FeurlegConfig.save(); },
                "Taille du panneau (50 à 200 %)", () -> true),
            toggle("Heure Minecraft du spawn", () -> leg.showMinecraftTime, v -> leg.showMinecraftTime = v,
                "L'heure en jeu à laquelle le légendaire tombera, avec le moment (aube, nuit...)", FeurlegConfig::save),
            toggle("Compte à rebours", () -> leg.showCountdown, v -> leg.showCountdown = v,
                "Le temps réel restant avant la tentative de spawn", FeurlegConfig::save),
            new Option("Prévenir avant", () -> leg.warnSeconds <= 0 ? "Jamais" : leg.warnSeconds < 60 ? leg.warnSeconds + " s" : (leg.warnSeconds / 60) + " min",
                () -> { leg.warnSeconds = stepWarn(leg.warnSeconds, 1); FeurlegConfig.save(); },
                () -> { leg.warnSeconds = stepWarn(leg.warnSeconds, -1); FeurlegConfig.save(); },
                "Quand afficher l'alerte au milieu de l'écran", () -> leg.warnSeconds > 0),
            toggle("Son d'alerte", () -> leg.alertSound, v -> leg.alertSound = v, "Cloche quand il reste peu de temps et à 0", FeurlegConfig::save),
            toggle("Message au milieu de l'écran", () -> leg.alertMessage, v -> leg.alertMessage = v,
                "Grand titre avec le nom de la zone", FeurlegConfig::save))));
    }

    private static Option toggle(String label, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set, String tip, Runnable save) {
        Runnable flip = () -> {
            set.accept(!get.get());
            save.run();
        };
        return new Option(label, () -> get.get() ? "Oui" : "Non", flip, flip, tip, get);
    }

    private static void themeChanged() {
        FeurmodConfig.apply();
        FeurmodConfig.save();
    }

    private static int cornerIndex(String corner) {
        for (int i = 0; i < CORNERS.length; i++) {
            if (CORNERS[i].equalsIgnoreCase(corner)) return i;
        }
        return 0;
    }

    private static int stepWarn(int current, int dir) {
        int idx = 0;
        for (int i = 0; i < WARN_STEPS.length; i++) {
            if (WARN_STEPS[i] == current) idx = i;
        }
        return WARN_STEPS[Math.floorMod(idx + dir, WARN_STEPS.length)];
    }

    // =======================================================================================

    private int panelW() {
        return Math.min(360, width - 20);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int top() {
        return 34;
    }

    private int bottom() {
        return height - 10;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        int x = panelX();
        int w = panelW();
        g.drawCenteredString(font, Component.literal("Réglages Feurmod").withColor(Theme.TEXT), width / 2, 12, Theme.TEXT);
        g.drawCenteredString(font, "Clic : suivant · Clic droit : précédent", width / 2, 22, Theme.MUTED);

        Theme.panel(g, x, top(), w, bottom() - top(), 4, Theme.PANEL, Theme.PANEL_BORDER);
        g.flush();
        contentH = 0;
        for (Section s : sections) contentH += HEAD + s.options().size() * ROW + 6;
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentH - (bottom() - top() - 8)));

        g.enableScissor(x + 1, top() + 1, x + w - 1, bottom() - 1);
        int y = top() + 6 - scroll;
        List<FormattedCharSequence> tip = null;
        for (Section s : sections) {
            g.drawString(font, s.title(), x + 10, y + 7, Theme.ACCENT, false);
            Theme.divider(g, x + 10, y + 18, w - 20);
            y += HEAD;
            for (Option o : s.options()) {
                boolean hover = mx >= x + 6 && mx < x + w - 6 && my >= y && my < y + ROW - 2 && my >= top() && my < bottom();
                Theme.rect(g, x + 6, y, w - 12, ROW - 2, 3, hover ? Theme.CARD_HOVER : Theme.CARD);
                g.drawString(font, o.label(), x + 12, y + 5, Theme.TEXT, false);
                String v = o.value().get();
                int vw = Math.max(54, font.width(v) + 12);
                int vx = x + w - 12 - vw;
                boolean on = o.on().get();
                Theme.rect(g, vx, y + 2, vw, ROW - 6, 3, on ? Theme.withAlpha(Theme.ACCENT, 0xB0) : Theme.FIELD);
                g.drawCenteredString(font, v, vx + vw / 2, y + 5, on ? Theme.TEXT : Theme.TEXT_2);
                if (hover) {
                    tip = font.split(Component.literal(o.tip()), 220);
                }
                y += ROW;
            }
            y += 6;
        }
        g.disableScissor();
        if (tip != null) {
            g.renderTooltip(font, tip, mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = panelX();
        int w = panelW();
        if (my >= top() && my < bottom() && mx >= x + 6 && mx < x + w - 6) {
            int y = top() + 6 - scroll;
            for (Section s : sections) {
                y += HEAD;
                for (Option o : s.options()) {
                    if (my >= y && my < y + ROW - 2) {
                        if (button == 1) o.prev().run();
                        else o.next().run();
                        return true;
                    }
                    y += ROW;
                }
                y += 6;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (int) Math.signum(sy) * 20;
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
