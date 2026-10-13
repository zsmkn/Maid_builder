package com.maidbuilder.client.territory;

import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** The client's copy of the territory list and the template catalog. */
public final class ClientTerritories {
    private static List<TerritoryPayloads.TerritoryInfo> territories = List.of();
    private static List<TerritoryPayloads.TemplateInfo> templates = List.of();

    private ClientTerritories() {
    }

    public static void set(TerritoryPayloads.TerritoryList list) {
        territories = List.copyOf(list.territories());
    }

    public static void setTemplates(TerritoryPayloads.TemplateList list) {
        List<TerritoryPayloads.TemplateInfo> sorted = new ArrayList<>(list.templates());
        sorted.sort(Comparator.<TerritoryPayloads.TemplateInfo>comparingInt(t -> t.def().requiredLevel())
                .thenComparingInt(t -> t.def().order())
                .thenComparing(t -> t.def().id().toString()));
        templates = List.copyOf(sorted);
    }

    public static List<TerritoryPayloads.TerritoryInfo> all() {
        return territories;
    }

    public static List<TerritoryPayloads.TemplateInfo> templates() {
        return templates;
    }

    @Nullable
    public static TerritoryPayloads.TemplateInfo template(ResourceLocation id) {
        for (TerritoryPayloads.TemplateInfo t : templates) if (t.def().id().equals(id)) return t;
        return null;
    }

    @Nullable
    public static TerritoryPayloads.TerritoryInfo get(UUID id) {
        for (TerritoryPayloads.TerritoryInfo t : territories) if (t.id().equals(id)) return t;
        return null;
    }

    /** The territory containing the column of {@code pos} in the player's dimension. */
    @Nullable
    public static TerritoryPayloads.TerritoryInfo at(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        ResourceLocation dim = mc.level.dimension().location();
        for (TerritoryPayloads.TerritoryInfo t : territories) {
            if (t.dimension().equals(dim) && t.contains(pos.getX(), pos.getZ())) return t;
        }
        return null;
    }

    /** The active territory of the local player that contains {@code pos}. */
    @Nullable
    public static TerritoryPayloads.TerritoryInfo ownAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        TerritoryPayloads.TerritoryInfo t = at(pos);
        if (t == null || mc.player == null || !t.active() || !t.owner().equals(mc.player.getUUID())) return null;
        return t;
    }

    public static void onReport(TerritoryPayloads.TerritoryReport report) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TerritoryScreen screen && screen.territory().equals(report.id())) {
            screen.update(report);
        } else if (report.open() && mc.player != null) {
            mc.setScreen(new TerritoryScreen(report));
        }
    }

    public static void clear() {
        territories = List.of();
    }
}
