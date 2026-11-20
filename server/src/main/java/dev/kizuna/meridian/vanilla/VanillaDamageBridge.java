package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.DamageIntegrationProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationContext;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationPlan;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationCommit;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.DamageAttachment;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HealthResult;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.vanilla.protocol.pb.AttackEffectMessages;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.BodyDamageIntegration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 每个事件先确定唯一执行方，再向同一宿主提交受版本保护的意图。 */
public final class VanillaDamageBridge {
    private static final String DAMAGE_SOURCE = "meridian_vanilla:damage";
    private final List<BodyHealthHost> hosts;
    private final DamagePreferences preferences;
    private final List<BodyDamageIntegration> integrations;
    private final List<AttackEffectProvider> attackEffectProviders;
    private final Map<String, Set<String>> appliedAttackEffects = new HashMap<>();

    public VanillaDamageBridge(List<BodyHealthHost> hosts, DamagePreferences preferences,
                               List<BodyDamageIntegration> integrations) {
        this(hosts, preferences, integrations, List.of());
    }

    public VanillaDamageBridge(List<BodyHealthHost> hosts, DamagePreferences preferences,
                               List<BodyDamageIntegration> integrations,
                               List<AttackEffectProvider> attackEffectProviders) {
        // 整套运行必须提供身体宿主和效果集成；未绑定实体由查询结果单独表达。
        this.hosts = List.copyOf(hosts);
        this.preferences = preferences;
        this.integrations = List.copyOf(integrations);
        this.attackEffectProviders = List.copyOf(attackEffectProviders);
        if (this.hosts.isEmpty()) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.NO_HOST);
        }
        if (this.integrations.isEmpty()) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.NO_INTEGRATION);
        }
    }

    public Optional<BodySnapshot> inspectBody(String subjectId) {
        // 攻击方只读查询复用同一唯一宿主规则，不向提供方暴露可写身体入口。
        return inspect(subjectId).map(Binding::body);
    }

    public double armCoefficient(String subjectId, MuscleRules.Arm arm) {
        // 撒放时重新读取权威身体，只取指定解剖侧的当前系数，不提交做功。
        MuscleRules rules = preferences.muscle();
        if (!rules.enabled()) {
            return 1;
        }
        Optional<Binding> found = inspect(subjectId);
        if (found.isEmpty()) {
            return 1;
        }
        return rules.plan(found.get().body(), arm, MuscleRules.Grip.MAIN_HAND).coefficient();
    }

    public double armCoefficientAt(String subjectId, MuscleRules.Arm arm, long elapsedMillis) {
        // 参考射击时刻使用当前快照中已登记的曲线，不预支未来做功或提交候选身体。
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException(VanillaDamageErrors.MUSCLE_PROJECTION_TIME);
        }
        MuscleRules rules = preferences.muscle();
        if (!rules.enabled()) {
            return 1;
        }
        Optional<Binding> found = inspect(subjectId);
        if (found.isEmpty()) {
            return 1;
        }
        return rules.armCoefficientAt(found.get().body(), arm, elapsedMillis);
    }

    public double exertStrike(String subjectId, MuscleRules.Arm mainArm, MuscleRules.Grip grip,
                              double vanillaDamage) {
        // 手臂每次做功按调用方提供的原版剂量换算焦耳。
        return exertStrike(subjectId, mainArm, grip, MuscleRules.Group.ARMS, vanillaDamage);
    }

    public double exertLegStrike(String subjectId, MuscleRules.Arm mainArm, MuscleRules.Grip grip,
                                 double vanillaDamage) {
        // 腿部每次摆腿按原版伤害换算焦耳，组织内均分同一段腿部做功。
        return exertStrike(subjectId, mainArm, grip, MuscleRules.Group.LEGS, vanillaDamage);
    }

    private double exertStrike(String subjectId, MuscleRules.Arm mainArm, MuscleRules.Grip grip,
                               MuscleRules.Group group, double vanillaDamage) {
        // 先用权威快照计算系数，再由同一宿主原子提交整组做功；关闭时不查询或记账。
        MuscleRules rules = preferences.muscle();
        if (!rules.enabled()) {
            return 1;
        }
        Optional<Binding> found = inspect(subjectId);
        if (found.isEmpty()) {
            return 1;
        }
        Binding binding = found.get();
        BodySnapshot body = binding.body();
        MuscleRules.Plan plan;
        if (group == MuscleRules.Group.LEGS) {
            plan = rules.planLeg(body, mainArm, grip, vanillaDamage);
        } else {
            plan = rules.plan(body, mainArm, grip, vanillaDamage);
        }
        if (plan.loads().getLoadsCount() == 0) {
            return plan.coefficient();
        }
        HealthHostRequest request = exertionRequest(subjectId, body, plan);
        HealthHostResponse response = HealthHostProtobuf.decodeResponse(
                binding.host().exchange(HealthHostProtobuf.encodeRequest(request)));
        if (!response.getRequestId().equals(request.getRequestId())) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HOST_RESPONSE);
        }
        if (response.hasFailure()) {
            FailureCode code = response.getFailure().getCode();
            if (code == FailureCode.TISSUE_LOAD_EXCEEDED || code == FailureCode.MODULE_UNAVAILABLE) {
                return rules.coefficients().exhausted();
            }
            throw VanillaDamageErrors.hostRejected(code);
        }
        if (!response.hasBody() || !response.getBody().getId().equals(body.getId())) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HOST_RESPONSE);
        }
        return plan.coefficient();
    }

    private static HealthHostRequest exertionRequest(String subjectId, BodySnapshot body, MuscleRules.Plan plan) {
        // 身份与版本固定在做功前读取的快照，宿主不能替调用方刷新版本。
        String requestId = UUID.randomUUID().toString();
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setExertTissues(plan.loads())
                .build();
        RequestEnvelope exertion = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        return HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setSubjectId(subjectId)
                .setExertion(exertion)
                .build();
    }

    public Optional<AttackEffectResolution> prepareAttackEffect(String targetSubjectId,
                                                                String attackerSubjectId,
                                                                String sourceId,
                                                                DamageKind kind,
                                                                double rawDamageHp,
                                                                boolean melee,
                                                                long serverTick) {
        // 未绑定攻击者不调用附效提供方；远程和配置关闭也在查询身体前短路。
        if (!preferences.attackEffectsEnabled() || !melee || attackEffectProviders.isEmpty()) {
            return Optional.empty();
        }
        return inspectBody(attackerSubjectId).flatMap(attackerBody -> prepareAttackEffect(
                targetSubjectId, attackerSubjectId, attackerBody, sourceId, kind, rawDamageHp, rawDamageHp,
                1, melee, serverTick));
    }

    public Optional<AttackEffectResolution> prepareAttackEffect(String targetSubjectId,
                                                                String attackerSubjectId,
                                                                String sourceId,
                                                                DamageKind kind,
                                                                double rawDamageHp,
                                                                double vanillaDamageHp,
                                                                double muscleMultiplier,
                                                                boolean melee,
                                                                long serverTick) {
        // 新调用方同时提供缩放后的原版剂量、缩放前剂量和肌肉倍率。
        // 旧入口保持倍率为一。
        if (!preferences.attackEffectsEnabled() || !melee || attackEffectProviders.isEmpty()) {
            return Optional.empty();
        }
        return inspectBody(attackerSubjectId).flatMap(attackerBody -> prepareAttackEffect(
                targetSubjectId, attackerSubjectId, attackerBody, sourceId, kind, rawDamageHp, vanillaDamageHp,
                muscleMultiplier, melee, serverTick));
    }

    public Optional<AttackEffectResolution> prepareAttackEffect(String targetSubjectId,
                                                                String attackerSubjectId,
                                                                BodySnapshot attackerBody,
                                                                String sourceId,
                                                                DamageKind kind,
                                                                double rawDamageHp,
                                                                boolean melee,
                                                                long serverTick) {
        return prepareAttackEffect(targetSubjectId, attackerSubjectId, attackerBody, sourceId, kind, rawDamageHp,
                rawDamageHp, 1, melee, serverTick);
    }

    public Optional<AttackEffectResolution> prepareAttackEffect(String targetSubjectId,
                                                                String attackerSubjectId,
                                                                BodySnapshot attackerBody,
                                                                String sourceId,
                                                                DamageKind kind,
                                                                double rawDamageHp,
                                                                double vanillaDamageHp,
                                                                double muscleMultiplier,
                                                                boolean melee,
                                                                long serverTick) {
        // 上下文固定攻击者快照和两种原版剂量；提供方不能改写目标身体或致伤类型。
        if (!preferences.attackEffectsEnabled() || !melee || attackEffectProviders.isEmpty()) {
            return Optional.empty();
        }
        var contextBuilder = AttackEffectMessages.AttackEffectContext.newBuilder();
        contextBuilder.setProtocolVersion(1);
        contextBuilder.setAttackerSubjectId(attackerSubjectId);
        contextBuilder.setAttackerBody(attackerBody);
        contextBuilder.setTargetSubjectId(targetSubjectId);
        contextBuilder.setSourceId(sourceId);
        contextBuilder.setKind(kind);
        contextBuilder.setRawDamageHp(rawDamageHp);
        contextBuilder.setVanillaDamageHp(vanillaDamageHp);
        contextBuilder.setMuscleMultiplier(muscleMultiplier);
        contextBuilder.setMelee(melee);
        contextBuilder.setServerTick(serverTick);
        var context = contextBuilder.build();
        AttackEffectMessages.AttackEffectPlan selected = null;
        for (AttackEffectProvider provider : attackEffectProviders) {
            final AttackEffectMessages.AttackEffectPlan candidate;
            try {
                candidate = AttackEffectProtobuf.decodePlan(provider.exchange(
                        AttackEffectProtobuf.encodeContext(context)));
            } catch (RuntimeException failure) {
                // 附效是可选增益；提供方坏掉时由 Fabric 层记录并保留原版剂量。
                throw new IllegalStateException(VanillaDamageErrors.ATTACK_EFFECT_PROVIDER, failure);
            }
            if (!candidate.getHandled()) {
                continue;
            }
            if (selected != null) {
                throw new IllegalStateException(VanillaDamageErrors.ATTACK_EFFECT_PROVIDER);
            }
            selected = candidate;
        }
        if (selected == null) {
            return Optional.empty();
        }
        String effectId = selected.getEffectId();
        if (!effectId.isBlank() && appliedAttackEffects.getOrDefault(targetSubjectId, Set.of()).contains(effectId)) {
            // 同一施放 ID 对同一目标只认领一次；提供方仍被询问以保持窗口状态推进。
            return Optional.empty();
        }
        double maximumExtra = vanillaDamageHp * preferences.maxExtraRatio();
        if (!Double.isFinite(maximumExtra)) {
            maximumExtra = Double.MAX_VALUE;
        }
        double extraDamage = Math.min(selected.getExtraDamageHp(), maximumExtra);
        return Optional.of(new AttackEffectResolution(extraDamage,
                selected.getStatusEffectsList(), effectId, extraDamage < selected.getExtraDamageHp()));
    }

    public void markAttackEffectApplied(String targetSubjectId, String effectId) {
        // 只有同一身体事务成功后才写入去重集合，失败重试不丢失附效。
        if (effectId == null || effectId.isBlank()) {
            return;
        }
        appliedAttackEffects.computeIfAbsent(targetSubjectId, ignored -> new HashSet<>()).add(effectId);
    }

    public void clearAttackEffectState() {
        // 服务器停止时丢弃实体引用和施放去重状态，避免跨世界复用旧效果 ID。
        appliedAttackEffects.clear();
    }

    public record AttackEffectResolution(double extraDamageHp,
                                         List<AttackEffectMessages.StatusEffect> statusEffects,
                                         String effectId,
                                         boolean truncated) {
        public AttackEffectResolution {
            statusEffects = List.copyOf(statusEffects);
        }
    }

    public Optional<BodySnapshot> damage(String subjectId, String sourceId, String attackerId,
                                         DamageKind kind, double actualLostHp, double roll) {
        // 已知最终剂量的宿主可一次完成；Minecraft 分阶段路径使用 prepare 固定目标。
        if (actualLostHp == 0) {
            return Optional.empty();
        }
        return prepare(subjectId, sourceId, attackerId, kind, roll).map(hit -> hit.commit(actualLostHp));
    }

    public Optional<PreparedHit> prepare(String subjectId, String sourceId, String attackerId,
                                         DamageKind kind, double roll) {
        return prepare(subjectId, sourceId, attackerId, kind, roll, Optional.empty());
    }

    public Optional<PreparedHit> prepare(String subjectId, String sourceId, String attackerId,
                                         DamageKind kind, double roll, String aimGroup) {
        return prepare(subjectId, sourceId, attackerId, kind, roll, Optional.ofNullable(aimGroup));
    }

    public Optional<PreparedHit> prepare(String subjectId, String sourceId, String attackerId,
                                         DamageKind kind, double roll, Optional<String> aimGroup) {
        // 护甲前确定唯一宿主与一次命中，后续减伤和身体提交都复用这一目标与版本。
        Optional<Binding> binding = inspect(subjectId);
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        BodySnapshot body = binding.get().body();
        if (body.getVitality().getDead()) {
            // 死态不再接受伤害；Human 显式复活后重新进入正常命中路径。
            return Optional.empty();
        }

        Optional<HealthTarget> target = preferences.select(body, attackerId, roll, aimGroup);
        return Optional.of(new PreparedHit(subjectId, sourceId, kind, binding.get(), target));
    }

    public Optional<PreparedHit> prepareDirect(String subjectId, String sourceId, DamageKind kind, String partId) {
        // 可信调用固定真实部位，沿用同一宿主、护甲覆盖和身体伤害事务。
        Optional<Binding> binding = inspect(subjectId);
        if (binding.isEmpty() || binding.get().body().getVitality().getDead()) {
            return Optional.empty();
        }
        BodySnapshot body = binding.get().body();
        boolean exists = body.getStructure().getPartsList().stream().anyMatch(part -> part.getId().equals(partId));
        if (!exists) {
            throw VanillaDamageErrors.hostRejected(FailureCode.MODULE_NOT_FOUND);
        }
        double hp = body.getPartStatesList().stream().filter(part -> part.getId().equals(partId))
                .findFirst().orElseThrow().getHp();
        if (hp == 0) {
            return Optional.empty();
        }
        HealthTarget target = HealthTarget.newBuilder()
                .setPartId(partId)
                .build();
        return Optional.of(new PreparedHit(subjectId, sourceId, kind, binding.get(), Optional.of(target)));
    }

    public final class PreparedHit {
        private final String subjectId;
        private final String sourceId;
        private final DamageKind kind;
        private final Binding binding;
        private Optional<HealthTarget> target;
        private boolean guarded;
        private boolean committed;
        private DamageAttachment attachment;
        private Supplier<BodySnapshot> absorbedPayment;

        public void attach(DamageAttachment payment, Supplier<BodySnapshot> absorbed) {
            // 正剂量和完全吸收共享同一明细，只有一个提交出口可消费本次命中。
            if (!guarded || committed || attachment != null) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HIT_COMMITTED);
            }
            attachment = payment;
            absorbedPayment = absorbed;
        }

        private PreparedHit(String subjectId, String sourceId, DamageKind kind, Binding binding,
                            Optional<HealthTarget> target) {
            // 只保存本次调用的结算上下文，不另建身体账本。
            this.subjectId = subjectId;
            this.sourceId = sourceId;
            this.kind = kind;
            this.binding = binding;
            this.target = target;
        }

        public Optional<ArmorRules.Slot> coveredSlot() {
            // 覆盖取身体自己的模板与已选部位；无目标时不能推断穿甲保护。
            return target.flatMap(hit -> preferences.armor().coveredSlot(
                    binding.body().getTemplate().getId(), hit.getPartId()));
        }

        public Optional<HealthTarget> target() {
            // 调试观察方读取已经固定的命中目标，不重新抽取部位。
            return target;
        }

        public BodySnapshot body() {
            // 选点时读到的目标身体，附伤按它的经脉内腔定相克；只读，不经它提交。
            return binding.body();
        }

        public boolean redirectGuard() {
            // 只改同一命中的目标，继续使用原宿主和 revision，不能重新选点或重复提交。
            if (committed || guarded) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HIT_COMMITTED);
            }
            BodySnapshot body = binding.body();
            Optional<String> group = target.filter(HealthTarget::hasPartId)
                    .flatMap(hit -> preferences.anatomyGroup(body, hit.getPartId()));
            GuardRules rules = preferences.guard();
            if (!rules.covers(body.getTemplate().getId(), group)) {
                return false;
            }
            Optional<String> arm = rules.selectArm(body);
            if (arm.isEmpty()) {
                return false;
            }
            HealthTarget redirected = HealthTarget.newBuilder()
                    .setPartId(arm.get())
                    .build();
            target = Optional.of(redirected);
            guarded = true;
            return true;
        }

        public double directDose(double damageAfterArmor) {
            // 格挡改点后交给身体原有溢伤规则；其余指定部位伤害仍裁到该部位剩余 HP。
            if (guarded) {
                return damageAfterArmor;
            }
            String partId = target.orElseThrow().getPartId();
            double hp = binding.body().getPartStatesList().stream()
                    .filter(part -> part.getId().equals(partId)).findFirst().orElseThrow().getHp();
            return Math.min(damageAfterArmor, preferences.vanillaHp(hp));
        }

        public long staggerMillis(double damageAfterEffects) {
            // 护甲后剂量与命中模块上限同为身体 HP；没有命中目标时无从比较，不产生僵直。
            if (guarded || damageAfterEffects == 0) {
                return 0;
            }
            double doseHp = preferences.damageHp(damageAfterEffects);
            BodySnapshot body = binding.body();
            return target.map(hit -> preferences.stagger().millis(body, hit, kind, doseHp)).orElse(0L);
        }

        public double remainingVanillaDamage(double rawVanillaHp, double armor, double toughness,
                                             OptionalDouble remainingDurabilityRatio) {
            // 先换算身体 HP 再做单件减伤，返回原版单位让其继续抗性、附魔和吸收结算。
            double bodyHp = preferences.damageHp(rawVanillaHp);
            double remainingHp = preferences.armor().remainingDamage(bodyHp, armor, toughness, remainingDurabilityRatio);
            return preferences.vanillaHp(remainingHp);
        }

        public BodySnapshot commit(double damageAfterEffects) {
            // 真正的 HP 变更只在非护甲机制结算后提交，避免吸收挡住仍留下局部伤势。
            return commitIntegrated(damageAfterEffects, false).body();
        }

        public CommitResult commitIntegrated(double damageAfterEffects, boolean bypassesDeathProtection) {
            // 没有附带的普通提交。
            return commitIntegrated(damageAfterEffects, bypassesDeathProtection, DamageExtras.NONE);
        }

        public boolean fits(List<BodyMessages.CapabilityModifier> modifiers) {
            // 身体最多带 32 条修正；同 ID 的会被换掉，不占新位置。装不下时调用方应当只提交伤害。
            Set<String> incoming = new HashSet<>();
            modifiers.forEach(modifier -> incoming.add(modifier.getId()));
            long kept = binding.body().getModifiersList().stream()
                    .filter(existing -> !incoming.contains(existing.getId()))
                    .count();
            return kept + incoming.size() <= CapabilityModifier.MAX_PER_BODY;
        }

        public CommitResult commitIntegrated(double damageAfterEffects, boolean bypassesDeathProtection,
                                             DamageExtras extras) {
            // 固定伤害上下文后询问效果附属；纯方案失败时不能预先消费黄心或图腾。
            if (committed) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HIT_COMMITTED);
            }
            if (guarded && damageAfterEffects == 0) {
                // 守式可完全吸收；不提交零剂量，也不消费局部黄心或图腾。
                committed = true;
                return absorbedResult();
            }
            var damage = DamageBody.newBuilder()
                    .setSourceId(DAMAGE_SOURCE)
                    .setDamageType(sourceId)
                    .setKind(kind)
                    .setDamageHp(preferences.damageHp(damageAfterEffects));
            target.ifPresent(damage::setHit);
            // 透劲份额原样交给健康宿主的分层；附带修正的来源写成本附属，开始时刻留给框架按这笔事务的身体时钟写。
            extras.organShare().ifPresent(damage::setOrganShare);
            for (BodyMessages.CapabilityModifier modifier : extras.modifiers()) {
                damage.addAddModifiers(modifier.toBuilder().setSource(DAMAGE_SOURCE).clearStartMillis());
            }
            if (!damage.hasHit()) {
                // 无实际目标交由核心验证，不将其当作缺少集成。
                committed = true;
                return submitDamage(damage.build());
            }
            var context = DamageIntegrationContext.newBuilder()
                    .setSubjectId(subjectId)
                    .setBody(binding.body())
                    .setDamage(damage)
                    .setBypassesDeathProtection(bypassesDeathProtection)
                    .build();
            PreparedIntegration selected = selectIntegration(context);
            // 宿主响应不确定时也不从此临时上下文重发，避免给同一事件换一个请求身份。
            committed = true;
            if (selected == null) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.NO_INTEGRATION);
            }
            DamageIntegrationPlan plan = selected.plan();
            CommitResult result = applyPlan(context.getDamage(), plan);
            var notification = DamageIntegrationCommit.newBuilder()
                    .setContext(context)
                    .setPlan(plan)
                    .setBody(result.body())
                    .setDeathPrevented(result.deathPrevented())
                    .build();
            selected.integration().committed(DamageIntegrationProtobuf.encodeCommit(notification));
            return result;
        }

        private PreparedIntegration selectIntegration(DamageIntegrationContext context) {
            // 同次伤害只接受一方认领，且集成方不能提高剂量或绕过禁用的保命来源。
            PreparedIntegration selected = null;
            byte[] payload = DamageIntegrationProtobuf.encodeContext(context);
            for (BodyDamageIntegration integration : integrations) {
                var candidate = DamageIntegrationProtobuf.decodePlan(integration.prepare(payload));
                if (!candidate.getHandled()) {
                    continue;
                }
                if (selected != null || candidate.getRemainingDamageHp() > context.getDamage().getDamageHp()
                        || (context.getBypassesDeathProtection() && candidate.hasDeathProtection())) {
                    throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.INVALID_INTEGRATION);
                }
                selected = new PreparedIntegration(integration, candidate);
            }
            return selected;
        }

        private CommitResult applyPlan(DamageBody original, DamageIntegrationPlan plan) {
            // 完全吸收不提交零剂量；其余情况携带保命方案一次提交完整伤害。
            double remainingDamageHp = plan.getRemainingDamageHp();
            if (remainingDamageHp == 0) {
                return absorbedResult();
            }
            var damage = original.toBuilder().setDamageHp(remainingDamageHp);
            if (plan.hasDeathProtection()) {
                damage.setDeathProtection(plan.getDeathProtection());
            }
            return submitDamage(damage.build());
        }

        private CommitResult absorbedResult() {
            // 完全吸收时只有附带费用需要写身体，保留原有的空健康结果。
            BodySnapshot before = binding.body();
            BodySnapshot after = before;
            if (absorbedPayment != null) {
                after = absorbedPayment.get();
            }
            return new CommitResult(before, after, HealthResult.getDefaultInstance());
        }

        private CommitResult submitDamage(DamageBody damage) {
            // 快照和回执来自同一响应，观察器不会再读一次身体或猜测保命是否发生。
            var command = BodyCommand.newBuilder().setDamageBody(damage);
            if (attachment != null) {
                command.setDamageAttachment(attachment);
            }
            var response = applyResponse(subjectId, binding, command);
            if (!response.hasHealth()) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.INVALID_INTEGRATION);
            }
            return new CommitResult(binding.body(), response.getBody(), response.getHealth());
        }

        private record PreparedIntegration(BodyDamageIntegration integration, DamageIntegrationPlan plan) { }
    }

    /**
     * 一下伤害另带给身体的两样：透进器官的份额（缺省照伤害种类的分层），和扣血同一笔加上的能力修正。
     * 修正只填 ID、目标、曲线和倍率；来源和开始时刻由本附属和框架写。完全被吸收的伤害不提交，也就不加修正。
     */
    public record DamageExtras(OptionalDouble organShare, List<BodyMessages.CapabilityModifier> modifiers) {
        public static final DamageExtras NONE = new DamageExtras(OptionalDouble.empty(), List.of());

        public DamageExtras {
            // 份额只接受 [0, 1]；列表固定下来，调用方之后再改不影响这一下。
            if (organShare.isPresent()) {
                double share = organShare.getAsDouble();
                if (!Double.isFinite(share) || share < 0 || share > 1) {
                    throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
                }
            }
            modifiers = List.copyOf(modifiers);
        }
    }

    public record CommitResult(BodySnapshot before, BodySnapshot body, HealthResult health) {
        public boolean deathPrevented() {
            // 同一固定回执同时供图腾副作用与只读伤害观察方使用。
            return health.getDeathPrevented();
        }
    }

    public Optional<BodySnapshot> died(String subjectId) {
        // 原版死亡只确认死态，不用虚构的大额伤害覆盖已有局部伤势。
        Optional<Binding> binding = inspect(subjectId);
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        if (binding.get().body().getVitality().getDead()) {
            return Optional.of(binding.get().body());
        }
        return Optional.of(apply(subjectId, binding.get(), BodyCommand.newBuilder().setConfirmBodyDeath(
                ConfirmBodyDeath.newBuilder().setSourceId("meridian_vanilla:death"))));
    }

    private Optional<Binding> inspect(String subjectId) {
        // 查询所有宿主以检查唯一归属；拒绝和未绑定有不同含义，不能静默换账本。
        String requestId = UUID.randomUUID().toString();
        var request = HealthHostRequest.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setSubjectId(subjectId).setInspect(true).build();
        Binding binding = null;
        for (BodyHealthHost host : hosts) {
            HealthHostResponse response = exchange(host, request);
            if (response.hasUnbound()) {
                continue;
            }
            if (binding != null) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.DUPLICATE_HOST);
            }
            binding = new Binding(host, response.getBody());
        }
        return Optional.ofNullable(binding);
    }

    private BodySnapshot apply(String subjectId, Binding binding, BodyCommand.Builder command) {
        // 同一事件只提交一次，失败不重新抽取目标；请求身份和读取版本固定在本次提交。
        return applyResponse(subjectId, binding, command).getBody();
    }

    private HealthHostResponse applyResponse(String subjectId, Binding binding, BodyCommand.Builder command) {
        // 保命事实来自已提交回执，不能用扣血后的可见比例猜测是否消费图腾。
        String requestId = UUID.randomUUID().toString();
        var effect = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setBodyCommand(command.setBodyId(binding.body().getId())
                        .setExpectedRevision(binding.body().getRevision())).build();
        var request = HealthHostRequest.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setSubjectId(subjectId).setEffect(effect).build();
        HealthHostResponse response = exchange(binding.host(), request);
        if (!response.hasBody() || !response.getBody().getId().equals(binding.body().getId())) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HOST_RESPONSE);
        }
        if (response.hasHealth()) {
            // 保命消费必须依据与本次命令一致的固定事实，不能接受错配的治疗或死亡回执。
            HealthProtobuf.validateResult(effect.getBodyCommand(), response.getHealth());
        }
        return response;
    }

    private HealthHostResponse exchange(BodyHealthHost host, HealthHostRequest request) {
        // 解析生成消息之外还执行框架契约校验，防止把错配或拒绝当作成功结算。
        HealthHostResponse response = HealthHostProtobuf.decodeResponse(
                host.exchange(HealthHostProtobuf.encodeRequest(request)));
        if (!response.getRequestId().equals(request.getRequestId())) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HOST_RESPONSE);
        }
        if (response.hasFailure()) {
            throw VanillaDamageErrors.hostRejected(response.getFailure().getCode());
        }
        return response;
    }

    private record Binding(BodyHealthHost host, BodySnapshot body) { }
}
