package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.core.Anatomy;
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
public record MuscleRules(boolean enabled, String tissueTag, Parts parts, Coefficients coefficients, Strike strike) {
    public enum Arm { LEFT, RIGHT }
    public enum Grip { MAIN_HAND, OFF_HAND, TWO_HANDED }
    public record Parts(Set<String> left, Set<String> right) { }
    public record Coefficients(double minimum, double maximum, double exhausted) { }
    public record Strike(double forceRatio, double powerRatio, double seconds) { }
    public record Plan(double coefficient, ExertTissues loads) { }

    public static MuscleRules parse(TomlTable table) {
        // 配置在加载时完整校验，已有配置缺节由调用方补入内置默认表。
        if (!(table.get("enabled") instanceof Boolean enabled)
                || !(table.get("tissue_tag") instanceof String tag)
                || !tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE);
        }
        Set<String> left = partIds(table, "left_parts");
        Set<String> right = partIds(table, "right_parts");
        if (left.stream().anyMatch(right::contains)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE);
        }
        double minimum = positive(table, "min_coefficient");
        double maximum = positive(table, "max_coefficient");
        double exhausted = positive(table, "exhausted_coefficient");
        if (minimum > maximum || exhausted < minimum || exhausted > maximum) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE);
        }
        var strike = new Strike(positive(table, "strike_force_ratio"), positive(table, "strike_power_ratio"),
                positive(table, "strike_seconds"));
        return new MuscleRules(enabled, tag, new Parts(left, right),
                new Coefficients(minimum, maximum, exhausted), strike);
    }

    private static double positive(TomlTable table, String key) {
        // 非有限值和零负值不能形成有效做功请求。
        if (!(table.get(key) instanceof Number number)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.MUSCLE + ": " + key);
        }
        return value;
    }

    private static Set<String> partIds(TomlTable table, String key) {
        // 两侧分组必须非空且无重复，防止同一肌肉被重复记账。
        if (!(table.get(key) instanceof TomlArray values) || values.isEmpty()) {
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

    public Plan plan(BodySnapshot snapshot, Arm mainArm, Grip grip) {
        // 使用做功前的同一快照，分子分母严格来自同一组出手组织。
        if (!enabled) {
            return new Plan(1, ExertTissues.getDefaultInstance());
        }
        var body = BodyProtobuf.fromMessage(snapshot);
        Set<String> selected = selectedParts(mainArm, grip);
        List<Anatomy.TissueDefinition> muscles = body.structure().tissues().stream()
                .filter(tissue -> selected.contains(tissue.partId().value()))
                .filter(tissue -> tissue.tags().stream().anyMatch(tag -> tag.value().equals(tissueTag)))
                .toList();
        if (muscles.isEmpty()) {
            return new Plan(1, ExertTissues.getDefaultInstance());
        }
        double baseStrength = 0;
        double availableStrength = 0;
        var loads = ExertTissues.newBuilder();
        for (var muscle : muscles) {
            var capacity = muscle.capacity();
            baseStrength += capacity.strengthNewtons();
            availableStrength += TissueCapabilities.available(body, muscle.id()).strengthNewtons();
            loads.addLoads(load(muscle));
        }
        if (!Double.isFinite(baseStrength) || baseStrength <= 0 || !Double.isFinite(availableStrength)) {
            throw new IllegalStateException(VanillaDamageErrors.MUSCLE_CAPACITY);
        }
        double ratio = availableStrength / baseStrength;
        double coefficient = Math.max(coefficients.minimum(), Math.min(coefficients.maximum(), ratio));
        return new Plan(coefficient, loads.build());
    }

    private ExertTissue load(Anatomy.TissueDefinition muscle) {
        // 每块组织以自身基础力量与功率按配置比例承担同一段做功。
        var capacity = muscle.capacity();
        return ExertTissue.newBuilder()
                .setTissueId(muscle.id().value())
                .setForceNewtons(capacity.strengthNewtons() * strike.forceRatio())
                .setPowerWatts(capacity.burstWatts() * strike.powerRatio())
                .setDurationSeconds(strike.seconds())
                .build();
    }

    private Set<String> selectedParts(Arm mainArm, Grip grip) {
        // 双手合并两组；副手取主手的相反侧。
        if (grip == Grip.TWO_HANDED) {
            var both = new HashSet<>(parts.left());
            both.addAll(parts.right());
            return both;
        }
        Arm selected;
        if (grip == Grip.OFF_HAND) {
            selected = mainArm == Arm.LEFT ? Arm.RIGHT : Arm.LEFT;
        } else {
            selected = mainArm;
        }
        if (selected == Arm.LEFT) {
            return parts.left();
        }
        return parts.right();
    }
}
