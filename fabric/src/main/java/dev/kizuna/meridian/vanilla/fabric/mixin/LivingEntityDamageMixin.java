package dev.kizuna.meridian.vanilla.fabric.mixin;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {
    @Shadow protected abstract void hurtArmor(DamageSource source, float amount);

    @WrapMethod(method = "hurtServer")
    private boolean meridian$strikeScope(ServerLevel level, DamageSource source, float amount,
                                         Operation<Boolean> original) {
        // 直接伤害在盾挡前记账；横扫目标沿用外层攻击的同一笔做功。
        try (var ignored = VanillaStrikeContext.damage(source)) {
            return original.call(level, source, amount);
        }
    }

    @ModifyVariable(method = "actuallyHurt", at = @At("HEAD"), argsOnly = true)
    private float meridian$beginApplication(float amount, ServerLevel level, DamageSource source,
                                            float originalAmount) {
        // 玩家覆盖的实现单独建立上下文；本入口只负责普通生物路径。
        return VanillaDamageFabric.beginApplication((LivingEntity) (Object) this, source, amount);
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void meridian$endApplication(ServerLevel level, DamageSource source, float amount,
                                         CallbackInfo callback) {
        // 零伤害早退也清理上下文，不把本次选点留给下一次攻击。
        VanillaDamageFabric.endApplication((LivingEntity) (Object) this);
    }

    @Inject(method = "getDamageAfterArmorAbsorb", at = @At("HEAD"), cancellable = true)
    private void meridian$applyLocalArmor(DamageSource source, float amount, CallbackInfoReturnable<Float> callback) {
        // 只替换全局护甲数值结算，后续 getDamageAfterMagicAbsorb 继续执行原版非护甲机制。
        var replacement = VanillaDamageFabric.prepareArmor((LivingEntity) (Object) this, source, amount);
        if (replacement.isPresent()) {
            if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
                // 装备耐久维持原版规则；命中所用数值在本次磨损前已经读取。
                hurtArmor(source, amount);
            }
            callback.setReturnValue(replacement.get());
        }
    }

    @Redirect(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setHealth(F)V"))
    private void meridian$recordFinalLoss(LivingEntity entity, float requestedHealth, ServerLevel level,
                                          DamageSource source, float amount) {
        // 身体接收完整结算剂量，原版血条的归零钳制不能截断高于剩余血量的伤害。
        float previousHealth = entity.getHealth();
        boolean integrated = VanillaDamageFabric.applyBodyDamage(entity, source, Math.max(0, previousHealth - requestedHealth));
        if (!integrated) {
            entity.setHealth(requestedHealth);
        }
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void meridian$finishDamage(ServerLevel level, DamageSource source, float amount,
                                       CallbackInfoReturnable<Boolean> callback) {
        // 原版图腾与死亡分支先结束，身体判死的同步随后完成且最多执行一次。
        VanillaDamageFabric.finishDamage((LivingEntity) (Object) this);
    }
}
