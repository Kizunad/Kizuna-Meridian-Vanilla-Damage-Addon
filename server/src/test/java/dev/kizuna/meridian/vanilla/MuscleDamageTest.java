package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.DamageIntegrationProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyDamageIntegration;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.kizuna.meridian.vanilla.MuscleRules.Arm.*;
import static dev.kizuna.meridian.vanilla.MuscleRules.Grip.*;
import static org.junit.Assert.*;

/** 整体规则回归使用真实能力查询与原子做功命令，不模拟核心结算结果。 */
public class MuscleDamageTest {
    private static final double EPSILON = 1e-9;
    private static final double HALF_HEALTH = 50;
    private static final double HALF_COEFFICIENT = 0.5;
    private static final double MAX_HP = 100;
    private static final double LEFT_STRENGTH = 100;
    private static final double RIGHT_STRENGTH = 300;
    private static final double POWER = 100;
    private static final double ENDURANCE = 1000;
    private static final double BOOST = 1.3;
    private static final double COLLAPSE = 0.85;
    private static final double MINIMUM = 0.25;
    private static final double MAXIMUM = 2;
    private static final double ABOVE_MAXIMUM = 3;
    private static final double CURVE_PEAK = 1.5;
    private static final long STRIKE_DURATION_MILLIS = 500;
    private static final long MODIFIER_DURATION_MILLIS = 1000;
    private static final double LEG_WORK_JOULES_PER_HP = 30;
    private static final double LEG_SWING_SECONDS = 0.4;
    private static final double LEG_DAMAGE = 2;
    private static final double LEG_POWER_PER_TISSUE = 50;
    private static final double ARM_WORK_JOULES_PER_HP = 20;
    private static final double ARM_STRIKE_SECONDS = 0.5;
    private static final double ARM_VANILLA_DAMAGE = 1;
    private static final long QUERY_START_MILLIS = 500;
    private static final long QUERY_PEAK_OFFSET_MILLIS = 400;
    private static final long QUERY_TROUGH_OFFSET_MILLIS = 800;
    private static final long QUERY_RISE_MILLIS = 700;
    private static final long QUERY_PEAK_MILLIS = 900;
    private static final long QUERY_TROUGH_MILLIS = 1300;
    private static final long QUERY_END_MILLIS = 1500;

    @Test
    public void armQueryUsesRequestedSideAndNeverExerts() {
        // 左右臂按解剖侧查询，重复读取不得改变身体、疲劳或版本。
        BodyMessages.BodySnapshot healthy = body();
        var injuredPart = healthy.getPartStates(0).toBuilder().setHp(HALF_HEALTH).build();
        var asymmetric = modified(healthy.toBuilder().setPartStates(0, injuredPart).build(), BOOST);
        var fixture = fixture(asymmetric, preferences());
        var beforeQueries = fixture.store().find(new BodyId("test:attacker")).orElseThrow();
        var bridge = fixture.bridge();
        for (int query = 0; query < 2; query++) {
            assertEquals(HALF_COEFFICIENT, bridge.armCoefficient("attacker", LEFT), EPSILON);
            assertEquals(BOOST, bridge.armCoefficient("attacker", RIGHT), EPSILON);
        }
        assertEquals(0, fixture.exertions().get());
        assertEquals(beforeQueries, fixture.store().find(new BodyId("test:attacker")).orElseThrow());
    }

    @Test
    public void armQueryReadsFreshFatigueAndCurrentModifiers() {
        // 两次撒放间的做功会降低下次系数；查询本身不再追加一笔做功。
        var healthy = body();
        DamagePreferences settings = preferences();
        var fixture = fixture(healthy, settings);
        var bridge = fixture.bridge();
        var store = fixture.store();
        assertEquals(1, bridge.armCoefficient("attacker", RIGHT), EPSILON);
        bridge.exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE);
        var afterStrike = store.find(new BodyId("test:attacker")).orElseThrow();
        var snapshot = BodyProtobuf.toMessage(afterStrike);
        double expected = settings.muscle().plan(snapshot, RIGHT, MAIN_HAND).coefficient();
        assertTrue(expected < 1);
        assertEquals(expected, bridge.armCoefficient("attacker", RIGHT), EPSILON);
        assertEquals(afterStrike, store.find(afterStrike.id()).orElseThrow());
        assertEquals(1, fixture.exertions().get());
        for (double multiplier : List.of(BOOST, COLLAPSE, ABOVE_MAXIMUM)) {
            var modifiedBody = modified(healthy, multiplier);
            double bounded = Math.min(MAXIMUM, multiplier);
            assertEquals(bounded,
                    fixture(modifiedBody, settings).bridge().armCoefficient("attacker", RIGHT), EPSILON);
        }
    }

    @Test
    public void armQueryHandlesDisabledUnboundAndUnmatchedBodies() {
        // 无身体与无匹配组织明确返回 1；服主禁用时连 inspect 都不应发出。
        DamagePreferences settings = preferences();
        var fixture = fixture(body(), settings);
        assertEquals(1, fixture.bridge().armCoefficient("unbound", RIGHT), EPSILON);
        var legs = legBody().toBuilder().setId("test:attacker").build();
        assertEquals(1, fixture(legs, settings).bridge().armCoefficient("attacker", LEFT), EPSILON);
        var disabled = DamagePreferences.parse("hp_per_vanilla_hp = 1\n" + muscleConfig("false"));
        BodyHealthHost unexpectedQuery = payload -> {
            throw new AssertionError("禁用肌肉系数时不应查询身体");
        };
        var bridge = new VanillaDamageBridge(List.of(unexpectedQuery), disabled, List.of(integration()));
        assertEquals(1, bridge.armCoefficient("attacker", RIGHT), EPSILON);
        assertEquals(0, fixture.exertions().get());
    }

    @Test
    public void futureArmQueryProjectsCurvesWithoutAdvancingAuthoritativeBody() {
        // 未生效、插值、峰值、低谷和到期均复用核心曲线；疲劳不会在预估中恢复。
        var healthy = scheduledArmCurve(body());
        var tired = healthy.getTissueStates(1).toBuilder().setFatigue(HALF_HEALTH).build();
        var snapshot = healthy.toBuilder().setTissueStates(1, tired).build();
        var fixture = fixture(snapshot, preferences());
        var beforeQueries = fixture.store().find(new BodyId("test:attacker")).orElseThrow();
        var bridge = fixture.bridge();
        assertEquals(HALF_COEFFICIENT, bridge.armCoefficientAt("attacker", RIGHT, 0), EPSILON);
        assertEquals(HALF_COEFFICIENT,
                bridge.armCoefficientAt("attacker", RIGHT, QUERY_START_MILLIS), EPSILON);
        double rising = HALF_COEFFICIENT * (1 + (CURVE_PEAK - 1) * HALF_COEFFICIENT);
        assertEquals(rising, bridge.armCoefficientAt("attacker", RIGHT, QUERY_RISE_MILLIS), EPSILON);
        assertEquals(HALF_COEFFICIENT * CURVE_PEAK,
                bridge.armCoefficientAt("attacker", RIGHT, QUERY_PEAK_MILLIS), EPSILON);
        assertEquals(MINIMUM, bridge.armCoefficientAt("attacker", RIGHT, QUERY_TROUGH_MILLIS), EPSILON);
        assertEquals(HALF_COEFFICIENT,
                bridge.armCoefficientAt("attacker", RIGHT, QUERY_END_MILLIS), EPSILON);
        assertEquals(1, bridge.armCoefficientAt("attacker", LEFT, QUERY_PEAK_MILLIS), EPSILON);
        assertEquals(0, fixture.exertions().get());
        assertEquals(beforeQueries, fixture.store().find(new BodyId("test:attacker")).orElseThrow());
    }

    @Test
    public void futureArmQueryRejectsPastAndPreservesUnboundAndDisabledRules() {
        // 回溯不是预测；无身体和规则禁用仍沿用当前查询的明确语义。
        var healthy = body();
        var current = healthy.getSettledAt().toBuilder().setElapsedMillis(QUERY_START_MILLIS).build();
        var snapshot = healthy.toBuilder().setSettledAt(current).build();
        DamagePreferences settings = preferences();
        var fixture = fixture(snapshot, settings);
        var bridge = fixture.bridge();
        assertThrows(IllegalArgumentException.class, () -> bridge.armCoefficientAt("attacker", RIGHT, -1));
        assertThrows(IllegalArgumentException.class,
                () -> bridge.armCoefficientAt("attacker", RIGHT, QUERY_START_MILLIS - 1));
        assertEquals(1, bridge.armCoefficientAt("unbound", RIGHT, QUERY_END_MILLIS), EPSILON);
        var legs = legBody().toBuilder().setId("test:attacker").build();
        assertEquals(1,
                fixture(legs, settings).bridge().armCoefficientAt("attacker", LEFT, QUERY_END_MILLIS), EPSILON);
        var disabled = DamagePreferences.parse("hp_per_vanilla_hp = 1\n" + muscleConfig("false"));
        BodyHealthHost unexpectedQuery = payload -> {
            throw new AssertionError("禁用肌肉系数时不应查询身体");
        };
        var disabledBridge = new VanillaDamageBridge(List.of(unexpectedQuery), disabled, List.of(integration()));
        assertEquals(1, disabledBridge.armCoefficientAt("attacker", RIGHT, QUERY_END_MILLIS), EPSILON);
        assertEquals(snapshot, BodyProtobuf.toMessage(fixture.store()
                .find(new BodyId("test:attacker")).orElseThrow()));
    }

    @Test
    public void coefficientUsesHealthFatigueModifiersAndBounds() {
        // 同一组肌肉的基础力量作分母，功率不足只影响后续做功能否被接受。
        MuscleRules rules = preferences().muscle();
        BodyMessages.BodySnapshot healthy = body();
        assertEquals(1, rules.plan(healthy, RIGHT, MAIN_HAND).coefficient(), EPSILON);
        var injuredPart = healthy.getPartStates(1).toBuilder().setHp(HALF_HEALTH).build();
        var injured = healthy.toBuilder().setPartStates(1, injuredPart).build();
        assertEquals(HALF_COEFFICIENT, rules.plan(injured, RIGHT, MAIN_HAND).coefficient(), EPSILON);
        var injuredTissue = healthy.getTissueStates(1).toBuilder().setHp(HALF_HEALTH).build();
        var tissueInjury = healthy.toBuilder().setTissueStates(1, injuredTissue).build();
        assertEquals(HALF_COEFFICIENT, rules.plan(tissueInjury, RIGHT, MAIN_HAND).coefficient(), EPSILON);
        var fatigued = healthy.getTissueStates(1).toBuilder().setFatigue(MAX_HP).build();
        var exhausted = healthy.toBuilder().setTissueStates(1, fatigued).build();
        assertEquals(MINIMUM, rules.plan(exhausted, RIGHT, MAIN_HAND).coefficient(), EPSILON);
        assertEquals(BOOST, rules.plan(modified(healthy, BOOST), RIGHT, MAIN_HAND).coefficient(), EPSILON);
        assertEquals(COLLAPSE, rules.plan(modified(healthy, COLLAPSE), RIGHT, MAIN_HAND).coefficient(), EPSILON);
        assertEquals(MAXIMUM, rules.plan(modified(healthy, ABOVE_MAXIMUM), RIGHT, MAIN_HAND).coefficient(), EPSILON);
    }

    @Test
    public void handednessSelectsSameMusclesForCoefficientAndLoads() {
        // 左臂半伤、两侧基础力量不同，双手必须按力量合计而非平均两个系数。
        MuscleRules rules = preferences().muscle();
        BodyMessages.BodySnapshot healthy = body();
        var leftPart = healthy.getPartStates(0).toBuilder().setHp(HALF_HEALTH).build();
        var asymmetric = healthy.toBuilder().setPartStates(0, leftPart).build();
        assertEquals(1, rules.plan(asymmetric, RIGHT, MAIN_HAND).coefficient(), EPSILON);
        assertEquals(HALF_COEFFICIENT, rules.plan(asymmetric, LEFT, MAIN_HAND).coefficient(), EPSILON);
        assertEquals(HALF_COEFFICIENT, rules.plan(asymmetric, RIGHT, OFF_HAND).coefficient(), EPSILON);
        assertEquals(1, rules.plan(asymmetric, LEFT, OFF_HAND).coefficient(), EPSILON);
        double expectedBoth = (LEFT_STRENGTH * HALF_COEFFICIENT + RIGHT_STRENGTH)
                / (LEFT_STRENGTH + RIGHT_STRENGTH);
        var both = rules.plan(asymmetric, RIGHT, TWO_HANDED, 1);
        assertEquals(expectedBoth, both.coefficient(), EPSILON);
        assertEquals(List.of("left_muscle", "right_muscle"),
                both.loads().getLoadsList().stream().map(ExertTissue::getTissueId).toList());
    }

    @Test
    public void armWorkUsesVanillaDoseAndBaseBurstShares() {
        MuscleRules rules = preferences().muscle();
        var plan = rules.plan(body(), RIGHT, TWO_HANDED, 6);
        assertEquals(2, plan.loads().getLoadsCount());
        assertTrue(plan.loads().getLoadsList().stream().allMatch(load -> load.getForceNewtons() == 0));
        assertTrue(plan.loads().getLoadsList().stream().allMatch(load ->
                load.getPowerWatts() == 120 && load.getDurationSeconds() == ARM_STRIKE_SECONDS));
        double work = plan.loads().getLoadsList().stream()
                .mapToDouble(load -> load.getPowerWatts() * load.getDurationSeconds()).sum();
        assertEquals(6 * ARM_WORK_JOULES_PER_HP, work, EPSILON);
    }

    @Test
    public void negativeOrNonFiniteStrikeDoseIsRejected() {
        MuscleRules rules = preferences().muscle();
        assertThrows(IllegalArgumentException.class, () -> rules.plan(body(), RIGHT, MAIN_HAND, -1));
        assertThrows(IllegalArgumentException.class, () -> rules.plan(body(), RIGHT, MAIN_HAND, Double.NaN));
    }

    @Test
    public void coefficientFallsLinearlyBeforePowerExhaustion() {
        MuscleRules rules = preferences().muscle();
        var healthy = body();
        assertEquals(0.6, rules.plan(modified(healthy, 0.6), RIGHT, MAIN_HAND).coefficient(), EPSILON);
        assertEquals(0.7, rules.plan(modified(healthy, 0.7), RIGHT, MAIN_HAND).coefficient(), EPSILON);
        var fixture = fixture(modified(healthy, 0.6), preferences());
        assertEquals(0.6,
                fixture.bridge().exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE), EPSILON);
        assertEquals(1, fixture.store().find(new BodyId("test:attacker")).orElseThrow().revision());
    }

    @Test
    public void coefficientIsMonotonicAcrossExhaustedThreshold() {
        MuscleRules rules = preferences().muscle();
        var strengthRatios = List.of(1.0, 0.6, 0.35, 0.30, 0.25, 0.20);
        double previousStrengthRatio = strengthRatios.get(0);
        double previousCoefficient = rules.plan(modified(body(), previousStrengthRatio), RIGHT, MAIN_HAND)
                .coefficient();
        for (double strengthRatio : strengthRatios.subList(1, strengthRatios.size())) {
            double coefficient = rules.plan(modified(body(), strengthRatio), RIGHT, MAIN_HAND).coefficient();
            double decrease = previousCoefficient - coefficient;
            double strengthStep = previousStrengthRatio - strengthRatio;
            assertTrue("出力比下降后系数不能上升", decrease >= -EPSILON);
            assertTrue("跨过力竭阈值不应跳变", decrease <= strengthStep + EPSILON);
            previousStrengthRatio = strengthRatio;
            previousCoefficient = coefficient;
        }
    }

    @Test
    public void exhaustedCoefficientOnlyComesFromFrameworkCapacityFailures() {
        double exhaustedCoefficient = preferences().muscle().coefficients().exhausted();
        for (FailureCode code : List.of(FailureCode.TISSUE_LOAD_EXCEEDED, FailureCode.MODULE_UNAVAILABLE)) {
            var bridge = rejectingBridge(body(), code);
            assertEquals(exhaustedCoefficient,
                    bridge.exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE), EPSILON);
        }
        var bridge = rejectingBridge(body(), FailureCode.REVISION_CONFLICT);
        assertThrows(IllegalStateException.class,
                () -> bridge.exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE));
    }

    @Test
    public void legRulesSelectOppositeSideAndDistributeVanillaWork() {
        MuscleRules rules = preferences().muscle();
        BodyMessages.BodySnapshot body = legBody();
        assertEquals(LEG_WORK_JOULES_PER_HP, rules.legWorkJoulesPerVanillaHp(), EPSILON);
        assertEquals(LEG_SWING_SECONDS, rules.legSwingSeconds(), EPSILON);

        var offHand = rules.planLeg(body, RIGHT, OFF_HAND, LEG_DAMAGE);
        assertEquals(List.of("left_gluteus_maximus", "left_rectus_femoris", "left_gastrocnemius"),
                offHand.loads().getLoadsList().stream().map(ExertTissue::getTissueId).toList());
        assertTrue(offHand.loads().getLoadsList().stream()
                .allMatch(load -> load.getPowerWatts() == LEG_POWER_PER_TISSUE
                        && load.getDurationSeconds() == LEG_SWING_SECONDS));

        var twoHanded = rules.planLeg(body, LEFT, TWO_HANDED, LEG_DAMAGE);
        assertEquals(6, twoHanded.loads().getLoadsCount());
        assertTrue(twoHanded.loads().getLoadsList().stream().allMatch(load -> load.getForceNewtons() == 0));
    }

    @Test
    public void strikeCommitsOncePerHitAndBothArmsAtomically() {
        // 正常做功使所有选中肌肉增加疲劳，系数仍取提交前；新命中是独立命令。
        DamagePreferences settings = preferences();
        var fixture = fixture(body(), settings);
        assertEquals(1, fixture.bridge().exertStrike("attacker", RIGHT, TWO_HANDED, ARM_VANILLA_DAMAGE), EPSILON);
        var committed = fixture.store().find(new BodyId("test:attacker")).orElseThrow();
        assertEquals(1, committed.revision());
        assertTrue(committed.tissueStates().stream().allMatch(tissue -> tissue.fatigue() > 0));
        fixture.bridge().exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE);
        assertEquals(2, fixture.store().find(committed.id()).orElseThrow().revision());
        assertEquals(2, fixture.exertions().get());

        var healthy = body();
        var fatigued = healthy.getTissueStates(1).toBuilder().setFatigue(MAX_HP).build();
        var exhausted = healthy.toBuilder().setTissueStates(1, fatigued).build();
        var rejected = fixture(exhausted, settings);
        assertEquals(MINIMUM,
                rejected.bridge().exertStrike("attacker", RIGHT, TWO_HANDED, ARM_VANILLA_DAMAGE), EPSILON);
        assertEquals(exhausted, BodyProtobuf.toMessage(rejected.store()
                .find(new BodyId("test:attacker")).orElseThrow()));
    }

    @Test
    public void disabledAndUnboundDoNotExertAndConfigurationIsValidated() {
        // 服主禁用不读取身体；无身体实体仍由实际宿主明确返回 unbound。
        var disabled = DamagePreferences.parse("hp_per_vanilla_hp = 1\n" + muscleConfig("false"));
        var fixture = fixture(body(), disabled);
        assertEquals(1, fixture.bridge().exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE), EPSILON);
        assertEquals(0, fixture.exertions().get());
        assertEquals(0, fixture.store().find(new BodyId("test:attacker")).orElseThrow().revision());
        assertEquals(1, fixture(body(), preferences()).bridge()
                .exertStrike("unbound", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE), EPSILON);
        assertEquals(0, disabled.muscle().plan(body(), RIGHT, TWO_HANDED).loads().getLoadsCount());
        for (String invalid : List.of("strike_seconds = nan", "min_coefficient = 3", "tissue_tag = \"invalid\"",
                "right_parts = [\"left_upper_arm\"]", "work_joules_per_vanilla_hp = 0")) {
            String key = invalid.substring(0, invalid.indexOf(" ="));
            String text = "hp_per_vanilla_hp = 1\n" + muscleConfig("true")
                    .replaceAll("(?m)^" + key + " = .*", invalid);
            assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse(text));
        }
    }

    @Test
    public void minimumCurveAcrossStrikeCanRejectPeakAndOtherFailuresRemainErrors() {
        // 当前力量仍在峰值，后半段跌到做功阈值以下时整次拒绝。
        var healthy = modified(body(), BOOST);
        var peak = healthy.getModifiers(0);
        var trough = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(STRIKE_DURATION_MILLIS)
                .setValue(-0.9)
                .build();
        var decaying = peak.toBuilder()
                .setStrengthPeak(CURVE_PEAK)
                .setBurstPeak(2)
                .setCurve(1, trough)
                .build();
        var body = healthy.toBuilder().setModifiers(0, decaying).build();
        DamagePreferences settings = preferences();
        var fixture = fixture(body, settings);
        assertEquals(MINIMUM,
                fixture.bridge().exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE), EPSILON);
        assertEquals(0, fixture.store().find(new BodyId("test:attacker")).orElseThrow().revision());
        BodyHealthHost broken = payload -> {
            var request = HealthHostProtobuf.decodeRequest(payload);
            var failure = RequestFailure.newBuilder().setCode(FailureCode.REVISION_CONFLICT).build();
            var response = HealthHostResponse.newBuilder()
                    .setProtocolVersion(1)
                    .setRequestId(request.getRequestId());
            if (request.hasInspect()) {
                return HealthHostProtobuf.encodeResponse(response.setBody(body).build());
            }
            return HealthHostProtobuf.encodeResponse(response.setFailure(failure).build());
        };
        var bridge = new VanillaDamageBridge(List.of(broken), settings, List.of(integration()));
        assertThrows(IllegalStateException.class,
                () -> bridge.exertStrike("attacker", RIGHT, MAIN_HAND, ARM_VANILLA_DAMAGE));
    }

    private static VanillaDamageBridge rejectingBridge(BodyMessages.BodySnapshot body, FailureCode code) {
        BodyHealthHost host = payload -> {
            var request = HealthHostProtobuf.decodeRequest(payload);
            var response = HealthHostResponse.newBuilder()
                    .setProtocolVersion(1)
                    .setRequestId(request.getRequestId());
            if (request.hasInspect()) {
                return HealthHostProtobuf.encodeResponse(response.setBody(body).build());
            }
            var failure = RequestFailure.newBuilder()
                    .setCode(code)
                    .build();
            return HealthHostProtobuf.encodeResponse(response.setFailure(failure).build());
        };
        return new VanillaDamageBridge(List.of(host), preferences(), List.of(integration()));
    }

    private static Fixture fixture(BodyMessages.BodySnapshot body, DamagePreferences preferences) {
        // 仅隔离实体身份，真实命令处理器负责幂等、版本与原子疲劳事务。
        var store = new InMemoryBodyStore(List.of(BodyProtobuf.fromMessage(body)), List.of());
        var exertions = new AtomicInteger();
        var commands = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, snapshot) -> true,
                (caller, snapshot) -> TimeAdvancePlan.newBuilder()
                        .setTarget(BodyProtobuf.toTime(snapshot.settledAt()))
                        .setMode(TimeAdvanceMode.ACTIVE)
                        .setBiologicalSecondsPerSimulationSecond(1)
                        .build(), store, (caller, receipt, previous, updated) -> { });
        BodyHealthHost host = payload -> {
            var request = HealthHostProtobuf.decodeRequest(payload);
            var response = HealthHostResponse.newBuilder()
                    .setProtocolVersion(1)
                    .setRequestId(request.getRequestId());
            if (!request.getSubjectId().equals("attacker")) {
                return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
            }
            if (request.hasExertion()) {
                exertions.incrementAndGet();
                var result = MeridianProtocol.decodeResponse(commands.handle("test:attacker",
                        MeridianProtocol.encodeRequest(request.getExertion())));
                if (result.hasFailure()) {
                    return HealthHostProtobuf.encodeResponse(response.setFailure(result.getFailure()).build());
                }
            }
            var current = BodyProtobuf.toMessage(store.find(new BodyId("test:attacker")).orElseThrow());
            return HealthHostProtobuf.encodeResponse(response.setBody(current).build());
        };
        var bridge = new VanillaDamageBridge(List.of(host), preferences, List.of(integration()));
        return new Fixture(bridge, store, exertions);
    }

    private record Fixture(VanillaDamageBridge bridge, InMemoryBodyStore store, AtomicInteger exertions) { }

    private static BodyDamageIntegration integration() {
        // 本组测试只做功，不进入健康效果；构造桥接仍必须具备完整接线。
        return new BodyDamageIntegration() {
            @Override
            public byte[] prepare(byte[] payload) {
                return DamageIntegrationProtobuf.encodePlan(
                        dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationPlan.getDefaultInstance());
            }

            @Override
            public void committed(byte[] payload) {
                // 做功测试不会发布健康效果提交。
            }
        };
    }

    private static DamagePreferences preferences() {
        // 缺少 muscle 节的旧配置也必须读取内置默认规则。
        return DamagePreferences.parse("hp_per_vanilla_hp = 1");
    }

    private static String muscleConfig(String enabled) {
        // 显式配置用于验证服主禁用及启动时的输入拒绝。
        return """
                [muscle]
                enabled = %s
                tissue_tag = "human:arm_muscle"
                left_parts = ["left_upper_arm"]
                right_parts = ["right_upper_arm"]
                min_coefficient = 0.25
                max_coefficient = 2.0
                exhausted_coefficient = 0.25
                work_joules_per_vanilla_hp = 20.0
                strike_seconds = 0.5
                leg_tissue_tag = "human:leg_muscle"
                left_leg = ["left_gluteus_maximus", "left_rectus_femoris", "left_gastrocnemius"]
                right_leg = ["right_gluteus_maximus", "right_rectus_femoris", "right_gastrocnemius"]
                leg_work_joules_per_vanilla_hp = 30.0
                leg_swing_seconds = 0.4
                """.formatted(enabled);
    }

    private static BodyMessages.BodySnapshot modified(BodyMessages.BodySnapshot body, double multiplier) {
        // 常值曲线只改力量，证明伤害系数不读取爆发功率。
        var start = BodyMessages.CurvePoint.newBuilder().setValue(1).build();
        var end = start.toBuilder().setOffsetMillis(MODIFIER_DURATION_MILLIS).build();
        var modifier = BodyMessages.CapabilityModifier.newBuilder()
                .setId("test:boost")
                .setSource("test:source")
                .setTissueId("right_muscle")
                .addCurve(start)
                .addCurve(end)
                .setStrengthPeak(multiplier)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .build();
        return body.toBuilder().addModifiers(modifier).build();
    }

    private static BodyMessages.BodySnapshot scheduledArmCurve(BodyMessages.BodySnapshot body) {
        // 从未来开始的完整曲线覆盖上升、峰顶、虚脱和到期边界。
        var start = BodyMessages.CurvePoint.getDefaultInstance();
        var peak = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(QUERY_PEAK_OFFSET_MILLIS)
                .setValue(1)
                .build();
        var trough = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(QUERY_TROUGH_OFFSET_MILLIS)
                .setValue(-1)
                .build();
        var end = BodyMessages.CurvePoint.newBuilder().setOffsetMillis(MODIFIER_DURATION_MILLIS).build();
        var modifier = BodyMessages.CapabilityModifier.newBuilder()
                .setId("test:future")
                .setSource("test:source")
                .setTissueId("right_muscle")
                .setStartMillis(QUERY_START_MILLIS)
                .addCurve(start)
                .addCurve(peak)
                .addCurve(trough)
                .addCurve(end)
                .setStrengthPeak(CURVE_PEAK)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .build();
        return body.toBuilder().addModifiers(modifier).build();
    }

    private static BodyMessages.BodySnapshot body() {
        // 两臂具有不同基础力量，防止双手系数被错误实现为无权平均。
        var left = new Anatomy.PartId("left_upper_arm");
        var right = new Anatomy.PartId("right_upper_arm");
        var leftMuscle = muscle("left_muscle", left, LEFT_STRENGTH);
        var rightMuscle = muscle("right_muscle", right, RIGHT_STRENGTH);
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(left, "左臂", MAX_HP),
                new Anatomy.PartDefinition(right, "右臂", MAX_HP)), List.of(leftMuscle, rightMuscle),
                List.of(), List.of(), List.of());
        var body = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("test:attacker"),
                new BodyTemplate.Reference(new DefinitionId("test:arms"), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(left, MAX_HP), new AnatomyState.Part(right, MAX_HP)),
                List.of(new AnatomyState.Tissue(leftMuscle.id(), MAX_HP, 0),
                        new AnatomyState.Tissue(rightMuscle.id(), MAX_HP, 0)),
                List.of(), List.of(), List.of(), new VitalityState(0, ENDURANCE, 0, false /* dead */),
                new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0);
        return BodyProtobuf.toMessage(body);
    }

    private static BodyMessages.BodySnapshot legBody() {
        var pelvis = new Anatomy.PartId("pelvis");
        var leftThigh = new Anatomy.PartId("left_thigh");
        var leftLowerLeg = new Anatomy.PartId("left_lower_leg");
        var rightThigh = new Anatomy.PartId("right_thigh");
        var rightLowerLeg = new Anatomy.PartId("right_lower_leg");
        var parts = List.of(pelvis, leftThigh, leftLowerLeg, rightThigh, rightLowerLeg);
        var definitions = List.of(
                muscle("left_gluteus_maximus", pelvis, LEFT_STRENGTH, "human:leg_muscle"),
                muscle("left_rectus_femoris", leftThigh, LEFT_STRENGTH, "human:leg_muscle"),
                muscle("left_gastrocnemius", leftLowerLeg, LEFT_STRENGTH, "human:leg_muscle"),
                muscle("right_gluteus_maximus", pelvis, RIGHT_STRENGTH, "human:leg_muscle"),
                muscle("right_rectus_femoris", rightThigh, RIGHT_STRENGTH, "human:leg_muscle"),
                muscle("right_gastrocnemius", rightLowerLeg, RIGHT_STRENGTH, "human:leg_muscle"));
        var structure = new BodyStructure(parts.stream()
                .map(part -> new Anatomy.PartDefinition(part, part.value(), MAX_HP)).toList(), definitions,
                List.of(), List.of(), List.of());
        var body = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("test:legs"),
                new BodyTemplate.Reference(new DefinitionId("test:legs"), 1), structure, Realm.WANDERING_QI,
                parts.stream().map(part -> new AnatomyState.Part(part, MAX_HP)).toList(),
                definitions.stream().map(tissue -> new AnatomyState.Tissue(tissue.id(), MAX_HP, 0)).toList(),
                List.of(), List.of(), List.of(), new VitalityState(0, ENDURANCE, 0, false /* dead */),
                new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0);
        return BodyProtobuf.toMessage(body);
    }

    private static Anatomy.TissueDefinition muscle(String id, Anatomy.PartId part, double strength) {
        return muscle(id, part, strength, "human:arm_muscle");
    }

    private static Anatomy.TissueDefinition muscle(String id, Anatomy.PartId part, double strength, String tag) {
        // 肌肉标签是唯一选择依据，不向规则引入 Human 实现依赖。
        return new Anatomy.TissueDefinition(new Anatomy.TissueId(id), "肌肉", new DefinitionId("test:muscle"),
                part, MAX_HP, MAX_HP, List.of(), new Anatomy.TissueCapacity(strength, POWER, ENDURANCE), 0,
                List.of(new DefinitionId(tag)));
    }
}
