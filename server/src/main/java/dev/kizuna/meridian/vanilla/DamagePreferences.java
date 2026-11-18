package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 怪物偏好只决定选点，内部连带伤与死亡阈值交给生物内容包。 */
public final class DamagePreferences {
    private static final double MAX_CONFIG_VALUE = 1000;
    private final double hpPerVanillaHp;
    private final Map<String, Map<String, List<String>>> anatomies;
    private final Map<String, Map<String, List<String>>> aims;
    private final Map<String, Map<String, Double>> attackers;
    private final ArmorRules armor;
    private final boolean attackEffectsEnabled;
    private final double maxExtraRatio;
    private final MuscleRules muscle;
    private final StaggerRules stagger;
    private final GuardRules guard;

    private DamagePreferences(double hpPerVanillaHp, TargetGroups groups,
                              Map<String, Map<String, Double>> attackers, ArmorRules armor,
                              CombatSettings combat) {
        // 配置在启动时固定，实际选点仍按每次伤害时的身体模块与 HP 计算。
        this.hpPerVanillaHp = hpPerVanillaHp;
        this.anatomies = groups.anatomies();
        this.aims = groups.aims();
        this.attackers = Map.copyOf(attackers);
        this.armor = armor;
        AttackSettings attack = combat.attack();
        this.attackEffectsEnabled = attack.enabled();
        this.maxExtraRatio = attack.maxExtraRatio();
        this.muscle = combat.muscle();
        this.stagger = combat.stagger();
        this.guard = combat.guard();
    }

    public static DamagePreferences load(Path path) {
        // 首次启动导出可编辑默认值；以后读取已有配置，不在世界加载时重写玩家调整。
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                try (InputStream input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
                    if (input == null) {
                        throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.DEFAULT_RESOURCE);
                    }
                    Files.copy(input, path);
                }
            }
            return parse(Files.readString(path));
        } catch (IOException failure) {
            throw VanillaDamageErrors.configReadFailed(failure);
        }
    }

    public static DamagePreferences parse(String text) {
        // TOML 仅作本地配置；跨附属交换仍使用框架的 protoc 消息。
        var config = Toml.parse(text);
        if (config.hasErrors()) {
            throw VanillaDamageErrors.invalidConfig(config.errors().toString());
        }
        Object configuredScale = config.get("hp_per_vanilla_hp");
        if (!(configuredScale instanceof Number scaleNumber)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.SCALE);
        }
        double scale = scaleNumber.doubleValue();
        if (!Double.isFinite(scale) || scale <= 0 || scale > MAX_CONFIG_VALUE) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.SCALE);
        }
        var anatomies = parseAnatomies(config);
        TomlTable aimTable = configTable(config, "aims", VanillaDamageErrors.AIMS);
        var aims = parsePartTables(aimTable, PartGrouping.OVERLAPPING);
        var groups = new TargetGroups(anatomies, aims);
        var attackers = parseAttackers(config);
        ArmorRules armor = ArmorRules.parse(configTable(config, "armor", VanillaDamageErrors.ARMOR_COVERAGE));
        CombatSettings combat = parseCombatSettings(config, anatomies);
        return new DamagePreferences(scale, groups, attackers, armor, combat);
    }

    private static CombatSettings parseCombatSettings(TomlTable config,
                                                       Map<String, Map<String, List<String>>> anatomies) {
        AttackSettings attack = parseAttackSettings(config);
        MuscleRules muscle = MuscleRules.parse(configTable(config, "muscle", VanillaDamageErrors.MUSCLE));
        StaggerRules stagger = StaggerRules.parse(configTable(config, "stagger", VanillaDamageErrors.STAGGER));
        GuardRules guard = parseGuard(config, anatomies);
        return new CombatSettings(attack, muscle, stagger, guard);
    }

    private static Map<String, Map<String, List<String>>> parseAnatomies(TomlTable config) {
        // 身体模板声明分组；同一部位只能属于一个组，改造新增的部位归 other。
        Object anatomyValue = config.get("anatomies");
        if (anatomyValue == null) {
            return Map.of();
        }
        if (!(anatomyValue instanceof TomlTable anatomyTable)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ANATOMIES);
        }
        return parsePartTables(anatomyTable, PartGrouping.DISJOINT);
    }

    private static Map<String, Map<String, List<String>>> parsePartTables(TomlTable templates,
                                                                         PartGrouping grouping) {
        // 偏好分组互斥，瞄准分组可以重叠；两者共用部位数组的结构校验。
        var tables = new HashMap<String, Map<String, List<String>>>();
        for (String templateId : templates.keySet()) {
            if (!(templates.get(List.of(templateId)) instanceof TomlTable groups)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.PART_GROUPS + ": " + templateId);
            }
            tables.put(templateId, parsePartGroups(groups, grouping));
        }
        return Map.copyOf(tables);
    }

    private static Map<String, List<String>> parsePartGroups(TomlTable groups, PartGrouping grouping) {
        // 偏好跨组核对唯一性，瞄准只在组内核对；保存组内部位顺序以保持随机选点行为。
        var partGroups = new LinkedHashMap<String, List<String>>();
        var disjointParts = new HashSet<String>();
        for (String group : groups.keySet()) {
            HashSet<String> usedParts;
            if (grouping == PartGrouping.DISJOINT) {
                usedParts = disjointParts;
            } else {
                usedParts = new HashSet<>();
            }
            if (!(groups.get(List.of(group)) instanceof TomlArray entries)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.PART_ARRAY + ": " + group);
            }
            var partIds = new ArrayList<String>();
            for (int index = 0; index < entries.size(); index++) {
                if (!(entries.get(index) instanceof String partId)) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.PART_ARRAY + ": " + group);
                }
                if (partId.isBlank() || !usedParts.add(partId)) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.DUPLICATE_PART + ": " + partId);
                }
                partIds.add(partId);
            }
            partGroups.put(group, List.copyOf(partIds));
        }
        return Map.copyOf(partGroups);
    }

    private static Map<String, Map<String, Double>> parseAttackers(TomlTable config) {
        // 攻击者权重与身体构型分开校验，未知类型仍由选点规则处理。
        var attackers = new HashMap<String, Map<String, Double>>();
        Object attackerValue = config.get("attackers");
        if (attackerValue != null) {
            if (!(attackerValue instanceof TomlTable attackerTable)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ATTACKERS);
            }
            for (String entityId : attackerTable.keySet()) {
                if (!(attackerTable.get(List.of(entityId)) instanceof TomlTable profile)) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ATTACKER_PROFILE + ": " + entityId);
                }
                var weights = new LinkedHashMap<String, Double>();
                for (String group : profile.keySet()) {
                    Object number = profile.get(List.of(group));
                    if (!(number instanceof Number)) {
                        throw VanillaDamageErrors.invalidConfig(
                                VanillaDamageErrors.WEIGHT + ": " + entityId + "/" + group);
                    }
                    double weight = ((Number) number).doubleValue();
                    if (!Double.isFinite(weight) || weight < 0 || weight > MAX_CONFIG_VALUE) {
                        throw VanillaDamageErrors.invalidConfig(
                                VanillaDamageErrors.WEIGHT + ": " + entityId + "/" + group);
                    }
                    weights.put(group, weight);
                }
                attackers.put(entityId, Map.copyOf(weights));
            }
        }
        return Map.copyOf(attackers);
    }

    private static AttackSettings parseAttackSettings(TomlTable config) {
        // 显式配置必须完整合法，缺节时使用内置规则。
        TomlTable attackEffects = configTable(config, "attack_effects", VanillaDamageErrors.ATTACK_EFFECTS);
        Object enabledValue = attackEffects.get("enabled");
        if (!(enabledValue instanceof Boolean enabled)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ATTACK_EFFECTS_ENABLED);
        }
        Object ratioValue = attackEffects.get("max_extra_ratio");
        if (!(ratioValue instanceof Number ratioNumber)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ATTACK_EFFECTS_RATIO);
        }
        double maxExtraRatio = ratioNumber.doubleValue();
        if (!Double.isFinite(maxExtraRatio) || maxExtraRatio < 0 || maxExtraRatio > MAX_CONFIG_VALUE) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ATTACK_EFFECTS_RATIO);
        }
        return new AttackSettings(enabled, maxExtraRatio);
    }

    private record AttackSettings(boolean enabled, double maxExtraRatio) { }
    private record CombatSettings(AttackSettings attack, MuscleRules muscle, StaggerRules stagger, GuardRules guard) { }
    private record TargetGroups(Map<String, Map<String, List<String>>> anatomies,
                                Map<String, Map<String, List<String>>> aims) { }
    private enum PartGrouping { DISJOINT, OVERLAPPING }

    private static TomlTable configTable(TomlTable config, String key, String error) {
        // 旧配置缺少新增节时只读取默认值，不重写服主已有文件。
        Object value = config.get(key);
        if (value == null) {
            try (InputStream input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
                if (input == null) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.DEFAULT_RESOURCE);
                }
                return Toml.parse(input).getTable(key);
            } catch (IOException failure) {
                throw VanillaDamageErrors.configReadFailed(failure);
            }
        }
        if (!(value instanceof TomlTable table)) {
            throw VanillaDamageErrors.invalidConfig(error);
        }
        return table;
    }

    public MuscleRules muscle() {
        // 肌肉规则保持与 Minecraft 无关，供宿主和整体回归共用。
        return muscle;
    }

    public StaggerRules stagger() {
        // 僵直换算与 Minecraft 无关，伤害提交与 AI 预估共用同一规则。
        return stagger;
    }

    private static GuardRules parseGuard(TomlTable config, Map<String, Map<String, List<String>>> anatomies) {
        TomlTable guardTable = configTable(config, "guard", VanillaDamageErrors.GUARD);
        if (config.get("guard") != null) {
            return GuardRules.parse(guardTable, anatomies);
        }
        // 随包表的引用在随包解剖表中校验；旧文件的自定义分组仍在每击覆盖判断时生效。
        TomlTable defaultAnatomies = configTable(Toml.parse(""), "anatomies", VanillaDamageErrors.ANATOMIES);
        return GuardRules.parse(guardTable, parsePartTables(defaultAnatomies, PartGrouping.DISJOINT));
    }

    public GuardRules guard() {
        return guard;
    }

    public Optional<String> anatomyGroup(BodySnapshot body, String partId) {
        Map<String, List<String>> anatomy = anatomies.getOrDefault(body.getTemplate().getId(), Map.of());
        return anatomy.entrySet().stream()
                .filter(entry -> entry.getValue().contains(partId))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public ArmorRules armor() {
        // 纯规则与宿主装备读取分离，整体回归可直接验证配置后的公式。
        return armor;
    }

    public boolean attackEffectsEnabled() {
        // 附效是可选增益；关闭时不查询攻击者身体，也不调用任何提供方。
        return attackEffectsEnabled;
    }

    public double maxExtraRatio() {
        // 上限以原版护甲前剂量为基准，避免提供方通过大数放大一次命中。
        return maxExtraRatio;
    }

    public double damageHp(double vanillaHp) {
        // 护甲前先换算为身体 HP，局部减伤与原版扣血使用相同的单位比例。
        double damage = vanillaHp * hpPerVanillaHp;
        if (!Double.isFinite(damage) || damage <= 0) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.DAMAGE);
        }
        return damage;
    }

    public double vanillaHp(double bodyHp) {
        // 原版后续抗性、附魔和吸收仍以原版 HP 为单位，局部护甲后需换回该单位。
        double vanillaHp = bodyHp / hpPerVanillaHp;
        if (!Double.isFinite(vanillaHp) || vanillaHp < 0) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.DAMAGE);
        }
        return vanillaHp;
    }

    public Map<String, Double> weights(BodySnapshot body, String attackerId) {
        return weights(body, attackerId, Optional.empty());
    }

    public Map<String, Double> weights(BodySnapshot body, String attackerId, String aimGroup) {
        return weights(body, attackerId, Optional.ofNullable(aimGroup));
    }

    public Map<String, Double> weights(BodySnapshot body, String attackerId, Optional<String> aimGroup) {
        // 已毁部位仍可被命中；带 aim 时在实际存在的声明部位中均匀选择。
        List<String> candidates = candidates(body, aimGroup);
        if (aimGroup.isPresent()) {
            if (candidates.isEmpty()) {
                // 目标没有该组或该组已不存在时，回到普通攻击者偏好路径。
                return weights(body, attackerId, Optional.empty());
            }
            var aimedWeights = new LinkedHashMap<String, Double>();
            candidates.forEach(part -> aimedWeights.put(part, 1.0));
            return aimedWeights;
        }
        Map<String, List<String>> anatomy = anatomies.get(body.getTemplate().getId());
        Map<String, Double> profile = attackers.get(attackerId);
        var weights = new LinkedHashMap<String, Double>();
        if (anatomy != null && profile != null) {
            // 权重表示一类部位的总份额；左右各增一条腿不会把整个腿组概率翻倍。
            var groupByPart = new HashMap<String, String>();
            anatomy.forEach((group, parts) -> parts.forEach(part -> groupByPart.put(part, group)));
            var groupCounts = new HashMap<String, Integer>();
            for (String part : candidates) {
                groupCounts.merge(groupByPart.getOrDefault(part, "other"), 1, Integer::sum);
            }
            for (String part : candidates) {
                String group = groupByPart.getOrDefault(part, "other");
                double groupWeight = profile.getOrDefault(group, 0.0);
                weights.put(part, groupWeight / groupCounts.get(group));
            }
        }

        // 未配置生物、未配置攻击者或偏好组全部缺失时，实际存在的部位等概率。
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight == 0) {
            candidates.forEach(part -> weights.put(part, 1.0));
        }
        return weights;
    }

    private List<String> candidates(BodySnapshot body, Optional<String> aimGroup) {
        List<String> actualParts = body.getPartStatesList().stream()
                .map(part -> part.getId()).toList();
        if (aimGroup.isEmpty()) {
            return actualParts;
        }
        List<String> aimedParts = aimedParts(body.getTemplate().getId(), aimGroup.get());
        return aimedParts.stream().filter(actualParts::contains).toList();
    }

    private List<String> aimedParts(String templateId, String aimGroup) {
        // 独立瞄准表优先；未声明的组仍沿用原有偏好分组。
        List<String> explicitParts = aims.getOrDefault(templateId, Map.of()).get(aimGroup);
        if (explicitParts != null) {
            return explicitParts;
        }
        return anatomies.getOrDefault(templateId, Map.of()).getOrDefault(aimGroup, List.of());
    }

    public Optional<HealthTarget> select(BodySnapshot body, String attackerId, double roll) {
        return select(body, attackerId, roll, Optional.empty());
    }

    public Optional<HealthTarget> select(BodySnapshot body, String attackerId, double roll, String aimGroup) {
        return select(body, attackerId, roll, Optional.ofNullable(aimGroup));
    }

    public Optional<HealthTarget> select(BodySnapshot body, String attackerId, double roll,
                                         Optional<String> aimGroup) {
        // 随机数由服务端生成并注入，允许整体回归验证区间而不依赖统计碰运气。
        if (!Double.isFinite(roll) || roll < 0 || roll >= 1) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ROLL);
        }
        Map<String, Double> weights = weights(body, attackerId, aimGroup);
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double remaining = roll * totalWeight;
        String lastCandidate = null;
        for (var entry : weights.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            lastCandidate = entry.getKey();
            remaining -= entry.getValue();
            if (remaining < 0) {
                return Optional.of(HealthTarget.newBuilder().setPartId(entry.getKey()).build());
            }
        }
        // 最后一个正权重候选吸收浮点求和尾差，空身体仍保留无候选语义。
        if (lastCandidate == null) {
            return Optional.empty();
        }
        return Optional.of(HealthTarget.newBuilder().setPartId(lastCandidate).build());
    }
}
