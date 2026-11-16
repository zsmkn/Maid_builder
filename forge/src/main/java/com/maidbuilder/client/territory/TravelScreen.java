package com.maidbuilder.client.territory;

import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.maidbuilder.network.ModNetwork;

/** The trading post's travel menu: the player's other trading posts, nearest first; a click travels there. */
public class TravelScreen extends Screen {
    private final TerritoryPayloads.TravelMenu menu;
    private final Item cost;

    public TravelScreen(TerritoryPayloads.TravelMenu menu) {
        super(Component.translatable("screen.maidbuilder.travel.title"));
        this.menu = menu;
        this.cost = BuiltInRegistries.ITEM.get(menu.costItem());
    }

    public static void open(TerritoryPayloads.TravelMenu menu) {
        Minecraft.getInstance().setScreen(new TravelScreen(menu));
    }

    private boolean free() {
        return menu.costCount() <= 0 || cost == Items.AIR;
    }

    private boolean canPay() {
        var player = Minecraft.getInstance().player;
        return free() || player == null || player.getAbilities().instabuild || player.getInventory().countItem(cost) >= menu.costCount();
    }

    @Override
    protected void init() {
        Destinations list = new Destinations(minecraft, width, height, 44, height - 30, 26);
        BlockPos here = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
        menu.destinations().stream()
                .sorted(java.util.Comparator.comparing((TerritoryPayloads.TravelDestination d) -> !isHere(d))
                        .thenComparingDouble(d -> d.pos().distSqr(here)))
                .forEach(list::add);
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    private boolean isHere(TerritoryPayloads.TravelDestination d) {
        return minecraft != null && minecraft.level != null && minecraft.level.dimension().location().equals(d.dimension());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        Component line = free() ? Component.translatable("screen.maidbuilder.travel.free")
                : Component.translatable("screen.maidbuilder.travel.cost", menu.costCount(), cost.getDescription())
                .withStyle(canPay() ? ChatFormatting.GRAY : ChatFormatting.RED);
        graphics.drawCenteredString(font, line, width / 2, 24, 0xFFFFFF);
        if (menu.destinations().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.travel.none").withStyle(ChatFormatting.GRAY),
                    width / 2, height / 2, 0xFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void go(TerritoryPayloads.TravelDestination d) {
        if (!canPay()) return;
        ModNetwork.sendToServer(new TerritoryPayloads.Travel(menu.from(), d.building()));
        onClose();
    }

    private class Destinations extends ObjectSelectionList<Destinations.Entry> {
        Destinations(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
            // keep the world visible behind the list, as on 1.21.1
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        void add(TerritoryPayloads.TravelDestination d) {
            addEntry(new Entry(d));
        }

        @Override
        public int getRowWidth() {
            return Math.min(320, width - 24);
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private static final ItemStack ICON = new ItemStack(Items.CARTOGRAPHY_TABLE);
            private final TerritoryPayloads.TravelDestination destination;

            Entry(TerritoryPayloads.TravelDestination destination) {
                this.destination = destination;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                boolean usable = canPay();
                if (hovering && usable) g.fill(left, top - 2, left + width, top + height - 2, 0x30FFFFFF);
                g.renderItem(ICON, left + 4, top + 3);
                BlockPos flag = destination.flag(), pos = destination.pos();
                g.drawString(font, Component.translatable("screen.maidbuilder.travel.territory", flag.getX(), flag.getZ()),
                        left + 26, top + 1, usable ? 0xFFFFFF : 0x808080, false);
                Component where;
                if (isHere(destination) && minecraft.player != null) {
                    int distance = (int) Math.sqrt(pos.distSqr(minecraft.player.blockPosition()));
                    where = Component.translatable("screen.maidbuilder.travel.distance", pos.getX(), pos.getY(), pos.getZ(), distance);
                } else {
                    where = Component.translatable("screen.maidbuilder.travel.dimension", pos.getX(), pos.getY(), pos.getZ(),
                            destination.dimension().toString());
                }
                g.drawString(font, where.copy().withStyle(ChatFormatting.GRAY), left + 26, top + 12, 0xFFFFFF, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                go(destination);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.translatable("screen.maidbuilder.travel.territory", destination.flag().getX(), destination.flag().getZ());
            }
        }
    }
}
