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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class GuardRulesTest {
    private static final String TEMPLATE = "human:adult";
    private static final String LEFT = "left_forearm";
    private static final String RIGHT = "right_forearm";
    private static final double ARM_HP = 12;
    private static final double LOW_HP = 3;
    private static final double LIFESPAN_SECONDS = 1000;
    private static final double GUARD_ANGLE = 120;
    private static final double HALF_ANGLE = 60;
    private static final double OUTSIDE_ANGLE = 60.01;
    private static final double REAR_ANGLE = 180;
    private static final double HEAD_NECK_ROLL = 0.5;
    private static final double WORLD_EDGE = 30_000_000;
    private static final double ATTACK_DISTANCE = 2;
    private static final double DEFAULT_SHOCK_SHARE = 0.1;
    private static final double SMALL_HIT = 1.25;
    private static final int CUSTOM_WEAR = 3;
    private static final GuardRules.Direction FORWARD = new GuardRules.Direction(0, 1);

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void frontAndBothEdgesBlockButOutsideRearAndUnknownDoNot() {
        for (double angle : List.of(0.0, HALF_ANGLE, -HALF_ANGLE)) {
            assertTrue(GuardRules.withinAngle(FORWARD, direction(angle), GUARD_ANGLE));
        }
        for (double angle : List.of(OUTSIDE_ANGLE, -OUTSIDE_ANGLE, REAR_ANGLE)) {
            assertFalse(GuardRules.withinAngle(FORWARD, direction(angle), GUARD_ANGLE));
        }
        assertFalse(GuardRules.withinAngle(FORWARD, new GuardRules.Direction(0, 0), GUARD_ANGLE));
        assertFalse(GuardRules.withinAngle(FORWARD, new GuardRules.Direction(Double.NaN, 1), GUARD_ANGLE));
        assertTrue(GuardRules.withinAngle(FORWARD, direction(REAR_ANGLE), REAR_ANGLE + REAR_ANGLE));
    }

    @Test
    public void inclusiveEdgesSurviveWorldCoordinateSubtraction() {
        for (double degrees : List.of(HALF_ANGLE, -HALF_ANGLE)) {
            GuardRules.Direction exact = direction(degrees);
            double x = WORLD_EDGE + exact.x() * ATTACK_DISTANCE - WORLD_EDGE;
            double z = WORLD_EDGE + exact.z() * ATTACK_DISTANCE - WORLD_EDGE;
            assertTrue(GuardRules.withinAngle(FORWARD, new GuardRules.Direction(x, z), GUARD_ANGLE));
        }
    }

    @Test
    public void strongestPresentArmWinsAndTiesFollowConfigurationOrder() throws IOException {
        GuardRules rules = DamagePreferences.parse(configuration()).guard();
        assertEquals(LEFT, rules.selectArm(body(ARM_HP, ARM_HP)).orElseThrow());
        assertEquals(RIGHT, rules.selectArm(body(LOW_HP, ARM_HP)).orElseThrow());
        assertEquals(RIGHT, rules.selectArm(body(0, LOW_HP)).orElseThrow());
        assertTrue(rules.selectArm(body(0, 0)).isEmpty());
        assertTrue(rules.selectArm(body(TEMPLATE, "head")).isEmpty());
        assertTrue(rules.selectArm(body("test:other", LEFT, RIGHT)).isEmpty());
        var absentRight = body(TEMPLATE, LEFT);
        assertEquals(LEFT, rules.selectArm(absentRight).orElseThrow());
    }

    @Test
    public void coverageUsesAnatomyAfterHeadNeckAimSelection() throws IOException {
        DamagePreferences preferences = DamagePreferences.parse(configuration());
        var body = body(TEMPLATE, "head", "neck", "chest", "abdomen", "pelvis", "left_thigh", LEFT, RIGHT);
        for (String part : List.of("head", "neck", "chest", "abdomen", "pelvis")) {
            assertTrue(preferences.guard().covers(TEMPLATE, preferences.anatomyGroup(body, part)));
        }
        for (String part : List.of("left_thigh", LEFT, RIGHT, "unknown")) {
            assertFalse(preferences.guard().covers(TEMPLATE, preferences.anatomyGroup(body, part)));
        }
        List<String> expectedParts = List.of("head", "neck");
        for (int index = 0; index < expectedParts.size(); index++) {
            double roll = index * HEAD_NECK_ROLL;
            String part = preferences.select(body, "minecraft:zombie", roll, "head_neck")
                    .orElseThrow().getPartId();
            assertEquals(expectedParts.get(index), part);
            assertTrue(preferences.guard().covers(TEMPLATE, preferences.anatomyGroup(body, part)));
        }
    }

    @Test
    public void oldConfigurationGetsDefaultsWithoutFileChanges() throws IOException {
        var path = temporary.newFile("damage.toml").toPath();
        String legacy = configuration().replace(defaultGuard(), "");
        byte[] original = legacy.getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);
        DamagePreferences preferences = DamagePreferences.load(path);
        assertEquals(LEFT, preferences.guard().selectArm(body(ARM_HP, ARM_HP)).orElseThrow());
        assertTrue(preferences.guard().covers(TEMPLATE, preferences.anatomyGroup(body(TEMPLATE, "neck"), "neck")));
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    public void missingWeaponTableLoadsDefaultsWithoutRewriting() throws IOException {
        var path = temporary.newFile("guard-before-weapons.toml").toPath();
        String legacy = configuration().replace(defaultWeapon(), "");
        assertFalse(legacy.contains("[guard_weapon]"));
        byte[] before = legacy.getBytes(StandardCharsets.UTF_8);
        Files.write(path, before);
        var weapon = DamagePreferences.load(path).guard().weapon();
        assertEquals(DEFAULT_SHOCK_SHARE, weapon.forearmShare(), 0);
        assertEquals(1, weapon.durabilityPerDamage(), 0);
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test
    public void configuredCostRoundsUpAndAllowsZeroShock() throws IOException {
        String config = configuration();
        String custom = "[guard_weapon]\nforearm_share = 0\ndurability_per_damage = 2\n";
        var weapon = DamagePreferences.parse(config.replace(defaultWeapon(), custom)).guard().weapon();
        assertEquals(0, weapon.forearmShare(), 0);
        assertEquals(CUSTOM_WEAR, weapon.durabilityCost(SMALL_HIT));
        assertEquals(1, weapon.durabilityCost(0));
        for (String invalid : List.of("forearm_share = -1\ndurability_per_damage = 1",
                "forearm_share = 1.1\ndurability_per_damage = 1", "forearm_share = 0\ndurability_per_damage = 0",
                "forearm_share = 0\ndurability_per_damage = nan", "forearm_share = 0")) {
            String replacement = "[guard_weapon]\n" + invalid + "\n";
            assertThrows(IllegalArgumentException.class,
                    () -> DamagePreferences.parse(config.replace(defaultWeapon(), replacement)));
        }
    }

    @Test
    public void explicitConfigurationWinsAndEmptyTableDisablesGuard() throws IOException {
        String reversed = defaultGuard().replace("[\"left_forearm\", \"right_forearm\"]",
                "[\"right_forearm\", \"left_forearm\"]");
        var preferences = DamagePreferences.parse(configuration().replace(defaultGuard(), reversed));
        assertEquals(RIGHT, preferences.guard().selectArm(body(ARM_HP, ARM_HP)).orElseThrow());
        var disabled = DamagePreferences.parse(configuration().replace(defaultGuard(), "[guard]\n"));
        assertTrue(disabled.guard().selectArm(body(ARM_HP, ARM_HP)).isEmpty());
    }

    @Test
    public void malformedEmptyDuplicateAndUnknownReferencesAreRejected() throws IOException {
        String config = configuration();
        for (String invalid : List.of("parts = []\ncovers = [\"head\"]",
                "parts = [\"left_forearm\", \"left_forearm\"]\ncovers = [\"head\"]",
                "parts = [\"missing\"]\ncovers = [\"head\"]",
                "parts = [\"\"]\ncovers = [\"head\"]",
                "parts = [\"left_forearm\"]\ncovers = []",
                "parts = [\"left_forearm\"]\ncovers = [\"head_neck\"]",
                "parts = [\"left_forearm\"]\ncovers = [\"head\", \"head\"]",
                "parts = 1\ncovers = [\"head\"]")) {
            String replacement = "[guard.\"human:adult\"]\n" + invalid + "\n";
            assertThrows(IllegalArgumentException.class,
                    () -> DamagePreferences.parse(config.replace(defaultGuard(), replacement)));
        }
    }

    private static GuardRules.Direction direction(double degrees) {
        double radians = Math.toRadians(degrees);
        return new GuardRules.Direction(Math.sin(radians), Math.cos(radians));
    }

    private static BodyMessages.BodySnapshot body(double leftHp, double rightHp) {
        var body = body(TEMPLATE, LEFT, RIGHT);
        var left = body.getPartStates(0).toBuilder().setHp(leftHp).build();
        var right = body.getPartStates(1).toBuilder().setHp(rightHp).build();
        return body.toBuilder()
                .setPartStates(0, left)
                .setPartStates(1, right)
                .build();
    }

    private static BodyMessages.BodySnapshot body(String templateId, String... partIds) {
        var parts = List.of(partIds).stream()
                .map(id -> new Anatomy.PartDefinition(new Anatomy.PartId(id), id, ARM_HP))
                .toList();
        var structure = new BodyStructure(parts, List.of(), List.of(), List.of(), List.of());
        var states = parts.stream().map(part -> new AnatomyState.Part(part.id(), ARM_HP)).toList();
        var template = new BodyTemplate.Reference(new DefinitionId(templateId), 1);
        var vitality = new VitalityState(0, LIFESPAN_SECONDS, 0, false /* dead */);
        var time = new TimeCursor(new DefinitionId("test:world"), "epoch", 0);
        var snapshot = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("guard"), template,
                structure, Realm.WANDERING_QI, states, List.of(), List.of(), List.of(), List.of(), vitality, time, 0);
        return BodyProtobuf.toMessage(snapshot);
    }

    private static String defaultGuard() {
        return """
                [guard."human:adult"]
                parts = ["left_forearm", "right_forearm"]
                covers = ["head", "torso"]
                """;
    }

    private static String defaultWeapon() {
        return """
                [guard_weapon]
                forearm_share = 0.1
                durability_per_damage = 1.0
                """;
    }

    private static String configuration() throws IOException {
        try (var input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
