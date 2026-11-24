package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.DamageAttachment;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Guard;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.GuardState;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.GuardRules;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/** 直接读取功法库守状态；只保存当前命中的身份，不另存守的生命周期。 */
final class TechniqueGuardDefense {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_vanilla_damage");

    private TechniqueGuardDefense() { }

    static void register(VanillaDamageBridge bridge) {
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addCastCheck((entity, request) ->
                check(service, bridge, entity, request)));
    }

    private static Optional<String> check(TechniqueService service, VanillaDamageBridge bridge,
                                           UUID entity, CastRequest request) {
        boolean guard = service.registry().find(request.getTechnique())
                .map(definition -> definition.getEffectsList().stream().anyMatch(TechniqueEffect::hasGuard))
                .orElse(false);
        if (!guard) {
            return Optional.empty();
        }
        var arm = bridge.inspectBody(entity.toString()).flatMap(VanillaDamageFabric.preferences().guard()::selectArm);
        if (arm.isEmpty()) {
            return Optional.of(VanillaDamageErrors.GUARD_UNAVAILABLE);
        }
        return Optional.empty();
    }

    static Optional<GuardState> active(LivingEntity entity) {
        // 功法宿主查询可能结算身体时钟，必须先于 Damage 固定命中 revision。
        return TechniqueFabric.service().activeGuard(entity.getUUID());
    }

    static void attach(LivingEntity entity, VanillaDamageBridge.PreparedHit hit, Defense defense,
                       VanillaDamageFabric.GuardContact contact, double dose) {
        // 护甲、吸收和兵器耐久均在守式免掉的这部分之外。
        var guarded = contact.state().filter(state -> state.guard().hasStamina());
        double prevented = dose * (1 - defense.multiplier());
        if (guarded.isEmpty() || prevented <= 0) {
            return;
        }
        var body = hit.body();
        var payment = VanillaDamageFabric.guardCost().payment(body, guarded.get(), prevented, contact.tool());
        if (!payment.equals(DamageAttachment.getDefaultInstance())) {
            var activity = BodyActivityExecution.damageActivity(payment);
            var submission = new TechniqueHost.Submission(UUID.randomUUID().toString(), body.getRevision(), activity);
            hit.attach(payment, () -> {
                var host = TechniqueFabric.service().statusHost(entity.getUUID()).orElseThrow();
                // 首次 inspect 是路由认领时的快照；先核对命中身份，提交后才能读取新版本。
                var current = host.inspect().body();
                if (!current.id().value().equals(body.getId()) || current.revision() != submission.expectedRevision()) {
                    throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.INVALID_INTEGRATION);
                }
                host.submit(submission);
                return BodyProtobuf.toMessage(host.inspect().body());
            });
        }
    }

    static Defense prepare(LivingEntity entity, DamageSource source, VanillaDamageBridge.PreparedHit hit,
                            OptionalDouble organShare, Optional<GuardState> observed) {
        Optional<GuardState> active = observed.filter(state -> state.bodyId().equals(hit.body().getId()));
        Defense unchanged = new Defense(active, Optional.empty(), 1);
        if (active.isEmpty() || organShare.isPresent() || excluded(source)) {
            return unchanged;
        }
        Guard guard = active.get().guard();
        if (!matches(source, guard)) {
            return unchanged;
        }
        Vec3 facing = Vec3.directionFromRotation(0, entity.getYRot());
        Optional<Vec3> incoming = incoming(entity, source);
        if (incoming.isEmpty() || !GuardRules.withinAngle(horizontal(facing), horizontal(incoming.get()),
                guard.getAngle().getValue()) || !hit.redirectGuard()) {
            return unchanged;
        }
        return new Defense(active, active, guard.getDamageMultiplier().getValue());
    }

    private static boolean excluded(DamageSource source) {
        // 即使数据包同时加入白名单，绕盾、环境和药水伤害也不由前臂承接。
        return source.is(DamageTypeTags.BYPASSES_SHIELD)
                || source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_EXPLOSION)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC);
    }

    private static boolean matches(DamageSource source, Guard guard) {
        return guard.getDamageTagsList().stream().anyMatch(tag ->
                source.is(TagKey.create(Registries.DAMAGE_TYPE, Identifier.parse(tag))));
    }

    private static Optional<Vec3> incoming(LivingEntity entity, DamageSource source) {
        if (source.getDirectEntity() instanceof Projectile projectile) {
            // 箭命中时取弹道反向；射手移动或箭已进入碰撞箱都不改变来向。
            return Optional.of(projectile.getDeltaMovement().reverse());
        }
        Optional<Vec3> sourcePosition = Optional.ofNullable(source.getSourcePosition())
                .map(position -> position.subtract(entity.position()));
        if (sourcePosition.isPresent()) {
            return sourcePosition;
        }
        if (source.getEntity() != null) {
            return Optional.of(source.getEntity().position().subtract(entity.position()));
        }
        return Optional.empty();
    }

    private static GuardRules.Direction horizontal(Vec3 vector) {
        return new GuardRules.Direction(vector.x, vector.z);
    }

    record Defense(Optional<GuardState> active, Optional<GuardState> guarded, double multiplier) {
        void afterCommit(LivingEntity entity, BodySnapshot body) {
            // commitIntegrated 已返回，才允许功法库提交收势活动；未挡的手臂命中也能触发撤防。
            if (active.isEmpty() || body.getVitality().getDead()
                    || VanillaDamageFabric.preferences().guard().selectArm(body).isPresent()) {
                return;
            }
            GuardState state = active.get();
            String bodyId = state.bodyId();
            if (!bodyId.equals(body.getId())) {
                return;
            }
            EndActiveRequest request = EndActiveRequest.newBuilder()
                    .setBodyId(bodyId)
                    .setCastId(state.castId())
                    .build();
            var outcome = TechniqueFabric.service().endActive(entity.getUUID(), request);
            if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                LOGGER.error("{} entity={} failure={}", VanillaDamageErrors.GUARD_END,
                        entity.getStringUUID(), outcome.getFailure());
            }
        }
    }
}
