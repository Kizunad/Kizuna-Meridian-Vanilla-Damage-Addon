package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
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

    @Inject(method = "handlePlayerAction", at = @At("HEAD"))
    private void meridian$trackPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        // 挖掘动画可能连续到达；只在对应的动作包存在时抑制，避免把挥空误认成交互。
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> VanillaStrikeContext.startMining(player);
            case ABORT_DESTROY_BLOCK -> VanillaStrikeContext.abortMining(player);
            case STOP_DESTROY_BLOCK -> VanillaStrikeContext.stopMining(player);
            case DROP_ITEM, DROP_ALL_ITEMS -> VanillaStrikeContext.suppressNextSwing(player);
            default -> { }
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void meridian$trackItemUse(ServerboundUseItemPacket packet, CallbackInfo callback) {
        // 使用物品的挥手只播放交互动画，不产生近战做功。
        if (packet.getHand() == InteractionHand.MAIN_HAND) {
            VanillaStrikeContext.suppressNextSwing(player);
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void meridian$trackBlockUse(ServerboundUseItemOnPacket packet, CallbackInfo callback) {
        // 使用方块的挥手只播放交互动画，不产生近战做功。
        if (packet.getHand() == InteractionHand.MAIN_HAND) {
            VanillaStrikeContext.suppressNextSwing(player);
        }
    }

    @Inject(method = "handleInteract", at = @At("HEAD"))
    private void meridian$trackEntityInteract(ServerboundInteractPacket packet, CallbackInfo callback) {
        // 右键实体的挥手只播放交互动画，不产生近战做功。
        if (packet.hand() == InteractionHand.MAIN_HAND) {
            VanillaStrikeContext.suppressNextSwing(player);
        }
    }

    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void meridian$clearSwingState(DisconnectionDetails details, CallbackInfo callback) {
        // 连接结束后不让旧玩家的包配对状态影响新连接。
        VanillaStrikeContext.clearPlayer(player);
    }
}
