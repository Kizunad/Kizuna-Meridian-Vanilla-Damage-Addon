package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 护甲结算后的本次命中，可按目标状态缩放承伤。 */
public record IncomingDamage(LivingEntity target, VanillaDamageFabric.DamageProvider provider, DamageKind kind,
                             Optional<String> part) {
    private static final List<ToDoubleFunction<IncomingDamage>> RULES = new CopyOnWriteArrayList<>();

    public static void register(ToDoubleFunction<IncomingDamage> rule) {
        RULES.add(Objects.requireNonNull(rule));
    }

    public static final class QiSource extends DamageSource {
        public QiSource(Holder<DamageType> type, Entity direct, Entity caster) {
            super(type, direct, caster);
        }
    }

    static double apply(LivingEntity target, DamageSource source, DamageKind kind, Optional<String> part, double hp) {
        // 附加剂量和主击各用各自的来源、部位与护甲后剂量。
        if (RULES.isEmpty() || hp == 0) {
            return hp;
        }
        var hit = new IncomingDamage(target, VanillaDamageFabric.damageProvider(source), kind, part);
        double amount = hp;
        for (var rule : RULES) {
            double multiplier = rule.applyAsDouble(hit);
            amount *= multiplier;
            if (multiplier < 0 || !Double.isFinite(amount) || amount > Float.MAX_VALUE) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
        }
        return amount;
    }
}
