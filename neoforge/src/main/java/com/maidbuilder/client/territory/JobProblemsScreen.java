package com.maidbuilder.client.territory;

import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The blocks of a queued job the maids left for the player: what goes where, what is there now
 * and why. A row (or "show all") outlines the blocks in the world; the player can also hand them
 * back to the maids or skip them.
 */
public class JobProblemsScreen extends Screen {
    @Nullable
    private final Screen parent;
    private final TerritoryPayloads.JobProblems problems;
    private long skipArmed;

    public JobProblemsScreen(@Nullable Screen parent, TerritoryPayloads.JobProblems problems) {
        super(Component.translatable("screen.maidbuilder.problems.title", jobName(problems)));
        this.parent = parent;
        this.problems = problems;
    }

    public static void open(TerritoryPayloads.JobProblems problems) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen instanceof JobProblemsScreen open ? open.parent : mc.screen;
        mc.setScreen(new JobProblemsScreen(parent, problems));
    }

    private static Component jobName(TerritoryPayloads.JobProblems problems) {
        return problems.template().isEmpty() ? Component.literal(problems.name()) : TerritoryScreen.templateName(problems.template());
    }

    @Override
    protected void init() {
        Rows list = new Rows(minecraft, width, height - 40 - 54, 40, 26);
        for (TerritoryPayloads.ProblemRow row : problems.rows()) list.add(row);
        addRenderableWidget(list);
        int y = height - 50, w = 98, x = width / 2 - (w * 4 + 6) / 2;
        boolean any = !problems.rows().isEmpty();
        Button all = addRenderableWidget(Button.builder(Component.translatable("screen.maidbuilder.problems.show_all"), b -> {
            ProblemHighlights.show(problems.dimension(), problems.rows().stream().map(TerritoryPayloads.ProblemRow::pos).toList());
            closeAll();
        }).bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("screen.maidbuilder.problems.show_tip"))).build());
        Button retry = addRenderableWidget(Button.builder(Component.translatable("screen.maidbuilder.problems.retry"), b -> {
            send(TerritoryPayloads.Action.JOB_RETRY);
            onClose();
        }).bounds(x + w + 2, y, w, 20).tooltip(Tooltip.create(Component.translatable("screen.maidbuilder.problems.retry_tip"))).build());
        Button skip = addRenderableWidget(Button.builder(Component.translatable("screen.maidbuilder.problems.skip"), b -> skip())
                .bounds(x + 2 * (w + 2), y, w, 20).tooltip(Tooltip.create(Component.translatable("screen.maidbuilder.problems.skip_tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + 3 * (w + 2), y, w, 20).build());
        all.active = retry.active = skip.active = any;
    }

    /** Two clicks within three seconds. */
    private void skip() {
        long now = Util.getMillis();
        if (now - skipArmed < 3000) {
            send(TerritoryPayloads.Action.JOB_SKIP);
            onClose();
        } else {
            skipArmed = now;
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("screen.maidbuilder.problems.skip_confirm"), true);
        }
    }

    private void send(TerritoryPayloads.Action action) {
        PacketDistributor.sendToServer(new TerritoryPayloads.TerritoryAction(problems.territory(), action, Optional.of(problems.job()), Optional.empty()));
    }

    /** Closes every screen so the player can look for the outlined blocks. */
    private void closeAll() {
        minecraft.setScreen(null);
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("screen.maidbuilder.problems.shown"), true);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        Component count = problems.total() > problems.rows().size()
                ? Component.translatable("screen.maidbuilder.problems.count_more", problems.total(), problems.rows().size())
                : Component.translatable("screen.maidbuilder.problems.count", problems.total());
        graphics.drawCenteredString(font, count.copy().withStyle(ChatFormatting.GRAY), width / 2, 24, 0xFFFFFF);
        if (problems.rows().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.problems.none").withStyle(ChatFormatting.GREEN),
                    width / 2, height / 2 - 20, 0xFFFFFF);
        }
        graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.problems.hint").withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height - 24, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static ChatFormatting color(BuildJob.Problem problem) {
        return switch (problem) {
            case UNBREAKABLE, PROTECTED -> ChatFormatting.RED;
            case OBSTRUCTED, NO_SUPPORT -> ChatFormatting.GOLD;
            case UNREACHABLE -> ChatFormatting.YELLOW;
            case UNKNOWN -> ChatFormatting.GRAY;
        };
    }

    private static Component blockName(BlockState state) {
        return state.getBlock().getName();
    }

    private class Rows extends ObjectSelectionList<Rows.Entry> {
        Rows(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void add(TerritoryPayloads.ProblemRow row) {
            addEntry(new Entry(row));
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, width - 24);
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final TerritoryPayloads.ProblemRow row;
            private final ItemStack icon;
            private final boolean clear;

            Entry(TerritoryPayloads.ProblemRow row) {
                this.row = row;
                this.clear = row.target().isAir();
                ItemStack stack = new ItemStack((clear ? row.current() : row.target()).getBlock().asItem());
                this.icon = stack.isEmpty() ? new ItemStack(Items.BARRIER) : stack;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                if (hovering) g.fill(left, top - 2, left + width, top + height - 2, 0x30FFFFFF);
                g.renderItem(icon, left + 4, top + 3);
                BlockPos p = row.pos();
                MutableComponent what = clear
                        ? Component.translatable("screen.maidbuilder.problems.clear", blockName(row.current()))
                        : blockName(row.target()).copy();
                if (!clear && !row.current().isAir() && !row.current().canBeReplaced()) {
                    what.append(Component.translatable("screen.maidbuilder.problems.now", blockName(row.current())).withStyle(ChatFormatting.GRAY));
                }
                g.drawString(font, what, left + 26, top + 1, 0xFFFFFF, false);
                String where = p.getX() + ", " + p.getY() + ", " + p.getZ();
                if (minecraft.player != null) {
                    where += String.format(Locale.ROOT, "  (%dm)", (int) Math.sqrt(minecraft.player.blockPosition().distSqr(p)));
                }
                g.drawString(font, where, left + width - 4 - font.width(where), top + 1, 0xAAAAAA, false);
                Component why = Component.translatable("screen.maidbuilder.problems.reason." + row.problem().name().toLowerCase(Locale.ROOT))
                        .withStyle(color(row.problem()));
                g.drawString(font, why, left + 26, top + 12, 0xFFFFFF, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ProblemHighlights.show(problems.dimension(), List.of(row.pos()));
                closeAll();
                return true;
            }

            @Override
            public Component getNarration() {
                return clear ? Component.translatable("screen.maidbuilder.problems.clear", blockName(row.current())) : blockName(row.target());
            }
        }
    }
}
