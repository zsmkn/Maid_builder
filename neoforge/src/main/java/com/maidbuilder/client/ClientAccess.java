package com.maidbuilder.client;

import net.minecraft.client.Minecraft;

/** Entry points called from common code; only ever invoked on the physical client. */
public final class ClientAccess {
    private ClientAccess() {
    }

    public static void openSchematicScreen() {
        Minecraft.getInstance().setScreen(new SchematicSelectScreen());
    }

    public static void openCaptureScreen(com.maidbuilder.item.CaptureArea area) {
        Minecraft.getInstance().setScreen(new CaptureScreen(area));
    }
}
