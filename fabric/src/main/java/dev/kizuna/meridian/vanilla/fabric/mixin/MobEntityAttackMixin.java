package dev.kizuna.meridian.vanilla.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MobEntity.class)
public abstract class MobEntityAttackMixin {
    @WrapMethod(method = "tryAttack")
    private boolean meridian$attackScope(Entity target, Operation<Boolean> original) {
        // 生物攻击同样在目标盾挡或拒绝之前完成出手做功。
        var mob = (MobEntity) (Object) this;
        try (var ignored = VanillaStrikeContext.attack(mob, mob.getDamageSources().mobAttack(mob))) {
            return original.call(target);
        }
    }
}
