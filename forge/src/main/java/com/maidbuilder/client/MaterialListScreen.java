package com.maidbuilder.client;

import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.common.wand.MaterialReports;
import com.maidbuilder.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.maidbuilder.network.ModNetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Material list of the schematic on the held Blueprint Wand. For a linked wand the server sends
 * the job's list (counting the player, the material containers and the job's maids); before the
 * job exists it is worked out here from the preview, against the player's inventory only.
 */
public class MaterialListScreen extends Screen {
    private static final int REFRESH_TICKS = 20;
    private static final int COLOR_DONE = 0xFF808080, COLOR_ENOUGH = 0xFF55FF55, COLOR_PARTIAL = 0xFFFFFF55, COLOR_MISSING = 0xFFFF5555;

    private enum State {
        MISSING, PARTIAL, ENOUGH, DONE;

        static State of(Payloads.MaterialRow row) {
            if (row.remaining() == 0) return DONE;
            int have = row.available();
            return have >= row.remaining() ? ENOUGH : have > 0 ? PARTIAL : MISSING;
        }
    }

    private enum Sort {
        MISSING_FIRST, NAME
    }

    /** The job shown, or null for the preview of a placement not confirmed yet. */
    @Nullable
    private final UUID job;
    @Nullable
    private Payloads.MaterialReport report;
    private List<Payloads.MaterialRow> rows = List.of();
    private Sort sort = Sort.MISSING_FIRST;
    private int ticks;

    private RowList list;
    private EditBox search;

    private MaterialListScreen(@Nullable UUID job) {
        super(Component.translatable("screen.maidbuilder.materials.title"));
        this.job = job;
    }

    public static MaterialListScreen forJob(Payloads.MaterialReport report) {
        MaterialListScreen screen = new MaterialListScreen(report.job());
        screen.report = report;
        screen.rows = report.rows();
        return screen;
    }

    public static MaterialListScreen forPreview() {
        MaterialListScreen screen = new MaterialListScreen(null);
        screen.rows = previewRows();
        return screen;
    }

    public boolean showsJob(UUID id) {
        return id.equals(job);
    }

    /** A newer report for the job shown. */
    public void update(Payloads.MaterialReport report) {
        this.report = report;
        this.rows = report.rows();
        refill();
    }

    @Override
    protected void init() {
        int center = width / 2;
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, center - 155, 32, 150, 18, Component.translatable("screen.maidbuilder.materials.search"));
        search.setValue(query);
        search.setHint(Component.translatable("screen.maidbuilder.materials.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setResponder(text -> refill());
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(sortLabel(), b -> {
            sort = sort == Sort.MISSING_FIRST ? Sort.NAME : Sort.MISSING_FIRST;
            b.setMessage(sortLabel());
            refill();
        }).bounds(center, 31, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.maidbuilder.materials.refresh"), b -> refresh())
                .bounds(center + 104, 31, 51, 20).build());

        list = new RowList(minecraft, width, height, 56, height - 42, 20);
        addRenderableWidget(list);
        refill();

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(center - 50, height - 24, 100, 20).build());
    }

    private Component sortLabel() {
        return Component.translatable(sort == Sort.MISSING_FIRST
                ? "screen.maidbuilder.materials.sort.missing" : "screen.maidbuilder.materials.sort.name");
    }

    private void refresh() {
        if (job != null) {
            ModNetwork.sendToServer(new Payloads.RequestMaterialReport(false));
        } else {
            rows = previewRows();
            refill();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) refresh();
    }

    private void refill() {
        if (list == null) return;
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Payloads.MaterialRow> shown = new ArrayList<>();
        for (Payloads.MaterialRow row : rows) {
            if (query.isEmpty() || name(row.item()).toLowerCase(Locale.ROOT).contains(query)
                    || BuiltInRegistries.ITEM.getKey(row.item()).toString().contains(query)) {
                shown.add(row);
            }
        }
        Comparator<Payloads.MaterialRow> byName = Comparator.comparing(r -> name(r.item()));
        shown.sort(sort == Sort.NAME ? byName : Comparator.<Payloads.MaterialRow, State>comparing(State::of)
                .thenComparing(Comparator.comparingInt(MaterialListScreen::shortage).reversed())
                .thenComparing(byName));
        double scroll = list.getScrollAmount();
        list.replace(shown);
        list.setScrollAmount(scroll);
    }

    private static int shortage(Payloads.MaterialRow row) {
        return Math.max(0, row.remaining() - row.available());
    }

    private static String name(Item item) {
        return item.getDescription().getString();
    }

    @Nullable
    private static GhostPreview preview() {
        return ClientSelfTest.preview != null ? ClientSelfTest.preview : PreviewManager.current();
    }

    /** The preview's material list against the player's inventory. */
    private static List<Payloads.MaterialRow> previewRows() {
        GhostPreview preview = preview();
        Minecraft mc = Minecraft.getInstance();
        if (preview == null || mc.player == null) return List.of();
        Map<Item, Integer> inventory = new HashMap<>();
        MaterialReports.count(mc.player.getInventory(), inventory);
        List<Payloads.MaterialRow> result = new ArrayList<>();
        preview.materials().forEach((item, counts) ->
                result.add(new Payloads.MaterialRow(item, counts[0], counts[1], inventory.getOrDefault(item, 0), 0, 0)));
        return result;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        graphics.drawCenteredString(font, heading(), center, 6, 0xFFFFFF);
        graphics.drawCenteredString(font, info(), center, 18, 0xAAAAAA);
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(job == null && preview() == null
                    ? "screen.maidbuilder.materials.loading" : "screen.maidbuilder.materials.empty"), center, height / 2, 0xAAAAAA);
        }
        graphics.drawCenteredString(font, summary(), center, height - 36, 0xFFFFFF);
    }

    private Component heading() {
        String name = report != null ? report.name() : preview() != null ? preview().placement.file() : "";
        return name.isEmpty() ? title : Component.translatable("screen.maidbuilder.materials.heading", title, name);
    }

    private Component info() {
        if (job == null) {
            GhostPreview preview = preview();
            int built = preview == null ? 0 : preview.count(GhostPreview.MATCH);
            int total = preview == null ? 0 : preview.total();
            return Component.translatable("screen.maidbuilder.materials.preview_info", built, total);
        }
        if (report == null) return Component.empty();
        MutableComponent line = Component.translatable("screen.maidbuilder.materials.job_info", report.done(), report.total(),
                report.needsPlayer(), report.maids(), report.sources());
        if (report.unloadedSources() > 0) {
            line.append(Component.translatable("screen.maidbuilder.materials.unloaded", report.unloadedSources())
                    .withStyle(ChatFormatting.YELLOW));
        }
        return line;
    }

    private Component summary() {
        int missing = 0, partial = 0, enough = 0;
        for (Payloads.MaterialRow row : rows) {
            switch (State.of(row)) {
                case MISSING -> missing++;
                case PARTIAL -> partial++;
                case ENOUGH, DONE -> enough++;
            }
        }
        return Component.translatable("screen.maidbuilder.materials.summary",
                Component.literal(String.valueOf(missing)).withStyle(ChatFormatting.RED),
                Component.literal(String.valueOf(partial)).withStyle(ChatFormatting.YELLOW),
                Component.literal(String.valueOf(enough)).withStyle(ChatFormatting.GREEN));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!search.isFocused() && WandKeys.MATERIALS.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** "200 (3 stacks + 8)" */
    private static Component amount(int count, int stackSize) {
        if (stackSize <= 1 || count < stackSize) return Component.literal(String.valueOf(count));
        int stacks = count / stackSize, rest = count % stackSize;
        return Component.translatable(rest == 0 ? "screen.maidbuilder.materials.stacks" : "screen.maidbuilder.materials.stacks_rest",
                count, stacks, rest);
    }

    private class RowList extends ObjectSelectionList<RowList.Entry> {
        RowList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
            // keep the world visible behind the list, as on 1.21.1
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        void replace(List<Payloads.MaterialRow> shown) {
            clearEntries();
            for (Payloads.MaterialRow row : shown) addEntry(new Entry(row));
        }

        @Override
        public int getRowWidth() {
            return Math.min(320, width - 24);
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final Payloads.MaterialRow row;
            private final ItemStack stack;
            private final State state;

            Entry(Payloads.MaterialRow row) {
                this.row = row;
                this.stack = new ItemStack(row.item());
                this.state = State.of(row);
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                int color = switch (state) {
                    case DONE -> COLOR_DONE;
                    case ENOUGH -> COLOR_ENOUGH;
                    case PARTIAL -> COLOR_PARTIAL;
                    case MISSING -> COLOR_MISSING;
                };
                if (hovering) graphics.fill(left, top - 2, left + width, top + height + 2, 0x30FFFFFF);
                graphics.fill(left, top - 1, left + 2, top + height + 1, color);
                graphics.renderItem(stack, left + 5, top);
                graphics.drawString(font, stack.getHoverName(), left + 25, top + 4, 0xFFFFFF);
                Component count = switch (state) {
                    case DONE -> Component.translatable("screen.maidbuilder.materials.done");
                    case ENOUGH -> Component.literal("✔ " + row.available() + " / " + row.remaining());
                    default -> Component.literal(row.available() + " / " + row.remaining());
                };
                graphics.drawString(font, count, left + width - 4 - font.width(count), top + 4, color);
                if (state == State.DONE || state == State.ENOUGH) {
                    // Dim the whole row, item icon included (items render at z 150-ish).
                    graphics.pose().pushPose();
                    graphics.pose().translate(0, 0, 300);
                    graphics.fill(left + 2, top - 1, left + width, top + height + 1, 0x90000000);
                    graphics.pose().popPose();
                }
                if (hovering) setTooltipForNextRenderPass(tooltip());
            }

            private List<FormattedCharSequence> tooltip() {
                int stackSize = stack.getMaxStackSize();
                List<Component> lines = new ArrayList<>();
                lines.add(stack.getHoverName());
                lines.add(Component.translatable("screen.maidbuilder.materials.tip.remaining", amount(row.remaining(), stackSize))
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("screen.maidbuilder.materials.tip.total", amount(row.total(), stackSize))
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("screen.maidbuilder.materials.tip.player", row.player()).withStyle(ChatFormatting.GRAY));
                if (job != null) {
                    lines.add(Component.translatable("screen.maidbuilder.materials.tip.containers", row.containers()).withStyle(ChatFormatting.GRAY));
                    lines.add(Component.translatable("screen.maidbuilder.materials.tip.maids", row.maids()).withStyle(ChatFormatting.GRAY));
                }
                int shortage = shortage(row);
                if (shortage > 0) {
                    lines.add(Component.translatable("screen.maidbuilder.materials.tip.short", amount(shortage, stackSize))
                            .withStyle(ChatFormatting.RED));
                }
                return lines.stream().map(Component::getVisualOrderText).toList();
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.translatable("screen.maidbuilder.materials.narration", stack.getHoverName(), row.available(), row.remaining());
            }
        }
    }
}
