package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.vanilla.fabric.mixin.ItemDurabilityAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

/** 一击在局部护甲之前的剂量，以及身体结算成功后兑现的主手磨损。 */
record WeaponGuardHit(double armDamage, VanillaDamageFabric.GuardContact contact, Runnable wear) {
    private static final TagKey<Item> WEAPONS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("meridian_vanilla_damage", "guard_weapons"));
    private static final Runnable NO_WEAR = () -> { };

    static WeaponGuardHit prepare(LivingEntity target, TechniqueGuardDefense.Defense defense, double dose) {
        // 先判定是否真的改点；没挡住的一击不会查询兵器或消耗附魔随机数。
        double guardedDamage = dose * defense.multiplier();
        var guarded = defense.guarded();
        var unarmed = VanillaDamageFabric.GuardContact.unarmed(guarded);
        if (guarded.isEmpty()) {
            return new WeaponGuardHit(guardedDamage, unarmed, NO_WEAR);
        }
        var stack = target.getMainHandItem();
        if (!stack.is(WEAPONS)) {
            return new WeaponGuardHit(guardedDamage, unarmed, NO_WEAR);
        }
        var rules = VanillaDamageFabric.preferences().guard().weapon();
        var access = (ItemDurabilityAccess) (Object) stack;
        ServerPlayer player = null;
        if (target instanceof ServerPlayer owner) {
            player = owner;
        }
        int wear = access.meridian$wear(rules.durabilityCost(guardedDamage), (ServerLevel) target.level(), player);
        var armed = new VanillaDamageFabric.GuardContact(guarded, VanillaDamageFabric.GuardTool.WEAPON);
        double shock = guardedDamage * rules.forearmShare();
        if (wear <= 0) {
            return new WeaponGuardHit(shock, armed, NO_WEAR);
        }
        int current = stack.getDamageValue();
        int maximum = stack.getMaxDamage();
        if (wear >= maximum - current) {
            return new WeaponGuardHit(guardedDamage, unarmed, applyWear(access, maximum, player, target));
        }
        return new WeaponGuardHit(shock, armed, applyWear(access, current + wear, player, target));
    }

    void commitWear() {
        wear.run();
    }

    private static Runnable applyWear(ItemDurabilityAccess access, int damage, ServerPlayer player,
                                      LivingEntity target) {
        // 对判定时的同一件物品只落实一次原版磨损；碎裂回调广播主手的音画并记统计。
        return () -> access.meridian$applyWear(damage, player, item ->
                target.onEquippedItemBroken(item, EquipmentSlot.MAINHAND));
    }
}
