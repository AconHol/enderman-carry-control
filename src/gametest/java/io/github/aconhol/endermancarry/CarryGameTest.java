package io.github.aconhol.endermancarry;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import java.util.Optional;

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
            testConfigGui(helper, dispatcher, source);
            helper.succeed();
        } finally {
            enderman.discard();
            level.getGameRules().set(GameRules.MOB_GRIEFING, griefingBefore, level.getServer());
            store.replace(before);
        }
    }

    private static void testConfigGui(GameTestHelper helper, CommandDispatcher<CommandSourceStack> dispatcher,
                                      CommandSourceStack console) throws Exception {
        ConfigStore store = EndermanCarryControl.config();
        require(commandFails(dispatcher, "ecc gui", console), "console must receive a player-only error for GUI");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        var players = helper.getLevel().getServer().getPlayerList();
        players.op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        try {
            CommandSourceStack source = console.withEntity(player);
            require(dispatcher.execute("ecc gui", source) == 1, "GUI command must open for an operator");
            require(player.containerMenu instanceof CarryConfigMenu, "server must install the configuration menu");
            CarryConfigMenu menu = (CarryConfigMenu) player.containerMenu;
            require(menu.getType() == MenuType.GENERIC_9x6 && menu.slots.size() == 90,
                    "GUI must use the vanilla six-row chest protocol plus 36 inventory slots");
            require(menu.slots.stream().noneMatch(slot -> slot.mayPickup(player) || slot.mayPlace(new ItemStack(Items.STONE))),
                    "all menu and inventory slots must reject actual item transfers");

            click(menu, CarryConfigMenu.MODE_START + Mode.WHITELIST.ordinal(), player);
            require(store.current().pickup().mode() == Mode.WHITELIST, "GUI mode click must change pickup config");
            click(menu, CarryConfigMenu.PLACE_SLOT, player);
            click(menu, CarryConfigMenu.MODE_START + Mode.NONE.ordinal(), player);
            require(store.current().place().mode() == Mode.NONE && store.current().pickup().mode() == Mode.WHITELIST,
                    "GUI action tabs must edit independent rules");

            click(menu, CarryConfigMenu.PICKUP_SLOT, player);
            player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
            int stoneSlot = menu.findSlot(player.getInventory(), 9).orElseThrow();
            click(menu, stoneSlot, player);
            require(store.current().pickup().blocks().contains("minecraft:stone"), "inventory block click must add its ID");
            require(player.getInventory().getItem(9).getCount() == 64 && menu.getCarried().isEmpty(),
                    "adding an inventory block must not move or consume it");
            CarryConfig unchanged = store.current();
            for (ContainerInput input : ContainerInput.values()) {
                if (input == ContainerInput.PICKUP) continue;
                for (int slot : new int[]{0, stoneSlot, CarryConfigMenu.MODE_START}) {
                    menu.clicked(slot, 0, input, player);
                }
            }
            require(store.current().equals(unchanged), "shift, swap, clone, drop, drag and double clicks must be ignored");
            require(menu.quickMoveStack(player, stoneSlot).isEmpty() && player.getInventory().getItem(9).getCount() == 64
                    && menu.getCarried().isEmpty(), "ignored inventory inputs must not duplicate icons or real items");

            require(dispatcher.execute("ecc gui pickup minecraft:stone", source) == 1, "GUI command must accept namespace filters");
            menu = (CarryConfigMenu) player.containerMenu;
            click(menu, findEntry(menu, "minecraft:stone", player), player);
            require(!store.current().pickup().blocks().contains("minecraft:stone"), "selected browser entry must toggle off");
            click(menu, findEntry(menu, "minecraft:stone", player), player);
            require(store.current().pickup().blocks().contains("minecraft:stone"), "unselected browser entry must toggle on");
            click(menu, 51, player); // Clear the filter, then browse current data pack tags.
            click(menu, 50, player);
            int tagSlot = findEntry(menu, "#minecraft:enderman_holdable", player);
            click(menu, tagSlot, player);
            require(store.current().pickup().blocks().contains("#minecraft:enderman_holdable"), "GUI tag browser must save a tag entry");
            click(menu, 48, player);
            click(menu, findEntry(menu, "#minecraft:enderman_holdable", player), player);
            require(!store.current().pickup().blocks().contains("#minecraft:enderman_holdable"), "GUI list must remove tags");

            click(menu, 46, player);
            click(menu, CarryConfigMenu.CLEAR_SLOT, player);
            require(store.current().pickup().blocks().contains("minecraft:stone"), "clear must wait for confirmation");
            click(menu, 33, player);
            require(store.current().pickup().blocks().contains("minecraft:stone"), "cancel must preserve the list");
            click(menu, CarryConfigMenu.CLEAR_SLOT, player);
            click(menu, 29, player);
            require(store.current().pickup().blocks().isEmpty() && store.current().pickup().mode() == Mode.WHITELIST,
                    "confirmed clear must preserve the selected mode");

            dispatcher.execute("ecc pickup mode all", source);
            menu.broadcastChanges();
            require(menu.getContainer().getItem(CarryConfigMenu.MODE_START + Mode.ALL.ordinal())
                    .getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false),
                    "open GUI must refresh after a command or another admin changes the config");
            String valid = Files.readString(store.path());
            Files.writeString(store.path(), "{ invalid }");
            click(menu, CarryConfigMenu.RELOAD_SLOT, player);
            require(store.current().pickup().mode() == Mode.ALL, "failed GUI reload must keep current rules");
            Files.writeString(store.path(), valid);

            click(menu, CarryConfigMenu.RESET_SLOT, player);
            click(menu, 29, player);
            require(store.current().equals(CarryConfig.defaults()), "confirmed GUI reset must restore both vanilla rules");
            require(ConfigStore.decode(Files.readString(store.path())).equals(store.current()), "GUI edits must persist immediately");

            players.deop(player.nameAndId());
            require(!menu.stillValid(player), "revoking operator permission must invalidate an open GUI");
            click(menu, CarryConfigMenu.MODE_START + Mode.NONE.ordinal(), player);
            require(store.current().equals(CarryConfig.defaults()), "revoked operator must not edit through a stale open menu");
            require(!CarryConfigMenu.open(player, Action.PICKUP, ""), "non-operators must not open the GUI");
            require(player.getInventory().getItem(9).getCount() == 64, "closing a GUI must preserve the player's inventory");
        } finally {
            player.closeContainer();
            players.deop(player.nameAndId());
            player.discard();
        }
    }

    private static void click(CarryConfigMenu menu, int slot, ServerPlayer player) {
        menu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }

    private static int findEntry(CarryConfigMenu menu, String entry, ServerPlayer player) {
        menu.clicked(45, 1, ContainerInput.PICKUP, player);
        for (int page = 0; page < 100; page++) {
            for (int slot = 0; slot < 45; slot++) {
                String title = menu.getContainer().getItem(slot).getHoverName().getString();
                if (title.equals(entry) || title.equals("✓ " + entry)) return slot;
            }
            String oldPage = menu.getContainer().getItem(49).getHoverName().getString();
            click(menu, 53, player);
            if (oldPage.equals(menu.getContainer().getItem(49).getHoverName().getString())) break;
        }
        throw new AssertionError("GUI entry missing: " + entry);
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
