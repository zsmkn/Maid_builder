package com.maidbuilder.common.command;

import com.maidbuilder.common.territory.Territory;
import com.maidbuilder.common.territory.TerritoryLevels;
import com.maidbuilder.common.territory.TerritoryManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * {@code /maidbuilder territory ...}
 * <ul>
 *   <li>{@code list}: your territories (operators: all)</li>
 *   <li>{@code info <id>}: level, radius, prosperity, queue</li>
 *   <li>{@code remove <id>}: delete a territory for good (its owner or an operator); frees a flag slot</li>
 *   <li>{@code setlevel <id> <level>}: operators only</li>
 * </ul>
 */
final class TerritoryCommand {
    private static final SimpleCommandExceptionType NOT_FOUND =
            new SimpleCommandExceptionType(Component.translatable("command.maidbuilder.territory.not_found"));
    private static final SimpleCommandExceptionType NOT_OWNER =
            new SimpleCommandExceptionType(Component.translatable("command.maidbuilder.territory.not_owner"));

    private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(TerritoryManager.data(ctx.getSource().getServer()).all().stream().map(Territory::shortId), builder);

    private TerritoryCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("territory")
                .then(Commands.literal("list").executes(TerritoryCommand::list))
                .then(Commands.literal("info").then(idArgument().executes(TerritoryCommand::info)))
                .then(Commands.literal("remove").then(idArgument().executes(TerritoryCommand::remove)))
                .then(Commands.literal("setlevel").requires(s -> s.hasPermission(2)).then(idArgument()
                        .then(Commands.argument("level", IntegerArgumentType.integer(1)).executes(TerritoryCommand::setLevel))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> idArgument() {
        return Commands.argument("territory", StringArgumentType.word()).suggests(IDS);
    }

    private static Territory territory(CommandContext<CommandSourceStack> ctx, boolean mustManage) throws CommandSyntaxException {
        String prefix = StringArgumentType.getString(ctx, "territory").toLowerCase(Locale.ROOT);
        Territory found = null;
        for (Territory t : TerritoryManager.data(ctx.getSource().getServer()).all()) {
            if (t.id().toString().startsWith(prefix)) {
                if (found != null) throw NOT_FOUND.create();
                found = t;
            }
        }
        if (found == null) throw NOT_FOUND.create();
        if (mustManage && !ctx.getSource().hasPermission(2)) {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null || !found.isOwnedBy(player.getUUID())) throw NOT_OWNER.create();
        }
        return found;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        List<Territory> shown = TerritoryManager.data(source.getServer()).all().stream()
                .filter(t -> source.hasPermission(2) || player != null && t.isOwnedBy(player.getUUID())).toList();
        if (shown.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.maidbuilder.territory.none"), false);
            return 0;
        }
        for (Territory t : shown) source.sendSuccess(() -> line(t), false);
        return shown.size();
    }

    private static Component line(Territory t) {
        return Component.translatable("command.maidbuilder.territory.line", t.shortId(), t.ownerName(), t.dimension().location().toString(),
                t.flagPos().toShortString(), t.level(), t.radius(),
                Component.translatable(t.isActive() ? "command.maidbuilder.territory.active" : "command.maidbuilder.territory.inactive"));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Territory t = territory(ctx, true);
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> line(t), false);
        source.sendSuccess(() -> Component.translatable("command.maidbuilder.territory.details", t.prosperity(), t.residentCount(),
                t.buildings().size(), t.jobQueue().size(), t.materialSources().size()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Territory t = territory(ctx, true);
        TerritoryManager.remove(ctx.getSource().getServer(), t);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.maidbuilder.territory.removed", t.shortId()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setLevel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Territory t = territory(ctx, false);
        int level = Math.min(IntegerArgumentType.getInteger(ctx, "level"), TerritoryLevels.table().maxLevel());
        TerritoryManager.setLevel(ctx.getSource().getServer(), t, level);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.maidbuilder.territory.level_set", t.shortId(), level), true);
        return Command.SINGLE_SUCCESS;
    }
}
