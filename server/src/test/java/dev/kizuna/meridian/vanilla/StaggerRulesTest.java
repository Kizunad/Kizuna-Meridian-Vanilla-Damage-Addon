package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 僵直换算：门槛、线性比例、上下限夹取和致伤类型系数都读取发布的默认配置。 */
public class StaggerRulesTest {
    private static final double CHEST_HP = 40;
    private static final double HAND_HP = 8;
    private static final double BELOW_THRESHOLD_DOSE = 1.9;
    private static final double THRESHOLD_DOSE = 2;
    private static final double MIDDLE_DOSE = 4;
    private static final double HEAVY_DOSE = 20;
    private static final long THRESHOLD_MILLIS = 200;
    private static final long MIDDLE_MILLIS = 400;
    private static final long BLUNT_BELOW_MILLIS = 285;
    private static final long BLUNT_MIDDLE_MILLIS = 600;
    private static final long PIERCING_MIDDLE_MILLIS = 300;
    private static final long MAXIMUM_MILLIS = 1000;
    private static final double LOW_DOSE = 0.8;
    private static final double MOVE_FACTOR = 0.5;
    private static final double LIFESPAN_SECONDS = 1000;

    @Test
    public void belowThresholdHasNoStagger() throws IOException {
        // 切割 1.9 / 40 = 4.75% 低于 5%；同样剂量的钝击乘 1.5 后越过门槛。
        StaggerRules rules = DamagePreferences.parse(configuration()).stagger();
        BodyMessages.BodySnapshot body = body();
        assertEquals(0, rules.millis(body, part("chest"), DamageKind.CUTTING, BELOW_THRESHOLD_DOSE));
        assertEquals(BLUNT_BELOW_MILLIS, rules.millis(body, part("chest"), DamageKind.BLUNT, BELOW_THRESHOLD_DOSE));
        assertEquals(0, rules.millis(body, part("chest"), DamageKind.CUTTING, 0));
    }

    @Test
    public void staggerIsLinearAboveThresholdAndClamped() throws IOException {
        // 比例 × 4000 毫秒，门槛处恰为下限 200，过重命中夹到上限 1000；比例分母取命中部位自己的上限。
        StaggerRules rules = DamagePreferences.parse(configuration()).stagger();
        BodyMessages.BodySnapshot body = body();
        assertEquals(THRESHOLD_MILLIS, rules.millis(body, part("chest"), DamageKind.CUTTING, THRESHOLD_DOSE));
        assertEquals(MIDDLE_MILLIS, rules.millis(body, part("chest"), DamageKind.CUTTING, MIDDLE_DOSE));
        assertEquals(MAXIMUM_MILLIS, rules.millis(body, part("chest"), DamageKind.CUTTING, HEAVY_DOSE));
        assertEquals(MAXIMUM_MILLIS, rules.millis(body, part("right_hand"), DamageKind.CUTTING, MIDDLE_DOSE));
        assertEquals(MOVE_FACTOR, rules.movementMultiplier(), 0);
    }

    @Test
    public void lowerBoundAppliesWhenThresholdIsBelowIt() throws IOException {
        // 服主调低门槛后，刚过门槛的轻击也至少僵直下限时长。
        String lowered = configuration().replace("threshold = 0.05", "threshold = 0.01");
        StaggerRules rules = DamagePreferences.parse(lowered).stagger();
        assertEquals(THRESHOLD_MILLIS, rules.millis(body(), part("chest"), DamageKind.CUTTING, LOW_DOSE));
    }

    @Test
    public void kindFactorsScaleRatioAndUnlistedKindsNeverStagger() throws IOException {
        // 钝击 ×1.5、切割 ×1.0、穿刺 ×0.75；灼烧等未列出类型不产生僵直。
        StaggerRules rules = DamagePreferences.parse(configuration()).stagger();
        BodyMessages.BodySnapshot body = body();
        assertEquals(BLUNT_MIDDLE_MILLIS, rules.millis(body, part("chest"), DamageKind.BLUNT, MIDDLE_DOSE));
        assertEquals(MIDDLE_MILLIS, rules.millis(body, part("chest"), DamageKind.CUTTING, MIDDLE_DOSE));
        assertEquals(PIERCING_MIDDLE_MILLIS, rules.millis(body, part("chest"), DamageKind.PIERCING, MIDDLE_DOSE));
        assertEquals(0, rules.millis(body, part("chest"), DamageKind.HEAT, HEAVY_DOSE));
        assertEquals(0, rules.millis(body, part("missing"), DamageKind.BLUNT, HEAVY_DOSE));
    }

    @Test
    public void oldConfigFallsBackAndInvalidStaggerIsRejected() throws IOException {
        // 旧配置缺少 [stagger] 时读默认节；显式写错的门槛、上下限、倍率和类型在加载时拒绝。
        assertEquals(MOVE_FACTOR, DamagePreferences.parse("hp_per_vanilla_hp = 1").stagger().movementMultiplier(), 0);
        String config = configuration();
        for (String invalid : List.of("threshold = 0", "threshold = 1.5")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DamagePreferences.parse(config.replace("threshold = 0.05", invalid)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> DamagePreferences.parse(config.replace("minimum_millis = 200", "minimum_millis = 2000")));
        assertThrows(IllegalArgumentException.class,
                () -> DamagePreferences.parse(config.replace("move_factor = 0.5", "move_factor = 1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> DamagePreferences.parse(config.replace("blunt = 1.5", "blunty = 1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> DamagePreferences.parse(config.replace("blunt = 1.5", "blunt = -1.5")));
    }

    private static HealthTarget part(String id) {
        // 命中目标只携带部位 ID，上限从身体结构读取。
        return HealthTarget.newBuilder().setPartId(id).build();
    }

    private static BodyMessages.BodySnapshot body() {
        // 胸 40 与手 8 取 Human 模板的同名部位上限，验证分母按部位而非整身。
        var chest = new Anatomy.PartDefinition(new Anatomy.PartId("chest"), "chest", CHEST_HP);
        var hand = new Anatomy.PartDefinition(new Anatomy.PartId("right_hand"), "right_hand", HAND_HP);
        var parts = List.of(chest, hand);
        var structure = new BodyStructure(parts, List.of(), List.of(), List.of(), List.of());
        var states = parts.stream().map(part -> new AnatomyState.Part(part.id(), part.maxHp())).toList();
        var template = new BodyTemplate.Reference(new DefinitionId("human:adult"), 1);
        var vitality = new VitalityState(0, LIFESPAN_SECONDS, 0, false);
        var time = new TimeCursor(new DefinitionId("test:world"), "epoch", 0);
        var snapshot = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("body"), template, structure,
                Realm.WANDERING_QI, states, List.of(), List.of(), List.of(), List.of(), vitality, time, 0);
        return BodyProtobuf.toMessage(snapshot);
    }

    private static String configuration() throws IOException {
        // 回归保护首次启动导出的同一份内置配置。
        try (var input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
