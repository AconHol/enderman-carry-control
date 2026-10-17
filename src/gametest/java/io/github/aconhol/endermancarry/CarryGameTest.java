package io.github.aconhol.endermancarry;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.gametest.framework.GameTestHelper;

import java.nio.file.Files;

import static io.github.aconhol.endermancarry.CarryConfig.*;

/** One sequential scenario avoids global configuration races between concurrent tests. */
public final class CarryGameTest {
    @GameTest
    public void commandsAndRealVanillaGoals(GameTestHelper helper) throws Exception {
        ConfigStore store = EndermanCarryControl.config();
        CarryConfig before = store.current();
        ServerLevel level = helper.getLevel();
        boolean griefingBefore = level.getGameRules().get(GameRules.MOB_GRIEFING);
        EnderMan enderman = helper.spawnWithNoFreeWill(EntityTypes.ENDERMAN, 3.5f, 2.0f, 3.5f);
        Goal pickup = goal("EndermanTakeBlockGoal", enderman);
        Goal place = goal("EndermanLeaveBlockGoal", enderman);
        try {
            store.replace(CarryConfig.defaults());
            level.getGameRules().set(GameRules.MOB_GRIEFING, true, level.getServer());
            var dirt = Blocks.DIRT.defaultBlockState();
            var stone = Blocks.STONE.defaultBlockState();
            require(dirt.is(BlockTags.ENDERMAN_HOLDABLE), "vanilla dirt tag must be loaded");

            attemptPickup(level, enderman, pickup, dirt);
            require(enderman.getCarriedBlock() != null && enderman.getCarriedBlock().is(Blocks.DIRT),
                    "default mode must still pick up dirt");
            enderman.setCarriedBlock(null);
            attemptPickup(level, enderman, pickup, stone);
            require(enderman.getCarriedBlock() == null, "default mode must not pick up stone");

            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            CarryCommands.register(dispatcher);
            CommandSourceStack source = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
            require(dispatcher.execute("ecc pickup mode none", source) == 1, "short alias must execute");
            attemptPickup(level, enderman, pickup, dirt);
            require(enderman.getCarriedBlock() == null, "none must block a real pickup");

            dispatcher.execute("ecc pickup add minecraft:stone", source);
            dispatcher.execute("endermancarry pickup mode whitelist", source);
            attemptPickup(level, enderman, pickup, stone);
            require(enderman.getCarriedBlock() != null && enderman.getCarriedBlock().is(Blocks.STONE),
                    "whitelist must enable stone in the actual vanilla goal");
            enderman.setCarriedBlock(null);
            attemptPickup(level, enderman, pickup, dirt);
            require(enderman.getCarriedBlock() == null, "whitelist must exclude unlisted dirt");

            dispatcher.execute("ecc pickup add #minecraft:enderman_holdable", source);
            attemptPickup(level, enderman, pickup, dirt);
            require(enderman.getCarriedBlock() != null, "tag entries with # and : must parse and match");
            enderman.setCarriedBlock(null);

            dispatcher.execute("ecc pickup clear", source);
            dispatcher.execute("ecc pickup add minecraft:dirt", source);
            dispatcher.execute("ecc pickup mode blacklist", source);
            attemptPickup(level, enderman, pickup, dirt);
            require(enderman.getCarriedBlock() == null, "blacklist must block listed dirt");
            attemptPickup(level, enderman, pickup, stone);
            require(enderman.getCarriedBlock() == null, "blacklist must preserve vanilla stone rejection");

            dispatcher.execute("ecc pickup mode all", source);
            attemptPickup(level, enderman, pickup, Blocks.CHEST.defaultBlockState());
            require(enderman.getCarriedBlock() == null, "block entity data must be protected");
            attemptPickup(level, enderman, pickup, Blocks.AIR.defaultBlockState());
            require(enderman.getCarriedBlock() == null, "air must never become a carried block");

            level.getGameRules().set(GameRules.MOB_GRIEFING, false, level.getServer());
            for (int i = 0; i < 100; i++) require(!pickup.canUse(), "mob griefing must still block pickup");
            enderman.setCarriedBlock(stone);
            for (int i = 0; i < 100; i++) require(!place.canUse(), "mob griefing must still block placement");
            level.getGameRules().set(GameRules.MOB_GRIEFING, true, level.getServer());

            dispatcher.execute("ecc place mode none", source);
            preparePlacement(level, enderman, stone);
            for (int i = 0; i < 128; i++) place.tick();
            require(enderman.getCarriedBlock() != null, "denied placement must preserve the carried block");
            dispatcher.execute("ecc place add minecraft:dirt", source);
            dispatcher.execute("ecc place mode whitelist", source);
            for (int i = 0; i < 128; i++) place.tick();
            require(enderman.getCarriedBlock() != null, "place whitelist must filter the held block");
            dispatcher.execute("ecc place add minecraft:stone", source);
            for (int i = 0; i < 128 && enderman.getCarriedBlock() != null; i++) place.tick();
            require(enderman.getCarriedBlock() == null, "allowed placement must use vanilla placement");

            dispatcher.execute("ecc reset", source);
            preparePlacement(level, enderman, stone);
            // Replace every support with bedrock: even ALL must not bypass vanilla's support rule.
            BlockPos center = enderman.blockPosition();
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 3);
            }
            dispatcher.execute("ecc place mode all", source);
            for (int i = 0; i < 128; i++) place.tick();
            require(enderman.getCarriedBlock() != null, "all must not bypass vanilla bedrock support rejection");

            CarryConfig saved = store.current();
            String validFile = Files.readString(store.path());
            Files.writeString(store.path(), "{ broken json }");
            try {
                require(commandFails(dispatcher, "ecc reload", source), "invalid reload must fail");
                require(store.current().equals(saved), "invalid reload must preserve live rules");
            } finally { Files.writeString(store.path(), validFile); }
            require(commandFails(dispatcher, "ecc pickup add test:missing_block", source), "unknown blocks must fail");
            require(commandFails(dispatcher, "ecc reset", source.withPermission(PermissionSet.NO_PERMISSIONS)),
                    "non-operators must not edit the rules");
            require(store.current().equals(saved), "failed commands must not change live rules");
            dispatcher.execute("ecc reload", source);
            require(store.current().equals(saved), "valid reload must restore the saved settings");
            dispatcher.execute("ecc check minecraft:stone", source);
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("ecc pickup add minecraft:st", source)).get();
            require(suggestions.getList().stream().anyMatch(s -> s.getText().equals("minecraft:stone")),
                    "vanilla clients must receive block ID suggestions");
            dispatcher.execute("ecc reset", source);
            require(store.current().equals(CarryConfig.defaults()), "reset must restore vanilla settings");
            helper.succeed();
        } finally {
            enderman.discard();
            level.getGameRules().set(GameRules.MOB_GRIEFING, griefingBefore, level.getServer());
            store.replace(before);
        }
    }

    private static Goal goal(String name, EnderMan enderman) throws Exception {
        var constructor = Class.forName("net.minecraft.world.entity.monster.EnderMan$" + name)
                .getDeclaredConstructor(EnderMan.class);
        constructor.setAccessible(true);
        return (Goal) constructor.newInstance(enderman);
    }

    private static void attemptPickup(ServerLevel level, EnderMan enderman, Goal pickup, BlockState block) {
        BlockPos center = enderman.blockPosition();
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 3; y++) for (int z = -2; z <= 2; z++) {
            level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        enderman.getRandom().setSeed(42L);
        int x = Mth.floor(enderman.getX() - 2 + enderman.getRandom().nextDouble() * 4);
        int y = Mth.floor(enderman.getY() + enderman.getRandom().nextDouble() * 3);
        int z = Mth.floor(enderman.getZ() - 2 + enderman.getRandom().nextDouble() * 4);
        BlockPos target = new BlockPos(x, y, z);
        level.setBlock(target, block, 3);
        enderman.getRandom().setSeed(42L);
        pickup.tick();
    }

    private static void preparePlacement(ServerLevel level, EnderMan enderman, BlockState held) {
        BlockPos center = enderman.blockPosition();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            for (int y = 0; y <= 3; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(center.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
        }
        enderman.setCarriedBlock(held);
        enderman.getRandom().setSeed(42L);
    }

    private static boolean commandFails(CommandDispatcher<CommandSourceStack> dispatcher,
                                        String command, CommandSourceStack source) {
        try { return dispatcher.execute(command, source) <= 0; }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { return true; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
