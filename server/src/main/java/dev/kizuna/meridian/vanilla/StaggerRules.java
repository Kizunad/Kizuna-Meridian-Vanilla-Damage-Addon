package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import org.tomlj.TomlTable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** 护甲后剂量占命中模块 HP 上限的比例换算为僵直时长；类型系数先乘到比例上再比较门槛。 */
public record StaggerRules(Duration duration, double movementMultiplier, Map<DamageKind, Double> kindFactors) {
    private static final double MAX_FACTOR = 1000;
    private static final long MAX_MILLIS = 60_000;
    private static final double UNLISTED_FACTOR = 0;

    /** 比例低于门槛无僵直；门槛以上按比例线性放大并夹在上下限内。 */
    public record Duration(double threshold, double scaleMillis, long minimumMillis, long maximumMillis) { }

    public StaggerRules {
        kindFactors = Map.copyOf(kindFactors);
    }

    public static StaggerRules parse(TomlTable table) {
        // 配置在加载时完整校验；未列出的致伤类型不产生僵直。
        double threshold = positive(table, "threshold");
        double scale = positive(table, "scale_millis");
        long minimum = millis(table, "minimum_millis");
        long maximum = millis(table, "maximum_millis");
        double multiplier = positive(table, "move_factor");
        if (threshold > 1 || minimum > maximum || multiplier > 1) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER);
        }
        if (!(table.get("kind_factors") instanceof TomlTable factors)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": kind_factors");
        }
        var duration = new Duration(threshold, scale, minimum, maximum);
        return new StaggerRules(duration, multiplier, kindFactors(factors));
    }

    private static Map<DamageKind, Double> kindFactors(TomlTable factors) {
        // 键为小写致伤类型名，系数为非负有限数；0 与未列出等价。
        var result = new EnumMap<DamageKind, Double>(DamageKind.class);
        for (String key : factors.keySet()) {
            DamageKind kind = kind(key);
            if (!(factors.get(key) instanceof Number number)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": " + key);
            }
            double factor = number.doubleValue();
            if (!Double.isFinite(factor) || factor < 0 || factor > MAX_FACTOR) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": " + key);
            }
            result.put(kind, factor);
        }
        return result;
    }

    private static DamageKind kind(String key) {
        // 只接受协议中已定义的具体类型，拼写错误在启动时暴露。
        DamageKind kind;
        try {
            kind = DamageKind.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": " + key);
        }
        if (kind == DamageKind.DAMAGE_KIND_UNSPECIFIED || kind == DamageKind.UNRECOGNIZED) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": " + key);
        }
        return kind;
    }

    private static double positive(TomlTable table, String key) {
        // 门槛、倍率与时长比例都必须是有限正数。
        if (!(table.get(key) instanceof Number number)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": " + key);
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": " + key);
        }
        return value;
    }

    private static long millis(TomlTable table, String key) {
        // 时长上下限为整毫秒，且不超过功法库接受的僵直上限。
        if (!(table.get(key) instanceof Long value) || value <= 0 || value > MAX_MILLIS) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.STAGGER + ": " + key);
        }
        return value;
    }

    public long millis(BodySnapshot body, HealthTarget hit, DamageKind kind, double doseHp) {
        // 剂量与上限同为身体 HP；没有上限的模块或零系数类型不产生僵直。
        double factor = kindFactors.getOrDefault(kind, UNLISTED_FACTOR);
        double maximumHp = maximumHp(body, hit);
        if (factor == UNLISTED_FACTOR || maximumHp <= 0 || !Double.isFinite(doseHp) || doseHp <= 0) {
            return 0;
        }
        double ratio = doseHp / maximumHp * factor;
        if (ratio < duration.threshold()) {
            return 0;
        }
        long scaled = Math.round(ratio * duration.scaleMillis());
        return Math.max(duration.minimumMillis(), Math.min(duration.maximumMillis(), scaled));
    }

    private static double maximumHp(BodySnapshot body, HealthTarget hit) {
        // 命中目标可以是部位、组织或器官，上限都读身体自身的结构定义。
        var structure = body.getStructure();
        return switch (hit.getModuleCase()) {
            case PART_ID -> structure.getPartsList().stream()
                    .filter(part -> part.getId().equals(hit.getPartId()))
                    .mapToDouble(part -> part.getMaxHp())
                    .findFirst().orElse(0);
            case TISSUE_ID -> structure.getTissuesList().stream()
                    .filter(tissue -> tissue.getId().equals(hit.getTissueId()))
                    .mapToDouble(tissue -> tissue.getMaxHp())
                    .findFirst().orElse(0);
            case ORGAN_ID -> structure.getOrgansList().stream()
                    .filter(organ -> organ.getId().equals(hit.getOrganId()))
                    .mapToDouble(organ -> organ.getMaxHp())
                    .findFirst().orElse(0);
            case MODULE_NOT_SET -> 0;
        };
    }
}
