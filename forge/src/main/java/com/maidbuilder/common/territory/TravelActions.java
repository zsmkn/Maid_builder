package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import com.maidbuilder.network.ModNetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Travel between trading posts: using the cartography table of a working trading post (not
 * sneaking) opens a menu of the owner's other working trading posts, in any territory and
 * dimension; a trip costs {@link MaidBuilderConfig#TRAVEL_COST_COUNT} of
 * {@link MaidBuilderConfig#TRAVEL_COST_ITEM} (free in creative mode).
 */
@Mod.EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TravelActions {
    /** How far from the trading post's box the player may be when the trip starts. */
    private static final double START_RANGE = 6;

    private TravelActions() {
    }

    /** A trading post with its territory. */
    record Post(Territory territory, RegisteredBuilding building) {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSecondaryUseActive() || !MaidBuilderConfig.TRAVEL.get()) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(Blocks.CARTOGRAPHY_TABLE)) return;
        Territory territory = TerritoryManager.data(player.server).at(player.level().dimension(), event.getPos());
        if (territory == null || !territory.isActive() || !territory.isOwnedBy(player.getUUID())) return;
        RegisteredBuilding post = ServiceBuildings.workingAt(territory, event.getPos(), TemplateDef.BONUS_TRADING_POST);
        if (post == null) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        openMenu(player, post);
    }

    static void openMenu(ServerPlayer player, RegisteredBuilding from) {
        List<TerritoryPayloads.TravelDestination> list = new ArrayList<>();
        for (Post p : posts(player.server, player.getUUID())) {
            if (p.building().id().equals(from.id())) continue;
            list.add(new TerritoryPayloads.TravelDestination(p.building().id(), p.territory().dimension().location(),
                    p.territory().flagPos(), p.building().center()));
        }
        ModNetwork.sendToPlayer(player, new TerritoryPayloads.TravelMenu(from.id(), list,
                BuiltInRegistries.ITEM.getKey(costItem()), costCount()));
    }

    /** The player's working trading posts, in active territories. */
    static List<Post> posts(MinecraftServer server, UUID owner) {
        List<Post> result = new ArrayList<>();
        for (Territory t : TerritoryManager.data(server).ownedBy(owner)) {
            if (!t.isActive()) continue;
            for (RegisteredBuilding b : t.buildings()) {
                if (b.isWorking() && TemplateDef.BONUS_TRADING_POST.equals(ServiceBuildings.bonusOf(b))) result.add(new Post(t, b));
            }
        }
        return result;
    }

    @Nullable
    private static Post post(MinecraftServer server, UUID owner, UUID building) {
        for (Post p : posts(server, owner)) if (p.building().id().equals(building)) return p;
        return null;
    }

    /**
     * Takes the player from one of their trading posts to another: where she arrives, or null
     * (with a message) if it cannot be done.
     */
    @Nullable
    public static BlockPos travel(ServerPlayer player, UUID fromId, UUID toId) {
        if (!MaidBuilderConfig.TRAVEL.get() || !player.isAlive() || fromId.equals(toId)) return null;
        MinecraftServer server = player.server;
        Post from = post(server, player.getUUID(), fromId);
        Post to = post(server, player.getUUID(), toId);
        if (from == null || to == null) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.travel.gone"));
            return null;
        }
        if (!player.level().dimension().equals(from.territory().dimension())
                || !from.building().box().inflate(START_RANGE).contains(player.position())) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.travel.too_far"));
            return null;
        }
        ServerLevel level = server.getLevel(to.territory().dimension());
        BlockPos spot = level == null ? null : ServiceBuildings.standSpot(level, to.building(), to.building().center());
        if (spot == null) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.travel.blocked"));
            return null;
        }
        Item cost = costItem();
        int count = costCount();
        if (!player.getAbilities().instabuild && count > 0) {
            if (player.getInventory().countItem(cost) < count) {
                player.sendSystemMessage(Component.translatable("message.maidbuilder.travel.cost", count, cost.getDescription()));
                return null;
            }
            player.getInventory().clearOrCountMatchingItems(s -> s.is(cost), count, player.inventoryMenu.getCraftSlots());
        }
        player.stopRiding();
        level.playSound(null, player.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
        player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(), player.getXRot());
        level.playSound(null, spot, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
        return spot;
    }

    static Item costItem() {
        ResourceLocation id = ResourceLocation.tryParse(MaidBuilderConfig.TRAVEL_COST_ITEM.get());
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == null ? Items.AIR : item;
    }

    static int costCount() {
        return costItem() == Items.AIR ? 0 : MaidBuilderConfig.TRAVEL_COST_COUNT.get();
    }
}
