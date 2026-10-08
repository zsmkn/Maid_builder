package com.maidbuilder.common.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.Convert;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.common.StateResolver;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobData;
import com.maidbuilder.common.job.BuildJobFactory;
import com.maidbuilder.item.WandPlacement;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.maid.WandInteractHandler;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.plan.BuildPlan;
import com.maidbuilder.core.plan.BuildPlanner;
import com.maidbuilder.core.plan.BuildStep;
import com.maidbuilder.core.plan.MaterialRules;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.transform.Placement;
import com.maidbuilder.init.ModItemData;
import com.maidbuilder.init.ModItems;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.TemplateMirrorArgument;
import net.minecraft.commands.arguments.TemplateRotationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code /maidbuilder ...}
 * <ul>
 *   <li>{@code list} / {@code info <file>}: schematics in {@code <game dir>/schematics}</li>
 *   <li>{@code paste <file> <pos> [rotation] [mirror]}: admin paste, no maid (plan phase 2)</li>
 *   <li>{@code job create|list|info|retry|remove|bind|wand}: build jobs for maids</li>
 *   <li>{@code save <name> <from> <to>}: capture an area into the player's schematics folder (like the Blueprint Quill)</li>
 * </ul>
 */
public final class MaidBuilderCommand {
    private static final int TOP_MATERIALS = 10;

    private static final SuggestionProvider<CommandSourceStack> FILES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(SchematicStore.listUserFiles().stream().map(MaidBuilderCommand::quoteIfNeeded), builder);
    private static final SuggestionProvider<CommandSourceStack> JOBS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(BuildJobManager.data(ctx.getSource().getServer()).all().stream().map(BuildJob::shortId), builder);

    private MaidBuilderCommand() {
    }

    @FunctionalInterface
    private interface PlacementCommand {
        int run(CommandContext<CommandSourceStack> ctx, Rotation rotation, Mirror mirror) throws CommandSyntaxException;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("maidbuilder")
                .then(Commands.literal("list").executes(MaidBuilderCommand::listFiles))
                .then(Commands.literal("info")
                        .then(Commands.argument("file", StringArgumentType.string()).suggests(FILES)
                                .executes(MaidBuilderCommand::fileInfo)))
                .then(placementArguments(Commands.literal("paste").requires(s -> s.hasPermission(2)), MaidBuilderCommand::paste))
                .then(Commands.literal("save").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(MaidBuilderCommand::save)))))
                .then(Commands.literal("job")
                        .then(placementArguments(Commands.literal("create"), MaidBuilderCommand::createJob))
                        .then(Commands.literal("list").executes(MaidBuilderCommand::listJobs))
                        .then(Commands.literal("info").then(jobArgument().executes(ctx -> {
                            sendJobSummary(ctx.getSource(), job(ctx, false));
                            return Command.SINGLE_SUCCESS;
                        })))
                        .then(Commands.literal("retry").then(jobArgument().executes(MaidBuilderCommand::retryJob)))
                        .then(Commands.literal("remove").then(jobArgument().executes(MaidBuilderCommand::removeJob)))
                        .then(Commands.literal("wand").then(jobArgument().executes(MaidBuilderCommand::giveWand)))
                        .then(Commands.literal("bind").then(jobArgument()
                                .then(Commands.argument("maids", EntityArgument.entities()).executes(MaidBuilderCommand::bindMaids))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> placementArguments(
            ArgumentBuilder<CommandSourceStack, ?> root, PlacementCommand command) {
        return root.then(Commands.argument("file", StringArgumentType.string()).suggests(FILES)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> command.run(ctx, Rotation.NONE, Mirror.NONE))
                        .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                .executes(ctx -> command.run(ctx, TemplateRotationArgument.getRotation(ctx, "rotation"), Mirror.NONE))
                                .then(Commands.argument("mirror", TemplateMirrorArgument.templateMirror())
                                        .executes(ctx -> command.run(ctx, TemplateRotationArgument.getRotation(ctx, "rotation"),
                                                TemplateMirrorArgument.getMirror(ctx, "mirror")))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> jobArgument() {
        return Commands.argument("job", StringArgumentType.word()).suggests(JOBS);
    }

    // ---- schematic files ----

    private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos from = BlockPosArgument.getLoadedBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(ctx, "to");
        boolean ok = com.maidbuilder.common.capture.CaptureActions.save(player, ctx.getSource().getLevel(),
                Convert.min(from, to), Convert.max(from, to), StringArgumentType.getString(ctx, "name"));
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    private static int listFiles(CommandContext<CommandSourceStack> ctx) {
        List<String> files = SchematicStore.listUserFiles();
        CommandSourceStack source = ctx.getSource();
        if (files.isEmpty()) {
            source.sendFailure(Component.translatable("command.maidbuilder.list.empty", SchematicStore.userDir().toString()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.maidbuilder.list.header", files.size()), false);
        for (String f : files) source.sendSuccess(() -> Component.literal(" - " + f), false);
        return files.size();
    }

    private static int fileInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "file");
        Schematic schematic = loadUserSchematic(name);
        CommandSourceStack source = ctx.getSource();
        var meta = schematic.metadata();
        IntPos size = schematic.maxCorner().subtract(schematic.minCorner()).add(1, 1, 1);
        source.sendSuccess(() -> Component.translatable("command.maidbuilder.info.header", name,
                meta.name(), meta.author(), size.x() + "x" + size.y() + "x" + size.z(),
                schematic.regions().size(), schematic.countNonAir(), schematic.minecraftDataVersion()), false);

        BuildPlan plan = BuildPlanner.plan(schematic, Placement.at(IntPos.ZERO),
                new BuildPlanner.Options(MaidBuilderConfig.PLACE_FLUIDS.get()));
        StateResolver resolver = new StateResolver(schematic.minecraftDataVersion());
        Map<Item, Integer> materials = new TreeMap<>(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()));
        for (BuildStep step : plan.steps()) {
            Item item = itemOf(resolver.resolve(step.schematicState()), step);
            if (item != Items.AIR) materials.merge(item, MaterialRules.countFor(step.schematicState()), Integer::sum);
        }
        sendMaterials(source, materials);
        if (!resolver.unknownBlocks().isEmpty()) {
            source.sendFailure(Component.translatable("command.maidbuilder.unknown_blocks", String.join(", ", resolver.unknownBlocks())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Item itemOf(BlockState state, BuildStep step) {
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            item = BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(MaterialRules.itemFor(step.schematicState())));
        }
        return item;
    }

    // ---- paste (phase 2: no maid) ----

    private static int paste(CommandContext<CommandSourceStack> ctx, Rotation rotation, Mirror mirror) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "file");
        BlockPos origin = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        Schematic schematic = loadUserSchematic(name);
        Placement placement = Convert.placement(origin, rotation, mirror);
        // An exact copy, fluids included, so results can be compared with Litematica's paste.
        BuildPlan plan = BuildPlanner.plan(schematic, placement, new BuildPlanner.Options(true));
        int max = MaidBuilderConfig.MAX_PASTE_BLOCKS.get();
        if (plan.size() > max) {
            throw error("command.maidbuilder.paste.too_big", plan.size(), max);
        }
        ServerLevel level = ctx.getSource().getLevel();
        StateResolver resolver = new StateResolver(schematic.minecraftDataVersion());
        int placed = 0;
        for (BuildStep step : plan.steps()) {
            BlockState state = resolver.resolve(step.schematicState(), placement);
            if (state.isAir()) continue;
            BlockPlacer.placeExact(level, Convert.toBlockPos(step.worldPos()), state);
            placed++;
        }
        int total = placed;
        ctx.getSource().sendSuccess(() -> Component.translatable("command.maidbuilder.paste.done", total, name,
                origin.toShortString(), rotation.getSerializedName(), mirror.getSerializedName()), true);
        if (!resolver.unknownBlocks().isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("command.maidbuilder.unknown_blocks", String.join(", ", resolver.unknownBlocks())));
        }
        return placed;
    }

    // ---- jobs ----

    private static int createJob(CommandContext<CommandSourceStack> ctx, Rotation rotation, Mirror mirror) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "file");
        BlockPos origin = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        MinecraftServer server = ctx.getSource().getServer();
        Path file = userFile(name);
        loadUserSchematic(name); // validates before copying
        String hash;
        try {
            hash = SchematicStore.storeCopy(server, file);
        } catch (IOException e) {
            throw error("command.maidbuilder.file_error", name, e.getMessage());
        }
        BuildJob job;
        try {
            job = BuildJobFactory.create(player, name, hash, origin, rotation, mirror);
        } catch (IOException e) {
            throw error("command.maidbuilder.file_error", name, e.getMessage());
        }
        giveLinkedWand(player, job);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.maidbuilder.job.created",
                job.shortId(), name, job.size(), origin.toShortString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listJobs(CommandContext<CommandSourceStack> ctx) {
        BuildJobData data = BuildJobManager.data(ctx.getSource().getServer());
        if (data.all().isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("command.maidbuilder.job.none"));
            return 0;
        }
        for (BuildJob job : data.all()) {
            BuildJobManager.loaded(ctx.getSource().getServer(), job.id());
            ctx.getSource().sendSuccess(() -> Component.translatable("command.maidbuilder.job.line",
                    job.shortId(), job.schematicName(), job.ownerName(), job.count(BuildJob.DONE), job.size(),
                    job.origin().toShortString(), job.dimension().location().toString()), false);
        }
        return data.all().size();
    }

    public static void sendJobSummary(CommandSourceStack source, BuildJob job) {
        source.sendSuccess(() -> Component.translatable("command.maidbuilder.job.summary",
                job.shortId(), job.schematicName(), job.origin().toShortString(), job.rotation().getSerializedName(),
                job.mirror().getSerializedName(), job.count(BuildJob.DONE), job.size(),
                job.count(BuildJob.NEEDS_PLAYER), job.count(BuildJob.FAILED)), false);
        if (!job.materialSources().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.maidbuilder.job.sources", job.materialSources().size(),
                    String.join(", ", job.materialSources().stream().map(BlockPos::toShortString).toList())), false);
        }
        if (!job.unknownBlocks().isEmpty()) {
            source.sendFailure(Component.translatable("command.maidbuilder.unknown_blocks", String.join(", ", job.unknownBlocks())));
        }
        Map<Item, Integer> remaining = job.remainingMaterials();
        if (!remaining.isEmpty()) sendMaterials(source, remaining);
    }

    private static void sendMaterials(CommandSourceStack source, Map<Item, Integer> materials) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(materials.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        source.sendSuccess(() -> Component.translatable("command.maidbuilder.materials.header", materials.size()), false);
        for (Map.Entry<Item, Integer> e : sorted.subList(0, Math.min(TOP_MATERIALS, sorted.size()))) {
            source.sendSuccess(() -> Component.literal(" - ").append(e.getKey().getDescription()).append(" x" + e.getValue()), false);
        }
        if (sorted.size() > TOP_MATERIALS) {
            source.sendSuccess(() -> Component.translatable("command.maidbuilder.materials.more", sorted.size() - TOP_MATERIALS), false);
        }
    }

    private static int retryJob(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BuildJob job = job(ctx, true);
        int n = job.retryNeedsPlayer();
        ctx.getSource().sendSuccess(() -> Component.translatable("command.maidbuilder.job.retried", n, job.shortId()), false);
        return n;
    }

    private static int removeJob(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BuildJob job = job(ctx, true);
        boolean removed = BuildJobManager.cancel(ctx.getSource().getServer(), job);
        ctx.getSource().sendSuccess(() -> Component.translatable(removed
                ? "command.maidbuilder.job.removed" : "command.maidbuilder.job.cancelling", job.shortId()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int giveWand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BuildJob job = job(ctx, true);
        giveLinkedWand(ctx.getSource().getPlayerOrException(), job);
        return Command.SINGLE_SUCCESS;
    }

    private static int bindMaids(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BuildJob job = job(ctx, true);
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int bound = 0;
        for (Entity entity : EntityArgument.getEntities(ctx, "maids")) {
            if (entity instanceof EntityMaid maid && (maid.isOwnedBy(player) || ctx.getSource().hasPermission(2))) {
                if (WandInteractHandler.bind(player, maid, job.id())) bound++;
            }
        }
        if (bound == 0) throw error("command.maidbuilder.job.no_maids");
        return bound;
    }

    // ---- helpers ----

    /** Links the held wand (or a new one) to the job; the placement lets the client draw the preview. */
    private static void giveLinkedWand(ServerPlayer player, BuildJob job) {
        ItemStack held = player.getMainHandItem();
        ItemStack wand = held.is(ModItems.BLUEPRINT_WAND.get()) ? held : new ItemStack(ModItems.BLUEPRINT_WAND.get());
        ModItemData.BUILD_JOB.set(wand, job.id());
        ModItemData.WAND_PLACEMENT.set(wand, new WandPlacement(job.schematicName(), job.schematicHash(), job.origin(), job.rotation(), job.mirror()));
        if (wand != held && !player.getInventory().add(wand)) player.drop(wand, false);
    }

    private static BuildJob job(CommandContext<CommandSourceStack> ctx, boolean requireOwner) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "job");
        BuildJob job = BuildJobManager.byIdPrefix(source.getServer(), id)
                .flatMap(j -> BuildJobManager.loaded(source.getServer(), j.id()))
                .orElseThrow(() -> error("message.maidbuilder.job.not_found", id));
        if (requireOwner && !source.hasPermission(2)) {
            ServerPlayer player = source.getPlayer();
            if (player == null || !player.getUUID().equals(job.owner())) throw error("command.maidbuilder.job.not_owner");
        }
        return job;
    }

    private static Path userFile(String name) throws CommandSyntaxException {
        try {
            return SchematicStore.resolveUserFile(name);
        } catch (IOException e) {
            throw error("command.maidbuilder.file_error", name, e.getMessage());
        }
    }

    private static Schematic loadUserSchematic(String name) throws CommandSyntaxException {
        try {
            return SchematicStore.load(userFile(name));
        } catch (IOException e) {
            throw error("command.maidbuilder.file_error", name, e.getMessage());
        }
    }

    private static CommandSyntaxException error(String key, Object... args) {
        return new SimpleCommandExceptionType(Component.translatable(key, args)).create();
    }

    private static String quoteIfNeeded(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!StringReader.isAllowedInUnquotedString(s.charAt(i))) {
                return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            }
        }
        return s;
    }
}
