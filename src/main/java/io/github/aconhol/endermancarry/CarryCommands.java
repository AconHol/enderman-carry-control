package io.github.aconhol.endermancarry;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.github.aconhol.endermancarry.CarryConfig.Action;
import static io.github.aconhol.endermancarry.CarryConfig.Mode;
import static io.github.aconhol.endermancarry.CarryConfig.Rule;

public final class CarryCommands {
    private CarryCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("endermancarry")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(CarryCommands::status)
                .then(Commands.literal("status").executes(CarryCommands::status))
                .then(Commands.literal("help").executes(CarryCommands::help))
                .then(Commands.literal("reload").executes(CarryCommands::reload))
                .then(Commands.literal("reset").executes(context -> update(context,
                        ignored -> CarryConfig.defaults(), "已恢复原版拿起和放下规则。")))
                .then(Commands.literal("check")
                        .then(Commands.argument("block", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString), builder))
                                .executes(CarryCommands::check)));
        for (Action action : Action.values()) root.then(actionNode(action));
        var command = dispatcher.register(root);
        dispatcher.register(Commands.literal("ecc")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(CarryCommands::status).redirect(command));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> actionNode(Action action) {
        return Commands.literal(action.id())
                .executes(CarryCommands::status)
                .then(Commands.literal("list").executes(CarryCommands::status))
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(Mode.values()).map(Mode::id), builder))
                                .executes(context -> {
                                    Mode mode;
                                    try { mode = Mode.parse(StringArgumentType.getString(context, "mode")); }
                                    catch (IllegalArgumentException e) { throw error(e.getMessage()); }
                                    return update(context, config -> config.withRule(action,
                                                    new Rule(mode, config.rule(action).blocks())),
                                            action.id() + " 模式设为 " + mode.id() + "；名单保留，已保存。" );
                                })))
                .then(Commands.literal("add")
                        .then(entryArgument(action, false)))
                .then(Commands.literal("remove")
                        .then(entryArgument(action, true)))
                .then(Commands.literal("clear").executes(context -> update(context,
                        config -> config.withRule(action, new Rule(config.rule(action).mode(), java.util.List.of())),
                        "已清空 " + action.id() + " 名单；模式未改变。")));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    entryArgument(Action action, boolean remove) {
        // Greedy strings preserve namespace colons, tag prefixes and nested paths.
        return Commands.argument("entry", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    Stream<String> entries = remove
                            ? EndermanCarryControl.config().current().rule(action).blocks().stream()
                            : Stream.concat(BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString),
                                    BuiltInRegistries.BLOCK.getTags().map(tag -> "#" + tag.key().location()));
                    return SharedSuggestionProvider.suggest(entries, builder);
                })
                .executes(context -> {
                    String entry;
                    try { entry = BlockRules.normalize(StringArgumentType.getString(context, "entry")); }
                    catch (IllegalArgumentException e) { throw error(e.getMessage()); }
                    return update(context, config -> {
                        Rule old = config.rule(action);
                        ArrayList<String> blocks = new ArrayList<>(old.blocks());
                        if (remove) {
                            if (!blocks.remove(entry)) throw new IllegalArgumentException("名单中没有 " + entry);
                        } else {
                            if (blocks.contains(entry)) throw new IllegalArgumentException("名单中已有 " + entry);
                            blocks.add(entry);
                        }
                        return config.withRule(action, new Rule(old.mode(), blocks));
                    }, (remove ? "已移除 " : "已添加 ") + entry + "；请用 mode 选择名单模式。" );
                });
    }

    private static int update(CommandContext<CommandSourceStack> context,
                              UnaryOperator<CarryConfig> change, String message) throws CommandSyntaxException {
        try {
            ConfigStore store = EndermanCarryControl.config();
            store.replace(change.apply(store.current()));
            context.getSource().sendSuccess(() -> Component.literal("[ECC] " + message), true);
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            throw error("修改未生效: " + e.getMessage());
        }
    }

    private static int reload(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        try {
            EndermanCarryControl.config().reload();
            context.getSource().sendSuccess(() -> Component.literal("[ECC] 配置已重新加载。"), true);
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            throw error("加载失败，继续使用之前的规则: " + e.getMessage());
        }
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CarryConfig config = EndermanCarryControl.config().current();
        for (Action action : Action.values()) {
            Rule rule = config.rule(action);
            context.getSource().sendSuccess(() -> Component.literal("[ECC] " + action.id() + ": "
                    + rule.mode().id() + " | " + (rule.blocks().isEmpty() ? "名单为空" : String.join(", ", rule.blocks()))), false);
        }
        return 1;
    }

    private static int check(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String entry;
        try { entry = BlockRules.normalize(StringArgumentType.getString(context, "block")); }
        catch (IllegalArgumentException e) { throw error(e.getMessage()); }
        if (entry.startsWith("#")) throw error("check 需要单个方块 ID");
        var state = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(entry)).defaultBlockState();
        CarryConfig config = EndermanCarryControl.config().current();
        boolean pickup = BlockRules.allows(config.pickup(), state,
                state.is(net.minecraft.tags.BlockTags.ENDERMAN_HOLDABLE), true);
        boolean place = BlockRules.allows(config.place(), state, true, false);
        context.getSource().sendSuccess(() -> Component.literal("[ECC] " + entry + " | 拿起: "
                + (pickup ? "允许" : "禁止") + " | 放下: " + (place ? "允许" : "禁止")
                + "（实际执行仍须通过原版游戏规则、随机时机与位置检查）"), false);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("[ECC] /ecc status | check <方块ID> | reload | reset\n"
                + "/ecc <pickup|place> mode <vanilla|whitelist|blacklist|all|none>\n"
                + "/ecc <pickup|place> add|remove <方块ID或#命名空间:标签> | clear | list\n"
                + "blacklist 只从原版范围排除；whitelist 可扩展拿起范围。修改立即生效并保存。"), false);
        return 1;
    }

    private static CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(Component.literal("[ECC] " + message)).create();
    }
}
