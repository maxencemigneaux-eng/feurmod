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

    private record Option(String label, Supplier<String> value, Runnable next, Runnable prev, String tip, Supplier<Boolean> on,
                          @Nullable Slider slider) {
        Option(String label, Supplier<String> value, Runnable next, Runnable prev, String tip, Supplier<Boolean> on) {
            this(label, value, next, prev, tip, on, null);
        }
    }

    /** Barre réglable : valeur de min à max, par pas de step ; onRelease quand on lâche la souris. */
    private record Slider(int min, int max, int step, java.util.function.IntSupplier get, java.util.function.IntConsumer set,
                          Runnable onRelease) {}

    @Nullable private Slider dragging;
    private int dragX, dragW;

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

        sections.add(new Section("Calculateur de dégâts", List.of(
            toggle("Tous les Pokémon au niveau 100", () -> fm.calcLevel100, v -> fm.calcLevel100 = v,
                "Oui : chaque Pokémon arrive au niv. 100 dans le calculateur (tu peux toujours baisser le niveau à la main). "
                    + "Non : son vrai niveau.", FeurmodConfig::save))));

        UndoConfig undo = UndoConfig.get();
        sections.add(new Section("Combat : Annuler / Envoyer", List.of(
            toggle("Confirmer avant d'envoyer", () -> undo.confirmerAvantEnvoi, v -> undo.confirmerAvantEnvoi = v,
                "Ton choix d'attaque attend que tu cliques sur Envoyer (ou Entrée). Non = envoi immédiat comme dans Cobblemon.",
                UndoConfig::save),
            toggle("Aussi contre PNJ / sauvages", () -> undo.aussiContrePnjEtSauvages, v -> undo.aussiContrePnjEtSauvages = v,
                "Non : seulement contre d'autres joueurs. Les touches Annuler / Envoyer se changent dans Options › Commandes › Feurmod.",
                UndoConfig::save))));

        FeurlegConfig leg = FeurlegConfig.get();
        sections.add(new Section("Timers des légendaires (FeurHUD)", List.of(
            toggle("Afficher les timers", () -> leg.enabled, v -> leg.enabled = v, "Aussi avec la touche K", FeurlegConfig::save),
            new Option("Coin de l'écran", () -> CORNER_LABELS[cornerIndex(leg.corner)],
                () -> { leg.corner = CORNERS[Math.floorMod(cornerIndex(leg.corner) + 1, 4)]; FeurlegConfig.save(); },
                () -> { leg.corner = CORNERS[Math.floorMod(cornerIndex(leg.corner) - 1, 4)]; FeurlegConfig.save(); },
                "Où afficher le panneau", () -> true),
            new Option("Fond du panneau", () -> BG_LABELS[bgIndex(leg.hudBackgroundAlpha)],
                () -> { leg.hudBackgroundAlpha = BG_ALPHAS[Math.floorMod(bgIndex(leg.hudBackgroundAlpha) + 1, BG_ALPHAS.length)]; FeurlegConfig.save(); },
                () -> { leg.hudBackgroundAlpha = BG_ALPHAS[Math.floorMod(bgIndex(leg.hudBackgroundAlpha) - 1, BG_ALPHAS.length)]; FeurlegConfig.save(); },
                "Aucun = juste le texte (comme Legend HUD), sinon un fond sombre derrière", () -> true),
            new Option("Noms des mondes", () -> "construction".equals(leg.zoneNames) ? "Construction01, 02, 03" : "Johto, Hoenn, Unys",
                () -> { leg.zoneNames = "construction".equals(leg.zoneNames) ? "regions" : "construction"; FeurlegConfig.save(); },
                () -> { leg.zoneNames = "construction".equals(leg.zoneNames) ? "regions" : "construction"; FeurlegConfig.save(); },
                "Johto = Construction01, Hoenn = Construction02, Unys = Construction03 (les Ressources restent Ressources01, 02, 03)",
                () -> true),
            new Option("Taille", () -> Math.round(leg.scale * 100) + " %",
                () -> { leg.scale = leg.scale >= 2f ? 0.5f : leg.scale + 0.25f; FeurlegConfig.save(); },
                () -> { leg.scale = leg.scale <= 0.5f ? 2f : leg.scale - 0.25f; FeurlegConfig.save(); },
                "Taille du panneau (50 à 200 %)", () -> true),
            toggle("Heure Minecraft du spawn", () -> leg.showMinecraftTime, v -> leg.showMinecraftTime = v,
                "L'heure en jeu à laquelle le légendaire tombera, entre crochets. Calculée une fois puis figée.", FeurlegConfig::save),
            toggle("Moment de la journée", () -> leg.showPeriodName, v -> leg.showPeriodName = v,
                "Après l'heure : minuit, aube, matin, midi, après-midi, crépuscule, nuit (ex. [23:29 minuit])", FeurlegConfig::save),
            toggle("Compte à rebours", () -> leg.showCountdown, v -> leg.showCountdown = v,
                "Le temps réel restant avant la tentative de spawn", FeurlegConfig::save),
            new Option("Prévenir avant", () -> leg.warnSeconds <= 0 ? "Jamais" : leg.warnSeconds < 60 ? leg.warnSeconds + " s" : (leg.warnSeconds / 60) + " min",
                () -> { leg.warnSeconds = stepWarn(leg.warnSeconds, 1); FeurlegConfig.save(); },
                () -> { leg.warnSeconds = stepWarn(leg.warnSeconds, -1); FeurlegConfig.save(); },
                "Quand afficher l'alerte au milieu de l'écran", () -> leg.warnSeconds > 0),
            new Option("Son d'alerte", () -> SOUND_LABELS[soundIndex(leg)],
                () -> { cycleSound(leg, 1); }, () -> { cycleSound(leg, -1); },
                "Le son quand il reste peu de temps et à 0. Chaque clic te fait écouter le son choisi.", () -> leg.alertSound),
            new Option("Volume de l'alerte", () -> leg.alertVolume + " %", () -> { }, () -> { },
                "Glisse la barre (0 à 300 %). Au-dessus de 100 %, le son est joué plusieurs fois en même temps pour être plus fort. "
                    + "Le son se joue quand tu lâches la barre.", () -> true,
                new Slider(0, 300, 10, () -> leg.alertVolume, v -> leg.alertVolume = v, () -> {
                    FeurlegConfig.save();
                    if (leg.alertSound) {
                        com.goat.feurleg.FeurlegClient.playAlertSound(net.minecraft.client.Minecraft.getInstance(), 1.2f);
                    }
                })),
            toggle("Message au milieu de l'écran", () -> leg.alertMessage, v -> leg.alertMessage = v,
                "Grand titre avec le nom de la zone", FeurlegConfig::save))));
        addResetSection();
    }

    /** Premier clic : demande confirmation ; second clic dans les 4 s : remet tout par défaut. */
    private long resetArmedUntil;

    private void addResetSection() {
        sections.add(new Section("Réinitialiser", List.of(new Option("Remettre tous les réglages par défaut",
            () -> System.currentTimeMillis() < resetArmedUntil ? "Confirmer ?" : "Réinitialiser",
            this::onReset, this::onReset,
            "Thème, Annuler / Envoyer et affichage des timers reviennent comme à l'installation. "
                + "Tes timers, tes sets enregistrés et les adversaires déjà vus sont gardés.",
            () -> System.currentTimeMillis() < resetArmedUntil))));
    }

    private void onReset() {
        if (System.currentTimeMillis() >= resetArmedUntil) {
            resetArmedUntil = System.currentTimeMillis() + 4000;
            return;
        }
        resetArmedUntil = 0;
        FeurmodConfig.reset();
        UndoConfig.reset();
        FeurlegConfig.reset();
        // Les options pointent vers les anciens objets de config : on les recrée
        sections.clear();
        build();
        rebuildWidgets();
    }

    private static Option toggle(String label, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set, String tip, Runnable save) {
        Runnable flip = () -> {
            set.accept(!get.get());
            save.run();
        };
        return new Option(label, () -> get.get() ? "Oui" : "Non", flip, flip, tip, get);
    }

    private static final String[] SOUND_LABELS = {"Aucun", "Cloche", "Damn les gens"};
    private static final String[] SOUND_IDS = {"", "cloche", "damn"};

    private static int soundIndex(FeurlegConfig leg) {
        if (!leg.alertSound) return 0;
        for (int i = 1; i < SOUND_IDS.length; i++) {
            if (SOUND_IDS[i].equals(leg.alertSoundChoice)) return i;
        }
        return 1;
    }

    /** Aucun → Cloche → Damn les gens, et on fait écouter le son choisi. */
    private static void cycleSound(FeurlegConfig leg, int dir) {
        int next = Math.floorMod(soundIndex(leg) + dir, SOUND_IDS.length);
        leg.alertSound = next != 0;
        leg.alertSoundChoice = next == 0 ? leg.alertSoundChoice : SOUND_IDS[next];
        FeurlegConfig.save();
        if (next != 0) {
            com.goat.feurleg.FeurlegClient.playAlertSound(net.minecraft.client.Minecraft.getInstance(), 1.2f);
        }
    }

    private static void themeChanged() {
        FeurmodConfig.apply();
        FeurmodConfig.save();
    }

    private static final int[] BG_ALPHAS = {0, 80, 150, 220};
    private static final String[] BG_LABELS = {"Aucun", "Léger", "Moyen", "Foncé"};

    private static int bgIndex(int alpha) {
        int best = 0;
        for (int i = 0; i < BG_ALPHAS.length; i++) {
            if (Math.abs(BG_ALPHAS[i] - alpha) < Math.abs(BG_ALPHAS[best] - alpha)) best = i;
        }
        return best;
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

    @Override
    protected void init() {
        addRenderableWidget(new FlatButton(panelX() + panelW() - 16, 7, 16, 16, Component.literal("✕"), this::onClose));
    }

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
        Theme.screenTitle(g, font, "Feurmod", "Réglages", panelX(), 11);
        g.drawString(font, "Clic : suivant · Clic droit : précédent", panelX(), 22, Theme.MUTED, false);

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
                if (o.slider() != null) {
                    // Barre : piste, partie remplie, poignée, et la valeur à droite
                    Slider sl = o.slider();
                    int valW = font.width("300 %") + 6;
                    int tw = Math.min(150, w / 2 - 20);
                    int tx = x + w - 12 - valW - tw;
                    float frac = (sl.get().getAsInt() - sl.min()) / (float) (sl.max() - sl.min());
                    Theme.rect(g, tx, y + 7, tw, 4, 2, Theme.TRACK);
                    Theme.rect(g, tx, y + 7, Math.max(2, (int) (tw * frac)), 4, 2, Theme.ACCENT);
                    // repère des 100 %
                    int mark = tx + (int) (tw * (100f - sl.min()) / (sl.max() - sl.min()));
                    g.fill(mark, y + 5, mark + 1, y + 13, Theme.MUTED);
                    int hx = tx + (int) (tw * frac) - 3;
                    Theme.rect(g, hx, y + 3, 6, 12, 2, sl == dragging ? Theme.lighten(Theme.ACCENT, 0.3F) : Theme.TEXT);
                    g.drawString(font, v, x + w - 12 - font.width(v), y + 5, sl.get().getAsInt() > 100 ? Theme.GOLD : Theme.TEXT, false);
                    sliderTrack.put(sl, new int[]{tx, tw});
                } else {
                int vw = Math.max(54, font.width(v) + 12);
                int vx = x + w - 12 - vw;
                boolean on = o.on().get();
                Theme.rect(g, vx, y + 2, vw, ROW - 6, 3, on ? Theme.withAlpha(Theme.ACCENT, 0xB0) : Theme.FIELD);
                g.drawCenteredString(font, v, vx + vw / 2, y + 5, on ? Theme.TEXT : Theme.TEXT_2);
                }
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
                        if (o.slider() != null) {
                            int[] t = sliderTrack.get(o.slider());
                            if (t != null && mx >= t[0] - 4 && mx <= t[0] + t[1] + 4) {
                                dragging = o.slider();
                                dragX = t[0];
                                dragW = t[1];
                                setSlider(mx);
                            }
                            return true;
                        }
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

    private final java.util.Map<Slider, int[]> sliderTrack = new java.util.HashMap<>();

    private void setSlider(double mx) {
        Slider sl = dragging;
        if (sl == null) return;
        float frac = Mth.clamp((float) (mx - dragX) / Math.max(1, dragW), 0F, 1F);
        int v = sl.min() + Math.round(frac * (sl.max() - sl.min()) / sl.step()) * sl.step();
        sl.set().accept(Mth.clamp(v, sl.min(), sl.max()));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            setSlider(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null) {
            Slider sl = dragging;
            dragging = null;
            sl.onRelease().run();
            return true;
        }
        return super.mouseReleased(mx, my, button);
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
