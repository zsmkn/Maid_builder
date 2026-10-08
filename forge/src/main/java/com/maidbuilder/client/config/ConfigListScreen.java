package com.maidbuilder.client.config;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A page of settings: a toggle for each on/off setting and a text field for each number (only
 * values inside the allowed range are taken; others turn the field red). Changes apply at once, so
 * client settings such as the ghost opacity show up immediately; the file is saved when leaving.
 */
class ConfigListScreen extends Screen {
    private static final int COLOR_INVALID = 0xFF5555, COLOR_VALID = 0xE0E0E0;

    private final Screen parent;
    private final ForgeConfigSpec spec;
    private final List<ConfigEntry> entries;
    /** Why the values cannot be changed, or null if they can. */
    @Nullable
    private final Component readOnly;
    private boolean changed;

    ConfigListScreen(Screen parent, Component title, ForgeConfigSpec spec, List<ConfigEntry> entries, @Nullable Component readOnly) {
        super(title);
        this.parent = parent;
        this.spec = spec;
        this.entries = entries;
        this.readOnly = readOnly;
    }

    @Override
    protected void init() {
        EntryList list = new EntryList(minecraft, width, height, 40, height - 36, 24);
        for (ConfigEntry entry : entries) list.add(new Row(entry, control(entry)));
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private AbstractWidget control(ConfigEntry entry) {
        AbstractWidget widget;
        if (entry.isBoolean()) {
            ForgeConfigSpec.BooleanValue value = (ForgeConfigSpec.BooleanValue) entry.value();
            widget = CycleButton.onOffBuilder(value.get()).displayOnlyValue()
                    .create(0, 0, 100, 20, entry.label(), (button, on) -> {
                        value.set(on);
                        changed = true;
                    });
        } else {
            EditBox box = new EditBox(font, 0, 0, 98, 18, entry.label());
            box.setMaxLength(32);
            box.setValue(String.valueOf(entry.value().get()));
            box.setResponder(text -> {
                Object parsed = entry.parse(text);
                box.setTextColor(parsed == null ? COLOR_INVALID : COLOR_VALID);
                if (parsed != null && !parsed.equals(entry.value().get())) {
                    entry.set(parsed);
                    changed = true;
                }
            });
            widget = box;
        }
        widget.setTooltip(Tooltip.create(tooltip(entry)));
        widget.active = readOnly == null;
        if (widget instanceof EditBox box) box.setEditable(readOnly == null);
        return widget;
    }

    private static Component tooltip(ConfigEntry entry) {
        MutableComponent text = entry.tooltip().copy();
        ForgeConfigSpec.Range<?> range = entry.range();
        if (range != null) {
            text.append("\n").append(Component.translatable("maidbuilder.configuration.range",
                    String.valueOf(range.getMin()), String.valueOf(range.getMax())).withStyle(ChatFormatting.GRAY));
        }
        return text;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        if (readOnly != null) graphics.drawCenteredString(font, readOnly, width / 2, 26, 0xFFFF55);
    }

    @Override
    public void onClose() {
        if (changed) spec.save();
        minecraft.setScreen(parent);
    }

    private final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final ConfigEntry entry;
        private final AbstractWidget control;

        Row(ConfigEntry entry, AbstractWidget control) {
            this.entry = entry;
            this.control = control;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            int textY = top + (height - font.lineHeight) / 2;
            graphics.drawString(font, entry.label(), left, textY, 0xFFFFFF);
            control.setX(left + width - control.getWidth() - (control instanceof EditBox ? 1 : 0));
            control.setY(top + (height - control.getHeight()) / 2);
            control.render(graphics, mouseX, mouseY, partialTick);
            if (hovering && mouseX < control.getX()) setTooltipForNextRenderPass(font.split(tooltip(entry), 240));
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(control);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(control);
        }
    }

    private static final class EntryList extends ContainerObjectSelectionList<Row> {
        EntryList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return 310;
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + 165;
        }
    }
}
