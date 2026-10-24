package dev.kizuna.meridian.vanilla;

/** 攻击方附效的可信服务端入口；交换 attack_effect.proto 的 Protobuf 字节。 */
@FunctionalInterface
public interface AttackEffectProvider {
    String ENTRYPOINT = "meridian_attack_effect";

    byte[] exchange(byte[] attackEffectContext);
}
