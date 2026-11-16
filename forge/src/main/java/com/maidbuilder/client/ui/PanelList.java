package com.maidbuilder.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/**
 * A scrolling list that sits inside a {@link Ui#panel}: no background or separators of its own.
 * Rows start at {@code x} and are {@link #getRowWidth()} wide, leaving room for the scroll bar at
 * the right edge.
 */
public class PanelList<E extends ContainerObjectSelectionList.Entry<E>> extends ContainerObjectSelectionList<E> {
    /** The game centres rows in the widget, so it is made this much wider on both sides of them. */
    private static final int SIDE = 8;

    public PanelList(Minecraft minecraft, int x, int y, int width, int height, int itemHeight) {
        super(minecraft, width + SIDE, height, y, y + height, itemHeight);
        setLeftPos(x - SIDE);
        // the panel behind is drawn by the screen; rows are clipped to the list by the game
        setRenderBackground(false);
        setRenderTopAndBottom(false);
    }

    public void add(E entry) {
        addEntry(entry);
    }

    public void clear() {
        clearEntries();
    }

    @Override
    public int getRowWidth() {
        return width - 2 * SIDE;
    }

    @Override
    public int getRowLeft() {
        return x0 + SIDE;
    }

    @Override
    protected int getScrollbarPosition() {
        return x1 - 6;
    }
}
