package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityDamageMixin {
    @WrapMethod(method = "attack")
    private void meridian$attackScope(Entity target, Operation<Void> original) {
        // 包围整个出手及横扫循环，目标拒绝和异常退出也不重复或泄漏上下文。
        var player = (PlayerEntity) (Object) this;
        try (var ignored = VanillaStrikeContext.attack(player, player.getDamageSources().playerAttack(player))) {
            original.call(target);
        }
        if (player instanceof ServerPlayerEntity serverPlayer) {
            VanillaStrikeContext.attacked(serverPlayer);
        }
    }

    @ModifyVariable(method = "applyDamage", at = @At("HEAD"), argsOnly = true)
    private float meridian$beginPlayerApplication(float amount, DamageSource source, float originalAmount) {
        // 玩家覆盖了 applyDamage，建立自身调用范围以复用父类的护甲阶段注入。
        return VanillaDamageFabric.beginApplication((PlayerEntity) (Object) this, source, amount);
    }

    @Inject(method = "applyDamage", at = @At("RETURN"))
    private void meridian$endPlayerApplication(DamageSource source, float amount, CallbackInfo callback) {
        // 即使吸收生命挡住全部伤害，也释放尚未提交的选点。
        VanillaDamageFabric.endApplication((PlayerEntity) (Object) this);
    }

    @Redirect(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;setHealth(F)V"))
    private void meridian$recordPlayerLoss(PlayerEntity player, float requestedHealth, DamageSource source, float amount) {
        // 玩家重写了 applyDamage，必须单独接入；该路径不会再调用 LivingEntity 的实现。
        float previousHealth = player.getHealth();
        boolean integrated = VanillaDamageFabric.applyBodyDamage(player, source, Math.max(0, previousHealth - requestedHealth));
        if (!integrated) {
            player.setHealth(requestedHealth);
        }
    }
}
