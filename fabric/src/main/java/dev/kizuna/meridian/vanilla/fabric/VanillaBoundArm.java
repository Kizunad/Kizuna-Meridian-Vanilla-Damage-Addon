package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponBinds;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

final class VanillaBoundArm implements TechniqueWeaponBinds.ArmHost {
    @Override
    public Optional<TechniqueWeaponBinds.Arm> inspect(LivingEntity entity, InteractionHand hand) {
        // 左利手的副手是右臂；没有该前臂或已毁损的身体不能被缠住。
        HumanoidArm main = entity.getMainArm();
        HumanoidArm side;
        if (hand == InteractionHand.OFF_HAND) {
            side = main.getOpposite();
        } else {
            side = main;
        }
        String part = side == HumanoidArm.LEFT ? "left_forearm" : "right_forearm";
        var body = VanillaDamageFabric.inspectBody(entity);
        if (body.isEmpty() || body.get().getPartStatesList().stream()
                .noneMatch(state -> state.getId().equals(part) && state.getHp() > 0)) {
            return Optional.empty();
        }
        return Optional.of(new TechniqueWeaponBinds.Arm(part, VanillaDamageFabric.armCoefficient(entity, side)));
    }

    @Override
    public void stagger(LivingEntity caster, long millis) {
        // 踉跄复用原版伤害附属的受击减速和禁出招时钟。
        VanillaDamageFabric.logExternalStagger(caster, millis, "weapon_bind");
        double movement = VanillaDamageFabric.preferences().stagger().movementMultiplier();
        var stumble = new TechniqueService.Stagger(millis, movement, TechniqueService.StaggerKind.HIT);
        TechniqueFabric.service().stagger(caster.getUUID(), stumble);
    }
}
