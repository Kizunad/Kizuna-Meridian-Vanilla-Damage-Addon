package dev.kizuna.meridian.vanilla;

import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** 单件护甲的覆盖与减伤规则；装备读取由 Minecraft 宿主负责。 */
public final class ArmorRules {
    public enum Slot { HEAD, CHEST, LEGS, FEET }

    private final Map<String, Map<String, Slot>> coverage;
    private final double damageDivisorBase;
    private final double toughnessDivisor;
    private final double minimumArmorFraction;
    private final double effectiveArmorCap;
    private final double reductionDivisor;
    private final double maximumReductionRatio;
    private final List<DurabilityPoint> durabilityCurve;

    private ArmorRules(Map<String, Map<String, Slot>> coverage, double damageDivisorBase,
                       double toughnessDivisor, double minimumArmorFraction,
                       double effectiveArmorCap, double reductionDivisor,
                       double maximumReductionRatio, List<DurabilityPoint> durabilityCurve) {
        // 参数在启动时校验并固定，伤害期间只读取本次命中的单件装备。
        this.coverage = Map.copyOf(coverage);
        this.damageDivisorBase = damageDivisorBase;
        this.toughnessDivisor = toughnessDivisor;
        this.minimumArmorFraction = minimumArmorFraction;
        this.effectiveArmorCap = effectiveArmorCap;
        this.reductionDivisor = reductionDivisor;
        this.maximumReductionRatio = maximumReductionRatio;
        this.durabilityCurve = List.copyOf(durabilityCurve);
    }

    static ArmorRules parse(TomlTable table, TemplateDamageDefaults defaults) {
        // 公式以明确的系数配置，不执行服务器配置文件中的任意脚本。
        if (!(table.get("formula") instanceof TomlTable formula)
                || !"toughness".equals(formula.get("type"))) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_FORMULA);
        }
        double base = number(formula, "damage_divisor_base", 0, 1000, false);
        double toughness = number(formula, "toughness_divisor", 0, 1000, false);
        double minimum = number(formula, "minimum_armor_fraction", 0, 1, true);
        double cap = number(formula, "effective_armor_cap", 0, 1000, true);
        double divisor = number(formula, "reduction_divisor", 0, 1000, false);
        if (cap > divisor) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_FORMULA);
        }

        // 比例上限与有效护甲值上限分开配置，耐久只能削弱单件公式已提供的减免。
        double maximumReduction = ratio(table.get("maximum_reduction_ratio"), VanillaDamageErrors.ARMOR_REDUCTION_CAP);
        List<DurabilityPoint> durabilityCurve = parseDurabilityCurve(table);

        // 一个模板的同一部位只归一个槽位，避免重叠配置偷偷叠加多件护甲。
        if (!(table.get("coverage") instanceof TomlTable templates)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_COVERAGE);
        }
        var coverage = new HashMap<String, Map<String, Slot>>();
        var effective = defaults.tables(templates, "armor.coverage", VanillaDamageErrors.ARMOR_COVERAGE);
        for (var entry : effective.entrySet()) {
            String templateId = entry.getKey();
            TomlTable slots = entry.getValue();
            if (templateId.isBlank()) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_COVERAGE);
            }
            var coveredParts = new HashMap<String, Slot>();
            for (String slotName : slots.keySet()) {
                Slot slot = switch (slotName) {
                    case "head" -> Slot.HEAD;
                    case "chest" -> Slot.CHEST;
                    case "legs" -> Slot.LEGS;
                    case "feet" -> Slot.FEET;
                    default -> throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_SLOT + ": " + slotName);
                };
                if (!(slots.get(slotName) instanceof TomlArray parts)) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_COVERAGE);
                }
                for (int index = 0; index < parts.size(); index++) {
                    if (!(parts.get(index) instanceof String partId) || partId.isBlank()
                            || coveredParts.putIfAbsent(partId, slot) != null) {
                        throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_COVERAGE);
                    }
                }
            }
            coverage.put(templateId, Map.copyOf(coveredParts));
        }
        return new ArmorRules(coverage, base, toughness, minimum, cap, divisor, maximumReduction, durabilityCurve);
    }

    public Optional<Slot> coveredSlot(String templateId, String partId) {
        // 未登记的生物或改造新增部位没有默认覆盖，不借用人体的装备规则。
        return Optional.ofNullable(coverage.getOrDefault(templateId, Map.of()).get(partId));
    }

    public double remainingDamage(double damageHp, double armor, double toughness,
                                  OptionalDouble remainingDurabilityRatio) {
        // 输入已经换算为身体 HP；大伤害削弱有效护甲，单件韧性抵消这种削弱。
        if (!Double.isFinite(damageHp) || damageHp < 0 || !Double.isFinite(armor) || armor < 0
                || !Double.isFinite(toughness) || toughness < 0) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_INPUT);
        }
        double damageDivisor = damageDivisorBase + toughness / toughnessDivisor;
        double minimumArmor = Math.min(effectiveArmorCap, armor * minimumArmorFraction);
        double effectiveArmor = Math.max(minimumArmor, armor - damageHp / damageDivisor);
        effectiveArmor = Math.min(effectiveArmorCap, effectiveArmor);
        double baseReductionRatio = effectiveArmor / reductionDivisor;

        // 先由耐久曲线衰减基础减免，再限制本部位本次的最终护甲减免比例。
        double durabilityMultiplier = durabilityMultiplier(remainingDurabilityRatio);
        double finalReductionRatio = Math.min(maximumReductionRatio, baseReductionRatio * durabilityMultiplier);
        return damageHp * (1 - finalReductionRatio);
    }

    private static List<DurabilityPoint> parseDurabilityCurve(TomlTable table) {
        // 完整覆盖 0 到 1 且严格按耐久递增，避免缺段、重复横坐标或磨损后反而增益。
        if (!(table.get("durability_curve") instanceof TomlArray points) || points.size() < 2) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_DURABILITY_CURVE);
        }
        var curve = new ArrayList<DurabilityPoint>();
        for (int index = 0; index < points.size(); index++) {
            if (!(points.get(index) instanceof TomlArray point) || point.size() != 2) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_DURABILITY_CURVE);
            }
            double remainingRatio = ratio(point.get(0), VanillaDamageErrors.ARMOR_DURABILITY_CURVE);
            double multiplier = ratio(point.get(1), VanillaDamageErrors.ARMOR_DURABILITY_CURVE);
            if (!curve.isEmpty()) {
                DurabilityPoint previous = curve.get(curve.size() - 1);
                if (remainingRatio <= previous.remainingRatio() || multiplier < previous.multiplier()) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_DURABILITY_CURVE);
                }
            }
            curve.add(new DurabilityPoint(remainingRatio, multiplier));
        }
        if (curve.get(0).remainingRatio() != 0 || curve.get(curve.size() - 1).remainingRatio() != 1) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_DURABILITY_CURVE);
        }
        return curve;
    }

    private double durabilityMultiplier(OptionalDouble remainingDurabilityRatio) {
        // 无耐久物品明确使用系数 1，不借用曲线在满耐久处可调整的端点值。
        if (remainingDurabilityRatio.isEmpty()) {
            return 1;
        }
        double remainingRatio = ratio(remainingDurabilityRatio.getAsDouble(), VanillaDamageErrors.ARMOR_DURABILITY_INPUT);
        for (int index = 1; index < durabilityCurve.size(); index++) {
            DurabilityPoint upper = durabilityCurve.get(index);
            if (remainingRatio <= upper.remainingRatio()) {
                DurabilityPoint lower = durabilityCurve.get(index - 1);
                double position = (remainingRatio - lower.remainingRatio()) / (upper.remainingRatio() - lower.remainingRatio());
                return lower.multiplier() + position * (upper.multiplier() - lower.multiplier());
            }
        }
        return durabilityCurve.get(durabilityCurve.size() - 1).multiplier();
    }

    private static double ratio(Object configured, String diagnostic) {
        // 百分比和系数使用 0 到 1 的有限数，配置不能额外放大原有单件护甲强度。
        if (!(configured instanceof Number value) || !Double.isFinite(value.doubleValue())
                || value.doubleValue() < 0 || value.doubleValue() > 1) {
            throw VanillaDamageErrors.invalidConfig(diagnostic);
        }
        return value.doubleValue();
    }

    private record DurabilityPoint(double remainingRatio, double multiplier) { }

    private static double number(TomlTable table, String key, double minimum, double maximum, boolean includesMinimum) {
        // 加载时拒绝非数值、非有限数和危险分母，避免战斗中才产生 NaN 或除零。
        Object configured = table.get(key);
        if (!(configured instanceof Number value)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_FORMULA + ": " + key);
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number > maximum || number < minimum
                || (!includesMinimum && number == minimum)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_FORMULA + ": " + key);
        }
        return number;
    }
}
