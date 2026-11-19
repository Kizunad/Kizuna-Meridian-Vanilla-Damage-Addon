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
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 瞄准分组独立于攻击者偏好，旧配置缺节时使用随包值。 */
public final class DamageAimTest {
    private static final String TEMPLATE = "human:adult";
    private static final String ATTACKER = "minecraft:zombie";
    private static final String HEAD_NECK = "head_neck";
    private static final double HALF_ROLL = 0.5;
    private static final double PART_HP = 100;
    private static final double LIFESPAN_SECONDS = 1000;
    private static final double HEAD_WEIGHT = 15;
    private static final double TORSO_WEIGHT = 45;
    private static final int TORSO_PARTS = 4;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void headNeckAimSelectsOnlyHeadAndNeckWithEqualWeight() throws IOException {
        DamagePreferences preferences = DamagePreferences.parse(configuration());
        var body = body(TEMPLATE, "head", "neck", "chest", "abdomen", "pelvis");
        assertEquals(Map.of("head", 1.0, "neck", 1.0), preferences.weights(body, ATTACKER, HEAD_NECK));
        assertEquals("head", preferences.select(body, ATTACKER, 0, HEAD_NECK).orElseThrow().getPartId());
        assertEquals("head", preferences.select(body, ATTACKER, Math.nextDown(HALF_ROLL), HEAD_NECK)
                .orElseThrow().getPartId());
        assertEquals("neck", preferences.select(body, ATTACKER, HALF_ROLL, HEAD_NECK).orElseThrow().getPartId());
        assertEquals("neck", preferences.select(body, ATTACKER, Math.nextDown(1.0), HEAD_NECK)
                .orElseThrow().getPartId());
    }

    @Test
    public void neckKeepsItsShareOfTheAttackerTorsoPreference() throws IOException {
        DamagePreferences preferences = DamagePreferences.parse(configuration());
        var body = body(TEMPLATE, "head", "neck", "chest", "abdomen", "pelvis");
        double torsoPartWeight = TORSO_WEIGHT / TORSO_PARTS;
        assertEquals(Map.of("head", HEAD_WEIGHT, "neck", torsoPartWeight, "chest", torsoPartWeight,
                "abdomen", torsoPartWeight, "pelvis", torsoPartWeight), preferences.weights(body, ATTACKER));
        assertEquals(Map.of("neck", 1.0, "chest", 1.0, "abdomen", 1.0, "pelvis", 1.0),
                preferences.weights(body, ATTACKER, "torso"));
    }

    @Test
    public void oldConfigLoadsBundledAimsWithoutRewritingTheFile() throws IOException {
        var path = temporary.newFile("damage.toml").toPath();
        byte[] original = "hp_per_vanilla_hp = 1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);
        DamagePreferences preferences = DamagePreferences.load(path);
        var body = body(TEMPLATE, "head", "neck", "chest");
        assertEquals(Map.of("head", 1.0, "neck", 1.0), preferences.weights(body, ATTACKER, HEAD_NECK));
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    public void explicitAimsTakePriorityAndAllowOverlappingGroups() {
        DamagePreferences preferences = DamagePreferences.parse("""
                hp_per_vanilla_hp = 1
                [anatomies."human:adult"]
                head = ["head"]
                torso = ["neck", "chest"]
                [aims."human:adult"]
                head = ["neck"]
                head_neck = ["head", "neck"]
                """);
        var body = body(TEMPLATE, "head", "neck", "chest");
        assertEquals(Map.of("neck", 1.0), preferences.weights(body, ATTACKER, "head"));
        assertEquals(Map.of("head", 1.0, "neck", 1.0), preferences.weights(body, ATTACKER, HEAD_NECK));
        assertEquals(Map.of("neck", 1.0, "chest", 1.0), preferences.weights(body, ATTACKER, "torso"));
    }

    @Test
    public void missingAimPartsFallBackToOrdinarySelection() throws IOException {
        DamagePreferences preferences = DamagePreferences.parse(configuration());
        for (var body : List.of(body(TEMPLATE, "chest", "abdomen"), body("addon:other", "shell"))) {
            assertEquals(preferences.weights(body, ATTACKER), preferences.weights(body, ATTACKER, HEAD_NECK));
        }
        var neckOnly = body(TEMPLATE, "neck", "chest");
        assertEquals(Map.of("neck", 1.0), preferences.weights(neckOnly, ATTACKER, HEAD_NECK));
    }

    @Test
    public void malformedAimsAndOverlappingAnatomiesAreRejected() {
        for (String invalid : List.of("aims = 1", "[aims]\n\"human:adult\" = 1",
                "[aims.\"human:adult\"]\nhead_neck = 1",
                "[aims.\"human:adult\"]\nhead_neck = [\"head\", \"head\"]",
                "[aims.\"human:adult\"]\nhead_neck = [\"\"]")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DamagePreferences.parse("hp_per_vanilla_hp = 1\n" + invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> DamagePreferences.parse("""
                hp_per_vanilla_hp = 1
                [anatomies."human:adult"]
                head = ["head", "neck"]
                torso = ["neck", "chest"]
                """));
    }

    @Test
    public void registeredTemplateFillsEachMissingSectionInExistingFile() throws IOException {
        String id = "species_test:body";
        var path = temporary.newFile("registered.toml").toPath();
        String old = "hp_per_vanilla_hp = 1\n";
        Files.writeString(path, old);
        var defaults = new TemplateDamageDefaults();
        defaults.register(id, TEMPLATE);
        var body = body(id, "head", "neck", "left_forearm");
        var inherited = DamagePreferences.load(path, defaults);
        assertEquals(ArmorRules.Slot.HEAD, inherited.armor().coveredSlot(id, "head").orElseThrow());
        assertEquals("torso", inherited.anatomyGroup(body, "neck").orElseThrow());
        assertEquals("left_forearm", inherited.guard().selectArm(body).orElseThrow());
        assertEquals(Map.of("head", 1.0, "neck", 1.0), inherited.weights(body, ATTACKER, HEAD_NECK));
        assertEquals(old, Files.readString(path));
        String overridden = old + """
                [aims."species_test:body"]
                head_neck = ["neck"]
                """;
        Files.writeString(path, overridden);
        var custom = DamagePreferences.load(path, defaults);
        assertEquals(Map.of("neck", 1.0), custom.weights(body, ATTACKER, HEAD_NECK));
        assertEquals("torso", custom.anatomyGroup(body, "neck").orElseThrow());
        assertEquals(overridden, Files.readString(path));
    }

    @Test
    public void playerOverridesArmorAnatomyAndGuardIndependentlyOfAim() throws IOException {
        String id = "species_test:shell";
        var path = temporary.newFile("custom.toml").toPath();
        Files.writeString(path, configuration() + """
                [armor.coverage."species_test:shell"]
                chest = ["head"]
                [anatomies."species_test:shell"]
                shell = ["neck", "head"]
                [guard."species_test:shell"]
                parts = ["head"]
                covers = ["shell"]
                """);
        var defaults = new TemplateDamageDefaults();
        defaults.register(id, TEMPLATE);
        var body = body(id, "neck", "head", "left_forearm");
        var rules = DamagePreferences.load(path, defaults);
        assertEquals(ArmorRules.Slot.CHEST, rules.armor().coveredSlot(id, "head").orElseThrow());
        assertEquals("shell", rules.anatomyGroup(body, "neck").orElseThrow());
        assertEquals("head", rules.guard().selectArm(body).orElseThrow());
        assertEquals(Map.of("head", 1.0, "neck", 1.0), rules.weights(body, ATTACKER, HEAD_NECK));
    }

    @Test
    public void unregisteredTemplateRetainsUniformSelectionAndNoGuardOrArmor() throws IOException {
        String id = "species_test:unregistered";
        var body = body(id, "left_forearm", "head");
        var preferences = DamagePreferences.parse(configuration());
        assertEquals(Map.of("left_forearm", 1.0, "head", 1.0), preferences.weights(body, ATTACKER, HEAD_NECK));
        assertEquals(Optional.empty(), preferences.anatomyGroup(body, "head"));
        assertEquals(Optional.empty(), preferences.armor().coveredSlot(id, "head"));
        assertEquals(Optional.empty(), preferences.guard().selectArm(body));
    }

    private static BodyMessages.BodySnapshot body(String templateId, String... partIds) {
        var parts = List.of(partIds).stream()
                .map(id -> new Anatomy.PartDefinition(new Anatomy.PartId(id), id, PART_HP))
                .toList();
        var structure = new BodyStructure(parts, List.of(), List.of(), List.of(), List.of());
        var states = parts.stream().map(part -> new AnatomyState.Part(part.id(), PART_HP)).toList();
        var template = new BodyTemplate.Reference(new DefinitionId(templateId), 1);
        var vitality = new VitalityState(0, LIFESPAN_SECONDS, 0, false /* dead */);
        var time = new TimeCursor(new DefinitionId("test:world"), "epoch", 0);
        var snapshot = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("aim"), template,
                structure, Realm.WANDERING_QI, states, List.of(), List.of(), List.of(), List.of(), vitality, time, 0);
        return BodyProtobuf.toMessage(snapshot);
    }

    private static String configuration() throws IOException {
        try (var input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
