package com.maidbuilder.client;

import com.maidbuilder.client.config.ConfigOverviewScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Client entry point, called from the mod constructor on the physical client only: the client
 * settings, and the settings screen behind the "Config" button of the mod list (client and server
 * settings; the latter only editable in single player).
 */
public final class MaidBuilderClient {
    private MaidBuilderClient() {
    }

    public static void init() {
        ModLoadingContext context = ModLoadingContext.get();
        context.registerConfig(ModConfig.Type.CLIENT, MaidBuilderClientConfig.SPEC);
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ConfigOverviewScreen(parent)));
    }
}
