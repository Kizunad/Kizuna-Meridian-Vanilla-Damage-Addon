package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerEntityDamageMixin {
    @WrapMethod(method = "attack")
    private void meridian$attackScope(Entity target, Operation<Void> original) {
        // 包围整个出手及横扫循环，目标拒绝和异常退出也不重复或泄漏上下文。
        // 26.2 的 Fabric 攻击回调也在这层包装里面，回调拒绝的攻击不开出手，也不吞掉随后的挥手包。
        var player = (Player) (Object) this;
        var scope = VanillaStrikeContext.attackScope(player);
        try (scope) {
            original.call(target);
        }
        if (scope != null && scope.started() && player instanceof ServerPlayer serverPlayer) {
            VanillaStrikeContext.attacked(serverPlayer);
        }
    }

    @Inject(method = "attack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;cannotAttack(Lnet/minecraft/world/entity/Entity;)Z"))
    private void meridian$beginAttack(Entity target, CallbackInfo callback) {
        // 方法体第一句之前开本次出手：头部的 Fabric 攻击回调已放行，目标不可攻击仍按出手记账，与 1.20.1 一致。
        VanillaStrikeContext.beginAttack((Player) (Object) this);
    }

    @ModifyVariable(method = "actuallyHurt", at = @At("HEAD"), argsOnly = true)
    private float meridian$beginPlayerApplication(float amount, ServerLevel level, DamageSource source,
                                                  float originalAmount) {
        // 玩家覆盖了 actuallyHurt，建立自身调用范围以复用父类的护甲阶段注入。
        return VanillaDamageFabric.beginApplication((Player) (Object) this, source, amount);
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void meridian$endPlayerApplication(ServerLevel level, DamageSource source, float amount,
                                               CallbackInfo callback) {
        // 即使吸收生命挡住全部伤害，也释放尚未提交的选点。
        VanillaDamageFabric.endApplication((Player) (Object) this);
    }

    @Redirect(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setHealth(F)V"))
    private void meridian$recordPlayerLoss(Player player, float requestedHealth, ServerLevel level,
                                           DamageSource source, float amount) {
        // 玩家重写了 actuallyHurt，必须单独接入；该路径不会再调用 LivingEntity 的实现。
        float previousHealth = player.getHealth();
        boolean integrated = VanillaDamageFabric.applyBodyDamage(player, source, Math.max(0, previousHealth - requestedHealth));
        if (!integrated) {
            player.setHealth(requestedHealth);
        }
    }
}
