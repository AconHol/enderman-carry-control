package io.github.aconhol.endermancarry.mixin;

import io.github.aconhol.endermancarry.BlockRules;
import io.github.aconhol.endermancarry.EndermanCarryControl;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only veto a placement that has already passed every vanilla placement check. */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal", remap = false)
abstract class EndermanLeaveBlockGoalMixin {
    @Shadow @Final private EnderMan enderman;

    @Inject(method = "canPlaceBlock", at = @At("RETURN"), cancellable = true)
    private void ecc$filterPlacement(CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ()) return;
        BlockState held = enderman.getCarriedBlock();
        if (held != null && !BlockRules.allows(EndermanCarryControl.config().current().place(), held, true, false)) {
            result.setReturnValue(false);
        }
    }
}
