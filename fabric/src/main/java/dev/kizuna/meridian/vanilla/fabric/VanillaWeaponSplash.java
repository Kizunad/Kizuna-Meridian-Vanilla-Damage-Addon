package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponSplash;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.OptionalDouble;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

final class VanillaWeaponSplash {
    private VanillaWeaponSplash() {
        // 局部热伤和迟滞共用一次可信身体事务。
    }

    static boolean hit(LivingEntity target, DamageSource source, double dose, WeaponSplash splash) {
        // 原版生命只有一条血量；有身体时在仍完好的部位中无放回取样。
        if (target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return false;
        }
        var body = VanillaDamageFabric.inspectBody(target);
        if (body.isEmpty()) {
            return VanillaInfusions.dealToVanilla((ServerLevel) target.level(), target, source, dose);
        }
        var snapshot = body.orElseThrow();
        var parts = hitParts(snapshot);
        if (parts.isEmpty()) {
            return false;
        }
        var random = target.getRandom();
        int hits = 1 + random.nextInt(Math.min(parts.size(), splash.getMaxParts()));
        double bodyHp = VanillaDamageFabric.bodyHp(dose);
        boolean landed = false;
        for (int index = 0; index < hits && target.isAlive(); index++) {
            String part = parts.remove(random.nextInt(parts.size()));
            var extras = new VanillaDamageFabric.DirectExtras(OptionalDouble.empty(), 1,
                    modifiers(snapshot, part, splash));
            var damage = new VanillaDamageFabric.DirectDamage(target, source, part, bodyHp, extras);
            boolean skin = snapshot.getStructure().getTissuesList().stream()
                    .anyMatch(t -> t.getPartId().equals(part) && t.getKind().equals("human:skin"));
            var layer = skin ? VanillaDamageFabric.DirectLayer.SKIN : VanillaDamageFabric.DirectLayer.PART;
            if (VanillaDamageFabric.damageDirect(damage, layer).isPresent()) {
                landed = true;
            }
        }
        if (landed) {
            VanillaDamageFabric.presentHurt(target, source);
        }
        return landed;
    }

    private static List<String> hitParts(BodySnapshot body) {
        // 沉积优先落在有出力组织的完好部位；只有被动组织的身体仍可被烫伤。
        var mobile = new HashSet<String>();
        for (var tissue : body.getStructure().getTissuesList()) {
            if (tissue.getCapacity().getBurstWatts() > 0) {
                mobile.add(tissue.getPartId());
            }
        }
        var intact = body.getPartStatesList().stream().filter(part -> part.getHp() > 0).map(PartState::getId).toList();
        var candidates = new ArrayList<>(intact.stream().filter(mobile::contains).toList());
        if (candidates.isEmpty()) {
            candidates.addAll(intact);
        }
        return candidates;
    }

    private static List<CapabilityModifier> modifiers(BodySnapshot body, String part, WeaponSplash splash) {
        // 每个部位只拖慢本部位的出力组织；同身份再泼刷新该组织的曲线。
        var coating = splash.getCoating();
        var start = CurvePoint.newBuilder().setValue(1).build();
        var end = start.toBuilder().setOffsetMillis(coating.getDurationMillis()).build();
        var result = new ArrayList<CapabilityModifier>();
        for (var tissue : body.getStructure().getTissuesList()) {
            if (!tissue.getPartId().equals(part) || tissue.getCapacity().getBurstWatts() <= 0) {
                continue;
            }
            var modifier = CapabilityModifier.newBuilder()
                    .setId(coating.getId() + "/" + tissue.getId())
                    .setTissueId(tissue.getId())
                    .setTempoPeak(splash.getTempoMultiplier())
                    .setStrengthPeak(1)
                    .setBurstPeak(1)
                    .setEndurancePeak(1)
                    .setRecoveryPeak(1)
                    .addCurve(start)
                    .addCurve(end)
                    .build();
            result.add(modifier);
        }
        return result;
    }
}
