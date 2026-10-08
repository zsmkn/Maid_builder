package com.maidbuilder;

import com.maidbuilder.common.command.MaidBuilderCommand;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.network.ModNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(MaidBuilder.MOD_ID)
public final class MaidBuilder {
    public static final String MOD_ID = "maidbuilder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaidBuilder() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MaidBuilderConfig.SPEC);
        ModItems.ITEMS.register(modBus);
        ModItems.registerCreativeTab(modBus);
        modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(ModNetwork::register));

        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> MaidBuilderCommand.register(e.getDispatcher(), e.getBuildContext()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> BuildJobManager.clearRuntimeCaches());

        // Forge 1.20.1 has no client-only @Mod entry point: the client part is set up from here.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> com.maidbuilder.client.MaidBuilderClient::init);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
