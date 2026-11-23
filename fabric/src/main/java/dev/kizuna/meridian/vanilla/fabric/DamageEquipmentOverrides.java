package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric.DamageProvider;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric.LocalArmor;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric.WeaponDamageCap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** 命中时查询装备上的临时规则，装备状态及有效期由提供方持有。 */
public final class DamageEquipmentOverrides {
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private DamageEquipmentOverrides() {
    }

    public interface Provider {
        Optional<LocalArmor> armor(LivingEntity target, String part, Optional<EquipmentSlot> slot);

        Optional<WeaponDamageCap> weapon(DamageProvider source);
    }

    public static void register(Provider provider) {
        PROVIDERS.add(Objects.requireNonNull(provider));
    }

    static Optional<LocalArmor> armor(LivingEntity target, String part, Optional<EquipmentSlot> slot) {
        // 没有调用方显式覆盖时，按登记顺序取首个认领该部位的提供方。
        for (Provider provider : PROVIDERS) {
            var armor = provider.armor(target, part, slot);
            if (armor.isPresent()) {
                return armor;
            }
        }
        return Optional.empty();
    }

    static double cappedDamage(DamageSource source, double damage, Optional<WeaponDamageCap> explicit) {
        if (source.getEntity() == null || (explicit.isEmpty() && PROVIDERS.isEmpty())) {
            return damage;
        }
        DamageProvider attacker = VanillaDamageFabric.damageProvider(source);
        ItemStack weapon = attacker.weapon();
        double maximum = matchingLimit(weapon, explicit);
        for (Provider provider : PROVIDERS) {
            maximum = Math.min(maximum, matchingLimit(weapon, provider.weapon(attacker)));
        }
        return Math.min(damage, maximum);
    }

    private static double matchingLimit(ItemStack weapon, Optional<WeaponDamageCap> candidate) {
        if (candidate.isPresent()) {
            WeaponDamageCap cap = candidate.get();
            if (ItemStack.matches(weapon, cap.weapon())) {
                return cap.maximumDamage();
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
