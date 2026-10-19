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
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.world.level.GameType;
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
        require(commandFails(dispatcher, "ecc gui", console), "console must receive a player-only error");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var players = helper.getLevel().getServer().getPlayerList();
        players.op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        try {
            CommandSourceStack source = console.withEntity(player);
            dispatcher.execute("ecc gui", source);
            CarryConfigMenu menu = (CarryConfigMenu) player.containerMenu;
            require(menu.getType() == MenuType.GENERIC_9x6 && menu.slots.size() == 90, "vanilla chest protocol required");
            require(menu.slots.stream().noneMatch(slot -> slot.mayPickup(player) || slot.mayPlace(new ItemStack(Items.STONE))),
                    "button GUI must reject real item transfers");
            require(title(menu, 4).contains("首页"), "bare GUI command must open home");
            click(menu, CarryConfigMenu.PICKUP_SLOT, player);
            require(title(menu, 4).contains("规则设置"), "home opens rule submenu");
            click(menu, CarryConfigMenu.LIST_SLOT, player);
            require(title(menu, 13).contains("先选择名单模式"), "inactive lists must guide users through mode selection");
            click(menu, CarryConfigMenu.MODE_START + Mode.WHITELIST.ordinal(), player);
            require(store.current().pickup().mode() == Mode.WHITELIST && title(menu, 4).contains("管理名单"),
                    "choosing whitelist must continue into list management");

            player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
            int stoneSlot = menu.findSlot(player.getInventory(), 9).orElseThrow();
            click(menu, stoneSlot, player);
            require(store.current().pickup().blocks().isEmpty(), "inventory clicks outside add page must not edit");
            click(menu, CarryConfigMenu.BLOCKS_SLOT, player);
            click(menu, stoneSlot, player);
            require(store.current().pickup().blocks().contains("minecraft:stone"), "add page inventory shortcut must work");
            require(player.getInventory().getItem(9).getCount() == 64 && menu.getCarried().isEmpty(),
                    "survival inventory must survive client predictions");

            dispatcher.execute("ecc gui pickup minecraft:stone", source);
            menu = (CarryConfigMenu) player.containerMenu;
            int entry = findEntry(menu, "minecraft:stone", player);
            click(menu, entry, player);
            require(store.current().pickup().blocks().contains("minecraft:stone"), "add page must never toggle an entry off");
            click(menu, CarryConfigMenu.BACK_SLOT, player);
            require(title(menu, 4).contains("管理名单"), "back must return exactly one level");
            click(menu, CarryConfigMenu.REMOVE_SLOT, player);
            click(menu, findEntry(menu, "minecraft:stone", player), player);
            require(store.current().pickup().blocks().isEmpty(), "remove page must remove an entry");
            click(menu, CarryConfigMenu.BACK_SLOT, player);
            click(menu, CarryConfigMenu.TAGS_SLOT, player);
            click(menu, findEntry(menu, "#minecraft:enderman_holdable", player), player);
            require(store.current().pickup().blocks().contains("#minecraft:enderman_holdable"), "tag browser must add tags");
            click(menu, CarryConfigMenu.BACK_SLOT, player);
            click(menu, CarryConfigMenu.CLEAR_SLOT, player);
            click(menu, 33, player);
            require(!store.current().pickup().blocks().isEmpty(), "cancel clear must preserve entries");
            click(menu, CarryConfigMenu.CLEAR_SLOT, player);
            click(menu, 29, player);
            require(store.current().pickup().blocks().isEmpty() && store.current().pickup().mode() == Mode.WHITELIST,
                    "confirmed clear must preserve mode");

            CarryConfig unchanged = store.current();
            for (ContainerInput input : ContainerInput.values()) {
                if (input == ContainerInput.PICKUP) continue;
                sendClick(menu, CarryConfigMenu.BLOCKS_SLOT, 0, input, player);
                sendClick(menu, stoneSlot, 0, input, player);
            }
            require(store.current().equals(unchanged) && player.getInventory().getItem(9).getCount() == 64
                    && menu.getCarried().isEmpty(), "shift/swap/clone/drop/drag/double click predictions must not edit or move items");
            click(menu, -999, player);
            click(menu, 32767, player);
            sendClick(menu, CarryConfigMenu.BLOCKS_SLOT, 9, ContainerInput.PICKUP, player);
            require(store.current().equals(unchanged), "invalid slot/button inputs must not edit");

            click(menu, CarryConfigMenu.HOME_SLOT, player);
            int staleState = menu.getStateId();
            click(menu, CarryConfigMenu.PLACE_SLOT, player);
            player.connection.handleContainerClick(new ServerboundContainerClickPacket(
                    menu.containerId, staleState, (short) CarryConfigMenu.MODE_SLOT, (byte) 0,
                    ContainerInput.PICKUP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
            require(title(menu, 4).contains("规则设置"), "stale page click must only resynchronize");
            player.connection.handleContainerClick(new ServerboundContainerClickPacket(
                    menu.containerId + 1, menu.getStateId(), (short) CarryConfigMenu.MODE_SLOT, (byte) 0,
                    ContainerInput.PICKUP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
            require(title(menu, 4).contains("规则设置"), "wrong container id must not act");

            click(menu, CarryConfigMenu.MODE_SLOT, player);
            click(menu, CarryConfigMenu.MODE_START + Mode.NONE.ordinal(), player);
            require(store.current().place().mode() == Mode.NONE && store.current().pickup().mode() == Mode.WHITELIST,
                    "pickup and place must stay independent");
            for (GameType mode : new GameType[]{GameType.CREATIVE, GameType.SPECTATOR, GameType.SURVIVAL}) {
                player.setGameMode(mode);
                click(menu, CarryConfigMenu.MODE_SLOT, player);
                require(title(menu, 4).contains("选择模式"), "button clicks must reach menu in " + mode);
                click(menu, CarryConfigMenu.MODE_START + Mode.NONE.ordinal(), player);
                require(player.gameMode.getGameModeForPlayer() == mode, "GUI must never change real game mode");
            }

            click(menu, CarryConfigMenu.MODE_SLOT, player);
            dispatcher.execute("ecc place mode all", source);
            menu.broadcastChanges();
            require(menu.getContainer().getItem(CarryConfigMenu.MODE_START + Mode.ALL.ordinal())
                    .getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false),
                    "open menu must refresh after command changes");
            click(menu, CarryConfigMenu.HOME_SLOT, player);
            click(menu, CarryConfigMenu.ADVANCED_SLOT, player);
            String valid = Files.readString(store.path());
            Files.writeString(store.path(), "{ invalid }");
            click(menu, CarryConfigMenu.RELOAD_SLOT, player);
            require(store.current().place().mode() == Mode.ALL, "invalid reload must keep live settings");
            Files.writeString(store.path(), valid);
            click(menu, CarryConfigMenu.RESET_SLOT, player);
            click(menu, 29, player);
            require(store.current().equals(CarryConfig.defaults()), "confirmed reset must restore both rules");
            require(ConfigStore.decode(Files.readString(store.path())).equals(store.current()), "GUI must persist edits");

            testSuggestions(dispatcher, source);

            click(menu, CarryConfigMenu.HOME_SLOT, player);
            players.deop(player.nameAndId());
            click(menu, CarryConfigMenu.PICKUP_SLOT, player);
            require(player.containerMenu != menu && store.current().equals(CarryConfig.defaults()),
                    "revoked operator must close without editing");
            require(!CarryConfigMenu.open(player, Action.PICKUP, ""), "non-operators must not open GUI");
            require(player.getInventory().getItem(9).getCount() == 64, "closing must preserve inventory");

            // This mod's packet interception must leave ordinary chest inventory transfers intact.
            players.op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, user) ->
                    net.minecraft.world.inventory.ChestMenu.sixRows(id, inv), net.minecraft.network.chat.Component.literal("normal")));
            var chest = player.containerMenu;
            chest.slots.get(0).set(new ItemStack(Items.DIRT, 3));
            player.connection.handleContainerClick(new ServerboundContainerClickPacket(
                    chest.containerId, chest.getStateId(), (short) 0, (byte) 0, ContainerInput.PICKUP,
                    Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
            require(chest.getCarried().is(Items.DIRT) && chest.getCarried().getCount() == 3,
                    "ordinary chests must still run vanilla click handling");
            chest.setCarried(ItemStack.EMPTY);
        } finally {
            player.closeContainer();
            players.deop(player.nameAndId());
            player.discard();
        }
    }

    private static void testSuggestions(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) throws Exception {
        assertSuggest(dispatcher, source, "ecc pickup add sto", "minecraft:stone", true);
        assertSuggest(dispatcher, source, "endermancarry place add minecraft:sto", "minecraft:stone", true);
        assertSuggest(dispatcher, source, "ecc place add #log", "#minecraft:logs", true);
        assertSuggest(dispatcher, source, "ecc pickup add #minecraft:log", "#minecraft:logs", true);
        assertSuggest(dispatcher, source, "ecc gui pickup sto", "minecraft:stone", true);
        assertSuggest(dispatcher, source, "ecc check sto", "minecraft:stone", true);
        assertSuggest(dispatcher, source, "ecc pickup remove ", "minecraft:stone", false);
        dispatcher.execute("ecc pickup add stone", source);
        dispatcher.execute("ecc pickup add #minecraft:logs", source);
        assertSuggest(dispatcher, source, "ecc pickup add sto", "minecraft:stone", false);
        assertSuggest(dispatcher, source, "ecc pickup remove sto", "minecraft:stone", true);
        assertSuggest(dispatcher, source, "ecc pickup remove #log", "#minecraft:logs", true);
        assertSuggest(dispatcher, source, "ecc place remove sto", "minecraft:stone", false);
        dispatcher.execute("ecc pickup remove stone", source);
        assertSuggest(dispatcher, source, "ecc pickup remove sto", "minecraft:stone", false);
        dispatcher.execute("ecc reset", source);
    }

    private static void assertSuggest(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                                      String input, String expected, boolean present) throws Exception {
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).get();
        // Use the response structure that an unmodified client receives.
        var response = new net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket(7, suggestions);
        var actual = response.toSuggestions();
        require(actual.getList().stream().anyMatch(s -> s.getText().equals(expected)) == present,
                "unexpected completion for " + input + ": " + expected);
        if (present) require(actual.getList().stream().filter(s -> s.getText().equals(expected))
                .allMatch(s -> s.apply(input).equals(input.substring(0, input.lastIndexOf(' ') + 1) + expected)),
                "suggestion must replace only the argument");
    }

    private static void click(CarryConfigMenu menu, int slot, ServerPlayer player) {
        sendClick(menu, slot, 0, ContainerInput.PICKUP, player);
    }

    private static void sendClick(CarryConfigMenu menu, int slot, int button, ContainerInput input, ServerPlayer player) {
        // Simulate an unmodified client's ChestMenu prediction, including carried icon hashes.
        var clientInventory = new net.minecraft.world.entity.player.Inventory(player, new net.minecraft.world.entity.EntityEquipment());
        var client = net.minecraft.world.inventory.ChestMenu.sixRows(menu.containerId, clientInventory);
        var before = menu.getItems().stream().map(ItemStack::copy).toList();
        client.initializeContents(menu.getStateId(), before.stream().map(ItemStack::copy).toList(), menu.getCarried().copy());
        if (slot < 90 && button >= 0 && button <= 1) client.clicked(slot, button, input, player);
        var changes = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<HashedStack>();
        for (int i = 0; i < 90; i++) {
            if (!ItemStack.matches(before.get(i), client.slots.get(i).getItem()))
                changes.put(i, HashedStack.create(client.slots.get(i).getItem(), component -> 0));
        }
        HashedStack carried = HashedStack.create(client.getCarried(), component -> 0);
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(
                menu.containerId, menu.getStateId(), (short) slot, (byte) button, input, changes, carried));
        require(menu.getCarried().isEmpty(), "client prediction must not transfer button to server cursor");
    }

    private static String title(CarryConfigMenu menu, int slot) {
        return menu.getContainer().getItem(slot).getHoverName().getString();
    }

    private static int findEntry(CarryConfigMenu menu, String entry, ServerPlayer player) {
        sendClick(menu, CarryConfigMenu.PREVIOUS_SLOT, 1, ContainerInput.PICKUP, player);
        for (int page = 0; page < 100; page++) {
            for (int slot = CarryConfigMenu.CONTENT_START; slot < CarryConfigMenu.CONTENT_START + CarryConfigMenu.PAGE_SIZE; slot++) {
                if (title(menu, slot).equals(entry) || title(menu, slot).equals("✓ " + entry)) return slot;
            }
            String oldPage = title(menu, CarryConfigMenu.INFO_SLOT);
            click(menu, CarryConfigMenu.NEXT_SLOT, player);
            if (oldPage.equals(title(menu, CarryConfigMenu.INFO_SLOT))) break;
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
