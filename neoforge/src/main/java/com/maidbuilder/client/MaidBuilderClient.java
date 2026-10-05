package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client entry point: the client settings, and the settings screen behind the "Config" button
 * of the mod list (client and server settings; the latter only editable in single player).
 */
@Mod(value = MaidBuilder.MOD_ID, dist = Dist.CLIENT)
public final class MaidBuilderClient {
    public MaidBuilderClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, MaidBuilderClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
