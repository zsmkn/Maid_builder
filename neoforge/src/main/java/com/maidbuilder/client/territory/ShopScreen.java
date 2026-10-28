package com.maidbuilder.client.territory;

import com.maidbuilder.network.TerritoryPayloads;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The building supplies shop: goods by category with today's stock (click buys one bundle, shift-click
 * four), and a tab with what the territory's queue lacks that orders it all into the material
 * containers. Every purchase comes back as a fresh {@link TerritoryPayloads.ShopMenu}.
 */
public class ShopScreen extends Screen {
    private static final String MISSING = "";
    private static final int SHIFT_BUNDLES = 4;

    private TerritoryPayloads.ShopMenu menu;
    private String tab;
    private String search = "";
    private EditBox searchBox;
    private Goods list;

    public ShopScreen(TerritoryPayloads.ShopMenu menu) {
        super(Component.translatable("screen.maidbuilder.shop.title"));
        this.menu = menu;
        List<String> categories = categories();
        this.tab = categories.isEmpty() ? MISSING : categories.getFirst();
    }

    public static void onMenu(TerritoryPayloads.ShopMenu menu) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ShopScreen shop && shop.menu.building().equals(menu.building())) {
            shop.menu = menu;
            shop.rebuild();
        } else if (menu.open()) {
            mc.setScreen(new ShopScreen(menu));
        }
    }

    private List<String> categories() {
        Set<String> result = new LinkedHashSet<>();
        for (TerritoryPayloads.ShopRow row : menu.rows()) result.add(row.category());
        return new ArrayList<>(result);
    }

    @Override
    protected void init() {
        List<String> tabs = categories();
        tabs.add(MISSING);
        int tabWidth = Math.min(70, (width - 20) / tabs.size() - 2);
        int x = width / 2 - (tabs.size() * (tabWidth + 2)) / 2;
        for (String c : tabs) {
            Component label = c.equals(MISSING) ? Component.translatable("screen.maidbuilder.shop.missing_tab")
                    : Component.translatableWithFallback("shop.maidbuilder.category." + c, c);
            Button b = Button.builder(label, btn -> {
                tab = c;
                rebuildWidgets();
            }).bounds(x, 34, tabWidth, 20).build();
            b.active = !c.equals(tab);
            addRenderableWidget(b);
            x += tabWidth + 2;
        }
        searchBox = new EditBox(font, width / 2 - 100, 58, 200, 16, Component.translatable("screen.maidbuilder.shop.search"));
        searchBox.setValue(search);
        searchBox.setResponder(text -> {
            search = text;
            rebuild();
        });
        addRenderableWidget(searchBox);
        list = new Goods(minecraft, width, height - 80 - 34, 80, 24);
        addRenderableWidget(list);
        if (tab.equals(MISSING)) {
            Button order = Button.builder(Component.translatable("screen.maidbuilder.shop.order", menu.missingCost()), b -> orderMissing())
                    .bounds(width / 2 - 154, height - 28, 150, 20).build();
            order.active = menu.missing().stream().anyMatch(m -> m.bundles() > 0) && canPay(menu.missingCost() > 0 ? 1 : 0);
            addRenderableWidget(order);
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 + 4, height - 28, 150, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
        }
        rebuild();
    }

    private void rebuild() {
        if (list == null) return;
        list.clear();
        String q = search.toLowerCase(Locale.ROOT);
        if (tab.equals(MISSING)) {
            for (TerritoryPayloads.MissingRow row : menu.missing()) if (matches(item(row.item()), q)) list.add(new MissingEntry(row));
        } else {
            for (TerritoryPayloads.ShopRow row : menu.rows()) {
                if (row.category().equals(tab) && matches(item(row.item()), q)) list.add(new GoodsEntry(row));
            }
        }
    }

    private static boolean matches(Item item, String query) {
        return query.isEmpty() || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(query)
                || BuiltInRegistries.ITEM.getKey(item).getPath().contains(query);
    }

    private static Item item(net.minecraft.resources.ResourceLocation id) {
        return BuiltInRegistries.ITEM.get(id);
    }

    private int emeralds() {
        var player = Minecraft.getInstance().player;
        return player == null ? 0 : player.getInventory().countItem(Items.EMERALD);
    }

    private boolean canPay(int emeralds) {
        var player = Minecraft.getInstance().player;
        return player != null && (player.getAbilities().instabuild || emeralds() >= emeralds);
    }

    private void buy(TerritoryPayloads.ShopRow row, int bundles) {
        PacketDistributor.sendToServer(new TerritoryPayloads.ShopBuy(menu.building(), Optional.of(row.item()), bundles));
    }

    private void orderMissing() {
        PacketDistributor.sendToServer(new TerritoryPayloads.ShopBuy(menu.building(), Optional.empty(), 0));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.shop.header", menu.clerk()), width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.shop.wallet", emeralds()).withStyle(ChatFormatting.GREEN),
                width / 2, 20, 0xFFFFFF);
        Component hint = tab.equals(MISSING) ? Component.translatable("screen.maidbuilder.shop.missing_hint")
                : Component.translatable("screen.maidbuilder.shop.hint", SHIFT_BUNDLES);
        graphics.drawCenteredString(font, hint.copy().withStyle(ChatFormatting.GRAY), width / 2, height - 40, 0xFFFFFF);
        if (list != null && list.children().isEmpty()) {
            Component empty = Component.translatable(tab.equals(MISSING) ? "screen.maidbuilder.shop.nothing_missing" : "screen.maidbuilder.shop.empty");
            graphics.drawCenteredString(font, empty.copy().withStyle(ChatFormatting.GRAY), width / 2, height / 2, 0xFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private class Goods extends ObjectSelectionList<Goods.Entry> {
        Goods(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void clear() {
            clearEntries();
        }

        void add(Entry entry) {
            addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(340, width - 24);
        }

        abstract static class Entry extends ObjectSelectionList.Entry<Entry> {
        }
    }

    private class GoodsEntry extends Goods.Entry {
        private final TerritoryPayloads.ShopRow row;
        private final ItemStack icon;

        GoodsEntry(TerritoryPayloads.ShopRow row) {
            this.row = row;
            this.icon = new ItemStack(item(row.item()));
        }

        private boolean locked() {
            return menu.level() < row.requiredLevel();
        }

        private boolean usable() {
            return !locked() && row.left() >= row.count() && canPay(row.price());
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            if (hovering && usable()) g.fill(left, top - 2, left + width, top + height - 2, 0x30FFFFFF);
            g.renderItem(icon, left + 4, top + 2);
            g.drawString(font, icon.getHoverName(), left + 26, top + 1, usable() ? 0xFFFFFF : 0x808080, false);
            MutableComponent info;
            if (locked()) {
                info = Component.translatable("screen.maidbuilder.shop.locked", row.requiredLevel()).withStyle(ChatFormatting.RED);
            } else {
                info = Component.translatable("screen.maidbuilder.shop.offer", row.count(), row.price())
                        .withStyle(canPay(row.price()) ? ChatFormatting.GRAY : ChatFormatting.RED)
                        .append(Component.translatable("screen.maidbuilder.shop.stock", row.left())
                                .withStyle(row.left() >= row.count() ? ChatFormatting.AQUA : ChatFormatting.RED));
            }
            g.drawString(font, info, left + 26, top + 11, 0xFFFFFF, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (usable()) buy(row, hasShiftDown() ? SHIFT_BUNDLES : 1);
            return true;
        }

        @Override
        public Component getNarration() {
            return icon.getHoverName();
        }
    }

    private class MissingEntry extends Goods.Entry {
        private final TerritoryPayloads.MissingRow row;
        private final ItemStack icon;

        MissingEntry(TerritoryPayloads.MissingRow row) {
            this.row = row;
            this.icon = new ItemStack(item(row.item()));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            g.renderItem(icon, left + 4, top + 2);
            g.drawString(font, icon.getHoverName(), left + 26, top + 1, row.bundles() > 0 ? 0xFFFFFF : 0x808080, false);
            MutableComponent info = Component.translatable("screen.maidbuilder.shop.needed", row.needed()).withStyle(ChatFormatting.GRAY);
            info.append(switch (row.status()) {
                case OK -> Component.translatable("screen.maidbuilder.shop.will_order", row.bundles()).withStyle(ChatFormatting.GREEN);
                case SOLD_OUT -> Component.translatable("screen.maidbuilder.shop.partly", row.bundles()).withStyle(ChatFormatting.YELLOW);
                case LOCKED -> Component.translatable("screen.maidbuilder.shop.missing_locked").withStyle(ChatFormatting.RED);
                case NOT_SOLD -> Component.translatable("screen.maidbuilder.shop.not_sold").withStyle(ChatFormatting.RED);
            });
            g.drawString(font, info, left + 26, top + 11, 0xFFFFFF, false);
        }

        @Override
        public Component getNarration() {
            return icon.getHoverName();
        }
    }
}
