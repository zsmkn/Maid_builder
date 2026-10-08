package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : WandKeys.ALL) event.register(key);
        event.register(WandKeys.MATERIALS);
        for (KeyMapping key : com.maidbuilder.client.territory.TerritoryKeys.ALL) event.register(key);
    }

    @SubscribeEvent
    public static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(MaidBuilder.id("wand_hud"), WandHud::render);
        event.registerAboveAll(MaidBuilder.id("quill_hud"), QuillClient::renderHud);
        event.registerAboveAll(MaidBuilder.id("build_mode_hud"), com.maidbuilder.client.territory.BuildModeHud::render);
    }
}
