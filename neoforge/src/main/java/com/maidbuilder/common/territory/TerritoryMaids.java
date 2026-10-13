package com.maidbuilder.common.territory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.common.maid.BuilderMaidData;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.Optional;

/** The maids the territory screen lists, and what it shows about them. */
public final class TerritoryMaids {
    private TerritoryMaids() {
    }

    /** Loaded maids of the territory's owner that are in it, live in it, build for it or work at one of its buildings. */
    public static List<? extends EntityMaid> of(ServerLevel level, Territory territory) {
        return level.getEntities(EntityTypeTest.forClass(EntityMaid.class), maid -> territory.owner().equals(maid.getOwnerUUID())
                && (territory.contains(maid.blockPosition()) || territory.residents().containsKey(maid.getUUID())
                || worksOn(level, territory, maid) || WorkplaceActions.buildingOf(territory, maid) != null));
    }

    /** Bound to a job of the territory's queue. */
    private static boolean worksOn(ServerLevel level, Territory territory, EntityMaid maid) {
        java.util.UUID job = BuilderMaidData.jobOf(maid);
        return job != null && territory.jobQueue().contains(job);
    }

    public static TerritoryPayloads.MaidRow row(Territory territory, EntityMaid maid) {
        IItemHandler backpack = maid.getAvailableBackpackInv();
        int used = 0;
        for (int i = 0; i < backpack.getSlots(); i++) if (!backpack.getStackInSlot(i).isEmpty()) used++;
        RegisteredBuilding building = WorkplaceActions.buildingOf(territory, maid);
        return new TerritoryPayloads.MaidRow(maid.getUUID(), maid.getName().getString(), true, maid.isHomeModeEnable(),
                maid.getTask().getUid().toString(), maid.getTask().getUid().equals(com.maidbuilder.common.maid.TaskBuilder.UID),
                Optional.ofNullable(building).map(RegisteredBuilding::id), used, backpack.getSlots(),
                WorkplaceActions.isPaused(maid), WorkplaceActions.isDepositing(maid));
    }
}
