package com.maidbuilder.client.territory;

import com.maidbuilder.client.ui.FlatButton;
import com.maidbuilder.client.ui.PanelList;
import com.maidbuilder.client.ui.Ui;
import com.maidbuilder.core.territory.TerritoryRules;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.maidbuilder.network.ModNetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The territory screen opened from the flag: overview, construction, buildings and maids tabs. */
public class TerritoryScreen extends Screen {
    private static final int REFRESH_TICKS = 20;
    /** Wide enough for "Construction" next to its badge. */
    private static final int RAIL = 92;
    private static final int PAD = 6;

    enum Tab {
        OVERVIEW, CONSTRUCTION, BUILDINGS, MAIDS
    }

    private static Tab lastTab = Tab.OVERVIEW;

    private TerritoryPayloads.TerritoryReport report;
    private Tab tab = lastTab;
    private int ticks;
    @Nullable
    private PanelList<Row> list;
    @Nullable
    private FlatButton constructionTab;

    // the panel and the content area right of the tabs, set in init()
    private int px, py, pw, ph;
    private int cx, cy, cw, ch;

    public TerritoryScreen(TerritoryPayloads.TerritoryReport report) {
        super(Component.translatable("screen.maidbuilder.territory.title"));
        this.report = report;
    }

    public UUID territory() {
        return report.id();
    }

    public TerritoryPayloads.TerritoryReport report() {
        return report;
    }

    public void update(TerritoryPayloads.TerritoryReport report) {
        this.report = report;
        double scroll = list == null ? 0 : list.getScrollAmount();
        rebuildWidgets();
        if (list != null) list.setScrollAmount(scroll);
    }

    void send(TerritoryPayloads.Action action, @Nullable UUID target, @Nullable UUID other) {
        ModNetwork.sendToServer(new TerritoryPayloads.TerritoryAction(report.id(), action, Optional.ofNullable(target),
                Optional.ofNullable(other)));
    }

    @Override
    protected void init() {
        pw = Math.min(Ui.MAX_WIDTH, width - 8);
        ph = Math.min(Ui.MAX_HEIGHT, height - 8);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 1 + RAIL + 1 + PAD;
        cy = py + Ui.HEADER + 1 + PAD;
        cw = px + pw - 1 - PAD - cx;
        ch = py + ph - 1 - PAD - cy;

        addRenderableWidget(new FlatButton(px + pw - 21, py + 4, 16, 16, Component.literal("✕"), FlatButton.Style.SECONDARY, b -> onClose()));
        int y = py + Ui.HEADER + 1 + PAD;
        constructionTab = null;
        for (Tab t : Tab.values()) {
            FlatButton b = new FlatButton(px + 4, y, RAIL - 6, 20,
                    Component.translatable("screen.maidbuilder.territory.tab." + t.name().toLowerCase(Locale.ROOT)), FlatButton.Style.TAB, btn -> {
                tab = t;
                lastTab = t;
                rebuildWidgets();
            }).selected(t == tab).alignLeft();
            addRenderableWidget(b);
            if (t == Tab.CONSTRUCTION) constructionTab = b;
            y += 22;
        }
        list = null;
        switch (tab) {
            case OVERVIEW -> initOverview();
            case CONSTRUCTION -> initConstruction();
            case BUILDINGS -> initBuildings();
            case MAIDS -> initMaids();
        }
    }

    private PanelList<Row> newList(int top, int itemHeight) {
        PanelList<Row> l = new PanelList<>(minecraft, cx, top, cw, cy + ch - top, itemHeight);
        addRenderableWidget(l);
        list = l;
        return l;
    }

    /** The panel and its fixed parts, under the buttons and the list (1.20.1 has no background pass of its own). */
    private void drawPanel(GuiGraphics g) {
        Ui.panel(g, px, py, pw, ph);
        Ui.header(g, px, py, pw);
        Ui.rail(g, px + 1, py + Ui.HEADER + 1, RAIL, ph - Ui.HEADER - 2);
        renderHeader(g);
    }

    private void renderHeader(GuiGraphics g) {
        Component state = Component.translatable(report.active() ? "screen.maidbuilder.territory.state.active"
                : "screen.maidbuilder.territory.state.inactive");
        int stateColor = report.active() ? Ui.GREEN : Ui.RED;
        int stateWidth = font.width(state) + 14;
        int stateX = px + pw - 25 - stateWidth;
        Ui.outlinedChip(g, font, state, stateX, py + 6, stateColor);

        Component heading = Component.translatable("screen.maidbuilder.territory.heading", report.ownerName(), report.level(), report.maxLevel());
        int pipsWidth = report.maxLevel() * 8;
        int room = stateX - 8 - (px + 8);
        boolean pips = font.width(heading) + 8 + pipsWidth <= room;
        Ui.textClipped(g, font, heading, px + 8, py + 9, pips ? room - pipsWidth - 8 : room, Ui.TEXT);
        if (pips) {
            int x = px + 8 + font.width(heading) + 8;
            for (int i = 0; i < report.maxLevel(); i++) {
                if (i < report.level()) g.fill(x, py + 10, x + 6, py + 16, Ui.AMBER);
                else Ui.outline(g, x, py + 10, 6, 6, Ui.LINE_MID);
                x += 8;
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int waiting = 0;
        for (TerritoryPayloads.JobRow job : report.jobs()) if (job.needsPlayer() > 0) waiting++;
        String count = String.valueOf(waiting);
        int w = Math.max(11, font.width(count) + 6);
        if (constructionTab != null) constructionTab.reserveRight(waiting > 0 ? w + 2 : 0);
        renderBackground(g);
        drawPanel(g);
        super.render(g, mouseX, mouseY, partialTick);
        if (waiting > 0 && constructionTab != null) {
            int x = (constructionTab.getX() + constructionTab.getWidth()) - w - 2, y = constructionTab.getY() + 4;
            g.fill(x, y, x + w, y + 11, Ui.AMBER);
            Ui.text(g, font, count, x + (w - font.width(count)) / 2 + 1, y + 2, Ui.INK);
        }
        switch (tab) {
            case OVERVIEW -> renderOverview(g, mouseX, mouseY);
            case CONSTRUCTION -> {
                long builders = report.maids().stream().filter(TerritoryPayloads.MaidRow::builder).count();
                Ui.textRight(g, font, Component.translatable("screen.maidbuilder.territory.builders", builders), cx + cw, cy + 6, Ui.MUTED);
                if (report.jobs().isEmpty()) emptyHint(g, "screen.maidbuilder.territory.no_jobs", cy + 50);
            }
            case BUILDINGS -> {
                Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.territory.buildings_hint"), cx, cy + 1, cw, Ui.MUTED);
                if (report.buildings().isEmpty()) emptyHint(g, "screen.maidbuilder.territory.no_buildings", cy + 40);
            }
            case MAIDS -> {
                Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.territory.maids_hint", report.residents()), cx, cy + 1, cw, Ui.MUTED);
                if (report.maids().isEmpty()) emptyHint(g, "screen.maidbuilder.territory.no_maids", cy + 40);
            }
        }
    }

    private void emptyHint(GuiGraphics g, String key, int y) {
        for (var line : font.split(Component.translatable(key), cw - 20)) {
            g.drawString(font, line, cx + (cw - font.width(line)) / 2, y, Ui.MUTED, false);
            y += font.lineHeight + 2;
        }
    }

    // ---- overview ----

    private static final int TILE_HEIGHT = 44;
    private static final int FOOTER_HEIGHT = 27;

    private void initOverview() {
        if (report.nextRadius() <= 0) return;
        Component label = Component.translatable("screen.maidbuilder.territory.upgrade", report.level() + 1);
        int w = Math.max(90, font.width(label) + 16);
        FlatButton upgrade = new FlatButton(cx + cw - w, cy + ch - 20, w, 20, label, FlatButton.Style.PRIMARY,
                b -> send(TerritoryPayloads.Action.UPGRADE, null, null));
        upgrade.active = report.canUpgrade() && report.active();
        if (!upgrade.active) upgrade.setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.upgrade_locked")));
        addRenderableWidget(upgrade);
    }

    private void renderOverview(GuiGraphics g, int mouseX, int mouseY) {
        int gap = 6;
        int tw = (cw - 2 * gap) / 3;
        int side = report.radius() * 2 + 1;
        MutableComponent rangeSub = Component.literal(side + " × " + side);
        if (report.nextRadius() > 0) rangeSub.append(Component.translatable("screen.maidbuilder.territory.stat.range_next", report.nextRadius()));
        tile(g, cx, tw, Component.translatable("screen.maidbuilder.territory.stat.range"), String.valueOf(report.radius()),
                Component.translatable("screen.maidbuilder.territory.stat.range_unit"), rangeSub, Ui.TEXT);
        tile(g, cx + tw + gap, tw, Component.translatable("screen.maidbuilder.territory.stat.prosperity"), String.valueOf(report.prosperity()),
                Component.empty(), Component.translatable("screen.maidbuilder.territory.stat.prosperity_sub", report.buildingPoints(),
                        report.residentPoints()), Ui.AMBER);
        tile(g, cx + 2 * (tw + gap), cw - 2 * (tw + gap), Component.translatable("screen.maidbuilder.territory.stat.residents"),
                String.valueOf(report.residents()), Component.translatable("screen.maidbuilder.territory.stat.residents_cap", report.residentCap()),
                Component.translatable("screen.maidbuilder.territory.stat.flags", report.flagsOwned(), report.maxFlags()), Ui.TEXT);
        if (mouseY >= cy && mouseY < cy + TILE_HEIGHT && mouseX >= cx && mouseX < cx + cw) {
            int column = Math.min(2, (mouseX - cx) / (tw + gap));
            setTooltipForNextRenderPass(switch (column) {
                case 0 -> Component.translatable("screen.maidbuilder.territory.radius", report.radius(), side);
                case 1 -> Component.translatable("screen.maidbuilder.territory.prosperity", report.prosperity(), report.buildingPoints(),
                        report.residentPoints(), report.residents(), report.residentCap());
                default -> Component.translatable("screen.maidbuilder.territory.flag", report.flag().toShortString(),
                        report.flagsOwned(), report.maxFlags());
            });
        }

        int y = cy + TILE_HEIGHT + 6;
        if (!report.bonuses().isEmpty()) {
            Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.territory.bonuses", bonusList(report.bonuses())), cx, y, cw, Ui.MUTED);
            y += 12;
        }
        if (report.nextRadius() < 0) {
            Ui.text(g, font, Component.translatable("screen.maidbuilder.territory.max_level"), cx, y, Ui.TEAL);
            return;
        }
        int bottom = cy + ch - FOOTER_HEIGHT - 4;
        Ui.text(g, font, Component.translatable("screen.maidbuilder.territory.next_level", report.level() + 1), cx, y, Ui.TEAL);
        y += 12;
        for (TerritoryPayloads.ConditionRow c : report.conditions()) {
            if (y + 10 > bottom) break;
            conditionRow(g, c, y);
            y += 12;
        }
        if (!report.upgradeItems().isEmpty() && y + 12 + 20 <= bottom) {
            y += 2;
            Ui.text(g, font, Component.translatable("screen.maidbuilder.territory.upgrade_items"), cx, y, Ui.TEAL);
            y += 12;
            int cell = cw / 3;
            for (int i = 0; i < report.upgradeItems().size(); i++) {
                int ix = cx + (i % 3) * cell, iy = y + (i / 3) * 21;
                if (iy + 18 > bottom) break;
                TerritoryPayloads.ItemRow item = report.upgradeItems().get(i);
                ItemStack stack = new ItemStack(item.icon());
                Ui.slot(g, ix, iy, stack);
                Ui.textClipped(g, font, itemName(item, stack), ix + 22, iy + 1, cell - 26, Ui.TEXT);
                boolean enough = item.have() >= item.need();
                Ui.text(g, font, (enough ? "✔ " : "") + Math.min(item.have(), 99999) + " / " + item.need(), ix + 22, iy + 10,
                        enough ? Ui.GREEN : item.have() > 0 ? Ui.AMBER : Ui.RED);
            }
        }

        // footer: what is still missing, next to the button it keeps locked
        int top = cy + ch - FOOTER_HEIGHT;
        g.fill(cx, top, cx + cw, top + 1, Ui.LINE_SOFT);
        int room = cw - Math.max(90, font.width(Component.translatable("screen.maidbuilder.territory.upgrade", report.level() + 1)) + 16) - 8;
        Component first = !report.active() ? Component.translatable("screen.maidbuilder.territory.inactive")
                : report.canUpgrade() ? Component.translatable("screen.maidbuilder.territory.upgrade_ready")
                : Component.translatable("screen.maidbuilder.territory.still_needed", stillNeeded());
        Ui.textClipped(g, font, first, cx, top + 6, room, !report.active() ? Ui.RED : report.canUpgrade() ? Ui.GREEN : Ui.TEXT);
        if (!report.nextBonuses().isEmpty()) {
            Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.territory.next_bonuses", bonusList(report.nextBonuses())),
                    cx, top + 17, room, Ui.MUTED);
        }
        if (mouseX >= cx && mouseX < cx + room && mouseY >= top + 4 && mouseY < top + 16 && font.width(first) > room) {
            setTooltipForNextRenderPass(font.split(first, 240));
        }
    }

    private void tile(GuiGraphics g, int x, int w, Component label, String value, Component unit, Component sub, int valueColor) {
        Ui.box(g, x, cy, w, TILE_HEIGHT, Ui.CARD, Ui.LINE_SOFT);
        Ui.textClipped(g, font, label, x + 5, cy + 4, w - 10, Ui.MUTED);
        Ui.textBig(g, font, value, x + 5, cy + 14, valueColor);
        Ui.textClipped(g, font, unit, x + 7 + font.width(value) * 2, cy + 21, w - 12 - font.width(value) * 2, Ui.MUTED);
        Ui.textClipped(g, font, sub, x + 5, cy + 33, w - 10, Ui.MUTED);
    }

    private void conditionRow(GuiGraphics g, TerritoryPayloads.ConditionRow c, int y) {
        if (c.met()) {
            g.fill(cx, y, cx + 9, y + 9, Ui.GREEN);
            Ui.text(g, font, "✔", cx + 1, y + 1, Ui.INK);
        } else {
            Ui.outline(g, cx, y, 9, 9, Ui.RED);
        }
        String count = c.have() + " / " + c.need();
        int countWidth = Math.max(40, font.width(count));
        int labelWidth = Math.min(font.width(conditionText(c)), cw - 13 - countWidth - 50);
        Ui.textClipped(g, font, conditionText(c), cx + 13, y + 1, labelWidth, Ui.TEXT);
        int barX = cx + 13 + labelWidth + 6, barRight = cx + cw - countWidth - 6;
        int color = c.met() ? Ui.GREEN : Ui.AMBER;
        if (barRight - barX >= 20) Ui.bar(g, barX, y + 2, barRight - barX, c.need() <= 0 ? 1 : (double) c.have() / c.need(), color);
        Ui.textRight(g, font, count, cx + cw, y + 1, color);
    }

    /** "prosperity 8, Any Logs ×64, Iron Ingot ×16": the unmet conditions and the items still short. */
    private Component stillNeeded() {
        MutableComponent parts = Component.empty();
        boolean first = true;
        for (TerritoryPayloads.ConditionRow c : report.conditions()) {
            if (c.met()) continue;
            if (!first) parts.append(Component.translatable("screen.maidbuilder.territory.separator"));
            first = false;
            parts.append(TerritoryRules.Kind.values()[c.kind()] == TerritoryRules.Kind.PROSPERITY
                    ? Component.translatable("screen.maidbuilder.territory.needed_prosperity", c.need() - c.have())
                    : conditionText(c));
        }
        for (TerritoryPayloads.ItemRow item : report.upgradeItems()) {
            if (item.have() >= item.need()) continue;
            if (!first) parts.append(Component.translatable("screen.maidbuilder.territory.separator"));
            first = false;
            parts.append(itemName(item, new ItemStack(item.icon()))).append(" ×" + (item.need() - item.have()));
        }
        return parts;
    }

    private static Component itemName(TerritoryPayloads.ItemRow item, ItemStack stack) {
        return item.key().startsWith("#") ? Component.translatable("screen.maidbuilder.territory.any_of", tagName(item.key())) : stack.getHoverName();
    }

    // ---- construction ----

    private void initConstruction() {
        Component buildLabel = Component.translatable("screen.maidbuilder.territory.build_mode");
        int buildWidth = font.width(buildLabel) + 16;
        FlatButton build = new FlatButton(cx, cy, buildWidth, 20, buildLabel, FlatButton.Style.PRIMARY, b -> {
            onClose();
            Minecraft.getInstance().setScreen(new TemplateCatalogScreen(report.id()));
        });
        build.active = report.active();
        addRenderableWidget(build);
        Component bindLabel = Component.translatable(report.bindingSources()
                ? "screen.maidbuilder.territory.binding_stop" : "screen.maidbuilder.territory.binding_start", report.sources());
        FlatButton bind = new FlatButton(cx + buildWidth + 4, cy, font.width(bindLabel) + 14, 20, bindLabel, FlatButton.Style.SECONDARY, b -> {
            send(TerritoryPayloads.Action.BIND_SOURCES, null, null);
            if (!report.bindingSources()) onClose();
        });
        bind.setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.binding_tip")));
        addRenderableWidget(bind);
        PanelList<Row> l = newList(cy + 22, 50);
        for (TerritoryPayloads.JobRow job : report.jobs()) l.add(new JobEntry(job));
    }

    // ---- buildings ----

    private void initBuildings() {
        PanelList<Row> l = newList(cy + 10, 34);
        for (TerritoryPayloads.BuildingRow b : report.buildings()) l.add(new BuildingEntry(b));
    }

    // ---- maids ----

    private void initMaids() {
        PanelList<Row> l = newList(cy + 10, 54);
        for (TerritoryPayloads.MaidRow m : report.maids()) l.add(new MaidEntry(m));
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) ModNetwork.sendToServer(new TerritoryPayloads.RequestTerritoryReport(report.id(), false));
    }

    // ---- text helpers ----

    static Component conditionText(TerritoryPayloads.ConditionRow c) {
        return switch (TerritoryRules.Kind.values()[c.kind()]) {
            case PROSPERITY -> Component.translatable("screen.maidbuilder.territory.cond.prosperity", c.need());
            case GROUP -> Component.translatable("screen.maidbuilder.territory.cond.group", groupName(c.key()), c.need());
            case CATEGORY -> Component.translatable("screen.maidbuilder.territory.cond.category",
                    Component.translatableWithFallback("category.maidbuilder." + c.key(), c.key()), c.need());
        };
    }

    static Component condition(TerritoryPayloads.ConditionRow c) {
        return Component.literal(c.met() ? "  ✔ " : "  ✘ ").withStyle(c.met() ? ChatFormatting.GREEN : ChatFormatting.RED)
                .append(conditionText(c).copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + c.have() + "/" + c.need() + ")").withStyle(ChatFormatting.GRAY));
    }

    /** "#minecraft:logs": the tag's translated name (NeoForge names common tags), else the id. */
    static Component tagName(String key) {
        ResourceLocation id = ResourceLocation.tryParse(key.substring(1));
        if (id == null) return Component.literal(key);
        return Component.translatableWithFallback("tag.item." + id.getNamespace() + "." + id.getPath().replace('/', '.'), key);
    }

    static Component groupName(String group) {
        return Component.translatableWithFallback("group.maidbuilder." + group, group);
    }

    static Component templateName(String template) {
        ResourceLocation id = ResourceLocation.tryParse(template);
        if (id == null) return Component.literal(template);
        return Component.translatableWithFallback("template." + id.getNamespace() + "." + id.getPath().replace('/', '.'), template);
    }

    static Component bonusList(List<String> bonuses) {
        MutableComponent list = Component.empty();
        for (int i = 0; i < bonuses.size(); i++) {
            if (i > 0) list.append(Component.translatable("screen.maidbuilder.territory.separator"));
            list.append(Component.translatableWithFallback("bonus.maidbuilder." + bonuses.get(i), bonuses.get(i)));
        }
        return list;
    }

    private Component buildingLabel(@Nullable UUID id) {
        if (id == null) return Component.translatable("screen.maidbuilder.territory.no_workplace");
        for (TerritoryPayloads.BuildingRow b : report.buildings()) {
            if (b.id().equals(id)) {
                return Component.translatable("screen.maidbuilder.territory.building_at", templateName(b.template()),
                        b.center().getX(), b.center().getZ());
            }
        }
        return Component.literal("?");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- rows ----

    private abstract class Row extends ContainerObjectSelectionList.Entry<Row> {
        protected final List<AbstractWidget> widgets = new ArrayList<>();

        protected FlatButton button(Component label, FlatButton.Style style, int width, Runnable action) {
            FlatButton b = FlatButton.of(label, style, width, 16, action);
            widgets.add(b);
            return b;
        }

        /** A button as wide as its label. */
        protected FlatButton fitted(Component label, FlatButton.Style style, Runnable action) {
            return button(label, style, font.width(label) + 12, action);
        }

        /** Lays the buttons out right-aligned; returns the x where they start. */
        protected int renderButtons(GuiGraphics g, int y, int right, int mouseX, int mouseY, float partialTick) {
            int x = right;
            for (int i = widgets.size() - 1; i >= 0; i--) {
                AbstractWidget w = widgets.get(i);
                x -= w.getWidth();
                w.setPosition(x, y);
                w.render(g, mouseX, mouseY, partialTick);
                x -= 2;
            }
            return x;
        }

        /** Lays the buttons out left-aligned. */
        protected void renderButtonsFrom(GuiGraphics g, int x, int y, int mouseX, int mouseY, float partialTick) {
            for (AbstractWidget w : widgets) {
                w.setPosition(x, y);
                w.render(g, mouseX, mouseY, partialTick);
                x += w.getWidth() + 3;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }

    private class JobEntry extends Row {
        private final TerritoryPayloads.JobRow job;
        @Nullable
        private final FlatButton problems;

        JobEntry(TerritoryPayloads.JobRow job) {
            this.job = job;
            button(Component.literal("▲"), FlatButton.Style.SECONDARY, 16, () -> send(TerritoryPayloads.Action.JOB_UP, job.id(), null))
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.job_up")));
            button(Component.literal("▼"), FlatButton.Style.SECONDARY, 16, () -> send(TerritoryPayloads.Action.JOB_DOWN, job.id(), null))
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.job_down")));
            fitted(Component.translatable("screen.maidbuilder.territory.job_materials"), FlatButton.Style.SECONDARY,
                    () -> send(TerritoryPayloads.Action.JOB_MATERIALS, job.id(), null));
            button(Component.literal("✕"), FlatButton.Style.DANGER, 16, () -> send(TerritoryPayloads.Action.JOB_CANCEL, job.id(), null))
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.job_cancel")));
            if (job.needsPlayer() > 0) {
                Component label = Component.translatable("screen.maidbuilder.territory.job_needs_you", job.needsPlayer());
                problems = FlatButton.of(label, FlatButton.Style.ATTENTION, font.width(label) + 12, 13,
                        () -> send(TerritoryPayloads.Action.JOB_PROBLEMS, job.id(), null));
                problems.setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.job_problems_tip")));
            } else {
                problems = null;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            if (problems == null) return widgets;
            List<AbstractWidget> all = new ArrayList<>(widgets);
            all.add(problems);
            return all;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            Ui.box(g, left, top, width, height, Ui.CARD, job.needsPlayer() > 0 ? Ui.AMBER : Ui.LINE_SOFT);
            String number = String.valueOf(index + 1);
            g.fill(left + 5, top + 5, left + 17, top + 17, Ui.SELECT);
            Ui.text(g, font, number, left + 11 - font.width(number) / 2, top + 7, Ui.TEXT);
            int buttonsLeft = renderButtons(g, top + 3, left + width - 4, mouseX, mouseY, partialTick);

            Component name = job.template().isEmpty() ? Component.literal(job.name()) : templateName(job.template());
            Component tag = Component.translatable("screen.maidbuilder.territory.repair_chip");
            int tagWidth = job.repair() ? font.width(tag) + 10 : 0;
            int nameRoom = buttonsLeft - (left + 21) - tagWidth - 4;
            Ui.textClipped(g, font, name, left + 21, top + 7, nameRoom, Ui.TEXT);
            if (job.repair()) Ui.chip(g, font, tag, left + 21 + Math.min(font.width(name), nameRoom) + 4, top + 5, Ui.SELECT, Ui.TEAL);

            Component progress = Component.translatable("screen.maidbuilder.territory.job_progress", job.done(), job.total(), job.maids());
            int progressWidth = font.width(progress);
            Ui.textRight(g, font, progress, left + width - 5, top + 21, Ui.MUTED);
            boolean complete = job.total() > 0 && job.done() >= job.total();
            Ui.bar(g, left + 5, top + 22, Math.max(20, width - 10 - progressWidth - 6), job.total() <= 0 ? 0 : (double) job.done() / job.total(),
                    complete ? Ui.GREEN : Ui.FRAME);

            if (problems != null) {
                problems.setPosition(left + 5, top + 30);
                problems.render(g, mouseX, mouseY, partialTick);
            } else if (job.suspended()) {
                Ui.text(g, font, Component.translatable("screen.maidbuilder.territory.job_state.paused"), left + 5, top + 33, Ui.RED);
            } else {
                Ui.text(g, font, Component.translatable(job.maids() > 0 ? "screen.maidbuilder.territory.job_state.building"
                        : "screen.maidbuilder.territory.job_state.queued"), left + 5, top + 33, Ui.MUTED);
            }
        }
    }

    private class BuildingEntry extends Row {
        private final TerritoryPayloads.BuildingRow building;

        BuildingEntry(TerritoryPayloads.BuildingRow building) {
            this.building = building;
            if (!building.working() || building.integrity() < 100) {
                FlatButton repair = fitted(Component.translatable("screen.maidbuilder.territory.repair"),
                        building.working() ? FlatButton.Style.SECONDARY : FlatButton.Style.PRIMARY,
                        () -> send(TerritoryPayloads.Action.REPAIR, building.id(), null));
                repair.active = !building.repairQueued() && report.active();
            }
            button(Component.literal("✕"), FlatButton.Style.DANGER, 16, this::remove)
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.unregister_tip")));
        }

        private long armed;

        /** Two clicks within three seconds. */
        private void remove() {
            long now = net.minecraft.Util.getMillis();
            if (now - armed < 3000) {
                send(TerritoryPayloads.Action.REMOVE_BUILDING, building.id(), null);
            } else {
                armed = now;
                Minecraft.getInstance().player.displayClientMessage(Component.translatable("screen.maidbuilder.territory.unregister_confirm"), true);
            }
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            Ui.box(g, left, top, width, height, Ui.CARD, building.working() ? Ui.LINE_SOFT : Ui.RED);
            int buttonsLeft = renderButtons(g, top + 7, left + width - 4, mouseX, mouseY, partialTick);
            int room = buttonsLeft - left - 9;
            Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.territory.building_at", templateName(building.template()),
                    building.center().getX(), building.center().getZ()), left + 5, top + 5, room, Ui.TEXT);
            MutableComponent status = building.working()
                    ? Component.translatable("screen.maidbuilder.territory.working", building.integrity()).withStyle(s -> s.withColor(Ui.GREEN & 0xFFFFFF))
                    : Component.translatable("screen.maidbuilder.territory.damaged", building.integrity()).withStyle(s -> s.withColor(Ui.RED & 0xFFFFFF));
            if (building.isWorkplace()) {
                status.append(Component.translatable("screen.maidbuilder.territory.workers", building.maids(), building.maxMaids())
                        .withStyle(s -> s.withColor(Ui.TEAL & 0xFFFFFF)));
            }
            if (building.repairQueued()) {
                status.append(Component.translatable("screen.maidbuilder.territory.repair_queued").withStyle(s -> s.withColor(Ui.AMBER & 0xFFFFFF)));
            }
            Ui.textClipped(g, font, status, left + 5, top + 17, room, Ui.TEXT);
        }
    }

    private class MaidEntry extends Row {
        private final TerritoryPayloads.MaidRow maid;

        MaidEntry(TerritoryPayloads.MaidRow maid) {
            this.maid = maid;
            if (!maid.loaded()) return;
            boolean canDeposit = report.hasWarehouse() && !maid.depositing();
            if (maid.paused() || maid.building().isPresent()) {
                FlatButton deposit = fitted(Component.translatable("screen.maidbuilder.territory.deposit"),
                        maid.paused() && canDeposit ? FlatButton.Style.PRIMARY : FlatButton.Style.SECONDARY,
                        () -> send(TerritoryPayloads.Action.DEPOSIT, maid.id(), null));
                deposit.active = canDeposit;
                deposit.setTooltip(Tooltip.create(Component.translatable(report.hasWarehouse()
                        ? "screen.maidbuilder.territory.deposit_tip" : "screen.maidbuilder.territory.deposit_no_warehouse")));
            }
            if (maid.paused()) {
                fitted(Component.translatable("screen.maidbuilder.territory.resume"), FlatButton.Style.SECONDARY,
                        () -> send(TerritoryPayloads.Action.RESUME, maid.id(), null));
            }
            fitted(Component.translatable(maid.builder() ? "screen.maidbuilder.territory.builder_on" : "screen.maidbuilder.territory.builder_off"),
                    FlatButton.Style.SECONDARY, () -> send(TerritoryPayloads.Action.TOGGLE_BUILDER, maid.id(), null))
                    .selected(maid.builder())
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.builder_tip")));
            if (report.buildings().stream().anyMatch(TerritoryPayloads.BuildingRow::isWorkplace)) {
                fitted(Component.translatable("screen.maidbuilder.territory.assign"), FlatButton.Style.SECONDARY,
                        () -> minecraft.setScreen(new WorkplacePickerScreen(TerritoryScreen.this, report, maid)))
                        .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.assign_tip", buildingLabel(maid.building().orElse(null)))));
            }
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            if (!maid.loaded()) {
                Ui.box(g, left, top, width, height, Ui.RAIL, Ui.LINE);
                Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.territory.maid_unloaded"), left + 5, top + 5, width - 10, Ui.MUTED);
                Ui.textClipped(g, font, Component.translatable("screen.maidbuilder.territory.maid_unloaded_hint"), left + 5, top + 17, width - 10, Ui.MUTED);
                return;
            }
            Ui.box(g, left, top, width, height, Ui.CARD, maid.paused() ? Ui.RED : Ui.LINE_SOFT);

            // backpack, right-aligned on the first line
            int packColor = maid.full() ? Ui.RED : Ui.FRAME;
            Component pack = Component.translatable("screen.maidbuilder.territory.backpack", maid.used(), maid.slots());
            int packX = left + width - 5 - font.width(pack);
            Ui.text(g, font, pack, packX, top + 5, maid.full() ? Ui.RED : Ui.MUTED);
            int pipsWidth = maid.slots() > 12 ? 60 : maid.slots() * 7 - 1;
            if (maid.slots() > 0) Ui.pips(g, packX - 6 - pipsWidth, top + 6, maid.used(), maid.slots(), packColor);

            int x = left + 5;
            int nameRoom = packX - 6 - pipsWidth - 8 - x;
            Component home = Component.translatable("screen.maidbuilder.territory.chip.home");
            Component builder = Component.translatable("screen.maidbuilder.territory.is_builder");
            if (maid.home()) nameRoom -= font.width(home) + 10;
            if (maid.builder()) nameRoom -= font.width(builder) + 10;
            Ui.textClipped(g, font, Component.literal(maid.name()), x, top + 5, nameRoom, Ui.TEXT);
            x += Math.min(font.width(maid.name()), Math.max(0, nameRoom)) + 5;
            if (maid.home()) x += Ui.chip(g, font, home, x, top + 3, Ui.GREEN_DARK, Ui.GREEN) + 3;
            if (maid.builder()) Ui.chip(g, font, builder, x, top + 3, Ui.AMBER_DARK, Ui.AMBER);

            Component status;
            int statusColor;
            if (maid.depositing()) {
                status = Component.translatable("screen.maidbuilder.territory.depositing");
                statusColor = Ui.TEAL;
            } else if (maid.paused()) {
                status = Component.translatable("screen.maidbuilder.territory.paused");
                statusColor = Ui.RED;
            } else if (maid.builder()) {
                status = Component.translatable("screen.maidbuilder.territory.builder_working");
                statusColor = Ui.AMBER;
            } else if (maid.building().isPresent()) {
                status = Component.translatable("screen.maidbuilder.territory.works_at", buildingLabel(maid.building().get()));
                statusColor = Ui.TEAL;
            } else {
                status = Component.translatableWithFallback("task." + maid.task().replace(':', '.'), maid.task());
                statusColor = Ui.MUTED;
            }
            Ui.textClipped(g, font, status, left + 5, top + 17, width - 10, statusColor);
            renderButtonsFrom(g, left + 5, top + 29, mouseX, mouseY, partialTick);
        }
    }
}
