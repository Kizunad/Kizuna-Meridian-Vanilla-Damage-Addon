package dev.kizuna.meridian.vanilla;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.vanilla.protocol.pb.AttackEffectMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 攻击附效协议的有界编解码与服务端输入校验。 */
public final class AttackEffectProtobuf {
    private static final int MAX_STATUS_EFFECTS = 64;

    private AttackEffectProtobuf() {
        // 协议边界不持有附属状态，去重和上限策略由 VanillaDamageBridge 维护。
    }

    public static byte[] encodeContext(AttackEffectMessages.AttackEffectContext context) {
        validateContext(context);
        return write(context);
    }

    public static AttackEffectMessages.AttackEffectContext decodeContext(byte[] bytes) {
        var context = read(bytes, AttackEffectMessages.AttackEffectContext.parser());
        validateContext(context);
        return context;
    }

    public static byte[] encodePlan(AttackEffectMessages.AttackEffectPlan plan) {
        validatePlan(plan);
        return write(plan);
    }

    public static AttackEffectMessages.AttackEffectPlan decodePlan(byte[] bytes) {
        var plan = read(bytes, AttackEffectMessages.AttackEffectPlan.parser());
        validatePlan(plan);
        return plan;
    }

    private static void validateContext(AttackEffectMessages.AttackEffectContext context) {
        require(context != null && context.getProtocolVersion() == 1, VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
        requireText(context.getAttackerSubjectId(), VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
        requireText(context.getTargetSubjectId(), VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
        requireText(context.getSourceId(), VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
        require(context.hasAttackerBody(), VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
        BodyProtobuf.fromMessage(context.getAttackerBody());
        require(context.getKind() != DamageKind.DAMAGE_KIND_UNSPECIFIED
                && context.getKind() != DamageKind.UNRECOGNIZED,
                VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
        require(Double.isFinite(context.getRawDamageHp()) && context.getRawDamageHp() > 0,
                VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
        require(context.getServerTick() >= 0, VanillaDamageErrors.ATTACK_EFFECT_CONTEXT);
    }

    private static void validatePlan(AttackEffectMessages.AttackEffectPlan plan) {
        require(plan != null, VanillaDamageErrors.ATTACK_EFFECT_PLAN);
        require(Double.isFinite(plan.getExtraDamageHp()) && plan.getExtraDamageHp() >= 0,
                VanillaDamageErrors.ATTACK_EFFECT_PLAN);
        require(plan.getStatusEffectsCount() <= MAX_STATUS_EFFECTS, VanillaDamageErrors.ATTACK_EFFECT_PLAN);
        if (!plan.getEffectId().isEmpty()) {
            requireText(plan.getEffectId(), VanillaDamageErrors.ATTACK_EFFECT_PLAN);
        }
        for (var status : plan.getStatusEffectsList()) {
            requireText(status.getEffectId(), VanillaDamageErrors.ATTACK_EFFECT_STATUS);
            require(status.getDurationTicks() > 0 && status.getAmplifier() >= 0,
                    VanillaDamageErrors.ATTACK_EFFECT_STATUS);
            require(status.getAmplifier() <= 255, VanillaDamageErrors.ATTACK_EFFECT_STATUS);
            require(status.getUnknownFields().asMap().isEmpty(), VanillaDamageErrors.ATTACK_EFFECT_STATUS);
        }
        require(plan.getUnknownFields().asMap().isEmpty(), VanillaDamageErrors.ATTACK_EFFECT_PLAN);
    }

    private static void requireText(String value, String diagnostic) {
        require(value != null && !value.isBlank()
                        && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                        && value.getBytes(StandardCharsets.UTF_8).length <= MeridianProtocol.MAX_TEXT_BYTES,
                diagnostic);
    }

    private static void require(boolean condition, String diagnostic) {
        if (!condition) {
            throw new IllegalArgumentException(diagnostic);
        }
    }

    private static <T extends Message> T read(byte[] bytes, Parser<T> parser) {
        require(bytes != null && bytes.length > 0 && bytes.length <= MeridianProtocol.MAX_PAYLOAD_BYTES,
                VanillaDamageErrors.ATTACK_EFFECT_PLAN);
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        input.setSizeLimit(MeridianProtocol.MAX_PAYLOAD_BYTES);
        input.setRecursionLimit(MeridianProtocol.MAX_RECURSION_DEPTH);
        try {
            T message = parser.parseFrom(input);
            require(input.isAtEnd(), VanillaDamageErrors.ATTACK_EFFECT_PLAN);
            require(message.getUnknownFields().asMap().isEmpty(), VanillaDamageErrors.ATTACK_EFFECT_PLAN);
            return message;
        } catch (IOException failure) {
            throw new IllegalArgumentException(VanillaDamageErrors.ATTACK_EFFECT_PLAN, failure);
        }
    }

    private static byte[] write(Message message) {
        require(message.getSerializedSize() <= MeridianProtocol.MAX_PAYLOAD_BYTES,
                VanillaDamageErrors.ATTACK_EFFECT_PLAN);
        return message.toByteArray();
    }
}
