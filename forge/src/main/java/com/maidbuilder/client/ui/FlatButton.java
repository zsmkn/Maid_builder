package com.maidbuilder.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A button in the mod's flat look. Still a {@link Button}, so everything that works with one does. */
public class FlatButton extends Button {
    public enum Style {
        /** The one main action of a screen. */
        PRIMARY,
        SECONDARY,
        /** Something waiting for the player. */
        ATTENTION,
        /** A destructive action: red label. */
        DANGER,
        /** A tab or category: no frame until hovered or selected. */
        TAB
    }

    private final Style style;
    /** A tab that is the current one, or a toggle that is on. */
    private boolean selected;
    private boolean alignLeft;
    private int reservedRight;

    public FlatButton(int x, int y, int width, int height, Component message, Style style, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.style = style;
    }

    public static FlatButton of(Component message, Style style, int width, int height, Runnable action) {
        return new FlatButton(0, 0, width, height, message, style, b -> action.run());
    }

    public FlatButton selected(boolean value) {
        this.selected = value;
        return this;
    }

    public FlatButton alignLeft() {
        this.alignLeft = true;
        return this;
    }

    /** Keeps a left-aligned label out of this much of the right end (where a badge is drawn over the button). */
    public void reserveRight(int pixels) {
        this.reservedRight = pixels;
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hot = active && isHoveredOrFocused();
        int fill, border, color;
        switch (style) {
            case PRIMARY -> {
                fill = active ? (hot ? Ui.AMBER_LIGHT : Ui.AMBER) : Ui.BUTTON;
                border = active ? Ui.AMBER_LIGHT : Ui.LINE;
                color = active ? Ui.INK : Ui.DISABLED;
            }
            case ATTENTION -> {
                fill = hot ? 0xFF54400F : Ui.AMBER_DARK;
                border = Ui.AMBER;
                color = Ui.AMBER;
            }
            case TAB -> {
                fill = selected ? Ui.SELECT : hot ? Ui.BUTTON : 0;
                border = selected ? Ui.FRAME : hot ? Ui.LINE_MID : 0;
                color = selected || hot ? Ui.TEXT : Ui.MUTED;
            }
            default -> {
                fill = selected ? Ui.SELECT : hot ? Ui.SELECT : Ui.BUTTON;
                border = !active ? Ui.LINE : selected ? Ui.FRAME : hot ? Ui.TEXT : Ui.LINE_MID;
                color = !active ? Ui.DISABLED : style == Style.DANGER ? Ui.RED : Ui.TEXT;
            }
        }
        if (border != 0) Ui.box(g, getX(), getY(), width, height, fill, border);
        Font font = Minecraft.getInstance().font;
        Component message = getMessage();
        int textWidth = font.width(message);
        int ty = getY() + (height - 8) / 2;
        if (alignLeft) {
            Ui.textClipped(g, font, message, getX() + 5, ty, width - (reservedRight > 0 ? 6 + reservedRight : 8), color);
        } else if (textWidth <= width - 4) {
            g.drawString(font, message, getX() + (width - textWidth) / 2, ty, color, false);
        } else {
            Ui.textClipped(g, font, message, getX() + 3, ty, width - 6, color);
        }
    }
}
