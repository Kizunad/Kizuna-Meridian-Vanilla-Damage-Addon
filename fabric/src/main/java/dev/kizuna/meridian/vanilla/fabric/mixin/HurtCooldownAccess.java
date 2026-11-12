package dev.kizuna.meridian.vanilla.fabric.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 无身体目标吃附伤时绕过了无敌帧，打完要把上次伤害值放回主击之后的样子。 */
@Mixin(LivingEntity.class)
public interface HurtCooldownAccess {
    @Accessor("lastHurt")
    float meridian$lastHurt();

    @Accessor("lastHurt")
    void meridian$setLastHurt(float lastHurt);
}
