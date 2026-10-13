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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Build mode catalog: the templates of each category, locked ones greyed out with the level they
 * need. Picking one starts placing it. The "custom" tab explains placing one's own blueprints.
 */
public class TemplateCatalogScreen extends Screen {
    private static final List<String> CATEGORY_ORDER = List.of("production", "storage", "housing", "decoration");
    private static final String CUSTOM = "custom";

    private final UUID territory;
    private String category;
    private TemplateList list;

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

    private int level() {
        TerritoryPayloads.TerritoryInfo info = ClientTerritories.get(territory);
        return info == null ? 0 : info.level();
    }

    @Override
    protected void init() {
        List<String> tabs = new ArrayList<>(categories());
        tabs.add(CUSTOM);
        int tabWidth = Math.min(80, (width - 20) / tabs.size() - 2);
        int x = width / 2 - (tabs.size() * (tabWidth + 2)) / 2;
        for (String c : tabs) {
            Button b = Button.builder(Component.translatableWithFallback("category.maidbuilder." + c, c), btn -> {
                category = c;
                rebuildWidgets();
            }).bounds(x, 22, tabWidth, 20).build();
            b.active = !c.equals(category);
            addRenderableWidget(b);
            x += tabWidth + 2;
        }
        if (!CUSTOM.equals(category)) {
            list = new TemplateList(minecraft, width, height - 50 - 34, 48, 36);
            for (TerritoryPayloads.TemplateInfo t : ClientTerritories.templates()) {
                if (t.def().category().equals(category)) list.add(t);
            }
            addRenderableWidget(list);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.catalog.heading", level()), width / 2, 8, 0xFFFFFF);
        if (CUSTOM.equals(category)) {
            int y = 64;
            for (FormattedCharSequence line : font.split(Component.translatable("screen.maidbuilder.catalog.custom"), Math.min(320, width - 40))) {
                graphics.drawCenteredString(font, line, width / 2, y, 0xCCCCCC);
                y += font.lineHeight + 2;
            }
        } else if (list != null && list.children().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.catalog.empty"), width / 2, height / 2, 0xAAAAAA);
        }
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
        if (info.def().requiredLevel() > level()) return;
        BuildMode.start(territory, info);
        onClose();
    }

    private class TemplateList extends ObjectSelectionList<TemplateList.Entry> {
        TemplateList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void add(TerritoryPayloads.TemplateInfo info) {
            addEntry(new Entry(info));
        }

        @Override
        public int getRowWidth() {
            return Math.min(340, width - 24);
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final TerritoryPayloads.TemplateInfo info;
            private final ItemStack icon;

            Entry(TerritoryPayloads.TemplateInfo info) {
                this.info = info;
                this.icon = new ItemStack(BuiltInRegistries.ITEM.get(info.def().icon()));
            }

            private boolean locked() {
                return info.def().requiredLevel() > level();
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                TemplateDef def = info.def();
                if (hovering && !locked()) g.fill(left, top - 2, left + width, top + height - 2, 0x30FFFFFF);
                g.renderItem(icon, left + 6, top + 6);
                int color = locked() ? 0x808080 : 0xFFFFFF;
                g.drawString(font, Component.translatable(def.nameKey()), left + 30, top + 2, color, false);
                Component stats = Component.translatable("screen.maidbuilder.catalog.stats", info.sizeX(), info.sizeY(), info.sizeZ(),
                        info.blocks(), def.prosperity());
                g.drawString(font, stats, left + 30, top + 13, locked() ? 0x606060 : 0xAAAAAA, false);
                Component status = locked()
                        ? Component.translatable("screen.maidbuilder.catalog.locked", def.requiredLevel()).withStyle(ChatFormatting.RED)
                        : extras(def);
                g.drawString(font, status, left + 30, top + 24, 0xFFFFFF, false);
                if (hovering) setTooltipForNextRenderPass(tooltip(def));
            }

            private Component extras(TemplateDef def) {
                List<Component> parts = new ArrayList<>();
                if (!def.bonus().isEmpty()) {
                    parts.add(Component.translatableWithFallback("screen.maidbuilder.catalog.bonus." + def.bonus(), def.bonus())
                            .withStyle(ChatFormatting.GREEN));
                }
                def.workplace().ifPresent(w -> parts.add(Component.translatable("screen.maidbuilder.catalog.workplace", w.maxMaids())
                        .withStyle(ChatFormatting.AQUA)));
                if (def.storage()) parts.add(Component.translatable("screen.maidbuilder.catalog.storage").withStyle(ChatFormatting.GOLD));
                var line = Component.empty();
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
                    line.append(parts.get(i));
                }
                return line;
            }

            private List<FormattedCharSequence> tooltip(TemplateDef def) {
                List<FormattedCharSequence> lines = new ArrayList<>();
                lines.add(Component.translatable(def.nameKey()).getVisualOrderText());
                lines.addAll(font.split(Component.translatableWithFallback(def.descriptionKey(), "").withStyle(ChatFormatting.GRAY), 240));
                lines.add(Component.translatable("screen.maidbuilder.catalog.group",
                        TerritoryScreen.groupName(def.group())).withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
                if (!locked()) {
                    lines.add(Component.translatable("screen.maidbuilder.catalog.pick").withStyle(ChatFormatting.YELLOW).getVisualOrderText());
                }
                return lines;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                pick(info);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.translatable(info.def().nameKey());
            }
        }
    }
}
