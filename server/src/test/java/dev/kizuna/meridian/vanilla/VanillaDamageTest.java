package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.DamageIntegrationProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationPlan;
import dev.kizuna.meridian.server.BodyDamageIntegration;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.vanilla.protocol.pb.AttackEffectMessages;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 整体回归：原版意图、宿主绑定与真实核心事务共同完成伤害和死亡。 */
public class VanillaDamageTest {
    @Test
    public void preferencesRespectWholeGroupWeightAndActualSurvivingParts() throws IOException {
        // 默认配置按整组计算，即使腿的数量不同，蜘蛛仍将 70 份权重分配到腿部。
        DamagePreferences preferences = preferences();
        BodyMessages.BodySnapshot body = BodyProtobuf.toMessage(body());
        var weights = preferences.weights(body, "minecraft:spider");
        assertEquals(70, weights.get("left_thigh") + weights.get("right_thigh"), 0);
        assertEquals(5, weights.get("head"), 0);
        assertEquals(30, preferences.weights(body, "minecraft:skeleton").get("head"), 0);
        assertEquals("head", preferences.select(body, "minecraft:skeleton", 0).orElseThrow().getPartId());
        assertEquals("right_thigh", preferences.select(body, "minecraft:spider", Math.nextDown(1.0))
                .orElseThrow().getPartId());

        // 新增生人配置使用整组权重；抽样结果应在合理统计误差内落到预期组。
        for (String attacker : List.of("minecraft:pillager", "minecraft:vindicator", "minecraft:evoker",
                "minecraft:illusioner", "minecraft:witch")) {
            var configuredWeights = preferences.weights(body, attacker);
            var random = new Random(attacker.hashCode());
            int torsoHits = 0;
            int headHits = 0;
            int legHits = 0;
            for (int sample = 0; sample < 10_000; sample++) {
                String part = preferences.select(body, attacker, random.nextDouble()).orElseThrow().getPartId();
                switch (part) {
                    case "head" -> headHits++;
                    case "chest" -> torsoHits++;
                    case "left_thigh", "right_thigh" -> legHits++;
                    default -> fail("样本落入未预期部位: " + part);
                }
            }
            double total = configuredWeights.values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(configuredWeights.getOrDefault("head", 0.0) / total, headHits / 10_000.0, 0.02);
            assertEquals(configuredWeights.getOrDefault("chest", 0.0) / total, torsoHits / 10_000.0, 0.02);
            double legs = configuredWeights.getOrDefault("left_thigh", 0.0)
                    + configuredWeights.getOrDefault("right_thigh", 0.0);
            assertEquals(legs / total, legHits / 10_000.0, 0.02);
        }

        var injured = body.toBuilder().setPartStates(2, body.getPartStates(2).toBuilder().setHp(0)).build();
        assertFalse(preferences.weights(injured, "minecraft:spider").containsKey("left_thigh"));
        assertEquals(70, preferences.weights(injured, "minecraft:spider").get("right_thigh"), 0);
        assertTrue(preferences.weights(body, "addon:unknown").values().stream().allMatch(weight -> weight == 1));
        var unknown = body.toBuilder().setTemplate(body.getTemplate().toBuilder().setId("addon:alien")).build();
        assertTrue(preferences.weights(unknown, "minecraft:spider").values().stream().allMatch(weight -> weight == 1));
        assertThrows(IllegalArgumentException.class, () -> preferences.select(body, "minecraft:spider", 1));

        // 服务器可用整数配置比例；错误的结构和权重应在加载时明确拒绝。
        assertEquals(2, DamagePreferences.parse("hp_per_vanilla_hp = 1").damageHp(2), 0);
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse("hp_per_vanilla_hp = nan"));
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse("""
                hp_per_vanilla_hp = 1.0
                [anatomies."human:adult"]
                legs = "left_thigh"
                """));
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse("""
                hp_per_vanilla_hp = 1.0
                [attackers."minecraft:spider"]
                legs = -1
                """));

        // 分类跟随实际来源，射手手持剑也不会把箭伤变为切割。
        assertEquals(DamageKind.PIERCING, VanillaDamageKinds.classify("minecraft:arrow", "minecraft:skeleton", true));
        assertEquals(DamageKind.CUTTING, VanillaDamageKinds.classify("minecraft:mob_attack", "minecraft:zombie", true));
        assertEquals(DamageKind.PIERCING, VanillaDamageKinds.classify("minecraft:mob_attack", "minecraft:spider", false));
        assertEquals(DamageKind.HEAT, VanillaDamageKinds.classify("minecraft:lava", "minecraft:unknown", false));
        assertEquals(DamageKind.PIERCING, VanillaDamageKinds.classify("minecraft:indirect_magic", "minecraft:evoker", false));
        assertEquals(DamageKind.TOXIC, VanillaDamageKinds.classify("minecraft:indirect_magic", "minecraft:witch", false));
        assertEquals(DamageKind.CUSTOM, VanillaDamageKinds.classify("minecraft:magic", "minecraft:witch", false));
        assertEquals(DamageKind.CUSTOM, VanillaDamageKinds.classify("minecraft:indirect_magic", "minecraft:unknown", false));
        assertEquals(DamageKind.CUSTOM, VanillaDamageKinds.classify("addon:unknown", "addon:unknown", false));

        assertTrue(preferences.attackEffectsEnabled());
        assertEquals(4.0, preferences.maxExtraRatio(), 0);
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(configuration()
                .replace("max_extra_ratio = 4.0", "max_extra_ratio = -1")));
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(configuration()
                .replace("enabled = true", "enabled = \"yes\"")));
    }

    @Test
    public void attackEffectProvidersRespectBindingMeleeClaimCapAndDeduplication() throws IOException {
        var store = new InMemoryBodyStore(List.of(body()), List.of());
        var providerCalls = new AtomicInteger();
        AttackEffectProvider provider = payload -> {
            providerCalls.incrementAndGet();
            var context = AttackEffectProtobuf.decodeContext(payload);
            return AttackEffectProtobuf.encodePlan(AttackEffectMessages.AttackEffectPlan.newBuilder()
                    .setHandled(true).setExtraDamageHp(2).setEffectId("test:cast")
                    .addStatusEffects(AttackEffectMessages.StatusEffect.newBuilder()
                            .setEffectId("minecraft:slowness").setDurationTicks(20).setAmplifier(0)).build());
        };
        var bridge = new VanillaDamageBridge(List.of(host(store, new AtomicInteger())), preferences(), List.of(effects()),
                List.of(provider));

        var resolution = bridge.prepareAttackEffect("target", "subject", "minecraft:mob_attack",
                DamageKind.BLUNT, 2, true, 10).orElseThrow();
        assertEquals(2, resolution.extraDamageHp(), 0);
        assertEquals(1, resolution.statusEffects().size());
        bridge.markAttackEffectApplied("target", resolution.effectId());
        assertTrue(bridge.prepareAttackEffect("target", "subject", "minecraft:mob_attack",
                DamageKind.BLUNT, 2, true, 10).isEmpty());
        assertEquals(2, providerCalls.get());

        // 远程命中和未绑定攻击者均不询问提供方。
        assertTrue(bridge.prepareAttackEffect("target", "missing", "minecraft:arrow", DamageKind.PIERCING,
                2, false, 10).isEmpty());
        assertTrue(bridge.prepareAttackEffect("target", "missing", "minecraft:mob_attack", DamageKind.BLUNT,
                2, true, 10).isEmpty());
        assertEquals(2, providerCalls.get());

        AttackEffectProvider oversized = payload -> AttackEffectProtobuf.encodePlan(
                AttackEffectMessages.AttackEffectPlan.newBuilder().setHandled(true).setExtraDamageHp(20).build());
        var capped = new VanillaDamageBridge(List.of(host(store, new AtomicInteger())), preferences(), List.of(effects()),
                List.of(oversized)).prepareAttackEffect("target", "subject", "minecraft:mob_attack",
                DamageKind.BLUNT, 2, true, 10).orElseThrow();
        assertEquals(8, capped.extraDamageHp(), 0);
        assertTrue(capped.truncated());

        AttackEffectProvider second = payload -> AttackEffectProtobuf.encodePlan(
                AttackEffectMessages.AttackEffectPlan.newBuilder().setHandled(true).build());
        var duplicateClaim = new VanillaDamageBridge(List.of(host(store, new AtomicInteger())), preferences(), List.of(effects()),
                List.of(provider, second));
        assertThrows(IllegalStateException.class, () -> duplicateClaim.prepareAttackEffect("target", "subject",
                "minecraft:mob_attack", DamageKind.BLUNT, 2, true, 10));
        assertThrows(IllegalArgumentException.class, () -> AttackEffectProtobuf.encodePlan(
                AttackEffectMessages.AttackEffectPlan.newBuilder().setHandled(true).setExtraDamageHp(-1).build()));
    }

    @Test
    public void finalLossAndExternalDeathPreserveInjuriesAndNeverWriteTwoHosts() throws IOException {
        // 实际核心持有唯一身体；模拟宿主只做协议路由，不直接修改快照。
        var store = new InMemoryBodyStore(List.of(body()), List.of());
        var calls = new AtomicInteger();
        BodyHealthHost host = host(store, calls);
        var bridge = new VanillaDamageBridge(List.of(host), preferences(), List.of(effects()));
        assertTrue(bridge.damage("subject", "minecraft:arrow", "minecraft:skeleton", DamageKind.PIERCING, 0, 0).isEmpty());
        assertEquals(0, calls.get());
        var hit = bridge.prepare("subject", "minecraft:arrow", "minecraft:skeleton", DamageKind.PIERCING, 0).orElseThrow();
        assertEquals(ArmorRules.Slot.HEAD, hit.coveredSlot().orElseThrow());
        assertEquals(0, store.receipts().size());
        var hurt = hit.commit(2);
        assertEquals(18, hurt.getPartStates(0).getHp(), 0);
        assertEquals(1, store.receipts().size());
        assertThrows(IllegalStateException.class, () -> hit.commit(2));

        var duplicated = new VanillaDamageBridge(List.of(host, host), preferences(), List.of(effects()));
        assertThrows(IllegalStateException.class, () -> duplicated.damage("subject", "minecraft:arrow",
                "minecraft:skeleton", DamageKind.PIERCING, 2, 0));
        assertEquals(1, store.receipts().size());

        var dead = bridge.died("subject").orElseThrow();
        assertTrue(dead.getVitality().getDead());
        assertEquals(hurt.getPartStatesList(), dead.getPartStatesList());
        assertEquals(2, store.receipts().size());
        bridge.died("subject");
        assertTrue(bridge.damage("subject", "minecraft:fall", "minecraft:unknown", DamageKind.BLUNT, 1, 0).isEmpty());
        assertEquals(2, store.receipts().size());

        // 已认领宿主拒绝时不能尝试另一账本；仅明确 unbound 才能继续查找。
        BodyHealthHost rejected = payload -> {
            // 权限失败保留公共失败码，不能被当作未绑定吞掉。
            var request = HealthHostProtobuf.decodeRequest(payload);
            return HealthHostProtobuf.encodeResponse(HealthHostResponse.newBuilder().setProtocolVersion(1)
                    .setRequestId(request.getRequestId()).setFailure(RequestFailure.newBuilder()
                            .setCode(FailureCode.ACCESS_DENIED)).build());
        };
        int before = calls.get();
        var denied = new VanillaDamageBridge(List.of(rejected, host), preferences(), List.of(effects()));
        assertThrows(IllegalStateException.class, () -> denied.died("subject"));
        assertEquals(before, calls.get());
        assertTrue(bridge.died("unbound").isEmpty());
    }

    @Test
    public void armorConfigurationControlsCoverageFormulaAndBodyDamageUnits() throws IOException {
        // 默认覆盖是独立于怪物偏好的表；未知模板和未覆盖部位不借用全局护甲。
        ArmorRules armor = preferences().armor();
        assertEquals(ArmorRules.Slot.HEAD, armor.coveredSlot("human:adult", "neck").orElseThrow());
        assertEquals(ArmorRules.Slot.CHEST, armor.coveredSlot("human:adult", "left_upper_arm").orElseThrow());
        assertEquals(ArmorRules.Slot.LEGS, armor.coveredSlot("human:adult", "pelvis").orElseThrow());
        assertEquals(ArmorRules.Slot.LEGS, armor.coveredSlot("human:adult", "right_lower_leg").orElseThrow());
        assertEquals(ArmorRules.Slot.FEET, armor.coveredSlot("human:adult", "left_foot").orElseThrow());
        assertTrue(armor.coveredSlot("human:adult", "left_forearm").isEmpty());
        assertTrue(armor.coveredSlot("addon:alien", "head").isEmpty());
        assertEquals(2.976, armor.remainingDamage(4, 8, 2, OptionalDouble.of(1)), 1e-12);
        assertEquals(4, armor.remainingDamage(4, 0, 0, OptionalDouble.of(1)), 0);
        assertTrue(armor.remainingDamage(12, 8, 2, OptionalDouble.of(1))
                < armor.remainingDamage(12, 8, 0, OptionalDouble.of(1)));

        // 修改同一公式参数和覆盖表便能改变结算；不需修改源码。
        String config = configuration().replace("hp_per_vanilla_hp = 1.0", "hp_per_vanilla_hp = 2.0")
                .replace("reduction_divisor = 25.0", "reduction_divisor = 50.0")
                .replace("head = [\"head\", \"neck\"]", "head = [\"head\", \"neck\", \"left_forearm\"]");
        DamagePreferences changed = DamagePreferences.parse(config);
        assertEquals(ArmorRules.Slot.HEAD, changed.armor().coveredSlot("human:adult", "left_forearm").orElseThrow());
        var store = new InMemoryBodyStore(List.of(body()), List.of());
        var bridge = new VanillaDamageBridge(List.of(host(store, new AtomicInteger())), changed, List.of(effects()));
        var hit = bridge.prepare("subject", "minecraft:arrow", "minecraft:skeleton", DamageKind.PIERCING, 0).orElseThrow();
        double remaining = hit.remainingVanillaDamage(4, 8, 2, OptionalDouble.of(1));
        assertEquals(3.616, remaining, 1e-12);
        assertEquals(20 - remaining * 2, hit.commit(remaining).getPartStates(0).getHp(), 1e-12);

        // 无效覆盖、公式或分母应在启动时拒绝，不在战斗时产生隐式叠加或 NaN。
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(configuration()
                .replace("toughness_divisor = 4.0", "toughness_divisor = 0.0")));
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(configuration()
                .replace("reduction_divisor = 25.0", "reduction_divisor = 10.0")));
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(configuration()
                .replace("type = \"toughness\"", "type = \"script\"")));
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(configuration()
                .replace("feet = [\"left_foot\", \"right_foot\"]", "feet = [\"head\"]")));
        assertThrows(IllegalArgumentException.class, () -> armor.remainingDamage(1, Double.NaN, 0, OptionalDouble.of(1)));
    }

    @Test
    public void durabilityCurveAndReductionCapApplyInOrderAndRemainConfigurable() throws IOException {
        // 固定基础减免为 25.6%，各端点和段中值保护真正发布的耐久曲线。
        ArmorRules armor = preferences().armor();
        double[][] samples = {{1, 1}, {0.75, 0.925}, {0.5, 0.85}, {0.375, 0.725},
                {0.25, 0.6}, {0.175, 0.475}, {0.1, 0.35}, {0.05, 0.175}, {0, 0}};
        for (double[] sample : samples) {
            assertEquals(4 - 1.024 * sample[1],
                    armor.remainingDamage(4, 8, 2, OptionalDouble.of(sample[0])), 1e-12);
        }
        assertEquals(2.976, armor.remainingDamage(4, 8, 2, OptionalDouble.empty()), 1e-12);

        // 高护甲的基础减免为 80%；半耐久先乘 0.85 再限到 60%，不能先限再乘。
        assertEquals(1.6, armor.remainingDamage(4, 100, 0, OptionalDouble.of(1)), 1e-12);
        assertEquals(1.6, armor.remainingDamage(4, 100, 0, OptionalDouble.of(0.5)), 1e-12);
        assertEquals(2.08, armor.remainingDamage(4, 100, 0, OptionalDouble.of(0.25)), 1e-12);
        ArmorRules lowerCap = DamagePreferences.parse(configuration()
                .replace("maximum_reduction_ratio = 0.60", "maximum_reduction_ratio = 0.20")).armor();
        assertEquals(3.2, lowerCap.remainingDamage(4, 100, 0, OptionalDouble.of(1)), 1e-12);
        assertEquals(4, DamagePreferences.parse(configuration()
                .replace("maximum_reduction_ratio = 0.60", "maximum_reduction_ratio = 0")).armor()
                .remainingDamage(4, 8, 2, OptionalDouble.of(1)), 0);

        // 自定义端点也必须真正生效；无耐久物品仍固定系数 1，不读取曲线满耐久值。
        ArmorRules customCurve = DamagePreferences.parse(configuration()
                .replace("[1.0, 1.0]", "[1.0, 0.9]")
                .replace("[0.5, 0.85]", "[0.5, 0.7]")).armor();
        assertEquals(4 - 1.024 * 0.8, customCurve.remainingDamage(4, 8, 2, OptionalDouble.of(0.75)), 1e-12);
        assertEquals(2.976, customCurve.remainingDamage(4, 8, 2, OptionalDouble.empty()), 1e-12);

        // 启动校验拒绝异常百分比、缺失端点、重复坐标和会随磨损增强的曲线。
        for (String invalid : List.of("-0.1", "1.01", "nan", "inf", "\"60%\"")) {
            assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(configuration()
                    .replace("maximum_reduction_ratio = 0.60", "maximum_reduction_ratio = " + invalid)));
        }
        String curve = "[[0.0, 0.0], [0.1, 0.35], [0.25, 0.6], [0.5, 0.85], [1.0, 1.0]]";
        for (String invalid : List.of("[]", "[[0.0, 0.0]]", "[[0.1, 0.0], [1.0, 1.0]]",
                "[[0.0, 0.0], [0.9, 1.0]]", "[[0.0, 0.0], [0.5, 0.8], [0.5, 0.9], [1.0, 1.0]]",
                "[[0.0, 0.0], [0.5, 0.9], [1.0, 0.8]]", "[[0.0, 0.0], [1.0, 1.1]]",
                "[[0.0, 0.0], [1.0, nan]]", "[[0.0, 0.0], [1.0]]", "[0.0, 1.0]")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DamagePreferences.parse(configuration().replace(curve, invalid)));
        }
        for (double invalid : new double[]{-0.1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> armor.remainingDamage(4, 8, 2, OptionalDouble.of(invalid)));
        }
    }

    private static BodyDamageIntegration effects() {
        // 纯协议回归显式装配效果端点；本样例没有黄心和图腾，仍确认同一提交事实。
        return new BodyDamageIntegration() {
            public byte[] prepare(byte[] payload) {
                // 效果为空是合法状态，不代表没有安装集成。
                var context = DamageIntegrationProtobuf.decodeContext(payload);
                return DamageIntegrationProtobuf.encodePlan(DamageIntegrationPlan.newBuilder().setHandled(true)
                        .setRemainingDamageHp(context.getDamage().getDamageHp()).build());
            }

            public void committed(byte[] payload) {
                // 消费方按正式契约验证核心提交后的通知。
                DamageIntegrationProtobuf.decodeCommit(payload);
            }
        };
    }

    private static BodyHealthHost host(InMemoryBodyStore store, AtomicInteger calls) {
        // 正式处理器负责 HP 和幂等；测试宿主仅模拟服务端身份绑定和已结算时钟。
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, current) -> true,
                (caller, current) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(current.settledAt()))
                        .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build(),
                store, (caller, receipt, previous, updated) -> { /* 断言直接读取核心提交结果。 */ });
        return payload -> {
            // 未绑定不产生状态；成功变更再读取核心快照作为宿主响应。
            calls.incrementAndGet();
            var request = HealthHostProtobuf.decodeRequest(payload);
            var response = HealthHostResponse.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId());
            if (!request.getSubjectId().equals("subject")) {
                return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
            }
            if (request.hasEffect()) {
                var effect = MeridianProtocol.decodeResponse(handler.handleHealthEffect("host", request.getEffect().toByteArray()));
                if (effect.hasFailure()) {
                    return HealthHostProtobuf.encodeResponse(response.setFailure(effect.getFailure()).build());
                }
                store.transact(new BodyId("body"), transaction -> {
                    // 效果集成需要本请求的健康事实，不能仅凭提交后的 HP 推断。
                    response.setHealth(transaction.receipt("host", request.getRequestId()).orElseThrow().getHealth());
                    return null;
                });
            }
            return HealthHostProtobuf.encodeResponse(response.setBody(BodyProtobuf.toMessage(
                    store.find(new BodyId("body")).orElseThrow())).build());
        };
    }

    private static BodySnapshot body() {
        // 最小身体只声明必要部位，用真实 schema 校验而不依赖 Human 实现。
        var parts = List.of("head", "chest", "left_thigh", "right_thigh").stream()
                .map(id -> new Anatomy.PartDefinition(new Anatomy.PartId(id), id, 20)).toList();
        var structure = new BodyStructure(parts, List.of(), List.of(), List.of(), List.of());
        return new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("body"),
                new BodyTemplate.Reference(new DefinitionId("human:adult"), 1), structure, Realm.WANDERING_QI,
                parts.stream().map(part -> new AnatomyState.Part(part.id(), 20)).toList(),
                List.of(), List.of(), List.of(), List.of(), new VitalityState(0, 1000, 0, false),
                new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0);
    }

    private static DamagePreferences preferences() throws IOException {
        // 偏好与护甲共用真正的内置配置，避免回归使用另一套默认值。
        return DamagePreferences.parse(configuration());
    }

    private static String configuration() throws IOException {
        // 同一内置资源也是首次启动导出的配置，回归保护实际发布的偏好值。
        try (var input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
