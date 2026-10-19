package io.github.aconhol.endermancarry.mixin;

import io.github.aconhol.endermancarry.CarryConfigMenu;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only this mod's button GUI bypasses vanilla chest inventory prediction handling. */
@Mixin(value = ServerGamePacketListenerImpl.class, remap = false)
public abstract class ConfigMenuPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void endermancarry$handleButtons(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.level());
        if (player.containerMenu instanceof CarryConfigMenu menu) {
            menu.handleClick(packet, player);
            ci.cancel();
        }
    }
}
