package com.maidbuilder.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * An in-world overlay card: rows are added top to bottom, then it sizes itself to the widest one
 * and draws at the given spot.
 */
public final class HudCard {
    private static final int PAD = 5, LINE = 11, MIN_WIDTH = 150, WRAP = 230;

    private interface Row {
        int width(Font font);

        int height();

        void draw(GuiGraphics g, Font font, int x, int y, int width);
    }

    /** A key (or two that belong together) and what it does. */
    public record Key(List<Component> caps, Component label) {
        public Key(Component cap, Component label) {
            this(List.of(cap), label);
        }
    }

    /** An item and how many of it. */
    public record Count(Item item, int count) {
    }

    private final Font font;
    private final List<Row> rows = new ArrayList<>();

    public HudCard(Font font) {
        this.font = font;
    }

    public HudCard text(Component text, int color) {
        rows.add(new Row() {
            @Override
            public int width(Font font) {
                return font.width(text);
            }

            @Override
            public int height() {
                return LINE;
            }

            @Override
            public void draw(GuiGraphics g, Font font, int x, int y, int width) {
                g.drawString(font, text, x, y + 1, color, false);
            }
        });
        return this;
    }

    /** Longer text, wrapped instead of making the card very wide. */
    public HudCard wrapped(Component text, int color) {
        for (FormattedCharSequence line : font.split(text, WRAP)) {
            rows.add(new Row() {
                @Override
                public int width(Font font) {
                    return font.width(line);
                }

                @Override
                public int height() {
                    return LINE - 1;
                }

                @Override
                public void draw(GuiGraphics g, Font font, int x, int y, int width) {
                    g.drawString(font, line, x, y + 1, color, false);
                }
            });
        }
        return this;
    }

    /** A progress bar with its figures to the right. */
    public HudCard bar(double fraction, String label, int color) {
        rows.add(new Row() {
            @Override
            public int width(Font font) {
                return 60 + 6 + font.width(label);
            }

            @Override
            public int height() {
                return LINE;
            }

            @Override
            public void draw(GuiGraphics g, Font font, int x, int y, int width) {
                int labelWidth = font.width(label);
                Ui.bar(g, x, y + 2, width - labelWidth - 6, fraction, color);
                g.drawString(font, label, x + width - labelWidth, y + 1, Ui.TEXT, false);
            }
        });
        return this;
    }

    public HudCard rule() {
        rows.add(new Row() {
            @Override
            public int width(Font font) {
                return 0;
            }

            @Override
            public int height() {
                return 5;
            }

            @Override
            public void draw(GuiGraphics g, Font font, int x, int y, int width) {
                g.fill(x, y + 2, x + width, y + 3, Ui.LINE_SOFT);
            }
        });
        return this;
    }

    /** A label followed by item icons with their counts, a few per line. */
    public HudCard items(Component label, int labelColor, List<Count> counts) {
        int perLine = 4;
        for (int start = 0; start < counts.size(); start += perLine) {
            List<Count> part = counts.subList(start, Math.min(counts.size(), start + perLine));
            boolean first = start == 0;
            int labelWidth = font.width(label) + 6;
            rows.add(new Row() {
                @Override
                public int width(Font font) {
                    int w = labelWidth;
                    for (Count c : part) w += 17 + font.width(String.valueOf(c.count())) + 5;
                    return w;
                }

                @Override
                public int height() {
                    return 17;
                }

                @Override
                public void draw(GuiGraphics g, Font font, int x, int y, int width) {
                    if (first) g.drawString(font, label, x, y + 5, labelColor, false);
                    int at = x + labelWidth;
                    for (Count c : part) {
                        g.renderItem(new ItemStack(c.item()), at, y);
                        String n = String.valueOf(c.count());
                        g.drawString(font, n, at + 17, y + 5, Ui.TEXT, false);
                        at += 17 + font.width(n) + 5;
                    }
                }
            });
        }
        return this;
    }

    /** Key caps with what each does, on one line. */
    public HudCard keys(List<Key> keys) {
        rows.add(new Row() {
            @Override
            public int width(Font font) {
                int w = 0;
                for (Key key : keys) {
                    for (Component cap : key.caps()) w += Ui.keycapWidth(font, cap) + 2;
                    w += 1 + font.width(key.label()) + 7;
                }
                return w - 7;
            }

            @Override
            public int height() {
                return 13;
            }

            @Override
            public void draw(GuiGraphics g, Font font, int x, int y, int width) {
                int at = x;
                for (Key key : keys) {
                    for (Component cap : key.caps()) at += Ui.keycap(g, font, cap, at, y + 1) + 2;
                    g.drawString(font, key.label(), at + 1, y + 3, Ui.MUTED, false);
                    at += 1 + font.width(key.label()) + 7;
                }
            }
        });
        return this;
    }

    public int width() {
        int w = MIN_WIDTH;
        for (Row row : rows) w = Math.max(w, row.width(font));
        return w + 2 * PAD;
    }

    public int height() {
        int h = 2 * PAD - 1;
        for (Row row : rows) h += row.height();
        return h;
    }

    /** Draws the card with its top-left corner at {@code x}/{@code y}. */
    public void render(GuiGraphics g, int x, int y, int border) {
        int w = width(), h = height();
        g.fill(x, y, x + w, y + h, Ui.HUD);
        Ui.outline(g, x, y, w, h, border);
        int at = y + PAD - 1;
        for (Row row : rows) {
            row.draw(g, font, x + PAD, at, w - 2 * PAD);
            at += row.height();
        }
    }
}
