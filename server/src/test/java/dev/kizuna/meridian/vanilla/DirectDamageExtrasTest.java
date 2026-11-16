package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.DamageIntegrationProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationPlan;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.ActionCatalog;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyDamageIntegration;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.MeridianOpeningCatalog;
import dev.kizuna.meridian.server.OrganRecipeCatalog;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 直接伤害另带的透劲份额和附带修正：原样进伤害命令，和扣血同一笔；装不下时由调用方只提交伤害。 */
public class DirectDamageExtrasTest {
    private static final String MUSCLE = "chest_muscle";
    private static final String NUMB = "test:numb";
    private static final double MAX_HP = 20;
    private static final double DOSE = 2;
    private static final double ORGAN_SHARE = 0.6;
    private static final double NUMB_PEAK = 0.3;
    private static final long NUMB_MILLIS = 6_000;

    @Test
    public void organShareAndModifiersTravelWithTheDamageInOneCommit() throws IOException {
        // 一次提交：部位扣血、修正加上、版本只进一格；伤害策略看得到透劲份额；修正的来源是本附属。
        var store = new InMemoryBodyStore(List.of(body(List.of())), List.of());
        var seen = new AtomicReference<DamageBody>();
        var bridge = new VanillaDamageBridge(List.of(host(store, seen)), preferences(), List.of(passThrough()));
        var hit = bridge.prepareDirect("subject", "test:inch_power", DamageKind.BLUNT, "chest").orElseThrow();
        var extras = new VanillaDamageBridge.DamageExtras(OptionalDouble.of(ORGAN_SHARE), List.of(numb(NUMB)));
        assertTrue(hit.fits(extras.modifiers()));
        var result = hit.commitIntegrated(DOSE, false /* bypassesDeathProtection */, extras);
        assertEquals(ORGAN_SHARE, seen.get().getOrganShare(), 0);
        assertEquals(result.before().getRevision() + 1, result.body().getRevision());
        assertEquals(MAX_HP - DOSE, result.body().getPartStates(1).getHp(), 0);
        assertEquals(1, result.body().getModifiersCount());
        var modifier = result.body().getModifiers(0);
        assertEquals("meridian_vanilla:damage", modifier.getSource());
        assertEquals(MUSCLE, modifier.getTissueId());
        assertEquals(NUMB_PEAK, modifier.getStrengthPeak(), 0);
    }

    @Test
    public void plainDirectDamageCarriesNothingExtra() throws IOException {
        // 不带附带的直接伤害和原来一样：策略看不到份额，身体上不多修正。
        var store = new InMemoryBodyStore(List.of(body(List.of())), List.of());
        var seen = new AtomicReference<DamageBody>();
        var bridge = new VanillaDamageBridge(List.of(host(store, seen)), preferences(), List.of(passThrough()));
        var hit = bridge.prepareDirect("subject", "test:poke", DamageKind.BLUNT, "chest").orElseThrow();
        var result = hit.commitIntegrated(DOSE, false /* bypassesDeathProtection */);
        assertFalse(seen.get().hasOrganShare());
        assertEquals(0, result.body().getModifiersCount());
    }

    @Test
    public void fullBodyOnlyFitsReplacements() throws IOException {
        // 身体已经带满 32 条修正：新 ID 装不下，和已有的同 ID（换掉旧的）装得下。
        var existing = new ArrayList<BodyMessages.CapabilityModifier>();
        for (int index = 0; index < CapabilityModifier.MAX_PER_BODY; index++) {
            existing.add(numb("test:old_" + index).toBuilder().setSource("meridian_vanilla:damage").build());
        }
        var store = new InMemoryBodyStore(List.of(body(existing)), List.of());
        var bridge = new VanillaDamageBridge(List.of(host(store, new AtomicReference<>())), preferences(),
                List.of(passThrough()));
        var hit = bridge.prepareDirect("subject", "test:inch_power", DamageKind.BLUNT, "chest").orElseThrow();
        assertFalse(hit.fits(List.of(numb(NUMB))));
        assertTrue(hit.fits(List.of(numb("test:old_0"))));
        assertThrows(IllegalArgumentException.class,
                () -> new VanillaDamageBridge.DamageExtras(OptionalDouble.of(1.5), List.of()));
    }

    private static BodyMessages.CapabilityModifier numb(String id) {
        // 麻：胸肌的力量和功率乘 0.3，持续 6 秒；来源和开始时刻不填。
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId(id)
                .setTissueId(MUSCLE)
                .addCurve(BodyMessages.CurvePoint.newBuilder().setOffsetMillis(0).setValue(1))
                .addCurve(BodyMessages.CurvePoint.newBuilder().setOffsetMillis(NUMB_MILLIS).setValue(1))
                .setStrengthPeak(NUMB_PEAK)
                .setBurstPeak(NUMB_PEAK)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .build();
    }

    private static BodySnapshot body(List<BodyMessages.CapabilityModifier> modifiers) {
        // 头和胸两个部位，胸上一块肌肉；可以预先带上若干修正。
        var head = new Anatomy.PartDefinition(new Anatomy.PartId("head"), "head", MAX_HP);
        var chest = new Anatomy.PartDefinition(new Anatomy.PartId("chest"), "chest", MAX_HP);
        var muscle = new Anatomy.TissueDefinition(new Anatomy.TissueId(MUSCLE), "肌肉", new DefinitionId("test:muscle"),
                chest.id(), MAX_HP, MAX_HP, List.of(), new Anatomy.TissueCapacity(100, 100, 1_000), 0,
                List.of(new DefinitionId("human:arm_muscle")));
        var structure = new BodyStructure(List.of(head, chest), List.of(muscle), List.of(), List.of(), List.of());
        var plain = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("body"),
                new BodyTemplate.Reference(new DefinitionId("human:adult"), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(head.id(), MAX_HP), new AnatomyState.Part(chest.id(), MAX_HP)),
                List.of(new AnatomyState.Tissue(muscle.id(), MAX_HP, 0)), List.of(), List.of(), List.of(),
                new VitalityState(0, 1_000, 0, false /* dead */),
                new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0);
        return BodyProtobuf.fromMessage(BodyProtobuf.toMessage(plain).toBuilder().addAllModifiers(modifiers).build());
    }

    private static BodyHealthHost host(InMemoryBodyStore store, AtomicReference<DamageBody> seen) {
        // 正式处理器负责事务；伤害策略记下它看到的命令，把损失记在命中的部位上。
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, current) -> true,
                (caller, current) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(current.settledAt()))
                        .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build(),
                store, (caller, receipt, previous, updated) -> { /* 断言直接读提交结果。 */ },
                new OrganRecipeCatalog(List.of()), (caller, current, time) -> Optional.empty(),
                new ActionCatalog(List.of()), new MeridianOpeningCatalog(List.of()), caller -> false, payload -> {
                    DamageBody damage = HealthProtobuf.decodeContext(payload).getDamage();
                    seen.set(damage);
                    var loss = HpLoss.newBuilder().setTarget(damage.getHit()).setHp(damage.getDamageHp());
                    return HealthProtobuf.encodeResolution(
                            DamageResolution.newBuilder().setLethal(false /* lethal */).addLosses(loss).build());
                });
        return payload -> {
            var request = HealthHostProtobuf.decodeRequest(payload);
            var response = HealthHostResponse.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId());
            if (request.hasEffect()) {
                var effect = MeridianProtocol.decodeResponse(
                        handler.handleHealthEffect("host", request.getEffect().toByteArray()));
                if (effect.hasFailure()) {
                    return HealthHostProtobuf.encodeResponse(response.setFailure(effect.getFailure()).build());
                }
                store.transact(new BodyId("body"), transaction -> {
                    response.setHealth(transaction.receipt("host", request.getRequestId()).orElseThrow().getHealth());
                    return null;
                });
            }
            return HealthHostProtobuf.encodeResponse(response.setBody(BodyProtobuf.toMessage(
                    store.find(new BodyId("body")).orElseThrow())).build());
        };
    }

    private static BodyDamageIntegration passThrough() {
        // 没有黄心和图腾的效果端点：伤害原样通过。
        return new BodyDamageIntegration() {
            public byte[] prepare(byte[] payload) {
                var context = DamageIntegrationProtobuf.decodeContext(payload);
                return DamageIntegrationProtobuf.encodePlan(DamageIntegrationPlan.newBuilder()
                        .setHandled(true /* handled */)
                        .setRemainingDamageHp(context.getDamage().getDamageHp()).build());
            }

            public void committed(byte[] payload) {
                DamageIntegrationProtobuf.decodeCommit(payload);
            }
        };
    }

    private static DamagePreferences preferences() throws IOException {
        // 用发布的内置配置，不另造一套默认值。
        try (var input = DamagePreferences.class.getResourceAsStream("/meridian-vanilla-damage.toml")) {
            return DamagePreferences.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
