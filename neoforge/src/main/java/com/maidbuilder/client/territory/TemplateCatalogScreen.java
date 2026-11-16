package com.maidbuilder.client.territory;

import com.maidbuilder.client.ui.FlatButton;
import com.maidbuilder.client.ui.PanelList;
import com.maidbuilder.client.ui.Ui;
import com.maidbuilder.common.territory.TemplateDef;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Build mode catalog: categories on the left, the templates of the chosen one in the middle
 * (locked ones dimmed, with the level they need), the selected template's details on the right
 * with the button that starts placing it. The "custom" category explains placing one's own blueprints.
 */
public class TemplateCatalogScreen extends Screen {
    private static final List<String> CATEGORY_ORDER = List.of("production", "storage", "housing", "service", "decoration");
    private static final String CUSTOM = "custom";
    private static final int RAIL = 74;
    private static final int DETAIL = 136;
    private static final int PAD = 6;

    private final UUID territory;
    private String category;
    @Nullable
    private TerritoryPayloads.TemplateInfo selected;
    @Nullable
    private FlatButton place;

    private int px, py, pw, ph;

    public TemplateCatalogScreen(UUID territory) {
        super(Component.translatable("screen.maidbuilder.catalog.title"));
        this.territory = territory;
        List<String> categories = categories();
        this.category = categories.isEmpty() ? CUSTOM : categories.getFirst();
    }

    private static List<String> categories() {
        Set<String> present = new LinkedHashSet<>();
        for (TerritoryPayloads.TemplateInfo t : ClientTerritories.templates()) present.add(t.def().category());
        List<String> ordered = new ArrayList<>();
        for (String c : CATEGORY_ORDER) if (present.remove(c)) ordered.add(c);
        ordered.addAll(present);
        return ordered;
    }

    private static List<TerritoryPayloads.TemplateInfo> templatesOf(String category) {
        List<TerritoryPayloads.TemplateInfo> list = new ArrayList<>();
        for (TerritoryPayloads.TemplateInfo t : ClientTerritories.templates()) {
            if (t.def().category().equals(category)) list.add(t);
        }
        return list;
    }

    private int level() {
        TerritoryPayloads.TerritoryInfo info = ClientTerritories.get(territory);
        return info == null ? 0 : info.level();
    }

    private boolean locked(TerritoryPayloads.TemplateInfo info) {
        return info.def().requiredLevel() > level();
    }

    @Override
    protected void init() {
        pw = Math.min(Ui.MAX_WIDTH, width - 8);
        ph = Math.min(Ui.MAX_HEIGHT, height - 8);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        addRenderableWidget(new FlatButton(px + pw - 21, py + 4, 16, 16, Component.literal("✕"), FlatButton.Style.SECONDARY, b -> onClose()));

        List<String> tabs = new ArrayList<>(categories());
        tabs.add(CUSTOM);
        int y = py + Ui.HEADER + 1 + PAD;
        for (String c : tabs) {
            addRenderableWidget(new FlatButton(px + 5, y, RAIL - 8, 18,
                    Component.translatableWithFallback("category.maidbuilder." + c, c), FlatButton.Style.TAB, b -> {
                category = c;
                selected = null;
                rebuildWidgets();
            }).selected(c.equals(category)).alignLeft());
            y += 20;
        }

        place = null;
        if (CUSTOM.equals(category)) return;
        List<TerritoryPayloads.TemplateInfo> templates = templatesOf(category);
        if (selected == null || !templates.contains(selected)) selected = templates.isEmpty() ? null : templates.getFirst();
        int listX = px + 1 + RAIL + 1 + PAD;
        int listWidth = detailX() - PAD - listX;
        int listTop = py + Ui.HEADER + 1 + 2;
        PanelList<Entry> list = new PanelList<>(minecraft, listX, listTop, listWidth, py + ph - 3 - listTop, 30);
        for (TerritoryPayloads.TemplateInfo t : templates) list.add(new Entry(t));
        addRenderableWidget(list);

        if (selected != null) {
            place = new FlatButton(detailX() + PAD, py + ph - 1 - PAD - 20, DETAIL - 2 * PAD, 20,
                    Component.translatable("screen.maidbuilder.catalog.place"), FlatButton.Style.PRIMARY, b -> {
                if (selected != null) pick(selected);
            });
            place.active = !locked(selected);
            addRenderableWidget(place);
        }
    }

    private int detailX() {
        return px + pw - 1 - DETAIL;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, px, py, pw, ph);
        Ui.header(g, px, py, pw);
        int bodyTop = py + Ui.HEADER + 1, bodyHeight = ph - Ui.HEADER - 2;
        Ui.rail(g, px + 1, bodyTop, RAIL, bodyHeight);
        Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.catalog.heading", level()), px + 8, py + 9, pw - 40, Ui.TEXT);
        if (!CUSTOM.equals(category)) {
            g.fill(detailX(), bodyTop, px + pw - 1, bodyTop + bodyHeight, Ui.RAIL);
            g.fill(detailX() - 1, bodyTop, detailX(), bodyTop + bodyHeight, Ui.LINE);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int contentX = px + 1 + RAIL + 1 + PAD;
        if (CUSTOM.equals(category)) {
            int y = py + Ui.HEADER + 1 + PAD + 2;
            for (FormattedCharSequence line : font.split(Component.translatable("screen.maidbuilder.catalog.custom"), px + pw - PAD - 4 - contentX)) {
                g.drawString(font, line, contentX, y, Ui.MUTED, false);
                y += font.lineHeight + 2;
            }
        } else if (selected == null) {
            Ui.text(g, font, Component.translatable("screen.maidbuilder.catalog.empty"), contentX, py + Ui.HEADER + 12, Ui.MUTED);
        } else {
            renderDetail(g, selected);
        }
    }

    private void renderDetail(GuiGraphics g, TerritoryPayloads.TemplateInfo info) {
        TemplateDef def = info.def();
        int x = detailX() + PAD, w = DETAIL - 2 * PAD;
        int y = py + Ui.HEADER + 1 + PAD;
        // the template's icon, large
        Ui.box(g, x, y, w, 40, Ui.WELL, Ui.LINE);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - 16, y + 4, 0);
        g.pose().scale(2, 2, 1);
        g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(def.icon())), 0, 0);
        g.pose().popPose();
        y += 45;
        Ui.textClipped(g, font, Component.translatable(def.nameKey()), x, y, w, Ui.TEXT);
        y += 12;
        y = stat(g, x, y, w, "screen.maidbuilder.catalog.stat.size", Component.literal(info.sizeX() + " × " + info.sizeY() + " × " + info.sizeZ()), Ui.TEXT);
        y = stat(g, x, y, w, "screen.maidbuilder.catalog.stat.blocks", Component.literal(String.valueOf(info.blocks())), Ui.TEXT);
        y = stat(g, x, y, w, "screen.maidbuilder.catalog.stat.prosperity", Component.literal("+" + def.prosperity()), Ui.AMBER);
        if (def.workplace().isPresent()) {
            y = stat(g, x, y, w, "screen.maidbuilder.catalog.stat.maids", Component.literal(String.valueOf(def.workplace().get().maxMaids())), Ui.TEAL);
        }
        if (!def.bonus().isEmpty()) {
            Ui.textClipped(g, font, Component.translatableWithFallback("screen.maidbuilder.catalog.bonus." + def.bonus(), def.bonus()), x, y, w, Ui.GREEN);
            y += 10;
        } else if (def.storage()) {
            Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.catalog.storage"), x, y, w, Ui.AMBER);
            y += 10;
        }
        y += 3;
        int bottom = py + ph - 1 - PAD - 20 - 4;
        if (locked(info)) {
            bottom -= 11;
            Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.catalog.locked", def.requiredLevel()), x, bottom + 2, w, Ui.RED);
        }
        List<FormattedCharSequence> lines = font.split(Component.translatableWithFallback(def.descriptionKey(), ""), w);
        for (int i = 0; i < lines.size() && y + 9 <= bottom; i++) {
            boolean last = y + 19 > bottom && i < lines.size() - 1;
            g.drawString(font, lines.get(i), x, y, 0xFFC6D4E8, false);
            if (last) g.drawString(font, "…", x + Math.min(w - 6, font.width(lines.get(i))), y, 0xFFC6D4E8, false);
            y += 10;
        }
    }

    private int stat(GuiGraphics g, int x, int y, int w, String key, Component value, int color) {
        Ui.text(g, font, Component.translatable(key), x, y, Ui.MUTED);
        Ui.textRight(g, font, value, x + w, y, color);
        return y + 10;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (TerritoryKeys.BUILD_MODE.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void pick(TerritoryPayloads.TemplateInfo info) {
        if (locked(info)) return;
        BuildMode.start(territory, info);
        onClose();
    }

    private void select(TerritoryPayloads.TemplateInfo info) {
        selected = info;
        if (place != null) place.active = !locked(info);
    }

    private class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        private final TerritoryPayloads.TemplateInfo info;
        private final ItemStack icon;
        private long lastClick;

        Entry(TerritoryPayloads.TemplateInfo info) {
            this.info = info;
            this.icon = new ItemStack(BuiltInRegistries.ITEM.get(info.def().icon()));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            TemplateDef def = info.def();
            boolean isSelected = info == selected, isLocked = locked(info);
            Ui.box(g, left, top, width, height, isSelected ? Ui.SELECT : isLocked ? Ui.RAIL : Ui.CARD,
                    isSelected ? Ui.FRAME : hovering ? Ui.LINE_MID : isLocked ? Ui.LINE : Ui.LINE_SOFT);
            Ui.slot(g, left + 4, top + 4, icon);
            Component right = isLocked ? Component.translatable("screen.maidbuilder.catalog.needs_level", def.requiredLevel())
                    : Component.literal("+" + def.prosperity());
            Ui.textRight(g, font, right, left + width - 5, top + 5, isLocked ? Ui.RED : Ui.AMBER);
            int room = width - 27 - 5 - font.width(right) - 4;
            Ui.textClipped(g, font, Component.translatable(def.nameKey()), left + 27, top + 5, room, isLocked ? Ui.MUTED : Ui.TEXT);
            Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.catalog.size_blocks", info.sizeX(), info.sizeY(), info.sizeZ(),
                    info.blocks()), left + 27, top + 15, width - 32, isSelected ? 0xFFC6D4E8 : Ui.MUTED);
        }

        /** One click selects (locked ones too, to read about them); a second quick click places. */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            long now = Util.getMillis();
            if (info == selected && now - lastClick < 300) {
                pick(info);
            } else {
                select(info);
            }
            lastClick = now;
            return true;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }
}
