package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : WandKeys.ALL) event.register(key);
        event.register(WandKeys.MATERIALS);
    }

    @SubscribeEvent
    public static void registerHud(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("wand_hud", (gui, graphics, partialTick, width, height) -> WandHud.render(graphics, partialTick));
        event.registerAboveAll("quill_hud", (gui, graphics, partialTick, width, height) -> QuillClient.renderHud(graphics, partialTick));
    }
}
