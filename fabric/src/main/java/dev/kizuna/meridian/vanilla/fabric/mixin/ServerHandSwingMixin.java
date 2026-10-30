package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerHandSwingMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onHandSwing", at = @At("TAIL"))
    private void meridian$exertEmptySwing(HandSwingC2SPacket packet, CallbackInfo callback) {
        // 原版已经切回主线程并验证连接，客户端只提交挥手意图。
        VanillaStrikeContext.swing(player, packet.getHand());
    }
}
