package com.maidbuilder.init;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.item.BlueprintQuillItem;
import com.maidbuilder.item.BlueprintWandItem;
import com.maidbuilder.item.TerritoryFlagItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MaidBuilder.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MaidBuilder.MOD_ID);

    public static final DeferredItem<BlueprintWandItem> BLUEPRINT_WAND =
            ITEMS.register("blueprint_wand", () -> new BlueprintWandItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlueprintQuillItem> BLUEPRINT_QUILL =
            ITEMS.register("blueprint_quill", () -> new BlueprintQuillItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<TerritoryFlagItem> TERRITORY_FLAG =
            ITEMS.register("territory_flag", () -> new TerritoryFlagItem(ModBlocks.TERRITORY_FLAG.get(), new Item.Properties().stacksTo(16)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.maidbuilder"))
            .icon(() -> BLUEPRINT_WAND.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(BLUEPRINT_WAND.get());
                output.accept(BLUEPRINT_QUILL.get());
                output.accept(TERRITORY_FLAG.get());
            })
            .build());

    public static void registerCreativeTab(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModItems() {
    }
}
