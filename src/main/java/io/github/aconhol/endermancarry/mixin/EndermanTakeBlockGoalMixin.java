package io.github.aconhol.endermancarry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.aconhol.endermancarry.BlockRules;
import io.github.aconhol.endermancarry.EndermanCarryControl;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve vanilla's goal scheduling, random draws, line of sight and gamerule checks. */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal", remap = false)
abstract class EndermanTakeBlockGoalMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean ecc$filterPickup(BlockState state, TagKey<Block> tag, Operation<Boolean> original) {
        boolean vanillaAllowed = original.call(state, tag);
        if (!tag.equals(BlockTags.ENDERMAN_HOLDABLE)) return vanillaAllowed;
        return BlockRules.allows(EndermanCarryControl.config().current().pickup(), state, vanillaAllowed, true);
    }
}
