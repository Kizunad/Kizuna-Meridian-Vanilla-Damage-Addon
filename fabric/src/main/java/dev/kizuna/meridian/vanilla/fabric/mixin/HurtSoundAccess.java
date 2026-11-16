package dev.kizuna.meridian.vanilla.fabric.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 直接伤害不经原版受伤入口，要补受伤表现时借原版自己的受伤声。 */
@Mixin(LivingEntity.class)
public interface HurtSoundAccess {
    @Invoker("playHurtSound")
    void meridian$playHurtSound(DamageSource source);
}
