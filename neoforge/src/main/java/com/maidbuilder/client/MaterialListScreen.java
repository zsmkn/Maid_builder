package com.maidbuilder.client;

import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.client.ui.FlatButton;
import com.maidbuilder.client.ui.PanelList;
import com.maidbuilder.client.ui.Ui;
import com.maidbuilder.common.wand.MaterialReports;
import com.maidbuilder.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
    private static final int PAD = 8;

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

    /** Which rows are listed. */
    private enum Filter {
        MISSING, PARTIAL, ENOUGH, ALL;

        boolean shows(State state) {
            return switch (this) {
                case ALL -> true;
                case MISSING -> state == State.MISSING;
                case PARTIAL -> state == State.PARTIAL;
                case ENOUGH -> state == State.ENOUGH || state == State.DONE;
            };
        }
    }

    /** The job shown, or null for the preview of a placement not confirmed yet. */
    @Nullable
    private final UUID job;
    @Nullable
    private Payloads.MaterialReport report;
    private List<Payloads.MaterialRow> rows = List.of();
    private Sort sort = Sort.MISSING_FIRST;
    private Filter filter = Filter.ALL;
    private int ticks;

    private PanelList<Entry> list;
    private EditBox search;
    private final Map<Filter, FlatButton> filterButtons = new EnumMap<>(Filter.class);
    private int px, py, pw, ph;

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
        pw = Math.min(360, width - 8);
        ph = Math.min(Ui.MAX_HEIGHT, height - 8);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int cx = px + PAD, cw = pw - 2 * PAD;

        addRenderableWidget(new FlatButton(px + pw - 21, py + 4, 16, 16, Component.literal("✕"), FlatButton.Style.SECONDARY, b -> onClose()));
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, searchX() + 4, py + 8, 92, 10, Component.translatable("screen.maidbuilder.materials.search"));
        search.setBordered(false);
        search.setTextColor(Ui.TEXT & 0xFFFFFF);
        search.setValue(query);
        search.setHint(Component.translatable("screen.maidbuilder.materials.search").withColor(Ui.DISABLED & 0xFFFFFF));
        search.setResponder(text -> refill());
        addRenderableWidget(search);

        int y = py + Ui.HEADER + 1 + 6 + 12;
        int x = cx;
        filterButtons.clear();
        for (Filter f : Filter.values()) {
            Component label = filterLabel(f, 0);
            FlatButton b = new FlatButton(x, y, font.width(label) + 16, 16, label, FlatButton.Style.SECONDARY, btn -> {
                filter = f;
                refill();
            });
            filterButtons.put(f, addRenderableWidget(b));
            x += b.getWidth() + 3;
        }
        Component sortText = sortLabel();
        int sortWidth = Math.max(font.width(Component.translatable("screen.maidbuilder.materials.sort_short.missing")),
                font.width(Component.translatable("screen.maidbuilder.materials.sort_short.name"))) + 12;
        addRenderableWidget(new FlatButton(cx + cw - sortWidth, y, sortWidth, 16, sortText, FlatButton.Style.SECONDARY, b -> {
            sort = sort == Sort.MISSING_FIRST ? Sort.NAME : Sort.MISSING_FIRST;
            b.setMessage(sortLabel());
            refill();
        }));

        int listTop = y + 18;
        int listBottom = py + ph - 1 - 6 - 20 - 4 - (job != null ? 11 : 0);
        list = new PanelList<>(minecraft, cx, listTop, cw, listBottom - listTop, 22);
        addRenderableWidget(list);
        refill();

        Component done = Component.translatable("gui.done");
        int doneWidth = Math.max(60, font.width(done) + 16);
        addRenderableWidget(new FlatButton(cx + cw - doneWidth, py + ph - 1 - 6 - 20, doneWidth, 20, done, FlatButton.Style.SECONDARY, b -> onClose()));
    }

    private int searchX() {
        return px + pw - 25 - 100;
    }

    private Component sortLabel() {
        return Component.translatable(sort == Sort.MISSING_FIRST
                ? "screen.maidbuilder.materials.sort_short.missing" : "screen.maidbuilder.materials.sort_short.name");
    }

    private static Component filterLabel(Filter f, int count) {
        MutableComponent label = Component.translatable("screen.maidbuilder.materials.filter." + f.name().toLowerCase(Locale.ROOT), count);
        return switch (f) {
            case MISSING -> label.withColor(Ui.RED & 0xFFFFFF);
            case PARTIAL -> label.withColor(Ui.AMBER & 0xFFFFFF);
            case ENOUGH -> label.withColor(Ui.GREEN & 0xFFFFFF);
            case ALL -> label;
        };
    }

    private void refresh() {
        if (job != null) {
            PacketDistributor.sendToServer(new Payloads.RequestMaterialReport(java.util.Optional.of(job), false));
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
        int[] counts = new int[Filter.values().length];
        for (Payloads.MaterialRow row : rows) {
            State state = State.of(row);
            for (Filter f : Filter.values()) if (f.shows(state)) counts[f.ordinal()]++;
            if (!filter.shows(state)) continue;
            if (query.isEmpty() || name(row.item()).toLowerCase(Locale.ROOT).contains(query)
                    || BuiltInRegistries.ITEM.getKey(row.item()).toString().contains(query)) {
                shown.add(row);
            }
        }
        filterButtons.forEach((f, button) -> {
            button.setMessage(filterLabel(f, counts[f.ordinal()]));
            button.selected(f == filter);
        });
        Comparator<Payloads.MaterialRow> byName = Comparator.comparing(r -> name(r.item()));
        shown.sort(sort == Sort.NAME ? byName : Comparator.<Payloads.MaterialRow, State>comparing(State::of)
                .thenComparing(Comparator.comparingInt(MaterialListScreen::shortage).reversed())
                .thenComparing(byName));
        double scroll = list.getScrollAmount();
        list.clear();
        for (Payloads.MaterialRow row : shown) list.add(new Entry(row));
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
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, px, py, pw, ph);
        Ui.header(g, px, py, pw);
        Ui.box(g, searchX(), py + 4, 100, 16, Ui.WELL, Ui.LINE);
        Ui.textClipped(g, font, heading(), px + 8, py + 9, searchX() - px - 16, Ui.TEXT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = px + PAD, cw = pw - 2 * PAD;
        int y = py + Ui.HEADER + 1 + 6;

        // progress: how much is built, then who is on it
        int done, total;
        Component note;
        if (job == null) {
            GhostPreview preview = preview();
            done = preview == null ? 0 : preview.count(GhostPreview.MATCH);
            total = preview == null ? 0 : preview.total();
            note = Component.translatable("screen.maidbuilder.materials.preview_note");
        } else if (report != null) {
            done = report.done();
            total = report.total();
            MutableComponent team = Component.translatable("screen.maidbuilder.materials.team", report.maids(), report.sources());
            if (report.unloadedSources() > 0) {
                team.append(Component.translatable("screen.maidbuilder.materials.unloaded", report.unloadedSources()).withColor(Ui.AMBER & 0xFFFFFF));
            }
            note = team;
        } else {
            done = total = 0;
            note = Component.empty();
        }
        Component built = Component.translatable("screen.maidbuilder.materials.built", done, total);
        Ui.text(g, font, built, cx, y, Ui.TEXT);
        int noteWidth = Math.min(font.width(note), cw - font.width(built) - 40);
        Ui.textClipped(g, font, note, cx + cw - noteWidth, y, noteWidth, Ui.MUTED);
        int barX = cx + font.width(built) + 6, barRight = cx + cw - noteWidth - 6;
        if (barRight - barX >= 20) Ui.bar(g, barX, y + 1, barRight - barX, total <= 0 ? 0 : (double) done / total, Ui.FRAME);
        if (mouseY >= y - 1 && mouseY < y + 10 && mouseX >= cx && mouseX < cx + cw) {
            setTooltipForNextRenderPass(font.split(info(), 260));
        }

        if (list.children().isEmpty()) {
            Component empty = Component.translatable(job == null && preview() == null ? "screen.maidbuilder.materials.loading"
                    : rows.isEmpty() ? "screen.maidbuilder.materials.empty" : "screen.maidbuilder.materials.none_shown");
            g.drawString(font, empty, cx + (cw - font.width(empty)) / 2, list.getY() + 30, Ui.MUTED, false);
        }

        int footer = py + ph - 1 - 6 - 20;
        if (job != null) {
            int lx = cx, ly = footer - 4 - 9;
            lx = legend(g, lx, ly, Ui.TEXT, "screen.maidbuilder.materials.legend.player");
            lx = legend(g, lx, ly, Ui.TEAL, "screen.maidbuilder.materials.legend.containers");
            legend(g, lx, ly, Ui.PURPLE, "screen.maidbuilder.materials.legend.maids");
        }
        Ui.textClipped(g, font, summary(), cx, footer + 6, cw - 70, Ui.MUTED);
    }

    private int legend(GuiGraphics g, int x, int y, int color, String key) {
        g.fill(x, y + 1, x + 6, y + 7, color);
        Component label = Component.translatable(key);
        Ui.text(g, font, label, x + 9, y, Ui.MUTED);
        return x + 9 + font.width(label) + 8;
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
                Component.literal(String.valueOf(missing)).withColor(Ui.RED & 0xFFFFFF),
                Component.literal(String.valueOf(partial)).withColor(Ui.AMBER & 0xFFFFFF),
                Component.literal(String.valueOf(enough)).withColor(Ui.GREEN & 0xFFFFFF));
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

    private class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        private static final int BAR = 56, COUNT = 58, STATUS = 50;
        private static final int[] SOURCE_COLORS = {Ui.TEXT, Ui.TEAL, Ui.PURPLE};

        private final Payloads.MaterialRow row;
        private final ItemStack stack;
        private final State state;

        Entry(Payloads.MaterialRow row) {
            this.row = row;
            this.stack = new ItemStack(row.item());
            this.state = State.of(row);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean settled = state == State.DONE || state == State.ENOUGH;
            Ui.box(g, left, top, width, height, settled ? Ui.RAIL : Ui.CARD, hovering ? Ui.LINE_MID : settled ? 0xFF22344F : Ui.LINE_SOFT);
            g.renderItem(stack, left + 2, top + 1);
            int right = left + width - 5;

            Component status;
            int statusColor;
            switch (state) {
                case DONE -> {
                    status = Component.translatable("screen.maidbuilder.materials.done");
                    statusColor = Ui.MUTED;
                }
                case ENOUGH -> {
                    status = Component.translatable("screen.maidbuilder.materials.row_enough");
                    statusColor = Ui.GREEN;
                }
                default -> {
                    status = Component.translatable("screen.maidbuilder.materials.row_short", shortage(row));
                    statusColor = state == State.MISSING ? Ui.RED : Ui.AMBER;
                }
            }
            Ui.textRight(g, font, status, right, top + 5, statusColor);
            right -= STATUS;
            if (state != State.DONE) {
                Ui.textRight(g, font, row.available() + " / " + row.remaining(), right, top + 5,
                        state == State.PARTIAL ? Ui.AMBER : settled ? Ui.MUTED : Ui.TEXT);
                right -= COUNT;
                Ui.segments(g, right - BAR, top + 6, BAR, row.remaining(), new int[]{row.player(), row.containers(), row.maids()}, SOURCE_COLORS);
                right -= BAR + 6;
            }
            Ui.textClipped(g, font, stack.getHoverName(), left + 22, top + 5, right - left - 22, settled ? Ui.MUTED : Ui.TEXT);
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
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }
}
