package io.github.aconhol.endermancarry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockRules {
    private BlockRules() {}

    public static String normalize(String entry) {
        boolean tag = entry.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? entry.substring(1) : entry);
        if (id == null) throw new IllegalArgumentException("方块 ID 或标签无效: " + entry);
        if (!tag && !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("未知方块: " + id);
        }
        return (tag ? "#" : "") + id;
    }

    public static void validateCanonical(String entry) {
        if (!normalize(entry).equals(entry)) {
            throw new IllegalArgumentException("配置中的 ID 须包含命名空间，例如 minecraft:stone: " + entry);
        }
    }

    public static boolean allows(CarryConfig.Rule rule, BlockState state, boolean vanillaAllowed,
                                 boolean pickup) {
        boolean allowed = rule.allows(vanillaAllowed, entry -> matches(state, entry));
        // Vanilla carries only a BlockState, never block entity NBT. Do not destroy inventories
        // when a whitelist extends vanilla's holdable set. Vanilla mode itself is untouched.
        if (allowed && pickup && !vanillaAllowed && (state.isAir() || state.hasBlockEntity())) return false;
        return allowed;
    }

    private static boolean matches(BlockState state, String entry) {
        if (entry.startsWith("#")) {
            return state.is(TagKey.create(Registries.BLOCK, Identifier.parse(entry.substring(1))));
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(entry);
    }
}
