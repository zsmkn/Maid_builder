package com.maidbuilder.client;

import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.item.CaptureArea;
import com.maidbuilder.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.maidbuilder.network.ModNetwork;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;

/** Names the area marked with a Blueprint Quill and asks the server to save it. */
public class CaptureScreen extends Screen {
    private final CaptureArea area;
    private EditBox name;
    private Button saveButton;

    public CaptureScreen(CaptureArea area) {
        super(Component.translatable("screen.maidbuilder.capture.title"));
        this.area = area;
    }

    @Override
    protected void init() {
        name = new EditBox(font, width / 2 - 100, height / 2 - 20, 200, 20, Component.translatable("screen.maidbuilder.capture.name"));
        name.setMaxLength(SchematicStore.MAX_NAME_LENGTH);
        name.setValue("blueprint");
        name.setResponder(text -> updateButton());
        addRenderableWidget(name);
        saveButton = addRenderableWidget(Button.builder(Component.translatable("screen.maidbuilder.capture.save"), b -> save())
                .bounds(width / 2 - 102, height / 2 + 30, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 + 2, height / 2 + 30, 100, 20).build());
        setInitialFocus(name);
        updateButton();
    }

    private void updateButton() {
        String n = SchematicStore.sanitizeFileName(name.getValue());
        saveButton.active = n != null;
        saveButton.setMessage(Component.translatable(n != null && exists(n)
                ? "screen.maidbuilder.capture.overwrite" : "screen.maidbuilder.capture.save"));
    }

    private static boolean exists(String sanitized) {
        return Files.exists(SchematicStore.newUserFile(sanitized));
    }

    private void save() {
        String n = SchematicStore.sanitizeFileName(name.getValue());
        if (n == null) return;
        if (exists(n)) ClientDownloads.allowOverwrite(n);
        ModNetwork.sendToServer(new Payloads.CaptureRequest(n));
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && saveButton.active) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFF);
        BlockPos size = area.size();
        graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.capture.size",
                size.getX(), size.getY(), size.getZ(), area.volume()), width / 2, height / 2 - 44, 0xAAAAAA);
        String n = SchematicStore.sanitizeFileName(name.getValue());
        Component hint = n == null
                ? Component.translatable("screen.maidbuilder.capture.invalid")
                : Component.translatable(exists(n) ? "screen.maidbuilder.capture.exists" : "screen.maidbuilder.capture.target",
                "schematics/" + n + ".litematic");
        graphics.drawCenteredString(font, hint, width / 2, height / 2 + 6, n == null ? 0xFF5555 : exists(n) ? 0xFFFF55 : 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
