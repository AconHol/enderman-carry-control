package io.github.aconhol.endermancarry;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
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

/** Vanilla chest visuals with server-authoritative buttons, never inventory transfers. */
public final class CarryConfigMenu extends ChestMenu {
    static final int PICKUP_SLOT = 20, PLACE_SLOT = 24, ADVANCED_SLOT = 40;
    static final int MODE_SLOT = 20, LIST_SLOT = 24, MODE_START = 20;
    static final int BLOCKS_SLOT = 20, TAGS_SLOT = 22, REMOVE_SLOT = 24, CLEAR_SLOT = 40;
    static final int RELOAD_SLOT = 20, RESET_SLOT = 24;
    static final int BACK_SLOT = 45, PREVIOUS_SLOT = 47, SEARCH_SLOT = 48;
    static final int HOME_SLOT = 49, INFO_SLOT = 50, NEXT_SLOT = 51, CLOSE_SLOT = 53;
    static final int CONTENT_START = 9, PAGE_SIZE = 36;

    private enum View { HOME, RULE, MODE, LIST, ADD_BLOCKS, ADD_TAGS, REMOVE, ADVANCED, CONFIRM_CLEAR, CONFIRM_RESET }

    private final UUID owner;
    private final ConfigStore store;
    private Action action;
    private View view;
    private View afterMode = View.RULE;
    private int page;
    private int pages = 1;
    private String filter;
    private CarryConfig observed;
    private List<String> shown = List.of();

    /** A null action opens the home page; an explicit action opens its rule page. */
    public static boolean open(ServerPlayer player, Action action, String filter) {
        if (!authorized(player)) return false;
        return player.openMenu(new SimpleMenuProvider((id, inventory, user) ->
                        new CarryConfigMenu(id, player, EndermanCarryControl.config(), action, filter),
                Component.literal("末影人搬运配置 · 自动保存"))).isPresent();
    }

    CarryConfigMenu(int id, Player player, ConfigStore store, Action action, String filter) {
        super(MenuType.GENERIC_9x6, id, player.getInventory(), new SimpleContainer(54), 6);
        owner = player.getUUID();
        this.store = store;
        this.action = action == null ? Action.PICKUP : action;
        this.filter = filter.trim().toLowerCase(Locale.ROOT);
        view = action == null ? View.HOME : View.RULE;
        if (!this.filter.isEmpty()) enterList(View.ADD_BLOCKS);
        render();
    }

    private static boolean authorized(Player player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override protected Slot addSlot(Slot original) {
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

    @Override public void broadcastChanges() {
        if (store != null && observed != store.current()) render();
        super.broadcastChanges();
    }

    /** Runs on the server thread before vanilla chest inventory prediction processing. */
    public void handleClick(ServerboundContainerClickPacket packet, ServerPlayer player) {
        if (packet.containerId() != containerId) return;
        player.resetLastActionTime();
        if (!stillValid(player)) { player.closeContainer(); return; }
        if (packet.stateId() != getStateId() || observed != store.current()) {
            render();
            broadcastFullState();
            return;
        }
        // Client changedSlots/carriedItem describe inventory predictions, not button actions.
        clicked(packet.slotNum(), packet.buttonNum(), packet.containerInput(), player);
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!stillValid(player)) {
            if (owner.equals(player.getUUID()) && player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();
            return;
        }
        try {
            if (observed != store.current()) return;
            if (input != ContainerInput.PICKUP || button < 0 || button > 1) return;
            if (slot >= 54 && slot < slots.size()) {
                if (view == View.ADD_BLOCKS) {
                    if (slots.get(slot).getItem().getItem() instanceof BlockItem block) {
                        addEntry(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString(), player);
                    } else message(player, "请点击背包中的方块物品。", false);
                }
                return;
            }
            if (slot < 0 || slot >= 54) return;
            if (slot == CLOSE_SLOT) { if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer(); return; }
            if (slot == HOME_SLOT) { filter = ""; navigate(View.HOME); return; }
            if (slot == BACK_SLOT && view != View.HOME) { back(); return; }
            switch (view) {
                case HOME -> {
                    if (slot == PICKUP_SLOT || slot == PLACE_SLOT) {
                        action = slot == PICKUP_SLOT ? Action.PICKUP : Action.PLACE;
                        navigate(View.RULE);
                    } else if (slot == ADVANCED_SLOT) navigate(View.ADVANCED);
                }
                case RULE -> {
                    if (slot == MODE_SLOT) { afterMode = View.RULE; navigate(View.MODE); }
                    else if (slot == LIST_SLOT) enterList(View.LIST);
                }
                case MODE -> {
                    if (slot >= MODE_START && slot < MODE_START + Mode.values().length) {
                        Mode mode = Mode.values()[slot - MODE_START];
                        change(config -> config.withRule(action, new Rule(mode, config.rule(action).blocks())),
                                player, actionName() + "模式已设为" + modeName(mode) + "，已保存。");
                        navigate(listMode() ? afterMode : View.RULE);
                    }
                }
                case LIST -> {
                    if (slot == BLOCKS_SLOT) enterList(View.ADD_BLOCKS);
                    else if (slot == TAGS_SLOT) enterList(View.ADD_TAGS);
                    else if (slot == REMOVE_SLOT) enterList(View.REMOVE);
                    else if (slot == CLEAR_SLOT) navigate(View.CONFIRM_CLEAR);
                }
                case ADD_BLOCKS, ADD_TAGS, REMOVE -> clickBrowser(slot, button, player);
                case ADVANCED -> {
                    if (slot == RELOAD_SLOT) { store.reload(); message(player, "配置已重新加载。", false); }
                    else if (slot == RESET_SLOT) navigate(View.CONFIRM_RESET);
                }
                case CONFIRM_CLEAR, CONFIRM_RESET -> {
                    if (slot == 29) {
                        if (view == View.CONFIRM_RESET) {
                            change(ignored -> CarryConfig.defaults(), player, "已恢复全部原版规则。");
                            navigate(View.ADVANCED);
                        } else {
                            change(config -> config.withRule(action, new Rule(config.rule(action).mode(), List.of())),
                                    player, "已清空" + actionName() + "名单；模式保留。");
                            navigate(View.LIST);
                        }
                    } else if (slot == 33) back();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            message(player, "修改未生效：" + e.getMessage(), true);
            EndermanCarryControl.LOGGER.warn("GUI configuration action failed", e);
        } finally {
            render();
            if (player.containerMenu == this) broadcastFullState();
        }
    }

    private void clickBrowser(int slot, int button, Player player) throws IOException {
        if (slot >= CONTENT_START && slot < CONTENT_START + shown.size()) {
            String entry = shown.get(slot - CONTENT_START);
            if (view == View.REMOVE) {
                change(config -> {
                    ArrayList<String> entries = new ArrayList<>(config.rule(action).blocks());
                    entries.remove(entry);
                    return config.withRule(action, new Rule(config.rule(action).mode(), entries));
                }, player, "已从" + actionName() + "名单移除 " + entry);
            } else addEntry(entry, player);
        } else if (slot == PREVIOUS_SLOT) page = Math.max(0, page - (button == 1 ? 10 : 1));
        else if (slot == NEXT_SLOT) page = Math.min(pages - 1, page + (button == 1 ? 10 : 1));
        else if (slot == SEARCH_SLOT) { filter = ""; page = 0; }
    }

    private boolean listMode() {
        Mode mode = store.current().rule(action).mode();
        return mode == Mode.WHITELIST || mode == Mode.BLACKLIST;
    }

    private void enterList(View target) {
        if (listMode()) navigate(target);
        else { afterMode = target; navigate(View.MODE); }
    }

    private void back() {
        View parent = switch (view) {
            case RULE, ADVANCED -> View.HOME;
            case MODE, LIST -> View.RULE;
            case ADD_BLOCKS, ADD_TAGS, REMOVE, CONFIRM_CLEAR -> View.LIST;
            case CONFIRM_RESET -> View.ADVANCED;
            default -> View.HOME;
        };
        filter = "";
        navigate(parent);
    }

    private void addEntry(String entry, Player player) throws IOException {
        if (!listMode()) {
            enterList(view);
            message(player, "请先选择白名单或黑名单模式。", false);
            return;
        }
        String canonical = BlockRules.normalize(entry);
        if (store.current().rule(action).blocks().contains(canonical)) return;
        change(config -> {
            ArrayList<String> entries = new ArrayList<>(config.rule(action).blocks());
            entries.add(canonical);
            return config.withRule(action, new Rule(config.rule(action).mode(), entries));
        }, player, "已加入" + actionName() + "名单：" + canonical);
    }

    private void change(UnaryOperator<CarryConfig> change, Player player, String text) throws IOException {
        store.replace(change.apply(store.current()));
        message(player, text, false);
    }

    private void navigate(View next) { view = next; page = 0; }

    private void render() {
        observed = store.current();
        getContainer().clearContent();
        shown = List.of();
        for (int slot = 0; slot < 9; slot++) blank(slot);
        for (int slot = 45; slot < 54; slot++) blank(slot);
        button(4, Items.ENDER_PEARL, viewName(), false,
                view == View.HOME || view == View.ADVANCED ? "分别配置末影人的拿起与放下规则" : summary(action),
                "普通左键或右键操作；修改立即生效并保存。");
        if (view != View.HOME) button(BACK_SLOT, Items.ARROW, "返回上一级", false);
        button(HOME_SLOT, Items.COMPASS, "返回首页", false);
        button(CLOSE_SLOT, Items.BARRIER, "关闭界面", false, "修改已自动保存。");
        switch (view) {
            case HOME -> {
                button(PICKUP_SLOT, Items.GRASS_BLOCK, "拿起规则", false, summary(Action.PICKUP), "点击进入：模式、名单。");
                button(PLACE_SLOT, Items.CHEST, "放下规则", false, summary(Action.PLACE), "配置末影人手中方块的放下许可。");
                button(31, Items.BOOK, "使用说明", false, "1. 选择拿起或放下", "2. 选择模式；名单需使用白名单或黑名单", "3. 进入管理名单，分别添加或移除条目");
                button(ADVANCED_SLOT, Items.COMPARATOR, "高级设置", false, "重新加载配置文件、恢复全部原版规则。");
            }
            case RULE -> {
                button(MODE_SLOT, modeIcon(observed.rule(action).mode()), "选择模式", false,
                        "当前：" + modeName(observed.rule(action).mode()), "点击查看五种模式及说明。");
                button(LIST_SLOT, Items.BOOK, "管理名单", false, "已保存 " + observed.rule(action).blocks().size() + " 个条目",
                        listMode() ? "进入：添加方块、添加标签、移除条目、清空。" : "当前模式不使用名单；点击后先选择白名单或黑名单。");
            }
            case MODE -> {
                if (afterMode != View.RULE) button(13, Items.BOOK, "先选择名单模式", false,
                        "白名单：只允许名单内方块", "黑名单：在原版范围内排除名单内方块", "选择白名单或黑名单后继续编辑名单。");
                for (Mode mode : Mode.values()) button(MODE_START + mode.ordinal(), modeIcon(mode), modeName(mode),
                        observed.rule(action).mode() == mode, mode.id(), modeDescription(mode), "点击选择并保存；已保存名单保留。");
            }
            case LIST -> {
                button(BLOCKS_SLOT, Items.STONE, "添加方块", false, "浏览方块，点击添加。", "也可在添加页面点击背包中的方块。");
                button(TAGS_SLOT, Items.NAME_TAG, "添加方块标签", false, "例如 #minecraft:logs", "点击一个标签，匹配该标签内的方块。");
                button(REMOVE_SLOT, Items.SHEARS, "移除条目", false, "查看已保存的 " + observed.rule(action).blocks().size() + " 个条目", "点击条目只执行移除。");
                button(CLEAR_SLOT, Items.BUCKET, "清空名单", false, "清空" + actionName() + "名单；保留模式。", "需要再次确认。");
            }
            case ADD_BLOCKS, ADD_TAGS, REMOVE -> renderBrowser();
            case ADVANCED -> {
                button(RELOAD_SLOT, Items.CLOCK, "重新加载配置文件", false, "读取 config/enderman-carry-control.json", "加载失败时保留当前规则。");
                button(RESET_SLOT, Items.REDSTONE_TORCH, "恢复全部原版规则", false, "同时重置拿起、放下模式并清空两个名单。", "需要再次确认。");
            }
            case CONFIRM_CLEAR, CONFIRM_RESET -> {
                boolean reset = view == View.CONFIRM_RESET;
                button(13, Items.REDSTONE_TORCH, reset ? "确认恢复全部原版规则？" : "确认清空" + actionName() + "名单？", false,
                        reset ? "两种模式恢复原版，两个名单清空。" : "当前模式保留，名单清空。", "此操作保存后立即生效。");
                button(29, Items.CONCRETE.pick(DyeColor.LIME), "确认并保存", false);
                button(33, Items.CONCRETE.pick(DyeColor.RED), "取消，返回上一级", false);
            }
        }
    }

    private void renderBrowser() {
        List<String> entries = switch (view) {
            case REMOVE -> observed.rule(action).blocks();
            case ADD_TAGS -> BuiltInRegistries.BLOCK.getTags().map(tag -> "#" + tag.key().location()).sorted().toList();
            default -> BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString)
                    .filter(id -> !id.equals("minecraft:air")).sorted().toList();
        };
        entries = entries.stream().filter(entry -> filter.isEmpty() || entry.contains(filter)).toList();
        pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        shown = List.copyOf(entries.subList(page * PAGE_SIZE, Math.min(entries.size(), (page + 1) * PAGE_SIZE)));
        for (int index = 0; index < shown.size(); index++) {
            String entry = shown.get(index);
            boolean listed = observed.rule(action).blocks().contains(entry);
            button(CONTENT_START + index, entryIcon(entry), entry, listed,
                    view == View.REMOVE ? "点击：从" + actionName() + "名单移除" : listed ? "已添加；请到“移除条目”页面删除。" : "点击：加入" + actionName() + "名单");
        }
        if (shown.isEmpty()) button(22, Items.PAPER, view == View.REMOVE ? "名单为空" : "没有匹配的条目", false,
                filter.isEmpty() ? "使用下方返回按钮继续操作。" : "点击下方望远镜清除筛选。");
        button(PREVIOUS_SLOT, Items.ARROW, "上一页", false, "左键 1 页，右键 10 页。");
        button(NEXT_SLOT, Items.ARROW, "下一页", false, "左键 1 页，右键 10 页。");
        button(INFO_SLOT, Items.PAPER, (page + 1) + "/" + pages + " 页 · " + entries.size() + " 个条目", false,
                view == View.ADD_BLOCKS ? "此页点击背包方块可添加；物品不会移动或消耗。" : "点击条目执行当前页面的操作。");
        button(SEARCH_SLOT, Items.SPYGLASS, filter.isEmpty() ? "筛选提示" : "清除筛选：" + filter, false,
                "/ecc gui " + action.id() + " 关键词", "例如 /ecc gui pickup stone");
    }

    private void blank(int slot) { button(slot, Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY), " ", false); }

    private void button(int slot, ItemLike item, String title, boolean selected, String... description) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal((selected ? "✓ " : "") + title)
                .withStyle(style -> style.withColor(selected ? ChatFormatting.GREEN : ChatFormatting.GOLD).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(description)
                .map(line -> (Component) Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false))).toList()));
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
        return (target == Action.PICKUP ? "拿起" : "放下") + " · " + modeName(rule.mode()) + " · " + rule.blocks().size() + " 个名单条目";
    }

    private String actionName() { return action == Action.PICKUP ? "拿起" : "放下"; }
    private String viewName() {
        return switch (view) {
            case HOME -> "首页 · 选择规则";
            case RULE -> actionName() + " · 规则设置";
            case MODE -> actionName() + " · 选择模式";
            case LIST -> actionName() + " · 管理名单";
            case ADD_BLOCKS -> actionName() + " · 添加方块";
            case ADD_TAGS -> actionName() + " · 添加方块标签";
            case REMOVE -> actionName() + " · 移除条目";
            case ADVANCED -> "高级设置";
            case CONFIRM_CLEAR, CONFIRM_RESET -> "确认操作";
        };
    }

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
            case WHITELIST -> "只允许名单内的方块；拿起可扩展原版范围。";
            case BLACKLIST -> "在原版允许范围内，排除名单中的方块。";
            case ALL -> "允许所有类型；保留原版时机、位置和数据保护。";
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
