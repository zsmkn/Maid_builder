package com.maidbuilder.client.config;

import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.client.MaidBuilderClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * Settings screen behind the "Config" button of the mod list (written for 1.20.1, which has no
 * NeoForge configuration screen): client settings, and the server settings of the open world,
 * which can only be changed in single player.
 */
public class ConfigOverviewScreen extends Screen {
    @Nullable
    private final Screen parent;

    public ConfigOverviewScreen(@Nullable Screen parent) {
        super(Component.translatable("maidbuilder.configuration.title"));
        this.parent = parent;
    }

    /** Why the server settings cannot be changed here, or null if they can. */
    @Nullable
    static Component serverReadOnlyReason() {
        Minecraft mc = Minecraft.getInstance();
        if (!MaidBuilderConfig.SPEC.isLoaded()) return Component.translatable("maidbuilder.configuration.readonly.no_world");
        if (!mc.hasSingleplayerServer() || mc.getSingleplayerServer() == null || mc.getSingleplayerServer().isPublished()) {
            return Component.translatable("maidbuilder.configuration.readonly.remote");
        }
        return null;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = height / 4 + 24;
        ConfigEntry.Section client = ConfigSections.client();
        addRenderableWidget(Button.builder(client.label(), b -> minecraft.setScreen(
                        new ConfigListScreen(this, Component.translatable(client.key() + ".title"), MaidBuilderClientConfig.SPEC,
                                client.entries(), null)))
                .bounds(x, y, 200, 20).build());
        Button server = addRenderableWidget(Button.builder(
                        Component.translatable("maidbuilder.configuration.section.maidbuilder.server.toml"),
                        b -> minecraft.setScreen(new ServerSectionsScreen(this)))
                .bounds(x, y + 24, 200, 20).build());
        // Server settings exist only while a world is open; elsewhere they are shown read-only.
        if (!MaidBuilderConfig.SPEC.isLoaded()) {
            server.active = false;
            server.setTooltip(Tooltip.create(Component.translatable("maidbuilder.configuration.readonly.no_world")));
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x, height - 28, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
