package dev.kizuna.meridian.vanilla;

import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;

/** 原版来源只映射本次致伤机制，不自行生成中毒或出血周期。 */
public final class VanillaDamageKinds {
    private VanillaDamageKinds() {
        // 映射不持有实体和世界，供 Fabric 与其他原版宿主复用。
    }

    public static DamageKind classify(String sourceId, String attackerId, boolean edgedWeapon) {
        // 先看具体来源，箭与火球不会因为射手手持武器而变成近战切割。
        return switch (sourceId) {
            case "minecraft:in_fire", "minecraft:on_fire", "minecraft:lava", "minecraft:hot_floor",
                    "minecraft:fireball", "minecraft:unattributed_fireball" -> DamageKind.HEAT;
            case "minecraft:freeze" -> DamageKind.COLD;
            case "minecraft:lightning_bolt" -> DamageKind.ELECTRIC;
            case "minecraft:arrow", "minecraft:trident", "minecraft:mob_projectile", "minecraft:sting",
                    "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:stalagmite",
                    "minecraft:falling_stalactite", "minecraft:thorns" -> DamageKind.PIERCING;
            case "minecraft:wither" -> DamageKind.TOXIC;
            case "minecraft:dragon_breath" -> DamageKind.CORROSIVE;
            case "minecraft:indirect_magic" -> switch (attackerId) {
                case "minecraft:evoker" -> DamageKind.PIERCING;
                case "minecraft:witch" -> DamageKind.TOXIC;
                default -> DamageKind.CUSTOM;
            };
            case "minecraft:mob_attack", "minecraft:mob_attack_no_aggro", "minecraft:player_attack" -> melee(attackerId, edgedWeapon);
            case "minecraft:fall", "minecraft:fly_into_wall", "minecraft:falling_block", "minecraft:falling_anvil",
                    "minecraft:explosion", "minecraft:player_explosion", "minecraft:cramming" -> DamageKind.BLUNT;
            default -> DamageKind.CUSTOM;
        };
    }

    private static DamageKind melee(String attackerId, boolean edgedWeapon) {
        // 利刃优先，蜘蛛与狼的咬击使用穿刺；普通推撞与徒手攻击按钝击。
        if (edgedWeapon) {
            return DamageKind.CUTTING;
        }
        return switch (attackerId) {
            case "minecraft:spider", "minecraft:cave_spider", "minecraft:wolf",
                    "minecraft:silverfish", "minecraft:endermite", "minecraft:phantom" -> DamageKind.PIERCING;
            default -> DamageKind.BLUNT;
        };
    }
}
