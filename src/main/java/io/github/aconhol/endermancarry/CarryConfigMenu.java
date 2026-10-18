package io.github.aconhol.endermancarry;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static io.github.aconhol.endermancarry.CarryConfig.*;

/** Uses the vanilla chest protocol; every displayed stack is a read-only button. */
public final class CarryConfigMenu extends ChestMenu {
    static final int PICKUP_SLOT = 10;
    static final int PLACE_SLOT = 12;
    static final int RELOAD_SLOT = 14;
    static final int RESET_SLOT = 16;
    static final int MODE_START = 20;
    static final int LIST_SLOT = 28;
    static final int BLOCKS_SLOT = 30;
    static final int TAGS_SLOT = 32;
    static final int HELD_SLOT = 34;
    static final int CLEAR_SLOT = 40;
    static final int CLOSE_SLOT = 49;
    private static final int PAGE_SIZE = 45;

    private enum View { HOME, LIST, BLOCKS, TAGS, CONFIRM_CLEAR, CONFIRM_RESET }

    private final UUID owner;
    private final ConfigStore store;
    private Action action;
    private View view;
    private int page;
    private int pages = 1;
    private String filter;
    private CarryConfig observed;
    private List<String> shown = List.of();

    public static boolean open(ServerPlayer player, Action action, String filter) {
        if (!authorized(player)) return false;
        return player.openMenu(new SimpleMenuProvider((id, inventory, user) ->
                        new CarryConfigMenu(id, player, EndermanCarryControl.config(), action, filter),
                Component.literal("末影人搬运配置 · 修改自动保存"))).isPresent();
    }

    CarryConfigMenu(int id, Player player, ConfigStore store, Action action, String filter) {
        super(MenuType.GENERIC_9x6, id, player.getInventory(), new SimpleContainer(54), 6);
        this.owner = player.getUUID();
        this.store = store;
        this.action = action;
        this.filter = filter.trim().toLowerCase(Locale.ROOT);
        this.view = this.filter.isEmpty() ? View.HOME : View.BLOCKS;
        render();
    }

    private static boolean authorized(Player player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    protected Slot addSlot(Slot original) {
        return super.addSlot(new Slot(original.container, original.getContainerSlot(), original.x, original.y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
    }

    @Override public boolean stillValid(Player player) {
        return owner.equals(player.getUUID()) && player.isAlive() && authorized(player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean canDragTo(Slot slot) { return false; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }

    @Override
    public void broadcastChanges() {
        if (store != null && observed != store.current()) render();
        super.broadcastChanges();
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!stillValid(player)) {
            if (owner.equals(player.getUUID()) && player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();
            return;
        }
        try {
            // Repaint a stale menu before accepting a click; never act on a row another admin changed.
            if (observed != store.current()) {
                render();
                return;
            }
            if (input != ContainerInput.PICKUP || button < 0 || button > 1) return;
            if (slot >= 54 && slot < slots.size()) {
                if (view == View.CONFIRM_CLEAR || view == View.CONFIRM_RESET) return;
                ItemStack stack = slots.get(slot).getItem();
                if (stack.getItem() instanceof BlockItem blockItem) {
                    addEntry(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString(), player);
                } else message(player, "请点击背包中的方块物品；物品不会被移动或消耗。", false);
                return;
            }
            if (slot < 0 || slot >= 54) return;
            if (view == View.HOME) clickHome(slot, player);
            else if (view == View.CONFIRM_CLEAR || view == View.CONFIRM_RESET) clickConfirmation(slot, player);
            else clickBrowser(slot, button, player);
        } catch (IOException | IllegalArgumentException e) {
            message(player, "修改未生效：" + e.getMessage(), true);
        } finally {
            render();
            // Vanilla clients predict inventory movement. Always correct that prediction, including
            // ignored shift/drag/number-key/drop/double clicks. No superclass inventory mutation runs.
            if (player.containerMenu == this) broadcastFullState();
        }
    }

    private void clickHome(int slot, Player player) throws IOException {
        if (slot >= MODE_START && slot < MODE_START + Mode.values().length) {
            Mode mode = Mode.values()[slot - MODE_START];
            change(config -> config.withRule(action, new Rule(mode, config.rule(action).blocks())),
                    player, actionName() + "模式已设为“" + modeName(mode) + "”，已保存。" );
            return;
        }
        switch (slot) {
            case PICKUP_SLOT -> action = Action.PICKUP;
            case PLACE_SLOT -> action = Action.PLACE;
            case RELOAD_SLOT -> {
                store.reload();
                message(player, "已重新加载配置。", false);
            }
            case RESET_SLOT -> view = View.CONFIRM_RESET;
            case LIST_SLOT -> navigate(View.LIST);
            case BLOCKS_SLOT -> navigate(View.BLOCKS);
            case TAGS_SLOT -> navigate(View.TAGS);
            case HELD_SLOT -> {
                if (player.getMainHandItem().getItem() instanceof BlockItem blockItem) {
                    addEntry(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString(), player);
                } else message(player, "请在主手拿着方块，或直接点击下方背包中的方块。", false);
            }
            case CLEAR_SLOT -> view = View.CONFIRM_CLEAR;
            case CLOSE_SLOT -> { if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer(); }
            default -> { }
        }
    }

    private void clickBrowser(int slot, int button, Player player) throws IOException {
        if (slot < PAGE_SIZE && slot < shown.size()) {
            String entry = shown.get(slot);
            if (view == View.LIST || store.current().rule(action).blocks().contains(entry)) {
                change(config -> {
                    ArrayList<String> entries = new ArrayList<>(config.rule(action).blocks());
                    entries.remove(entry);
                    return config.withRule(action, new Rule(config.rule(action).mode(), entries));
                }, player, "已从" + actionName() + "名单移除 " + entry);
            } else addEntry(entry, player);
            return;
        }
        switch (slot) {
            case 45 -> page = Math.max(0, page - (button == 1 ? 10 : 1));
            case 46 -> navigate(View.HOME);
            case 47 -> {
                action = action == Action.PICKUP ? Action.PLACE : Action.PICKUP;
                page = 0;
            }
            case 48 -> navigate(View.LIST);
            case 50 -> navigate(view == View.TAGS ? View.BLOCKS : View.TAGS);
            case 51 -> { filter = ""; page = 0; }
            case 52 -> { if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer(); }
            case 53 -> page = Math.min(pages - 1, page + (button == 1 ? 10 : 1));
            default -> { }
        }
    }

    private void clickConfirmation(int slot, Player player) throws IOException {
        if (slot == 29) {
            if (view == View.CONFIRM_RESET) {
                change(ignored -> CarryConfig.defaults(), player, "已恢复全部原版规则。" );
            } else {
                change(config -> config.withRule(action, new Rule(config.rule(action).mode(), List.of())),
                        player, "已清空" + actionName() + "名单；当前模式保留。" );
            }
            navigate(View.HOME);
        } else if (slot == 33) navigate(View.HOME);
    }

    private void addEntry(String entry, Player player) throws IOException {
        String canonical = BlockRules.normalize(entry);
        if (store.current().rule(action).blocks().contains(canonical)) {
            message(player, "名单中已有 " + canonical, false);
            return;
        }
        change(config -> {
            ArrayList<String> entries = new ArrayList<>(config.rule(action).blocks());
            entries.add(canonical);
            return config.withRule(action, new Rule(config.rule(action).mode(), entries));
        }, player, "已加入" + actionName() + "名单：" + canonical + "；名单模式需选白名单或黑名单。" );
    }

    private void change(UnaryOperator<CarryConfig> change, Player player, String message) throws IOException {
        store.replace(change.apply(store.current()));
        message(player, message, false);
    }

    private void navigate(View next) { view = next; page = 0; }

    private void render() {
        observed = store.current();
        getContainer().clearContent();
        shown = List.of();
        if (view == View.HOME) renderHome();
        else if (view == View.CONFIRM_CLEAR || view == View.CONFIRM_RESET) renderConfirmation();
        else renderBrowser();
    }

    private void renderHome() {
        fill();
        button(4, Items.ENDER_PEARL, "末影人搬运配置", false,
                "正在编辑：" + actionName(), "模式：" + modeName(observed.rule(action).mode()),
                "修改立即生效并保存；客户端无需安装。", "点击下方背包里的方块可加入当前名单。" );
        button(PICKUP_SLOT, Items.GRASS_BLOCK, "编辑：拿起规则", action == Action.PICKUP,
                summary(Action.PICKUP), "左键切换到拿起配置。" );
        button(PLACE_SLOT, Items.CHEST, "编辑：放下规则", action == Action.PLACE,
                summary(Action.PLACE), "左键切换到放下配置。" );
        button(RELOAD_SLOT, Items.CLOCK, "重新加载配置文件", false, "读取 config/enderman-carry-control.json", "错误时保留当前生效规则。" );
        button(RESET_SLOT, Items.REDSTONE_TORCH, "恢复全部原版规则", false, "恢复拿起、放下规则并清空名单。", "点击后需要再次确认。" );
        for (Mode mode : Mode.values()) {
            button(MODE_START + mode.ordinal(), modeIcon(mode), modeName(mode), observed.rule(action).mode() == mode,
                    mode.id(), modeDescription(mode), "左键选择此模式；名单会保留。" );
        }
        button(LIST_SLOT, Items.BOOK, "编辑当前名单", false, "共 " + observed.rule(action).blocks().size() + " 个条目", "查看条目，点击移除。" );
        button(BLOCKS_SLOT, Items.STONE, "选择方块", false, "从所有已注册方块中翻页选择。", "点击加入，再次点击移除。" );
        button(TAGS_SLOT, Items.NAME_TAG, "选择方块标签", false, "从服务器数据包标签中选择。", "如 #minecraft:logs" );
        button(HELD_SLOT, Items.HOPPER, "添加主手方块", false, "将主手拿着的方块加入当前名单。", "不移动、不消耗实际物品。" );
        button(CLEAR_SLOT, Items.BUCKET, "清空当前名单", false, "仅清空" + actionName() + "名单，模式保留。", "点击后需要再次确认。" );
        button(CLOSE_SLOT, Items.BARRIER, "关闭界面", false, "修改已自动保存。" );
    }

    private void renderBrowser() {
        List<String> entries = switch (view) {
            case LIST -> observed.rule(action).blocks();
            case TAGS -> BuiltInRegistries.BLOCK.getTags().map(tag -> "#" + tag.key().location()).sorted().toList();
            default -> BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString)
                    .filter(id -> !id.equals("minecraft:air")).sorted().toList();
        };
        entries = entries.stream().filter(entry -> filter.isEmpty() || entry.contains(filter)).toList();
        pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        shown = List.copyOf(entries.subList(page * PAGE_SIZE, Math.min(entries.size(), (page + 1) * PAGE_SIZE)));
        for (int slot = 0; slot < shown.size(); slot++) {
            String entry = shown.get(slot);
            boolean listed = observed.rule(action).blocks().contains(entry);
            button(slot, entryIcon(entry), entry, listed,
                    listed ? "已在" + actionName() + "名单中" : "尚未加入" + actionName() + "名单",
                    (view == View.LIST || listed) ? "左键：从名单移除" : "左键：加入名单",
                    "名单仅在白名单、黑名单模式下生效。" );
        }
        for (int slot = 45; slot < 54; slot++) button(slot, Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY), " ", false);
        button(45, Items.ARROW, "上一页", false, "左键：上一页；右键：向前 10 页。" );
        button(46, Items.COMPASS, "返回配置主页", false);
        button(47, Items.ENDER_PEARL, "当前：" + actionName(), false, "左键切换拿起/放下规则。", summary(action));
        button(48, Items.BOOK, "查看当前名单", view == View.LIST, "条目数：" + observed.rule(action).blocks().size());
        button(49, Items.PAPER, viewName() + " · " + (page + 1) + "/" + pages + " 页", false,
                "匹配条目：" + entries.size(), "点击背包方块也可加入当前名单。" );
        button(50, Items.NAME_TAG, view == View.TAGS ? "切换：方块选择" : "切换：标签选择", false);
        button(51, Items.SPYGLASS, filter.isEmpty() ? "搜索提示" : "清除搜索：" + filter, false,
                "/ecc gui " + action.id() + " 关键词", "例如 /ecc gui pickup stone", "点击清除当前筛选。" );
        button(52, Items.BARRIER, "关闭界面", false);
        button(53, Items.ARROW, "下一页", false, "左键：下一页；右键：向后 10 页。" );
    }

    private void renderConfirmation() {
        fill();
        boolean reset = view == View.CONFIRM_RESET;
        button(13, Items.REDSTONE_TORCH, reset ? "确认恢复全部原版规则？" : "确认清空" + actionName() + "名单？", false,
                reset ? "拿起和放下都将恢复原版，两个名单都会清空。" : "当前模式保留，名单将被清空。",
                "当前拿起：" + summary(Action.PICKUP), "当前放下：" + summary(Action.PLACE));
        button(29, Items.CONCRETE.pick(DyeColor.LIME), "确认并保存", false);
        button(33, Items.CONCRETE.pick(DyeColor.RED), "取消，返回主页", false);
    }

    private void fill() {
        for (int slot = 0; slot < 54; slot++) button(slot, Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY), " ", false);
    }

    private void button(int slot, ItemLike item, String title, boolean selected, String... description) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal((selected ? "✓ " : "") + title)
                .withStyle(style -> style.withColor(selected ? ChatFormatting.GREEN : ChatFormatting.GOLD).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(description)
                .map(line -> (Component) Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)))
                .toList()));
        if (selected) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        getContainer().setItem(slot, stack);
    }

    private static ItemLike entryIcon(String entry) {
        if (entry.startsWith("#")) return Items.NAME_TAG;
        var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(entry));
        return block.asItem() == Items.AIR ? Items.PAPER : block;
    }

    private String summary(Action target) {
        Rule rule = observed.rule(target);
        return modeName(rule.mode()) + " · " + rule.blocks().size() + " 个名单条目";
    }

    private String actionName() { return action == Action.PICKUP ? "拿起" : "放下"; }
    private String viewName() { return view == View.LIST ? "当前名单" : view == View.TAGS ? "方块标签" : "方块选择"; }

    private static String modeName(Mode mode) {
        return switch (mode) {
            case VANILLA -> "原版";
            case WHITELIST -> "白名单";
            case BLACKLIST -> "黑名单";
            case ALL -> "全部允许";
            case NONE -> "全部禁止";
        };
    }

    private static String modeDescription(Mode mode) {
        return switch (mode) {
            case VANILLA -> "遵循原版规则，名单不参与判定。";
            case WHITELIST -> "仅允许名单内的方块；拿起可扩展原版范围。";
            case BLACKLIST -> "从原版允许的范围中排除名单内方块。";
            case ALL -> "允许所有类型；仍遵循原版时机、位置和数据保护。";
            case NONE -> "禁止此项搬运操作。";
        };
    }

    private static ItemLike modeIcon(Mode mode) {
        return switch (mode) {
            case VANILLA -> Items.GRASS_BLOCK;
            case WHITELIST -> Items.DYE.pick(DyeColor.LIME);
            case BLACKLIST -> Items.DYE.pick(DyeColor.RED);
            case ALL -> Items.EMERALD_BLOCK;
            case NONE -> Items.BARRIER;
        };
    }

    private static void message(Player player, String text, boolean error) {
        player.sendSystemMessage(Component.literal("[ECC] " + text).withStyle(error ? ChatFormatting.RED : ChatFormatting.GREEN));
    }
}
