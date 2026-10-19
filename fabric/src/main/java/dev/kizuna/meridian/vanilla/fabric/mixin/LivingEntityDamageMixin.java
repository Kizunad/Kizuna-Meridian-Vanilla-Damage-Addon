package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.registry.tag.DamageTypeTags;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {
    @Shadow protected abstract void damageArmor(DamageSource source, float amount);

    @Inject(method = "applyDamage", at = @At("HEAD"))
    private void meridian$beginApplication(DamageSource source, float amount, CallbackInfo callback) {
        // 玩家覆盖的实现单独建立上下文；本入口只负责普通生物路径。
        VanillaDamageFabric.beginApplication((LivingEntity) (Object) this);
    }

    @Inject(method = "applyDamage", at = @At("RETURN"))
    private void meridian$endApplication(DamageSource source, float amount, CallbackInfo callback) {
        // 零伤害早退也清理上下文，不把本次选点留给下一次攻击。
        VanillaDamageFabric.endApplication((LivingEntity) (Object) this);
    }

    @Inject(method = "applyArmorToDamage", at = @At("HEAD"), cancellable = true)
    private void meridian$applyLocalArmor(DamageSource source, float amount, CallbackInfoReturnable<Float> callback) {
        // 只替换全局护甲数值结算，后续 modifyAppliedDamage 继续执行原版非护甲机制。
        var replacement = VanillaDamageFabric.prepareArmor((LivingEntity) (Object) this, source, amount);
        if (replacement.isPresent()) {
            if (!source.isIn(DamageTypeTags.BYPASSES_ARMOR)) {
                // 装备耐久维持原版规则；命中所用数值在本次磨损前已经读取。
                damageArmor(source, amount);
            }
            callback.setReturnValue(replacement.get());
        }
    }

    @Redirect(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;setHealth(F)V"))
    private void meridian$recordFinalLoss(LivingEntity entity, float requestedHealth, DamageSource source, float amount) {
        // 身体接收完整结算剂量，原版血条的归零钳制不能截断高于剩余血量的伤害。
        float previousHealth = entity.getHealth();
        boolean integrated = VanillaDamageFabric.applyBodyDamage(entity, source, Math.max(0, previousHealth - requestedHealth));
        if (!integrated) {
            entity.setHealth(requestedHealth);
        }
    }

    @Inject(method = "damage", at = @At("RETURN"))
    private void meridian$finishDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        // 原版图腾与死亡分支先结束，身体判死的同步随后完成且最多执行一次。
        VanillaDamageFabric.finishDamage((LivingEntity) (Object) this);
    }
}
