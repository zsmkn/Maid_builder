package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.core.territory.LevelDef;
import com.maidbuilder.core.territory.LevelTable;
import com.maidbuilder.core.territory.TerritoryRules;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds and sends the territory screen's data. */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TerritoryReports {
    /** A player gets at most one refresh per this many ticks (the screen asks once a second). */
    private static final int MIN_INTERVAL_TICKS = 10;
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();

    private TerritoryReports() {
    }

    /** The player used the flag at {@code pos}. */
    public static void openAtFlag(ServerPlayer player, BlockPos pos) {
        Territory territory = TerritoryManager.data(player.server).atFlag(player.level().dimension(), pos);
        if (territory == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.none"), true);
            return;
        }
        if (!TerritoryManager.mayManage(player, territory)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.not_owner", territory.ownerName()), true);
            return;
        }
        send(player, territory, true);
    }

    public static void onRequest(ServerPlayer player, UUID id, boolean open) {
        Territory territory = TerritoryManager.data(player.server).get(id);
        if (territory == null || !TerritoryManager.mayManage(player, territory)) return;
        long now = player.server.getTickCount();
        Long last = LAST_SENT.get(player.getUUID());
        if (!open && last != null && now - last < MIN_INTERVAL_TICKS && now >= last) return;
        send(player, territory, open);
    }

    public static void send(ServerPlayer player, Territory territory, boolean open) {
        LAST_SENT.put(player.getUUID(), (long) player.server.getTickCount());
        PacketDistributor.sendToPlayer(player, build(player, territory, open));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    public static TerritoryPayloads.TerritoryReport build(ServerPlayer player, Territory territory, boolean open) {
        LevelTable table = TerritoryLevels.table();
        LevelDef current = table.get(territory.level());
        LevelDef next = table.next(territory.level());
        var buildings = territory.builtBuildings();
        int residents = territory.residentCount();
        int buildingPoints = TerritoryRules.buildingPoints(buildings);
        int residentPoints = TerritoryRules.residentPoints(residents, current);
        int prosperity = buildingPoints + residentPoints;

        List<TerritoryPayloads.ConditionRow> conditions = new ArrayList<>();
        if (next != null) {
            for (TerritoryRules.Condition c : TerritoryRules.upgradeConditions(next, prosperity, buildings)) {
                conditions.add(new TerritoryPayloads.ConditionRow(c.kind().ordinal(), c.key(), c.have(), c.need()));
            }
        }

        List<String> bonuses = new ArrayList<>();
        for (LevelDef def : table.all()) if (def.level() <= territory.level()) bonuses.addAll(def.bonuses());
        List<String> nextBonuses = next == null ? List.of() : List.copyOf(next.bonuses());

        return new TerritoryPayloads.TerritoryReport(open, territory.id(), territory.ownerName(), territory.flagPos(),
                territory.level(), table.maxLevel(), current.radius(), next == null ? -1 : next.radius(), territory.isActive(),
                TerritoryManager.data(player.server).ownedBy(territory.owner()).size(), TerritoryManager.maxFlags(),
                prosperity, buildingPoints, residentPoints, residents, current.residentCap(),
                conditions, TerritoryUpgrades.itemRows(player, territory), TerritoryUpgrades.canUpgrade(player, territory),
                jobRows(player.server, territory), buildingRows(player.server, territory), maidRows(player.server, territory),
                territory.materialSources().size(), TerritoryActions.isBinding(player, territory), WorkplaceActions.hasWarehouse(territory),
                bonuses, nextBonuses);
    }

    private static List<TerritoryPayloads.BuildingRow> buildingRows(MinecraftServer server, Territory territory) {
        List<TerritoryPayloads.BuildingRow> rows = new ArrayList<>();
        for (RegisteredBuilding b : territory.buildings()) {
            TemplateDef def = TemplateRegistry.get(b.templateId());
            int maxMaids = def == null ? 0 : def.workplace().map(TemplateDef.Workplace::maxMaids).orElse(0);
            rows.add(new TerritoryPayloads.BuildingRow(b.id(), b.templateId(), b.isWorking(), b.integrity(), b.assignedMaids().size(),
                    maxMaids, TerritoryUpgrades.repairQueued(server, territory, b.id()), b.center()));
        }
        return rows;
    }

    private static List<TerritoryPayloads.JobRow> jobRows(MinecraftServer server, Territory territory) {
        List<TerritoryPayloads.JobRow> rows = new ArrayList<>();
        long now = server.overworld().getGameTime();
        for (UUID id : territory.jobQueue()) {
            BuildJob job = BuildJobManager.loaded(server, id).orElse(null);
            if (job == null) continue;
            rows.add(new TerritoryPayloads.JobRow(job.id(), job.schematicName(), job.templateId() == null ? "" : job.templateId(),
                    job.count(BuildJob.DONE), job.size(), job.count(BuildJob.NEEDS_PLAYER), job.activeWorkers(now),
                    job.isSuspended(), job.buildingId() != null));
        }
        return rows;
    }

    /** The owner's loaded maids that live or work in the territory, plus residents that are not loaded right now. */
    private static List<TerritoryPayloads.MaidRow> maidRows(MinecraftServer server, Territory territory) {
        List<TerritoryPayloads.MaidRow> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        ServerLevel level = server.getLevel(territory.dimension());
        if (level != null) {
            for (EntityMaid maid : TerritoryMaids.of(level, territory)) {
                seen.add(maid.getUUID());
                rows.add(TerritoryMaids.row(territory, maid));
            }
        }
        for (UUID resident : territory.residents().keySet()) {
            if (seen.add(resident)) {
                rows.add(new TerritoryPayloads.MaidRow(resident, "", false, true, "", false, Optional.empty(), 0, 0, false, false));
            }
        }
        return rows;
    }
}
