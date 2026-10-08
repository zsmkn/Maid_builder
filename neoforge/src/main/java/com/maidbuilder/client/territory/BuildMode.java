package com.maidbuilder.client.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.ClientSchematics;
import com.maidbuilder.client.WandKeys;
import com.maidbuilder.common.Convert;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.schematic.SchematicReader;
import com.maidbuilder.core.territory.TerritoryRules;
import com.maidbuilder.core.transform.Placement;
import com.maidbuilder.item.WandPlacement;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Client state of build mode: placing a template inside one's own territory. The template follows
 * the crosshair (its footprint centred on the block looked at, its bottom on that block's top),
 * R / M / PgUp / PgDn rotate, mirror and raise it, right-click queues it, left-click leaves.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT)
public final class BuildMode {
    /** File name prefix of template placements given to the preview. */
    public static final String FILE_PREFIX = "template:";
    private static final double PICK_RANGE = 48;

    @Nullable
    private static UUID territory;
    @Nullable
    private static TerritoryPayloads.TemplateInfo template;
    @Nullable
    private static ClientSchematics.Loaded loaded;
    private static Rotation rotation = Rotation.NONE;
    private static Mirror mirror = Mirror.NONE;
    private static int yOffset;
    @Nullable
    private static BlockPos origin;
    @Nullable
    private static Component problem;
    private static boolean waitingForServer;

    private BuildMode() {
    }

    public static boolean active() {
        return template != null;
    }

    @Nullable
    public static TerritoryPayloads.TemplateInfo template() {
        return template;
    }

    @Nullable
    public static BlockPos origin() {
        return origin;
    }

    public static Rotation rotation() {
        return rotation;
    }

    public static Mirror mirror() {
        return mirror;
    }

    /** Why the current position cannot be built on, or null. */
    @Nullable
    public static Component problem() {
        return problem;
    }

    public static boolean loading() {
        return active() && loaded == null;
    }

    public static boolean waiting() {
        return waitingForServer;
    }

    /** Starts placing {@code info} in the territory; the schematic is requested from the server. */
    public static void start(UUID territoryId, TerritoryPayloads.TemplateInfo info) {
        boolean same = template != null && template.def().id().equals(info.def().id()) && loaded != null;
        territory = territoryId;
        template = info;
        waitingForServer = false;
        origin = null;
        problem = Component.translatable("hud.maidbuilder.build.aim");
        if (!same) {
            loaded = null;
            rotation = Rotation.NONE;
            mirror = Mirror.NONE;
            yOffset = 0;
            PacketDistributor.sendToServer(new TerritoryPayloads.RequestTemplate(info.def().id()));
        }
    }

    public static void stop() {
        territory = null;
        template = null;
        loaded = null;
        origin = null;
        problem = null;
        waitingForServer = false;
    }

    @Nullable
    public static UUID territory() {
        return territory;
    }

    public static void onStructure(TerritoryPayloads.TemplateStructure msg) {
        if (template == null || !template.def().id().equals(msg.id())) return;
        try {
            Schematic schematic = new SchematicReader(256L * 256 * 256, 64L * 1024 * 1024).read(new ByteArrayInputStream(msg.bytes()));
            loaded = new ClientSchematics.Loaded(FILE_PREFIX + msg.id(), msg.sha1(), msg.bytes(), schematic, 0);
        } catch (IOException e) {
            MaidBuilder.LOGGER.error("Cannot read template {}", msg.id(), e);
            stop();
        }
    }

    public static void onPlaceResult(TerritoryPayloads.PlaceResult msg) {
        waitingForServer = false;
        if (msg.success()) stop();
    }

    /** The schematic of the template being placed, once received. */
    @Nullable
    public static ClientSchematics.Loaded loaded() {
        return loaded;
    }

    /** What the preview should show, or null while nothing can be shown. */
    @Nullable
    public static WandPlacement placement() {
        if (loaded == null || origin == null) return null;
        return new WandPlacement(loaded.file(), loaded.sha1(), origin, rotation, mirror);
    }

    /** Follows the crosshair and re-checks the placement; leaves build mode outside one's own territory. */
    public static void tick(Minecraft mc) {
        if (template == null) return;
        LocalPlayer player = mc.player;
        TerritoryPayloads.TerritoryInfo info = territory == null ? null : ClientTerritories.get(territory);
        if (player == null || info == null || !info.active() || !mc.level.dimension().location().equals(info.dimension())) {
            stop();
            return;
        }
        if (loaded == null) return;
        HitResult hit = player.pick(PICK_RANGE, 1f, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos target = block.getDirection() == Direction.UP ? block.getBlockPos().above() : block.getBlockPos().relative(block.getDirection());
            origin = originFor(loaded.schematic(), target);
        }
        problem = origin == null ? Component.translatable("hud.maidbuilder.build.aim") : check(info);
    }

    /** Origin that centres the template's footprint on {@code target} with its bottom at the target's height. */
    private static BlockPos originFor(Schematic schematic, BlockPos target) {
        Placement p = Convert.placement(BlockPos.ZERO, rotation, mirror);
        BlockPos a = Convert.toBlockPos(p.toWorld(schematic.minCorner())), b = Convert.toBlockPos(p.toWorld(schematic.maxCorner()));
        BlockPos lo = BlockPos.min(a, b), hi = BlockPos.max(a, b);
        return new BlockPos(target.getX() - Math.floorDiv(lo.getX() + hi.getX(), 2), target.getY() - lo.getY() + yOffset,
                target.getZ() - Math.floorDiv(lo.getZ() + hi.getZ(), 2));
    }

    /** The world box (inclusive) the template takes at the current origin. */
    @Nullable
    public static BlockPos[] box() {
        if (loaded == null || origin == null) return null;
        Placement p = Convert.placement(origin, rotation, mirror);
        BlockPos a = Convert.toBlockPos(p.toWorld(loaded.schematic().minCorner()));
        BlockPos b = Convert.toBlockPos(p.toWorld(loaded.schematic().maxCorner()));
        return new BlockPos[]{BlockPos.min(a, b), BlockPos.max(a, b)};
    }

    /** Same checks as the server (which has the last word). */
    @Nullable
    private static Component check(TerritoryPayloads.TerritoryInfo info) {
        BlockPos[] box = box();
        if (box == null) return Component.translatable("hud.maidbuilder.build.aim");
        if (info.level() < template.def().requiredLevel()) {
            return Component.translatable("message.maidbuilder.build.locked", template.def().requiredLevel());
        }
        if (!TerritoryRules.insideBox(info.flag().getX(), info.flag().getZ(), info.radius(), box[0].getX(), box[0].getZ(),
                box[1].getX(), box[1].getZ())) {
            return Component.translatable("message.maidbuilder.build.outside");
        }
        Minecraft mc = Minecraft.getInstance();
        if (box[0].getY() < mc.level.getMinBuildHeight() || box[1].getY() >= mc.level.getMaxBuildHeight()) {
            return Component.translatable("message.maidbuilder.build.height");
        }
        BlockPos f = info.flag();
        if (f.getX() >= box[0].getX() && f.getX() <= box[1].getX() && f.getY() >= box[0].getY() && f.getY() <= box[1].getY()
                && f.getZ() >= box[0].getZ() && f.getZ() <= box[1].getZ()) {
            return Component.translatable("message.maidbuilder.build.flag");
        }
        for (TerritoryPayloads.Box other : info.boxes()) {
            if (other.intersects(box[0], box[1])) return Component.translatable("message.maidbuilder.build.overlap");
        }
        return null;
    }

    /** A wand key while placing: rotate, mirror, raise or lower. */
    public static void adjust(KeyMapping key) {
        if (key == WandKeys.ROTATE) rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        else if (key == WandKeys.MIRROR) mirror = switch (mirror) {
            case NONE -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.NONE;
        };
        else if (key == WandKeys.UP) yOffset++;
        else if (key == WandKeys.DOWN) yOffset--;
    }

    private static void confirm(LocalPlayer player) {
        if (waitingForServer || template == null || territory == null || origin == null || loaded == null) return;
        if (problem != null) {
            player.displayClientMessage(problem, true);
            return;
        }
        waitingForServer = true;
        PacketDistributor.sendToServer(new TerritoryPayloads.PlaceTemplate(territory, template.def().id(), origin, rotation, mirror));
    }

    /** While placing, the mouse buttons belong to build mode: use = queue the building, attack = leave. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (player == null) return;
        if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND) {
            confirm(player);
        } else if (event.isAttack()) {
            stop();
            player.displayClientMessage(Component.translatable("message.maidbuilder.build.left"), true);
        }
    }

    /** The build mode key: opens the template catalog inside one's own territory. */
    public static void onBuildKey(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        TerritoryPayloads.TerritoryInfo info = ClientTerritories.ownAt(player.blockPosition());
        if (info == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.build.own_territory_only"), true);
            return;
        }
        mc.setScreen(new TemplateCatalogScreen(info.id()));
    }
}
