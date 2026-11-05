package com.goat.feurmod.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Bouton plat et arrondi (onglets, flèches, étoile shiny...), au lieu du bouton en pierre de Minecraft. */
public class FlatButton extends AbstractWidget {
    private final Runnable onPress;
    /** Onglet choisi / option activée : rempli avec la couleur d'accent. */
    public boolean selected;
    private int accent = Theme.ACCENT;
    private int radius = 3;

    public FlatButton(int x, int y, int width, int height, Component message, Runnable onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    public FlatButton accent(int color) {
        this.accent = color;
        return this;
    }

    public FlatButton radius(int r) {
        this.radius = r;
        return this;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        onPress.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHovered() && active;
        int bg;
        if (!active) {
            bg = Theme.CARD_DISABLED;
        } else if (selected) {
            bg = hovered ? Theme.lighten(accent, 0.12F) : accent;
        } else {
            bg = hovered ? Theme.CARD_HOVER : Theme.CARD;
        }
        Theme.rect(g, getX(), getY(), width, height, radius, bg);
        if (!selected && active) {
            // fine ligne claire en haut : effet de relief discret
            g.fill(getX() + radius, getY(), getX() + width - radius, getY() + 1, 0x14FFFFFF);
        }
        g.flush();

        Font font = Minecraft.getInstance().font;
        int color = !active ? Theme.MUTED : selected ? 0xFFFFFFFF : hovered ? 0xFFFFFFFF : Theme.TEXT;
        Component message = getMessage();
        int maxW = width - 6;
        FormattedCharSequence text;
        int textW;
        if (font.width(message) <= maxW) {
            text = message.getVisualOrderText();
            textW = font.width(message);
        } else {
            String cut = Theme.ellipsize(font, message.getString(), maxW);
            text = Component.literal(cut).withStyle(message.getStyle()).getVisualOrderText();
            textW = font.width(cut);
        }
        g.drawString(font, text, getX() + (width - textW) / 2, getY() + (height - 8) / 2, color, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
