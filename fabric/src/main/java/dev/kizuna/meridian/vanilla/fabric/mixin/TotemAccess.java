package dev.kizuna.meridian.vanilla.fabric.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 伤害事务确认保命后调用原版图腾，保留原版物品与视听副作用。 */
@Mixin(LivingEntity.class)
public interface TotemAccess {
    @Invoker("checkTotemDeathProtection")
    boolean meridian$tryUseTotem(DamageSource source);
}
