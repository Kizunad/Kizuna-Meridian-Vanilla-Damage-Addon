package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.vanilla.MuscleRules;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Optional;

/** 数据包标签决定近战接入与出手侧；致伤分类冲突必须明确拒绝。 */
public final class VanillaDamageTypeTags {
    public static final TagKey<DamageType> MELEE = tag("melee");
    public static final TagKey<DamageType> OFF_HAND = tag("off_hand");
    public static final TagKey<DamageType> TWO_HANDED = tag("two_handed");
    private static final Map<TagKey<DamageType>, DamageKind> KINDS = Map.of(
            tag("kind/blunt"), DamageKind.BLUNT,
            tag("kind/cutting"), DamageKind.CUTTING,
            tag("kind/piercing"), DamageKind.PIERCING,
            tag("kind/heat"), DamageKind.HEAT,
            tag("kind/cold"), DamageKind.COLD,
            tag("kind/electric"), DamageKind.ELECTRIC,
            tag("kind/corrosive"), DamageKind.CORROSIVE,
            tag("kind/toxic"), DamageKind.TOXIC);

    private VanillaDamageTypeTags() {
        // 标签不缓存世界注册表，数据包重载后直接读取新成员。
    }

    private static TagKey<DamageType> tag(String path) {
        // 对外接入统一使用 meridian 命名空间。
        return TagKey.of(RegistryKeys.DAMAGE_TYPE, new Identifier("meridian", path));
    }

    public static MuscleRules.Grip grip(DamageSource source) {
        // 双手优先于副手，双手标签本身已明确包含两侧。
        if (source.isIn(TWO_HANDED)) {
            return MuscleRules.Grip.TWO_HANDED;
        }
        if (source.isIn(OFF_HAND)) {
            return MuscleRules.Grip.OFF_HAND;
        }
        return MuscleRules.Grip.MAIN_HAND;
    }

    public static Optional<DamageKind> kind(DamageSource source) {
        // 即使重载被其他监听器中断，冲突来源也不能静默采用任一标签。
        return kind(source.getTypeRegistryEntry());
    }

    private static Optional<DamageKind> kind(net.minecraft.registry.entry.RegistryEntry<DamageType> type) {
        // 同一来源最多有一种显式致伤类型，其余继续交给默认分类器。
        DamageKind selected = null;
        for (var entry : KINDS.entrySet()) {
            if (!type.isIn(entry.getKey())) {
                continue;
            }
            if (selected != null) {
                throw new IllegalStateException(VanillaDamageErrors.DAMAGE_KIND_TAGS + ": " + type.getKey());
            }
            selected = entry.getValue();
        }
        return Optional.ofNullable(selected);
    }

    public static void validate(Registry<DamageType> registry) {
        // 标签加载和重载完成后逐项校验，冲突会中止加载并报告来源 ID。
        registry.streamEntries().forEach(VanillaDamageTypeTags::kind);
    }
}
