package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponHeatProfile;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge.PreparedHit;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

final class VanillaWeaponHeat {
    private VanillaWeaponHeat() {
        // 烫伤不另存计时或身体状态。
    }

    static void burnHand(LivingEntity actor, InteractionHand hand, WeaponHeatProfile heat) {
        // 按实际惯用侧解释主副手，落在握剑手的皮肤上。
        if (immune(actor)) {
            return;
        }
        HumanoidArm main = actor.getMainArm();
        HumanoidArm arm;
        if (hand == InteractionHand.OFF_HAND) {
            arm = main.getOpposite();
        } else {
            arm = main;
        }
        String part = arm == HumanoidArm.LEFT ? "left_hand" : "right_hand";
        var damage = new VanillaDamageFabric.DirectDamage(actor, source(actor, null, heat), part,
                heat.getHandDamageHp());
        VanillaDamageFabric.damageDirect(damage, VanillaDamageFabric.DirectLayer.SKIN);
    }

    static void burnTarget(LivingEntity target, LivingEntity actor, WeaponHeatProfile heat,
                           double strikeHp, Optional<PreparedHit> hit) {
        // 命中部位已由主击固定；普通原版目标沿用附伤的原版无敌帧处理。
        if (immune(target)) {
            return;
        }
        DamageSource fire = source(target, actor, heat);
        double burn = strikeHp * heat.getHitDamageRatio();
        if (hit.isEmpty()) {
            VanillaInfusions.dealToVanilla((ServerLevel) target.level(), target, fire, burn);
            return;
        }
        hit.get().target().filter(HealthTarget::hasPartId).ifPresent(part -> {
            double bodyHp = VanillaDamageFabric.preferences().damageHp(burn);
            var damage = new VanillaDamageFabric.DirectDamage(target, fire, part.getPartId(), bodyHp);
            VanillaDamageFabric.damageDirect(damage, VanillaDamageFabric.DirectLayer.SKIN);
        });
    }

    private static boolean immune(LivingEntity target) {
        // 实体火免和原版抗火都能免受剑身的热量。
        return target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE);
    }

    private static DamageSource source(LivingEntity target, LivingEntity actor, WeaponHeatProfile heat) {
        // 烫手没有攻击者，命中附伤按实际施放者归属。
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(heat.getFireDamageType()));
        var type = target.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
        return new DamageSource(type, actor);
    }
}
