package dev.kizuna.meridian.vanilla.test;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.*;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 同一核心存储服务健康与功法两个公共入口；伤害使用真实 Human 策略。 */
public final class GuardTestHost implements BodyHealthHost {
    static final String LEFT = "left_forearm";
    static final String RIGHT = "right_forearm";
    static final double ARM_HP = 12;
    static final double PART_HP = 100;
    private static final double MUSCLE_STRENGTH = 300;
    private static final double MUSCLE_POWER = 1000;
    private static final double MUSCLE_ENDURANCE = 1000;
    private static final double LIFESPAN_SECONDS = 1000;
    private static final InMemoryBodyStore STORE = new InMemoryBodyStore(List.of(), List.of());
    private static final Map<String, Long> CLOCKS = new HashMap<>();
    private static final Map<String, Integer> ACTIVITIES = new HashMap<>();
    private static int healthDepth;
    private static final BodyCommandHandler<String> COMMANDS = new BodyCommandHandler<>(
            (caller, command) -> Optional.of(caller), (caller, command, body) -> true,
            (caller, body) -> timePlan(body), STORE,
            (caller, receipt, before, after) -> { /* 断言直接读取核心存储。 */ },
            new OrganRecipeCatalog(List.of()), (caller, body, plan) -> Optional.empty(),
            new ActionCatalog(List.of()), new MeridianOpeningCatalog(List.of()), caller -> false, new HumanDamage());

    static void bind(LivingEntity entity, double leftHp, double rightHp) {
        bind(entity, "human:adult", leftHp, rightHp);
    }

    static void bind(LivingEntity entity, String templateId, double leftHp, double rightHp) {
        String id = entity.getStringUUID();
        List<Anatomy.PartDefinition> parts = List.of("head", "neck", "chest", "abdomen", "pelvis", "left_thigh")
                .stream().map(part -> part(part, PART_HP)).toList();
        var allParts = new java.util.ArrayList<>(parts);
        allParts.add(part(LEFT, ARM_HP));
        allParts.add(part(RIGHT, ARM_HP));
        List<Anatomy.TissueDefinition> tissues = List.of(muscle(LEFT), muscle(RIGHT));
        var structure = new BodyStructure(allParts, tissues, List.of(), List.of(), List.of());
        List<AnatomyState.Part> states = allParts.stream()
                .map(part -> state(part, leftHp, rightHp)).toList();
        List<AnatomyState.Tissue> tissueStates = tissues.stream()
                .map(tissue -> new AnatomyState.Tissue(tissue.id(), PART_HP, 0)).toList();
        var vitality = new VitalityState(0, LIFESPAN_SECONDS, 0, false /* dead */);
        var template = new BodyTemplate.Reference(new DefinitionId(templateId), 1);
        var time = new TimeCursor(new DefinitionId("test:world"), "epoch", 0);
        var body = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId(id), template, structure,
                Realm.WANDERING_QI, states, tissueStates, List.of(), List.of(), List.of(), vitality, time, 0);
        STORE.registerBody(body);
        CLOCKS.put(id, 0L);
        if (entity instanceof net.minecraft.world.entity.player.Player
                && entity.level().getEntity(entity.getId()) != entity) {
            entity.level().addFreshEntity(entity);
        }
    }

    private static Anatomy.PartDefinition part(String id, double hp) {
        return new Anatomy.PartDefinition(new Anatomy.PartId(id), id, hp);
    }

    private static AnatomyState.Part state(Anatomy.PartDefinition part, double leftHp, double rightHp) {
        double hp = part.maxHp();
        if (part.id().value().equals(LEFT)) {
            hp = leftHp;
        } else if (part.id().value().equals(RIGHT)) {
            hp = rightHp;
        }
        return new AnatomyState.Part(part.id(), hp);
    }

    private static Anatomy.TissueDefinition muscle(String part) {
        var capacity = new Anatomy.TissueCapacity(MUSCLE_STRENGTH, MUSCLE_POWER, MUSCLE_ENDURANCE);
        return new Anatomy.TissueDefinition(new Anatomy.TissueId(part + "_muscle"), part,
                new DefinitionId("human:muscle"), new Anatomy.PartId(part), PART_HP, PART_HP, List.of(),
                capacity, 0, List.of(new DefinitionId("human:arm_muscle")));
    }

    static BodySnapshot body(LivingEntity entity) {
        return STORE.find(new BodyId(entity.getStringUUID())).orElseThrow();
    }

    static double hp(LivingEntity entity, String part) {
        return body(entity).partStates().stream().filter(state -> state.id().value().equals(part))
                .findFirst().orElseThrow().hp();
    }

    static int activities(LivingEntity entity) {
        return ACTIVITIES.getOrDefault(entity.getStringUUID(), 0);
    }

    static void advance(LivingEntity entity, long millis) {
        elapse(entity, millis);
        settle(body(entity));
    }

    static void elapse(LivingEntity entity, long millis) {
        CLOCKS.put(entity.getStringUUID(), millis);
    }

    private static void settle(BodySnapshot body) {
        if (CLOCKS.get(body.id().value()) == body.settledAt().elapsedMillis()) {
            return;
        }
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setAdvanceTime(AdvanceTime.getDefaultInstance())
                .build();
        var request = envelope(UUID.randomUUID().toString(), command);
        var response = MeridianProtocol.decodeResponse(COMMANDS.handle("clock", request.toByteArray()));
        if (response.hasFailure()) {
            throw new IllegalStateException(response.getFailure().toString());
        }
    }

    private static TimeAdvancePlan timePlan(BodySnapshot body) {
        long millis = CLOCKS.get(body.id().value());
        var time = body.settledAt();
        var target = new TimeCursor(time.domain(), time.epoch(), millis);
        return TimeAdvancePlan.newBuilder()
                .setTarget(BodyProtobuf.toTime(target))
                .setMode(TimeAdvanceMode.ACTIVE)
                .setBiologicalSecondsPerSimulationSecond(1)
                .build();
    }

    private static RequestEnvelope envelope(String requestId, BodyCommand command) {
        return RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
    }

    @Override
    public byte[] exchange(byte[] payload) {
        var request = HealthHostProtobuf.decodeRequest(payload);
        var response = HealthHostResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        var id = new BodyId(request.getSubjectId());
        if (STORE.find(id).isEmpty()) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
        }
        if (request.hasEffect()) {
            healthDepth++;
            try {
                var result = MeridianProtocol.decodeResponse(COMMANDS.handleHealthEffect(
                        "health", request.getEffect().toByteArray()));
                if (result.hasFailure()) {
                    return HealthHostProtobuf.encodeResponse(response.setFailure(result.getFailure()).build());
                }
                var receipt = STORE.transact(id, transaction ->
                        transaction.receipt("health", request.getRequestId()).orElseThrow());
                response.setHealth(receipt.getHealth());
            } finally {
                healthDepth--;
            }
        } else if (request.hasExertion()) {
            var result = MeridianProtocol.decodeResponse(
                    COMMANDS.handle("health", request.getExertion().toByteArray()));
            if (result.hasFailure()) {
                return HealthHostProtobuf.encodeResponse(response.setFailure(result.getFailure()).build());
            }
        }
        return HealthHostProtobuf.encodeResponse(response.setBody(BodyProtobuf.toMessage(
                STORE.find(id).orElseThrow())).build());
    }

    public static final class Techniques implements TechniqueHostEntrypoint {
        @Override
        public byte[] exchange(byte[] payload) {
            var request = TechniqueProtocol.decodeHost(payload);
            var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
            var id = new BodyId(request.getEntityUuid());
            Optional<BodySnapshot> body = STORE.find(id);
            if (body.isEmpty()) {
                return TechniqueProtocol.encodeHostResponse(response.setUnbound(true /* unbound */).build());
            }
            switch (request.getOperationCase()) {
                case INSPECT -> inspect(response, body.get());
                case ACTIVITY -> activity(request, response, id);
                case EFFECT -> response.setEffectAccepted(true /* effectAccepted */);
                default -> throw new IllegalArgumentException("测试宿主不支持该操作");
            }
            return TechniqueProtocol.encodeHostResponse(response.build());
        }

        private static void inspect(TechniqueHostResponse.Builder response, BodySnapshot body) {
            settle(body);
            BodySnapshot current = STORE.find(body.id()).orElseThrow();
            HeldItems held = HeldItems.newBuilder()
                    .setMainHand(HeldItem.getDefaultInstance())
                    .setOffHand(HeldItem.getDefaultInstance())
                    .setMainHandSide(HandSide.RIGHT)
                    .build();
            response.setBody(BodyProtobuf.toMessage(current));
            response.setHeldItems(held);
            var inspection = new TechniqueHost.Inspection(current, held);
            response.setInspectFacts(inspection.facts());
        }

        private static void activity(TechniqueHostRequest request, TechniqueHostResponse.Builder response, BodyId id) {
            if (healthDepth != 0) {
                throw new IllegalStateException("不允许在伤害提交内重入功法活动");
            }
            ACTIVITIES.merge(id.value(), 1, Integer::sum);
            BodyCommand command = BodyCommand.newBuilder()
                    .setBodyId(id.value())
                    .setExpectedRevision(request.getExpectedRevision())
                    .setExecuteActivity(request.getActivity())
                    .build();
            var envelope = envelope(request.getRequestId(), command);
            var result = MeridianProtocol.decodeResponse(
                    COMMANDS.handleActivity(TechniqueHost.CALLER_ID, envelope.toByteArray()));
            if (result.hasFailure()) {
                response.setFailure(result.getFailure());
                return;
            }
            TechniqueActivityCommit commit = TechniqueActivityCommit.newBuilder()
                    .setRevision(result.getCommandCommitted().getRevision())
                    .setActivity(request.getActivity())
                    .build();
            response.setActivity(commit);
        }
    }
}
