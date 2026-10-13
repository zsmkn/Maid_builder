package com.maidbuilder;

import com.maidbuilder.common.command.MaidBuilderCommand;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.territory.TemplateRegistry;
import com.maidbuilder.common.territory.TerritoryLevels;
import com.maidbuilder.init.ModBlocks;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.init.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(MaidBuilder.MOD_ID)
public final class MaidBuilder {
    public static final String MOD_ID = "maidbuilder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaidBuilder(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, MaidBuilderConfig.SPEC);
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModItems.registerCreativeTab(modBus);
        ModDataComponents.COMPONENTS.register(modBus);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> MaidBuilderCommand.register(e.getDispatcher(), e.getBuildContext()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> BuildJobManager.clearRuntimeCaches());
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> {
            e.addListener(new TerritoryLevels());
            e.addListener(new TemplateRegistry());
        });
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
