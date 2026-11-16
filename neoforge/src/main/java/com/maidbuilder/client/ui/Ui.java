package com.maidbuilder.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The mod's own look for screens and overlays: a dark "blueprint paper" panel, square corners,
 * one-pixel lines. Everything is drawn with fills, so there are no textures to keep in step.
 */
public final class Ui {
    public static final int PANEL = 0xFF17253D, GRID = 0xFF1B2C49, RAIL = 0xFF132038, CARD = 0xFF101A2C, WELL = 0xFF05080D;
    public static final int SELECT = 0xFF284066, BUTTON = 0xFF1F3150;
    public static final int LINE = 0xFF3C5A8A, LINE_SOFT = 0xFF2B4268, LINE_MID = 0xFF56739F, FRAME = 0xFF7FA6DA;
    public static final int TEXT = 0xFFEAF1FB, MUTED = 0xFFA7B9D3, DISABLED = 0xFF8FA2BF;
    public static final int AMBER = 0xFFF5C563, AMBER_LIGHT = 0xFFFFE3A3, AMBER_DARK = 0xFF3A2C0C, INK = 0xFF1A1405;
    public static final int GREEN = 0xFF7BD88F, GREEN_DARK = 0xFF163A22, RED = 0xFFFF8A80, RED_DARK = 0xFF3B1E1C;
    public static final int TEAL = 0xFF6FD3E0, PURPLE = 0xFFA98BEA;
    /** Overlay panels let the world show through a little. */
    public static final int HUD = 0xE60B111B;

    /** Largest panel; it still fits the smallest window the game allows at its largest GUI scale. */
    public static final int MAX_WIDTH = 400, MAX_HEIGHT = 230;
    public static final int HEADER = 24;
    private static final int GRID_STEP = 16;

    private Ui() {
    }

    /** A filled box with a one-pixel border. */
    public static void box(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** A one-pixel outline. */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** The screen panel: dark outline, light frame, grid paper inside. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, WELL);
        box(g, x, y, w, h, PANEL, FRAME);
        for (int gx = x + GRID_STEP; gx < x + w - 1; gx += GRID_STEP) g.fill(gx, y + 1, gx + 1, y + h - 1, GRID);
        for (int gy = y + GRID_STEP; gy < y + h - 1; gy += GRID_STEP) g.fill(x + 1, gy, x + w - 1, gy + 1, GRID);
    }

    /** The panel's title strip, with the line under it. */
    public static void header(GuiGraphics g, int x, int y, int w) {
        g.fill(x + 1, y + 1, x + w - 1, y + HEADER, PANEL);
        g.fill(x + 1, y + HEADER, x + w - 1, y + HEADER + 1, LINE);
    }

    /** A side strip (tabs, categories) of the panel below its header, with the line to its right. */
    public static void rail(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, RAIL);
        g.fill(x + w, y, x + w + 1, y + h, LINE);
    }

    public static final int BAR_HEIGHT = 6;

    /** A progress bar, {@link #BAR_HEIGHT} high. */
    public static void bar(GuiGraphics g, int x, int y, int w, double fraction, int color) {
        box(g, x, y, w, BAR_HEIGHT, WELL, LINE_SOFT);
        int inner = w - 2;
        int filled = (int) Math.round(inner * Math.max(0, Math.min(1, fraction)));
        if (fraction > 0 && filled == 0) filled = 1;
        if (filled > 0) g.fill(x + 1, y + 1, x + 1 + filled, y + BAR_HEIGHT - 1, color);
    }

    /** A bar whose fill is split into parts (amounts out of {@code total}), each in its own colour. */
    public static void segments(GuiGraphics g, int x, int y, int w, int total, int[] amounts, int[] colors) {
        box(g, x, y, w, BAR_HEIGHT, WELL, LINE_SOFT);
        if (total <= 0) return;
        int inner = w - 2, at = 0;
        long sum = 0;
        for (int i = 0; i < amounts.length; i++) {
            sum += Math.max(0, amounts[i]);
            int end = (int) Math.min(inner, Math.round((double) inner * sum / total));
            if (amounts[i] > 0 && end == at && at < inner) end = at + 1;
            if (end > at) g.fill(x + 1 + at, y + 1, x + 1 + end, y + BAR_HEIGHT - 1, colors[i]);
            at = end;
        }
    }

    public static final int CHIP_HEIGHT = 11;

    /** A small label on a coloured ground; returns its width. */
    public static int chip(GuiGraphics g, Font font, Component text, int x, int y, int ground, int color) {
        int w = font.width(text) + 6;
        g.fill(x, y, x + w, y + CHIP_HEIGHT, ground);
        g.drawString(font, text, x + 3, y + 2, color, false);
        return w;
    }

    /** A chip with a border instead of a filled ground (a state, e.g. "Active"). */
    public static int outlinedChip(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        int w = font.width(text) + 14;
        outline(g, x, y, w, CHIP_HEIGHT + 2, color);
        g.fill(x + 4, y + 4, x + 8, y + 8, color);
        g.drawString(font, text, x + 11, y + 3, color, false);
        return w;
    }

    public static final int SLOT = 18;

    /** An item in a dark slot, {@link #SLOT} square. */
    public static void slot(GuiGraphics g, int x, int y, ItemStack stack) {
        box(g, x, y, SLOT, SLOT, WELL, LINE);
        g.renderItem(stack, x + 1, y + 1);
    }

    /** A key cap with the key's name; returns its width. */
    public static int keycap(GuiGraphics g, Font font, Component key, int x, int y) {
        int w = Math.max(11, font.width(key) + 6);
        box(g, x, y, w, 11, SELECT, LINE_MID);
        g.drawString(font, key, x + (w - font.width(key)) / 2 + 1, y + 2, TEXT, false);
        return w;
    }

    public static int keycapWidth(Font font, Component key) {
        return Math.max(11, font.width(key) + 6);
    }

    /** Small squares for a few slots (used ones filled); a bar instead when there are many. */
    public static int pips(GuiGraphics g, int x, int y, int used, int slots, int color) {
        if (slots <= 0) return 0;
        if (slots > 12) {
            bar(g, x, y, 60, (double) used / slots, color);
            return 60;
        }
        for (int i = 0; i < slots; i++) {
            int px = x + i * 7;
            if (i < used) g.fill(px, y, px + 6, y + 6, color);
            else outline(g, px, y, 6, 6, LINE);
        }
        return slots * 7 - 1;
    }

    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        g.drawString(font, text, x, y, color, false);
    }

    public static void text(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, text, x, y, color, false);
    }

    public static void textRight(GuiGraphics g, Font font, Component text, int right, int y, int color) {
        g.drawString(font, text, right - font.width(text), y, color, false);
    }

    public static void textRight(GuiGraphics g, Font font, String text, int right, int y, int color) {
        g.drawString(font, text, right - font.width(text), y, color, false);
    }

    /** Text cut off with "…" when it is wider than {@code maxWidth}. */
    public static void textClipped(GuiGraphics g, Font font, Component text, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) return;
        if (font.width(text) <= maxWidth) {
            g.drawString(font, text, x, y, color, false);
            return;
        }
        FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - font.width("…")));
        FormattedCharSequence line = net.minecraft.locale.Language.getInstance()
                .getVisualOrder(FormattedText.composite(cut, FormattedText.of("…")));
        g.drawString(font, line, x, y, color, false);
    }

    /** Text at twice the size (the one bigger size the look uses, for key numbers). */
    public static void textBig(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(2, 2, 1);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }
}
