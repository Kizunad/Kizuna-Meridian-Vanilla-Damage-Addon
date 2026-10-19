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
    private final double hpPerVanillaHp;
    private final Map<String, Map<String, List<String>>> anatomies;
    private final Map<String, Map<String, Double>> attackers;
    private final ArmorRules armor;

    private DamagePreferences(double hpPerVanillaHp, Map<String, Map<String, List<String>>> anatomies,
                              Map<String, Map<String, Double>> attackers, ArmorRules armor) {
        // 配置在启动时固定，实际选点仍按每次伤害时的身体模块与 HP 计算。
        this.hpPerVanillaHp = hpPerVanillaHp;
        this.anatomies = Map.copyOf(anatomies);
        this.attackers = Map.copyOf(attackers);
        this.armor = armor;
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
        if (!Double.isFinite(scale) || scale <= 0 || scale > 1000) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.SCALE);
        }

        // 身体模板声明分组；同一部位只能属于一个组，改造新增的部位归 other。
        var anatomies = new HashMap<String, Map<String, List<String>>>();
        Object anatomyValue = config.get("anatomies");
        if (anatomyValue != null) {
            if (!(anatomyValue instanceof TomlTable anatomyTable)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ANATOMIES);
            }
            for (String templateId : anatomyTable.keySet()) {
                if (!(anatomyTable.get(List.of(templateId)) instanceof TomlTable groups)) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.PART_GROUPS + ": " + templateId);
                }
                var partGroups = new LinkedHashMap<String, List<String>>();
                var usedParts = new HashSet<String>();
                for (String group : groups.keySet()) {
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
                anatomies.put(templateId, Map.copyOf(partGroups));
            }
        }

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
                        throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.WEIGHT + ": " + entityId + "/" + group);
                    }
                    double weight = ((Number) number).doubleValue();
                    if (!Double.isFinite(weight) || weight < 0 || weight > 1000) {
                        throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.WEIGHT + ": " + entityId + "/" + group);
                    }
                    weights.put(group, weight);
                }
                attackers.put(entityId, Map.copyOf(weights));
            }
        }
        // 已有配置缺少 armor 时从内置资源读取此新增节，不覆盖用户的怪物偏好。
        Object armorValue = config.get("armor");
        if (armorValue == null) {
            try (InputStream input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
                if (input == null) {
                    throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.DEFAULT_RESOURCE);
                }
                armorValue = Toml.parse(input).getTable("armor");
            } catch (IOException failure) {
                throw VanillaDamageErrors.configReadFailed(failure);
            }
        }
        if (!(armorValue instanceof TomlTable armorTable)) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ARMOR_COVERAGE);
        }
        return new DamagePreferences(scale, anatomies, attackers, ArmorRules.parse(armorTable));
    }

    public ArmorRules armor() {
        // 纯规则与宿主装备读取分离，整体回归可直接验证配置后的公式。
        return armor;
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
        // 只选仍有 HP 的实际部位，移除的组织和新版模板不会替换已有身体的构型。
        List<String> candidates = body.getPartStatesList().stream().filter(part -> part.getHp() > 0)
                .map(part -> part.getId()).toList();
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

        // 未配置生物、未配置攻击者或偏好组全部缺失时，退回实际存活部位等概率。
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight == 0) {
            candidates.forEach(part -> weights.put(part, 1.0));
        }
        return weights;
    }

    public Optional<HealthTarget> select(BodySnapshot body, String attackerId, double roll) {
        // 随机数由服务端生成并注入，允许整体回归验证区间而不依赖统计碰运气。
        if (!Double.isFinite(roll) || roll < 0 || roll >= 1) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.ROLL);
        }
        Map<String, Double> weights = weights(body, attackerId);
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
