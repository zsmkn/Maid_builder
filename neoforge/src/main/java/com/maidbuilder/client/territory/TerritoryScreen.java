package com.maidbuilder.client.territory;

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
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The territory screen opened from the flag: overview, construction, buildings and maids tabs. */
public class TerritoryScreen extends Screen {
    private static final int REFRESH_TICKS = 20;
    private static final int PANEL_WIDTH = 340;
    private static final int LIST_TOP = 74;

    enum Tab {
        OVERVIEW, CONSTRUCTION, BUILDINGS, MAIDS
    }

    private static Tab lastTab = Tab.OVERVIEW;

    private TerritoryPayloads.TerritoryReport report;
    private Tab tab = lastTab;
    private int ticks;
    @Nullable
    private RowList list;

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
        PacketDistributor.sendToServer(new TerritoryPayloads.TerritoryAction(report.id(), action, Optional.ofNullable(target),
                Optional.ofNullable(other)));
    }

    private int left() {
        return (width - PANEL_WIDTH) / 2;
    }

    @Override
    protected void init() {
        int x = left();
        int tabWidth = PANEL_WIDTH / Tab.values().length - 2;
        for (Tab t : Tab.values()) {
            Button b = Button.builder(Component.translatable("screen.maidbuilder.territory.tab." + t.name().toLowerCase(Locale.ROOT)), btn -> {
                tab = t;
                lastTab = t;
                rebuildWidgets();
            }).bounds(x, 22, tabWidth, 20).build();
            b.active = t != tab;
            addRenderableWidget(b);
            x += tabWidth + 2;
        }
        list = null;
        switch (tab) {
            case OVERVIEW -> initOverview();
            case CONSTRUCTION -> initConstruction();
            case BUILDINGS -> initBuildings();
            case MAIDS -> initMaids();
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    private RowList newList(int top) {
        return newList(top, 24);
    }

    private RowList newList(int top, int itemHeight) {
        RowList l = new RowList(minecraft, width, height - 32 - top, top, itemHeight);
        addRenderableWidget(l);
        list = l;
        return l;
    }

    // ---- overview ----

    private int overviewTextHeight;

    private void initOverview() {
        List<Component> lines = overviewLines();
        overviewTextHeight = lines.size() * (font.lineHeight + 3);
        int top = 50 + overviewTextHeight + 4;
        if (report.nextRadius() > 0 && !report.upgradeItems().isEmpty()) {
            RowList l = newList(top + 12);
            for (TerritoryPayloads.ItemRow item : report.upgradeItems()) l.add(new ItemEntry(item));
        }
        if (report.nextRadius() > 0) {
            Button upgrade = Button.builder(Component.translatable("screen.maidbuilder.territory.upgrade", report.level() + 1),
                    b -> send(TerritoryPayloads.Action.UPGRADE, null, null)).bounds(left() + PANEL_WIDTH - 120, 50, 120, 20).build();
            upgrade.active = report.canUpgrade() && report.active();
            if (!upgrade.active) upgrade.setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.upgrade_locked")));
            addRenderableWidget(upgrade);
        }
    }

    private List<Component> overviewLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(report.active()
                ? Component.translatable("screen.maidbuilder.territory.active").withStyle(ChatFormatting.GREEN)
                : Component.translatable("screen.maidbuilder.territory.inactive").withStyle(ChatFormatting.RED));
        lines.add(Component.translatable("screen.maidbuilder.territory.flag", report.flag().toShortString(),
                report.flagsOwned(), report.maxFlags()));
        MutableComponent range = Component.translatable("screen.maidbuilder.territory.radius", report.radius(), report.radius() * 2 + 1);
        if (report.nextRadius() > 0) range.append(Component.translatable("screen.maidbuilder.territory.next_radius", report.nextRadius()));
        lines.add(range);
        lines.add(Component.translatable("screen.maidbuilder.territory.prosperity", report.prosperity(), report.buildingPoints(),
                report.residentPoints(), report.residents(), report.residentCap()).withStyle(ChatFormatting.GOLD));
        if (!report.bonuses().isEmpty()) lines.add(Component.translatable("screen.maidbuilder.territory.bonuses", bonusList(report.bonuses())));
        if (report.nextRadius() < 0) {
            lines.add(Component.translatable("screen.maidbuilder.territory.max_level").withStyle(ChatFormatting.AQUA));
        } else {
            lines.add(Component.translatable("screen.maidbuilder.territory.next_level", report.level() + 1).withStyle(ChatFormatting.AQUA));
            for (TerritoryPayloads.ConditionRow c : report.conditions()) lines.add(condition(c));
            if (!report.nextBonuses().isEmpty()) {
                lines.add(Component.translatable("screen.maidbuilder.territory.next_bonuses", bonusList(report.nextBonuses()))
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        return lines;
    }

    private void renderOverview(GuiGraphics g) {
        int y = 50;
        for (Component line : overviewLines()) {
            g.drawString(font, line, left(), y, 0xFFFFFF, false);
            y += font.lineHeight + 3;
        }
        if (report.nextRadius() > 0 && !report.upgradeItems().isEmpty()) {
            g.drawString(font, Component.translatable("screen.maidbuilder.territory.upgrade_items").withStyle(ChatFormatting.AQUA),
                    left(), y + 4, 0xFFFFFF, false);
        }
    }

    // ---- construction ----

    private void initConstruction() {
        int x = left();
        Button build = Button.builder(Component.translatable("screen.maidbuilder.territory.build_mode"), b -> {
            onClose();
            Minecraft.getInstance().setScreen(new TemplateCatalogScreen(report.id()));
        }).bounds(x, 48, 110, 20).build();
        build.active = report.active();
        addRenderableWidget(build);
        addRenderableWidget(Button.builder(Component.translatable(report.bindingSources()
                        ? "screen.maidbuilder.territory.binding_stop" : "screen.maidbuilder.territory.binding_start", report.sources()),
                b -> {
                    send(TerritoryPayloads.Action.BIND_SOURCES, null, null);
                    if (!report.bindingSources()) onClose();
                }).bounds(x + 114, 48, 150, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.binding_tip"))).build());
        RowList l = newList(LIST_TOP);
        for (TerritoryPayloads.JobRow job : report.jobs()) l.add(new JobEntry(job));
    }

    // ---- buildings ----

    private void initBuildings() {
        RowList l = newList(60);
        for (TerritoryPayloads.BuildingRow b : report.buildings()) l.add(new BuildingEntry(b));
    }

    // ---- maids ----

    private void initMaids() {
        RowList l = newList(60, 46);
        for (TerritoryPayloads.MaidRow m : report.maids()) l.add(new MaidEntry(m));
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) PacketDistributor.sendToServer(new TerritoryPayloads.RequestTerritoryReport(report.id(), false));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.territory.heading", report.ownerName(),
                report.level(), report.maxLevel()), width / 2, 8, 0xFFFFFF);
        switch (tab) {
            case OVERVIEW -> renderOverview(graphics);
            case CONSTRUCTION -> {
                if (report.jobs().isEmpty()) {
                    graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.territory.no_jobs"), width / 2, LIST_TOP + 20, 0xAAAAAA);
                }
            }
            case BUILDINGS -> {
                if (report.buildings().isEmpty()) {
                    graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.territory.no_buildings"), width / 2, 72, 0xAAAAAA);
                }
                graphics.drawString(font, Component.translatable("screen.maidbuilder.territory.buildings_hint").withStyle(ChatFormatting.GRAY),
                        left(), 48, 0xFFFFFF, false);
            }
            case MAIDS -> {
                if (report.maids().isEmpty()) {
                    graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.territory.no_maids"), width / 2, 72, 0xAAAAAA);
                }
                graphics.drawString(font, Component.translatable("screen.maidbuilder.territory.maids_hint", report.residents())
                        .withStyle(ChatFormatting.GRAY), left(), 48, 0xFFFFFF, false);
            }
        }
    }

    // ---- text helpers ----

    static Component condition(TerritoryPayloads.ConditionRow c) {
        Component what = switch (TerritoryRules.Kind.values()[c.kind()]) {
            case PROSPERITY -> Component.translatable("screen.maidbuilder.territory.cond.prosperity", c.need());
            case GROUP -> Component.translatable("screen.maidbuilder.territory.cond.group", groupName(c.key()), c.need());
            case CATEGORY -> Component.translatable("screen.maidbuilder.territory.cond.category",
                    Component.translatableWithFallback("category.maidbuilder." + c.key(), c.key()), c.need());
        };
        return Component.literal(c.met() ? "  ✔ " : "  ✘ ").withStyle(c.met() ? ChatFormatting.GREEN : ChatFormatting.RED)
                .append(what.copy().withStyle(ChatFormatting.WHITE))
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

    private class RowList extends ContainerObjectSelectionList<Row> {
        RowList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return PANEL_WIDTH;
        }
    }

    private abstract class Row extends ContainerObjectSelectionList.Entry<Row> {
        protected final List<AbstractWidget> widgets = new ArrayList<>();

        protected Button button(Component label, int width, Runnable action) {
            Button b = Button.builder(label, btn -> action.run()).size(width, 20).build();
            widgets.add(b);
            return b;
        }

        /** Lays the buttons out right-aligned in the row. */
        protected void renderButtons(GuiGraphics g, int top, int left, int width, int mouseX, int mouseY, float partialTick) {
            int x = left + width;
            for (int i = widgets.size() - 1; i >= 0; i--) {
                AbstractWidget w = widgets.get(i);
                x -= w.getWidth();
                w.setPosition(x, top);
                w.render(g, mouseX, mouseY, partialTick);
                x -= 2;
            }
        }

        /** Lays the buttons out left-aligned on their own line. */
        protected void renderButtonsBelow(GuiGraphics g, int y, int left, int mouseX, int mouseY, float partialTick) {
            int x = left + 2;
            for (AbstractWidget w : widgets) {
                w.setPosition(x, y);
                w.render(g, mouseX, mouseY, partialTick);
                x += w.getWidth() + 3;
            }
        }

        /** A button as wide as its label. */
        protected Button fitted(Component label, Runnable action) {
            return button(label, Math.max(40, font.width(label) + 12), action);
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

    private class ItemEntry extends Row {
        private final TerritoryPayloads.ItemRow item;
        private final ItemStack stack;

        ItemEntry(TerritoryPayloads.ItemRow item) {
            this.item = item;
            this.stack = new ItemStack(item.icon());
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            g.renderItem(stack, left + 4, top + 2);
            Component name = item.key().startsWith("#") ? Component.translatable("screen.maidbuilder.territory.any_of", tagName(item.key()))
                    : stack.getHoverName();
            g.drawString(font, name, left + 26, top + 6, 0xFFFFFF, false);
            boolean enough = item.have() >= item.need();
            String count = (enough ? "✔ " : "") + Math.min(item.have(), 99999) + " / " + item.need();
            g.drawString(font, count, left + width - 4 - font.width(count), top + 6, enough ? 0x55FF55 : item.have() > 0 ? 0xFFFF55 : 0xFF5555, false);
        }
    }

    private class JobEntry extends Row {
        private final TerritoryPayloads.JobRow job;

        JobEntry(TerritoryPayloads.JobRow job) {
            this.job = job;
            button(Component.literal("▲"), 20, () -> send(TerritoryPayloads.Action.JOB_UP, job.id(), null))
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.job_up")));
            button(Component.literal("▼"), 20, () -> send(TerritoryPayloads.Action.JOB_DOWN, job.id(), null))
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.job_down")));
            button(Component.translatable("screen.maidbuilder.territory.job_materials"), 50,
                    () -> send(TerritoryPayloads.Action.JOB_MATERIALS, job.id(), null));
            button(Component.literal("✕"), 20, () -> send(TerritoryPayloads.Action.JOB_CANCEL, job.id(), null))
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.job_cancel")));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            Component name = job.template().isEmpty() ? Component.literal(job.name()) : templateName(job.template());
            MutableComponent title = Component.literal((index + 1) + ". ").append(name);
            if (job.repair()) title.append(Component.translatable("screen.maidbuilder.territory.repair_tag").withStyle(ChatFormatting.YELLOW));
            g.drawString(font, title, left + 2, top + 1, 0xFFFFFF, false);
            MutableComponent status = Component.translatable("screen.maidbuilder.territory.job_progress", job.done(), job.total(), job.maids());
            if (job.needsPlayer() > 0) {
                status.append(Component.translatable("screen.maidbuilder.territory.job_needs_player", job.needsPlayer()).withStyle(ChatFormatting.YELLOW));
            }
            if (job.suspended()) status.append(Component.translatable("screen.maidbuilder.territory.job_suspended").withStyle(ChatFormatting.RED));
            g.drawString(font, status, left + 2, top + 11, 0xAAAAAA, false);
            renderButtons(g, top, left, width, mouseX, mouseY, partialTick);
        }
    }

    private class BuildingEntry extends Row {
        private final TerritoryPayloads.BuildingRow building;

        BuildingEntry(TerritoryPayloads.BuildingRow building) {
            this.building = building;
            if (!building.working() || building.integrity() < 100) {
                Button repair = button(Component.translatable("screen.maidbuilder.territory.repair"), 60,
                        () -> send(TerritoryPayloads.Action.REPAIR, building.id(), null));
                repair.active = !building.repairQueued() && report.active();
            }
            button(Component.literal("✕"), 20, this::remove)
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
            g.drawString(font, Component.translatable("screen.maidbuilder.territory.building_at", templateName(building.template()),
                    building.center().getX(), building.center().getZ()), left + 2, top + 1, 0xFFFFFF, false);
            MutableComponent status = building.working()
                    ? Component.translatable("screen.maidbuilder.territory.working", building.integrity()).withStyle(ChatFormatting.GREEN)
                    : Component.translatable("screen.maidbuilder.territory.damaged", building.integrity()).withStyle(ChatFormatting.RED);
            if (building.isWorkplace()) {
                status.append(Component.translatable("screen.maidbuilder.territory.workers", building.maids(), building.maxMaids())
                        .withStyle(ChatFormatting.AQUA));
            }
            if (building.repairQueued()) status.append(Component.translatable("screen.maidbuilder.territory.repair_queued").withStyle(ChatFormatting.YELLOW));
            g.drawString(font, status, left + 2, top + 11, 0xFFFFFF, false);
            renderButtons(g, top, left, width, mouseX, mouseY, partialTick);
        }
    }

    private class MaidEntry extends Row {
        private final TerritoryPayloads.MaidRow maid;

        MaidEntry(TerritoryPayloads.MaidRow maid) {
            this.maid = maid;
            if (!maid.loaded()) return;
            fitted(Component.translatable(maid.builder() ? "screen.maidbuilder.territory.builder_on" : "screen.maidbuilder.territory.builder_off"),
                    () -> send(TerritoryPayloads.Action.TOGGLE_BUILDER, maid.id(), null))
                    .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.builder_tip")));
            List<TerritoryPayloads.BuildingRow> workplaces = report.buildings().stream().filter(TerritoryPayloads.BuildingRow::isWorkplace).toList();
            if (!workplaces.isEmpty()) {
                fitted(Component.translatable("screen.maidbuilder.territory.assign"),
                        () -> minecraft.setScreen(new WorkplacePickerScreen(TerritoryScreen.this, report, maid)))
                        .setTooltip(Tooltip.create(Component.translatable("screen.maidbuilder.territory.assign_tip", buildingLabel(maid.building().orElse(null)))));
            }
            if (maid.paused()) {
                Button deposit = fitted(Component.translatable("screen.maidbuilder.territory.deposit"),
                        () -> send(TerritoryPayloads.Action.DEPOSIT, maid.id(), null));
                deposit.active = report.hasWarehouse() && !maid.depositing();
                deposit.setTooltip(Tooltip.create(Component.translatable(report.hasWarehouse()
                        ? "screen.maidbuilder.territory.deposit_tip" : "screen.maidbuilder.territory.deposit_no_warehouse")));
                fitted(Component.translatable("screen.maidbuilder.territory.resume"), () -> send(TerritoryPayloads.Action.RESUME, maid.id(), null));
            } else if (maid.building().isPresent()) {
                Button deposit = fitted(Component.translatable("screen.maidbuilder.territory.deposit"),
                        () -> send(TerritoryPayloads.Action.DEPOSIT, maid.id(), null));
                deposit.active = report.hasWarehouse() && !maid.depositing();
                deposit.setTooltip(Tooltip.create(Component.translatable(report.hasWarehouse()
                        ? "screen.maidbuilder.territory.deposit_tip" : "screen.maidbuilder.territory.deposit_no_warehouse")));
            }
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            if (!maid.loaded()) {
                g.drawString(font, Component.translatable("screen.maidbuilder.territory.maid_unloaded").withStyle(ChatFormatting.GRAY),
                        left + 2, top + 6, 0xFFFFFF, false);
                return;
            }
            MutableComponent title = Component.literal(maid.name());
            if (maid.home()) title.append(Component.translatable("screen.maidbuilder.territory.home").withStyle(ChatFormatting.GREEN));
            g.drawString(font, title, left + 2, top + 1, 0xFFFFFF, false);
            MutableComponent status;
            if (maid.depositing()) {
                status = Component.translatable("screen.maidbuilder.territory.depositing").withStyle(ChatFormatting.AQUA);
            } else if (maid.paused()) {
                status = Component.translatable("screen.maidbuilder.territory.paused").withStyle(ChatFormatting.RED);
            } else if (maid.builder()) {
                status = Component.translatable("screen.maidbuilder.territory.is_builder").withStyle(ChatFormatting.GOLD);
            } else if (maid.building().isPresent()) {
                status = Component.translatable("screen.maidbuilder.territory.works_at", buildingLabel(maid.building().get()))
                        .withStyle(ChatFormatting.AQUA);
            } else {
                status = Component.translatableWithFallback("task." + maid.task().replace(':', '.'), maid.task()).copy()
                        .withStyle(ChatFormatting.GRAY);
            }
            status.append(Component.literal("  "));
            status.append(Component.translatable("screen.maidbuilder.territory.backpack", maid.used(), maid.slots())
                    .withStyle(maid.full() ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
            g.drawString(font, status, left + 2, top + 11, 0xFFFFFF, false);
            renderButtonsBelow(g, top + 22, left, mouseX, mouseY, partialTick);
        }
    }
}
