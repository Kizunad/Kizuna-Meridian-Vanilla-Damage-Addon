package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissues;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 出手侧只选择实际组织，能力与疲劳均由 Framework 解释。 */
public record MuscleRules(boolean enabled, String tissueTag, Parts parts, Coefficients coefficients, Strike strike,
                          LegSettings legs) {
    private static final String DEFAULT_LEG_TISSUE_TAG = "human:leg_muscle";
    private static final double DEFAULT_LEG_WORK_JOULES_PER_VANILLA_HP = 30.0;
    private static final double DEFAULT_LEG_SWING_SECONDS = 0.4;
    private static final Set<String> DEFAULT_LEFT_LEG = Set.of(
            "left_gluteus_maximus", "left_rectus_femoris", "left_gastrocnemius");
    private static final Set<String> DEFAULT_RIGHT_LEG = Set.of(
            "right_gluteus_maximus", "right_rectus_femoris", "right_gastrocnemius");

    public enum Arm { LEFT, RIGHT }
    public enum Grip { MAIN_HAND, OFF_HAND, TWO_HANDED }
    public enum Group { ARMS, LEGS }
    public record Parts(Set<String> left, Set<String> right) { }
    public record Coefficients(double minimum, double maximum, double exhausted) { }
    public record Strike(double workJoulesPerVanillaHp, double seconds) { }
    public record LegSettings(String tissueTag, Parts parts, LegWork work) { }
    public record LegWork(double joulesPerVanillaHp, double seconds) { }
    public record Plan(double coefficient, ExertTissues loads) { }

    public MuscleRules(boolean enabled, String tissueTag, Parts parts, Coefficients coefficients, Strike strike) {
        this(enabled, tissueTag, parts, coefficients, strike, defaultLegSettings());
    }

    public static MuscleRules parse(TomlTable table) {
        // 配置在加载时完整校验，已有配置缺少腿法字段时使用内置腿部规则。
        if (!(table.get("enabled") instanceof Boolean enabled)
                || !(table.get("tissue_tag") instanceof String tag)
                || !tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE);
        }
        Set<String> left = requiredPartIds(table, "left_parts");
        Set<String> right = requiredPartIds(table, "right_parts");
        rejectOverlap(left, right);
        double minimum = positive(table, "min_coefficient");
        double maximum = positive(table, "max_coefficient");
        double exhausted = positive(table, "exhausted_coefficient");
        if (minimum > maximum || exhausted < minimum || exhausted > maximum) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE);
        }
        double workJoulesPerVanillaHp = positive(table, "work_joules_per_vanilla_hp");
        double strikeSeconds = positive(table, "strike_seconds");
        var strike = new Strike(workJoulesPerVanillaHp, strikeSeconds);
        String legTag = optionalTag(table, "leg_tissue_tag", DEFAULT_LEG_TISSUE_TAG);
        Set<String> leftLeg = partIds(table, "left_leg", DEFAULT_LEFT_LEG);
        Set<String> rightLeg = partIds(table, "right_leg", DEFAULT_RIGHT_LEG);
        rejectOverlap(leftLeg, rightLeg);
        double legWork = optionalPositive(table, "leg_work_joules_per_vanilla_hp",
                DEFAULT_LEG_WORK_JOULES_PER_VANILLA_HP);
        double legSeconds = optionalPositive(table, "leg_swing_seconds", DEFAULT_LEG_SWING_SECONDS);
        var legSettings = new LegSettings(legTag, new Parts(leftLeg, rightLeg),
                new LegWork(legWork, legSeconds));
        return new MuscleRules(enabled, tag, new Parts(left, right),
                new Coefficients(minimum, maximum, exhausted), strike, legSettings);
    }

    private static LegSettings defaultLegSettings() {
        return new LegSettings(DEFAULT_LEG_TISSUE_TAG,
                new Parts(DEFAULT_LEFT_LEG, DEFAULT_RIGHT_LEG),
                new LegWork(DEFAULT_LEG_WORK_JOULES_PER_VANILLA_HP, DEFAULT_LEG_SWING_SECONDS));
    }

    private static String optionalTag(TomlTable table, String key, String fallback) {
        Object configured = table.get(key);
        if (configured == null) {
            return fallback;
        }
        if (!(configured instanceof String tag) || !tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        return tag;
    }

    private static double positive(TomlTable table, String key) {
        return optionalPositive(table, key, Double.NaN);
    }

    private static double optionalPositive(TomlTable table, String key, double fallback) {
        Object configured = table.get(key);
        if (configured == null) {
            if (Double.isFinite(fallback)) {
                return fallback;
            }
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        if (!(configured instanceof Number number)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        return value;
    }

    private static Set<String> partIds(TomlTable table, String key, Set<String> fallback) {
        Object configured = table.get(key);
        if (configured == null) {
            return Set.copyOf(fallback);
        }
        return parsePartIds(configured, key);
    }

    private static Set<String> requiredPartIds(TomlTable table, String key) {
        Object configured = table.get(key);
        if (configured == null) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        return parsePartIds(configured, key);
    }

    private static Set<String> parsePartIds(Object configured, String key) {
        if (!(configured instanceof TomlArray values) || values.isEmpty()) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        var result = new HashSet<String>();
        for (int index = 0; index < values.size(); index++) {
            if (!(values.get(index) instanceof String id) || !id.matches("[a-z0-9_./-]+") || !result.add(id)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
            }
        }
        return Set.copyOf(result);
    }

    private static void rejectOverlap(Set<String> left, Set<String> right) {
        if (left.stream().anyMatch(right::contains)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE);
        }
    }

    public Plan plan(BodySnapshot snapshot, Arm mainArm, Grip grip) {
        return plan(snapshot, mainArm, grip, Group.ARMS, 0);
    }

    public double armCoefficientAt(BodySnapshot snapshot, Arm arm, long elapsedMillis) {
        // 只在内存候选中推进曲线时钟，冻结伤势、疲劳和老化，不写回宿主。
        long currentMillis = snapshot.getSettledAt().getElapsedMillis();
        if (elapsedMillis < currentMillis) {
            throw new IllegalArgumentException(VanillaDamageErrors.MUSCLE_PROJECTION_TIME);
        }
        if (!enabled) {
            return 1;
        }
        if (elapsedMillis == currentMillis || snapshot.getVitality().getDead()) {
            return plan(snapshot, arm, Grip.MAIN_HAND).coefficient();
        }
        var body = BodyProtobuf.fromMessage(snapshot);
        TimeCursor current = body.settledAt();
        var target = new TimeCursor(current.domain(), current.epoch(), elapsedMillis);
        var projection = new BodyTimeAdvancement.Plan(target, 0, BodyTimeAdvancement.Mode.LIFESPAN_ONLY);
        var projected = BodyTimeAdvancement.apply(body, projection).body();
        return plan(BodyProtobuf.toMessage(projected), arm, Grip.MAIN_HAND).coefficient();
    }

    public Plan plan(BodySnapshot snapshot, Arm mainArm, Grip grip, double vanillaDamage) {
        return plan(snapshot, mainArm, grip, Group.ARMS, vanillaDamage);
    }

    public Plan planLeg(BodySnapshot snapshot, Arm mainArm, Grip grip, double vanillaDamage) {
        return plan(snapshot, mainArm, grip, Group.LEGS, vanillaDamage);
    }

    public Parts legParts() {
        return legs.parts();
    }

    public String legTissueTag() {
        return legs.tissueTag();
    }

    public double legWorkJoulesPerVanillaHp() {
        return legs.work().joulesPerVanillaHp();
    }

    public double legSwingSeconds() {
        return legs.work().seconds();
    }

    private Plan plan(BodySnapshot snapshot, Arm mainArm, Grip grip, Group group, double vanillaDamage) {
        // 使用做功前的同一快照，分子分母严格来自同一组出手组织。
        if (!enabled) {
            return new Plan(1, ExertTissues.getDefaultInstance());
        }
        validateDamage(group, vanillaDamage);
        var body = BodyProtobuf.fromMessage(snapshot);
        Set<String> selected = selectedIds(mainArm, grip, group);
        List<Anatomy.TissueDefinition> muscles = body.structure().tissues().stream()
                .filter(tissue -> selected.contains(selectionId(tissue, group)))
                .filter(tissue -> tissue.tags().stream().anyMatch(tag -> tag.value().equals(tissueTag(group))))
                .toList();
        if (muscles.isEmpty()) {
            return new Plan(1, ExertTissues.getDefaultInstance());
        }
        double baseStrength = 0;
        double availableStrength = 0;
        for (var muscle : muscles) {
            var capacity = muscle.capacity();
            baseStrength += capacity.strengthNewtons();
            availableStrength += TissueCapabilities.available(body, muscle.id()).strengthNewtons();
        }
        if (!Double.isFinite(baseStrength) || baseStrength <= 0 || !Double.isFinite(availableStrength)) {
            throw new IllegalStateException(VanillaDamageErrors.MUSCLE_CAPACITY);
        }
        double ratio = availableStrength / baseStrength;
        double coefficient = Math.max(coefficients.minimum(), Math.min(coefficients.maximum(), ratio));
        ExertTissues loads = loads(muscles, group, vanillaDamage);
        return new Plan(coefficient, loads);
    }

    private static void validateDamage(Group group, double vanillaDamage) {
        if (!Double.isFinite(vanillaDamage) || vanillaDamage < 0
                || (group == Group.LEGS && vanillaDamage <= 0)) {
            throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
        }
    }

    private ExertTissues loads(List<Anatomy.TissueDefinition> muscles, Group group, double vanillaDamage) {
        if (group == Group.LEGS) {
            return legLoads(muscles, vanillaDamage);
        }
        return armLoads(muscles, vanillaDamage);
    }

    private ExertTissues legLoads(List<Anatomy.TissueDefinition> muscles, double vanillaDamage) {
        double swingSeconds = legs.work().seconds();
        double totalWork = vanillaDamage * legs.work().joulesPerVanillaHp();
        double legPower = totalWork / swingSeconds / muscles.size();
        var loads = ExertTissues.newBuilder();
        for (var muscle : muscles) {
            loads.addLoads(legLoad(muscle, legPower, swingSeconds));
        }
        return loads.build();
    }

    private ExertTissue legLoad(Anatomy.TissueDefinition muscle, double legPower, double swingSeconds) {
        return ExertTissue.newBuilder()
                .setTissueId(muscle.id().value())
                .setForceNewtons(0)
                .setPowerWatts(legPower)
                .setDurationSeconds(swingSeconds)
                .build();
    }

    private ExertTissues armLoads(List<Anatomy.TissueDefinition> muscles, double vanillaDamage) {
        if (vanillaDamage == 0) {
            return ExertTissues.getDefaultInstance();
        }
        double totalWork = vanillaDamage * strike.workJoulesPerVanillaHp();
        double totalBaseBurstWatts = muscles.stream()
                .mapToDouble(muscle -> muscle.capacity().burstWatts())
                .sum();
        if (!Double.isFinite(totalWork) || totalWork <= 0
                || !Double.isFinite(totalBaseBurstWatts) || totalBaseBurstWatts <= 0) {
            throw new IllegalStateException(VanillaDamageErrors.MUSCLE_CAPACITY);
        }
        double strikeSeconds = strike.seconds();
        double totalPower = totalWork / strikeSeconds;
        if (!Double.isFinite(totalPower) || totalPower <= 0) {
            throw new IllegalStateException(VanillaDamageErrors.MUSCLE_CAPACITY);
        }
        var loads = ExertTissues.newBuilder();
        for (var muscle : muscles) {
            double powerShare = muscle.capacity().burstWatts() / totalBaseBurstWatts;
            double musclePower = totalPower * powerShare;
            loads.addLoads(armLoad(muscle, musclePower, strikeSeconds));
        }
        return loads.build();
    }

    private ExertTissue armLoad(Anatomy.TissueDefinition muscle, double powerWatts, double strikeSeconds) {
        return ExertTissue.newBuilder()
                .setTissueId(muscle.id().value())
                .setForceNewtons(0)
                .setPowerWatts(powerWatts)
                .setDurationSeconds(strikeSeconds)
                .build();
    }

    private Set<String> selectedIds(Arm mainArm, Grip grip, Group group) {
        Parts configured;
        if (group == Group.ARMS) {
            configured = parts;
        } else {
            configured = legs.parts();
        }
        if (grip == Grip.TWO_HANDED) {
            var both = new HashSet<>(configured.left());
            both.addAll(configured.right());
            return both;
        }
        Arm selected = mainArm;
        if (grip == Grip.OFF_HAND) {
            selected = mainArm == Arm.LEFT ? Arm.RIGHT : Arm.LEFT;
        }
        if (selected == Arm.LEFT) {
            return configured.left();
        }
        return configured.right();
    }

    private String selectionId(Anatomy.TissueDefinition tissue, Group group) {
        if (group == Group.LEGS) {
            return tissue.id().value();
        }
        return tissue.partId().value();
    }

    private String tissueTag(Group group) {
        if (group == Group.LEGS) {
            return legs.tissueTag();
        }
        return tissueTag;
    }
}
