package com.maidbuilder.client.territory;

import com.maidbuilder.common.territory.TemplateDef;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * Picks where a maid works: every building of the territory a maid can work at (with its task,
 * state and how many maids work there), or none. Full and damaged buildings cannot be picked.
 */
public class WorkplacePickerScreen extends Screen {
    private final TerritoryScreen parent;
    private final TerritoryPayloads.TerritoryReport report;
    private final TerritoryPayloads.MaidRow maid;

    public WorkplacePickerScreen(TerritoryScreen parent, TerritoryPayloads.TerritoryReport report, TerritoryPayloads.MaidRow maid) {
        super(Component.translatable("screen.maidbuilder.workplace.title", maid.name()));
        this.parent = parent;
        this.report = report;
        this.maid = maid;
    }

    @Override
    protected void init() {
        Choices list = new Choices(minecraft, width, height - 32 - 30, 30, 30);
        list.add(null);
        for (TerritoryPayloads.BuildingRow b : report.buildings()) if (b.isWorkplace()) list.add(b);
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isCurrent(@Nullable TerritoryPayloads.BuildingRow b) {
        return b == null ? maid.building().isEmpty() : maid.building().map(b.id()::equals).orElse(false);
    }

    private static boolean selectable(TerritoryPayloads.BuildingRow b, boolean current) {
        return b.working() && (current || b.maids() < b.maxMaids());
    }

    private void pick(@Nullable TerritoryPayloads.BuildingRow b) {
        if (isCurrent(b)) {
            onClose();
            return;
        }
        if (b == null) parent.send(TerritoryPayloads.Action.UNASSIGN, maid.id(), null);
        else if (selectable(b, false)) parent.send(TerritoryPayloads.Action.ASSIGN, maid.id(), b.id());
        else return;
        onClose();
    }

    /** The building's work task name, from the template catalog. */
    private static Component taskName(TerritoryPayloads.BuildingRow b) {
        ResourceLocation id = ResourceLocation.tryParse(b.template());
        var info = id == null ? null : ClientTerritories.template(id);
        if (info == null || info.def().workplace().isEmpty()) return Component.literal("?");
        ResourceLocation task = info.def().workplace().get().task();
        return Component.translatableWithFallback("task." + task.getNamespace() + "." + task.getPath(), task.toString());
    }

    private static ItemStack icon(TerritoryPayloads.BuildingRow b) {
        ResourceLocation id = ResourceLocation.tryParse(b.template());
        var info = id == null ? null : ClientTerritories.template(id);
        TemplateDef def = info == null ? null : info.def();
        return def == null ? new ItemStack(Items.BRICKS) : new ItemStack(BuiltInRegistries.ITEM.get(def.icon()));
    }

    private class Choices extends ObjectSelectionList<Choices.Entry> {
        Choices(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void add(@Nullable TerritoryPayloads.BuildingRow b) {
            addEntry(new Entry(b));
        }

        @Override
        public int getRowWidth() {
            return Math.min(320, width - 24);
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            @Nullable
            private final TerritoryPayloads.BuildingRow building;
            private final ItemStack icon;

            Entry(@Nullable TerritoryPayloads.BuildingRow building) {
                this.building = building;
                this.icon = building == null ? new ItemStack(Items.BARRIER) : WorkplacePickerScreen.icon(building);
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                boolean current = isCurrent(building);
                boolean usable = building == null || selectable(building, current);
                if (hovering && usable) g.fill(left, top - 2, left + width, top + height - 2, 0x30FFFFFF);
                g.renderItem(icon, left + 4, top + 3);
                MutableComponent name = building == null
                        ? Component.translatable("screen.maidbuilder.workplace.none")
                        : Component.translatable("screen.maidbuilder.territory.building_at", TerritoryScreen.templateName(building.template()),
                        building.center().getX(), building.center().getZ()).copy();
                if (current) name.append(Component.translatable("screen.maidbuilder.workplace.current").withStyle(ChatFormatting.GREEN));
                g.drawString(font, name, left + 26, top + 1, usable ? 0xFFFFFF : 0x808080, false);
                if (building != null) {
                    MutableComponent info = Component.translatable("screen.maidbuilder.workplace.task", taskName(building)).withStyle(ChatFormatting.GRAY)
                            .append(Component.translatable("screen.maidbuilder.territory.workers", building.maids(), building.maxMaids())
                                    .withStyle(building.maids() >= building.maxMaids() && !current ? ChatFormatting.RED : ChatFormatting.AQUA));
                    if (!building.working()) {
                        info.append(Component.translatable("screen.maidbuilder.workplace.damaged").withStyle(ChatFormatting.RED));
                    }
                    g.drawString(font, info, left + 26, top + 12, 0xFFFFFF, false);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                pick(building);
                return true;
            }

            @Override
            public Component getNarration() {
                return building == null ? Component.translatable("screen.maidbuilder.workplace.none")
                        : TerritoryScreen.templateName(building.template());
            }
        }
    }
}
