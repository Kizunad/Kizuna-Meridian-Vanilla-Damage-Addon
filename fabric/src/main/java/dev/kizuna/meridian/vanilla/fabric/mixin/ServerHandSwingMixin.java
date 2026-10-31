package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerHandSwingMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void meridian$exertEmptySwing(ServerboundSwingPacket packet, CallbackInfo callback) {
        // 原版已经切回主线程并验证连接，客户端只提交挥手意图。
        VanillaStrikeContext.swing(player, packet.getHand());
    }
}
