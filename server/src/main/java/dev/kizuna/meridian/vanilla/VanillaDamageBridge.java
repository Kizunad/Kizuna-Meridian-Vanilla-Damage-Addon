package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.DamageIntegrationProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationContext;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationPlan;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationCommit;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.BodyDamageIntegration;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/** 每个事件先确定唯一执行方，再向同一宿主提交受版本保护的意图。 */
public final class VanillaDamageBridge {
    private final List<BodyHealthHost> hosts;
    private final DamagePreferences preferences;
    private final List<BodyDamageIntegration> integrations;

    public VanillaDamageBridge(List<BodyHealthHost> hosts, DamagePreferences preferences,
                               List<BodyDamageIntegration> integrations) {
        // 整套运行必须提供身体宿主和效果集成；未绑定实体由查询结果单独表达。
        this.hosts = List.copyOf(hosts);
        this.preferences = preferences;
        this.integrations = List.copyOf(integrations);
        if (this.hosts.isEmpty()) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.NO_HOST);
        }
        if (this.integrations.isEmpty()) {
            throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.NO_INTEGRATION);
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
        // 护甲前确定唯一宿主与一次命中，后续减伤和身体提交都复用这一目标与版本。
        Optional<Binding> binding = inspect(subjectId);
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        BodySnapshot body = binding.get().body();
        if (body.getVitality().getDead()) {
            // 死体伤势保留；原版重生尚未赋予身体复活的语义。
            return Optional.empty();
        }

        Optional<HealthTarget> target = preferences.select(body, attackerId, roll);
        return Optional.of(new PreparedHit(subjectId, sourceId, kind, binding.get(), target));
    }

    public final class PreparedHit {
        private final String subjectId;
        private final String sourceId;
        private final DamageKind kind;
        private final Binding binding;
        private final Optional<HealthTarget> target;
        private boolean committed;

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
            // 固定伤害上下文后询问效果附属；纯方案失败时不能预先消费黄心或图腾。
            if (committed) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.HIT_COMMITTED);
            }
            var damage = DamageBody.newBuilder().setSourceId("meridian_vanilla:damage")
                    .setDamageType(sourceId).setKind(kind)
                    .setDamageHp(preferences.damageHp(damageAfterEffects));
            target.ifPresent(damage::setHit);
            if (!damage.hasHit()) {
                // 无存活目标是身体状态；交由核心验证，不将其当作缺少集成。
                committed = true;
                return new CommitResult(apply(subjectId, binding, BodyCommand.newBuilder().setDamageBody(damage)), false);
            }
            BodyDamageIntegration selected = null;
            DamageIntegrationPlan plan = null;
            var context = DamageIntegrationContext.newBuilder().setSubjectId(subjectId).setBody(binding.body())
                    .setDamage(damage).setBypassesDeathProtection(bypassesDeathProtection).build();
            if (damage.hasHit()) {
                for (BodyDamageIntegration integration : integrations) {
                    var candidate = DamageIntegrationProtobuf.decodePlan(integration.prepare(
                            DamageIntegrationProtobuf.encodeContext(context)));
                    if (!candidate.getHandled()) {
                        continue;
                    }
                    if (selected != null || candidate.getRemainingDamageHp() > damage.getDamageHp()
                            || (bypassesDeathProtection && candidate.hasDeathProtection())) {
                        throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.INVALID_INTEGRATION);
                    }
                    selected = integration;
                    plan = candidate;
                }
            }
            // 宿主响应不确定时也不从此临时上下文重发，避免给同一事件换一个请求身份。
            committed = true;
            if (selected == null) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.NO_INTEGRATION);
            }

            BodySnapshot body = binding.body();
            boolean deathPrevented = false;
            if (plan.getRemainingDamageHp() > 0) {
                damage.setDamageHp(plan.getRemainingDamageHp());
                if (plan.hasDeathProtection()) {
                    damage.setDeathProtection(plan.getDeathProtection());
                }
                var response = applyResponse(subjectId, binding, BodyCommand.newBuilder().setDamageBody(damage));
                if (!response.hasHealth()) {
                    throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.INVALID_INTEGRATION);
                }
                body = response.getBody();
                deathPrevented = response.getHealth().getDeathPrevented();
            }
            selected.committed(DamageIntegrationProtobuf.encodeCommit(DamageIntegrationCommit.newBuilder()
                    .setContext(context).setPlan(plan).setBody(body).setDeathPrevented(deathPrevented).build()));
            return new CommitResult(body, deathPrevented);
        }
    }

    public record CommitResult(BodySnapshot body, boolean deathPrevented) { }

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
