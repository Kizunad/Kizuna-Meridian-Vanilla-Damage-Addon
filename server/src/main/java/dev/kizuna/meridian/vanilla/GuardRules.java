package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 格挡部位、覆盖和水平扇面的纯规则；守式参数与伤害标签由宿主提供。 */
public final class GuardRules {
    private static final double FULL_CIRCLE_DEGREES = 360;
    private static final double HALF = 0.5;
    private static final double DIRECTION_EPSILON = 1e-12;
    private static final double ANGLE_EPSILON = 1e-8;
    private final Map<String, Profile> profiles;
    private final Weapon weapon;

    private GuardRules(Map<String, Profile> profiles, Weapon weapon) {
        this.profiles = Map.copyOf(profiles);
        this.weapon = weapon;
    }

    static GuardRules parse(TomlTable table, Map<String, Map<String, List<String>>> anatomies, Weapon weapon) {
        var profiles = new HashMap<String, Profile>();
        for (String templateId : table.keySet()) {
            if (!(table.get(List.of(templateId)) instanceof TomlTable profile)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.GUARD);
            }
            Map<String, List<String>> anatomy = anatomies.getOrDefault(templateId, Map.of());
            List<String> parts = strings(profile, "parts");
            List<String> covers = strings(profile, "covers");
            Set<String> knownParts = new HashSet<>();
            anatomy.values().forEach(knownParts::addAll);
            if (!knownParts.containsAll(parts) || !anatomy.keySet().containsAll(covers)
                    || covers.stream().anyMatch(group -> anatomy.get(group).isEmpty())) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.GUARD + ": " + templateId);
            }
            profiles.put(templateId, new Profile(parts, Set.copyOf(covers)));
        }
        return new GuardRules(profiles, weapon);
    }

    public Weapon weapon() {
        return weapon;
    }

    public record Weapon(double forearmShare, double durabilityPerDamage) {
        static Weapon parse(TomlTable table) {
            // 两项须同时声明；震劲可为零，耐久代价必须为正。
            if (!(table.get("forearm_share") instanceof Number share)
                    || !(table.get("durability_per_damage") instanceof Number rate)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.GUARD_WEAPON);
            }
            double arm = share.doubleValue();
            double wear = rate.doubleValue();
            if (!Double.isFinite(arm) || arm < 0 || arm > 1 || !Double.isFinite(wear) || wear <= 0) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.GUARD_WEAPON);
            }
            return new Weapon(arm, wear);
        }

        public int durabilityCost(double guardedDamage) {
            // 按兵器承担的那一份向上取整，每次挡中至少磨损一点。
            double cost = guardedDamage * (1 - forearmShare) * durabilityPerDamage;
            return Math.max(1, (int) Math.ceil(cost));
        }
    }

    private static List<String> strings(TomlTable table, String key) {
        if (!(table.get(key) instanceof TomlArray values) || values.size() == 0) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.GUARD + ": " + key);
        }
        var result = new ArrayList<String>();
        for (int index = 0; index < values.size(); index++) {
            if (!(values.get(index) instanceof String value) || value.isBlank() || result.contains(value)) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.GUARD + ": " + key);
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    public Optional<String> selectArm(BodySnapshot body) {
        Profile profile = profiles.get(body.getTemplate().getId());
        if (profile == null || body.getVitality().getDead()) {
            return Optional.empty();
        }
        Set<String> existing = new HashSet<>();
        body.getStructure().getPartsList().forEach(part -> existing.add(part.getId()));
        Map<String, Double> health = new HashMap<>();
        body.getPartStatesList().forEach(part -> health.put(part.getId(), part.getHp()));
        String selected = null;
        double highestHp = 0;
        for (String part : profile.parts()) {
            double hp = health.getOrDefault(part, 0.0);
            if (existing.contains(part) && hp > highestHp) {
                selected = part;
                highestHp = hp;
            }
        }
        return Optional.ofNullable(selected);
    }

    public boolean covers(String templateId, Optional<String> anatomyGroup) {
        Profile profile = profiles.get(templateId);
        return profile != null && anatomyGroup.filter(profile.covers()::contains).isPresent();
    }

    public static boolean withinAngle(Direction facing, Direction incoming, double angle) {
        // incoming 指向来处；垂直入射和无法确定的水平向量均不挡，边界包含浮点尾差。
        double facingX = facing.x();
        double facingZ = facing.z();
        double incomingX = incoming.x();
        double incomingZ = incoming.z();
        double facingLength = Math.hypot(facingX, facingZ);
        double incomingLength = Math.hypot(incomingX, incomingZ);
        if (!Double.isFinite(angle) || angle <= 0 || angle > FULL_CIRCLE_DEGREES
                || !Double.isFinite(facingLength) || !Double.isFinite(incomingLength)
                || facingLength <= DIRECTION_EPSILON || incomingLength <= DIRECTION_EPSILON) {
            return false;
        }
        double dot = facingX / facingLength * (incomingX / incomingLength)
                + facingZ / facingLength * (incomingZ / incomingLength);
        double boundary = Math.cos(Math.toRadians(angle * HALF));
        // 大世界坐标求差会损失低位精度，扇面边界比较单独容纳该误差。
        return dot + ANGLE_EPSILON >= boundary;
    }

    public record Direction(double x, double z) { }
    private record Profile(List<String> parts, Set<String> covers) { }
}
