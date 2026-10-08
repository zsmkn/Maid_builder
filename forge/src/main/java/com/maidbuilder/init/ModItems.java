package com.maidbuilder.init;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.item.BlueprintQuillItem;
import com.maidbuilder.item.BlueprintWandItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MaidBuilder.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MaidBuilder.MOD_ID);

    public static final RegistryObject<BlueprintWandItem> BLUEPRINT_WAND =
            ITEMS.register("blueprint_wand", () -> new BlueprintWandItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<BlueprintQuillItem> BLUEPRINT_QUILL =
            ITEMS.register("blueprint_quill", () -> new BlueprintQuillItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.maidbuilder"))
            .icon(() -> BLUEPRINT_WAND.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(BLUEPRINT_WAND.get());
                output.accept(BLUEPRINT_QUILL.get());
            })
            .build());

    public static void registerCreativeTab(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModItems() {
    }
}
