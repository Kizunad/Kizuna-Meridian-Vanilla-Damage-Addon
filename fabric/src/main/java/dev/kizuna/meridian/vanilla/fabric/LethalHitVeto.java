package dev.kizuna.meridian.vanilla.fabric;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** 命中提交前认领点到为止的交手，宿主只在实际挡下致死时收到通知。 */
public interface LethalHitVeto {
    record Hit(LivingEntity target, LivingEntity attacker, DamageSource source) { }

    boolean protects(Hit hit);

    void spared(Hit hit);

    static void register(LethalHitVeto veto) {
        Registered.RULES.add(Objects.requireNonNull(veto));
    }

    final class Registered {
        private static final List<LethalHitVeto> RULES = new CopyOnWriteArrayList<>();

        private Registered() { }

        static LethalHitVeto first(Hit hit) {
            for (var rule : RULES) {
                if (rule.protects(hit)) {
                    return rule;
                }
            }
            return null;
        }
    }
}
