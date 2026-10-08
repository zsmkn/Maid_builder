package com.maidbuilder.client.config;

import com.maidbuilder.MaidBuilderConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/** The groups of the server settings ("Building", "Schematics"), each opening its own page. */
class ServerSectionsScreen extends Screen {
    private final Screen parent;
    @Nullable
    private Component readOnly;

    ServerSectionsScreen(Screen parent) {
        super(Component.translatable("maidbuilder.configuration.section.maidbuilder.server.toml.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        readOnly = ConfigOverviewScreen.serverReadOnlyReason();
        int x = width / 2 - 100;
        int y = height / 4 + 24;
        for (ConfigEntry.Section section : ConfigSections.server()) {
            Component label = Component.translatable("maidbuilder.configuration.edit", section.label());
            addRenderableWidget(Button.builder(label, b -> minecraft.setScreen(new ConfigListScreen(this, section.label(),
                            MaidBuilderConfig.SPEC, section.entries(), ConfigOverviewScreen.serverReadOnlyReason())))
                    .tooltip(Tooltip.create(section.tooltip()))
                    .bounds(x, y, 200, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x, height - 28, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        if (readOnly != null) graphics.drawCenteredString(font, readOnly, width / 2, 30, 0xFFFF55);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
