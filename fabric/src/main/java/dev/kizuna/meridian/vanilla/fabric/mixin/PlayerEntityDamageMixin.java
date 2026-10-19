package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityDamageMixin {
    @Inject(method = "applyDamage", at = @At("HEAD"))
    private void meridian$beginPlayerApplication(DamageSource source, float amount, CallbackInfo callback) {
        // 玩家覆盖了 applyDamage，建立自身调用范围以复用父类的护甲阶段注入。
        VanillaDamageFabric.beginApplication((PlayerEntity) (Object) this);
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
