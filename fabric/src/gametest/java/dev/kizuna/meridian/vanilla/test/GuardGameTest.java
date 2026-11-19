package dev.kizuna.meridian.vanilla.test;

import dev.kizuna.meridian.protocol.pb.HealthMessages.HealthResult;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.GuardState;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import static dev.kizuna.meridian.vanilla.test.DamageGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.vanilla.test.GuardTestHost.*;

/** 可控身体时钟、命中部位与来向；普通受伤和直接肢击共用真实功法及人体结算。 */
public final class GuardGameTest {
    private static final String GUARD_ID = "meridian_damage_test:guard";
    private static final String CUSTOM_GUARD_ID = "meridian_damage_test:custom_guard";
    private static final long WARMUP = 200;
    private static final long RECOVERY = 300;
    private static final float DOSE = 10;
    private static final double GUARDED_DOSE = 7;
    private static final double WEAPON_SHOCK = 0.7;
    private static final int WEAPON_WEAR = 7;
    private static final double LOW_HP = 3;
    private static final double SPILL_MULTIPLIER = 0.7;
    private static final double EPSILON = 1e-5;
    private static final double DISTANCE = 2;
    private static final double SPACING = 4;
    private static final double HALF_ANGLE = 60;
    private static final double OUTSIDE_ANGLE = 60.01;
    private static final double REAR_ANGLE = 180;
    private static final double ORGAN_SHARE = 0.6;
    private static final double HALF_ARMOR = 0.5;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int ORIGIN = 2;
    private static final int EFFECT_TICKS = 200;
    private static final double LOCAL_ABSORPTION = 0.5;
    private static final double ATTACK_FATIGUE = 20;
    private static final double CUSTOM_OUTSIDE_ANGLE = 30;
    private static final double LETHAL_DOSE = 1000;
    private static final long STAGGER_LIMIT = 1000;
    private static final int COMPLETE_RESISTANCE = 4;
    private static final int COMPLETE_ABSORPTION = 13;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void swordTakesWearAndForearmTakesShockOnBothPaths(GameTestHelper context) {
        for (HitPath path : HitPath.values()) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                var sword = new ItemStack(Items.IRON_SWORD);
                scene.target().setItemSlot(EquipmentSlot.MAINHAND, sword);
                var events = observe(scene);
                path.hit(scene);
                context.assertTrue(sword.getDamageValue() == WEAPON_WEAR, "铁剑应承担七点耐久");
                close(context, hp(scene.target(), LEFT), ARM_HP - WEAPON_SHOCK, "前臂应承担一成震劲");
                close(context, hp(scene.target(), "head"), PART_HP, "持剑挡下的原部位不掉血");
                scene.target().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                context.assertTrue(guardedInput(context, events).weaponGuarded(), "换手后仍通知判定当刻的兵器挡");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void emptyHandAndStickKeepFullArmDamage(GameTestHelper context) {
        for (var mainHand : List.of(ItemStack.EMPTY, new ItemStack(Items.STICK))) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                var offhand = new ItemStack(Items.IRON_SWORD);
                scene.target().setItemSlot(EquipmentSlot.MAINHAND, mainHand);
                scene.target().setItemSlot(EquipmentSlot.OFFHAND, offhand);
                var events = observe(scene);
                scene.normal();
                close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE, "空手和木棍全落前臂");
                context.assertTrue(mainHand.getDamageValue() == 0 && offhand.getDamageValue() == 0, "两手物品均无磨损");
                context.assertTrue(!guardedInput(context, events).weaponGuarded(), "前臂挡的兵器标记为假");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void exhaustedSwordBreaksAndEntireHitFallsOnArm(GameTestHelper context) {
        for (int remaining : List.of(1, WEAPON_WEAR)) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                var sword = new ItemStack(Items.IRON_SWORD);
                sword.setDamageValue(sword.getMaxDamage() - remaining);
                scene.target().setItemSlot(EquipmentSlot.MAINHAND, sword);
                var events = observe(scene);
                scene.normal();
                context.assertTrue(scene.target().getMainHandItem().isEmpty(), "耗尽的剑应在这一击碎掉");
                close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE, "碎剑这一击全落前臂");
                context.assertTrue(!guardedInput(context, events).weaponGuarded(), "碎剑时报告前臂承接");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unbreakableSwordGuardsWithoutWear(GameTestHelper context) {
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            var sword = new ItemStack(Items.IRON_SWORD);
            sword.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
            scene.target().setItemSlot(EquipmentSlot.MAINHAND, sword);
            var events = observe(scene);
            scene.direct("head");
            close(context, hp(scene.target(), LEFT), ARM_HP - WEAPON_SHOCK, "不可损剑也只传一成震劲");
            context.assertTrue(sword.getDamageValue() == 0, "不可损剑无磨损");
            context.assertTrue(guardedInput(context, events).weaponGuarded(), "不可损剑报告兵器挡");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void mobGuardsWithItsOwnAxe(GameTestHelper context) {
        var defender = context.spawn(EntityTypes.ZOMBIFIED_PIGLIN, ORIGIN, ORIGIN, ORIGIN);
        defender.setNoAi(true /* noAi */);
        try (var scene = scene(context, defender, ARM_HP, ARM_HP)) {
            startGuard(context, scene, GUARD_ID);
            var axe = new ItemStack(Items.IRON_AXE);
            var attackerSword = new ItemStack(Items.IRON_SWORD);
            defender.setItemSlot(EquipmentSlot.MAINHAND, axe);
            scene.attacker().setItemSlot(EquipmentSlot.MAINHAND, attackerSword);
            var events = observe(scene);
            scene.direct("head");
            close(context, hp(defender, LEFT), ARM_HP - WEAPON_SHOCK, "生物持斧只吃震劲");
            context.assertTrue(axe.getDamageValue() == WEAPON_WEAR, "磨损守方主手斧");
            context.assertTrue(attackerSword.getDamageValue() == 0, "不磨损攻击方兵器");
            context.assertTrue(guardedInput(context, events).weaponGuarded(), "生物持斧也报告兵器挡");
        }
        context.succeed();
    }

    private static VanillaDamageFabric.DamageInput guardedInput(GameTestHelper context,
                                                               List<VanillaDamageFabric.DamageEvent> events) {
        context.assertTrue(events.size() == 1, "挡住的一击应只通知一次");
        var input = events.getFirst().input();
        context.assertTrue(input.guarded().isPresent(), "应保留守的身份");
        return input;
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardNotificationFreezesIdentityOnBothPaths(GameTestHelper context) {
        // 同次命中只通知一次，身份取判定时的守，原前臂命中不能冒充格挡。
        for (HitPath path : HitPath.values()) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                GuardState guard = TechniqueFabric.service().activeGuard(scene.target().getUUID()).orElseThrow();
                var events = observe(scene);
                path.hit(scene);
                context.assertTrue(events.size() == 1, "成功格挡恰好通知一次");
                context.assertTrue(events.getFirst().input().guarded().orElseThrow().equals(guard), "冻结 body/cast 身份");
            }
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                var events = observe(scene);
                scene.moveAttacker(REAR_ANGLE);
                path.hit(scene);
                context.assertTrue(events.size() == 1 && events.getFirst().input().guarded().isEmpty(),
                        "背后命中不报告格挡");
            }
        }
        assertUnguardedNotification(context, LEFT, VanillaDamageFabric.DirectExtras.NONE);
        var extras = new VanillaDamageFabric.DirectExtras(OptionalDouble.of(ORGAN_SHARE), HALF_ARMOR, List.of());
        assertUnguardedNotification(context, "chest", extras);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lastArmNotificationPrecedesRecoveryOnBothPaths(GameTestHelper context) {
        // 最后一臂受击反馈先交付，随后收势停止反馈，不能用旧事实重新起架。
        for (HitPath path : HitPath.values()) {
            try (var scene = guarding(context, LOW_HP, 0)) {
                var order = new ArrayList<String>();
                var events = observe(scene);
                VanillaDamageFabric.registerDamageObserver(event -> {
                    if (event.target() == scene.target()) {
                        order.add("hit");
                    }
                });
                TechniqueFabric.service().addCastStateListener(event -> {
                    if (event.entityUuid().equals(scene.target().getUUID()) && event.phase() == CastPhase.RECOVERING) {
                        order.add("recover");
                    }
                });
                GuardState guard = TechniqueFabric.service().activeGuard(scene.target().getUUID()).orElseThrow();
                path.hit(scene);
                context.assertTrue(order.equals(List.of("hit", "recover")), "通知先于毁臂收势且各一次");
                context.assertTrue(events.size() == 1, "毁臂仍只通知一次");
                context.assertTrue(events.getFirst().input().guarded().orElseThrow().equals(guard), "毁臂保留原身份");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void zeroFinalDoseStillNotifiesGuardOnce(GameTestHelper context) {
        // 零倍率、原版抗性与局部黄心完全吸收都保留挡住事实，不增加健康提交。
        for (HitPath path : HitPath.values()) {
            try (var scene = guarding(context, ARM_HP, ARM_HP, CUSTOM_GUARD_ID)) {
                var sword = new ItemStack(Items.IRON_SWORD);
                scene.target().setItemSlot(EquipmentSlot.MAINHAND, sword);
                var events = observe(scene);
                long before = body(scene.target()).revision();
                DamageSource source = source(scene.target(), "head_neck_strike", scene.attacker());
                path.hit(scene, source);
                assertAbsorbedNotification(context, scene, events);
                context.assertTrue(sword.getDamageValue() == 1, "零倍率的兵器挡只扣一次最低耐久");
                context.assertTrue(events.getFirst().input().weaponGuarded(), "零倍率仍报告兵器挡");
                context.assertTrue(body(scene.target()).revision() == before, "零倍率不提交健康事务");
            }
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                var events = observe(scene);
                if (path == HitPath.NORMAL) {
                    scene.target().addEffect(new MobEffectInstance(MobEffects.RESISTANCE,
                            EFFECT_TICKS, COMPLETE_RESISTANCE));
                } else {
                    scene.target().addEffect(new MobEffectInstance(MobEffects.ABSORPTION,
                            EFFECT_TICKS, COMPLETE_ABSORPTION));
                }
                path.hit(scene);
                assertAbsorbedNotification(context, scene, events);
            }
        }
        context.succeed();
    }

    private static List<VanillaDamageFabric.DamageEvent> observe(Scene scene) {
        // 仅收当前夹具玩家的事件，其他测试玩家不会混入计数。
        var events = new ArrayList<VanillaDamageFabric.DamageEvent>();
        VanillaDamageFabric.registerDamageObserver(event -> {
            if (event.target() == scene.target()) {
                events.add(event);
            }
        });
        return events;
    }

    private static void assertUnguardedNotification(GameTestHelper context, String part,
                                                    VanillaDamageFabric.DirectExtras extras) {
        // 未改点的原前臂伤与透劲都正常通知，但成功格挡事实必须为空。
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            var events = observe(scene);
            damage(scene.target(), scene.source(), part, extras);
            context.assertTrue(events.size() == 1 && events.getFirst().input().guarded().isEmpty(),
                    "未挡住仍通知一次且无格挡身份：" + part);
        }
    }

    private static void assertAbsorbedNotification(GameTestHelper context, Scene scene,
                                                   List<VanillaDamageFabric.DamageEvent> events) {
        // 受击事实与 HP 损失分开；完全吸收仍需播放一次挡中反馈。
        context.assertTrue(events.size() == 1, "零最终剂量只通知一次");
        GuardState guard = TechniqueFabric.service().activeGuard(scene.target().getUUID()).orElseThrow();
        context.assertTrue(events.getFirst().input().guarded().orElseThrow().equals(guard), "零剂量仍带守身份");
        close(context, hp(scene.target(), LEFT), ARM_HP, "完全吸收不损伤前臂");
    }

    private enum HitPath {
        NORMAL, DIRECT;

        void hit(Scene scene) {
            // 两条入口共享同一固定原始瞄准和剂量。
            hit(scene, scene.source());
        }

        void hit(Scene scene, DamageSource source) {
            // 只有入口不同；守式、身体和来源由夹具保持一致。
            if (this == NORMAL) {
                scene.target().hurtServer((net.minecraft.server.level.ServerLevel) scene.target().level(), source, DOSE);
            } else {
                damage(scene.target(), source, "head", VanillaDamageFabric.DirectExtras.NONE);
            }
        }
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardQueriesSettleTimeBeforePreparingDamageRevision(GameTestHelper context) {
        try (var ordinary = guarding(context, ARM_HP, ARM_HP);
             var direct = guarding(context, ARM_HP, ARM_HP);
             var inactive = scene(context, ARM_HP, ARM_HP)) {
            elapse(ordinary.target(), WARMUP + 1);
            elapse(direct.target(), WARMUP + 1);
            elapse(inactive.target(), WARMUP);
            ordinary.normal();
            direct.direct("head");
            inactive.direct("head");
            close(context, hp(ordinary.target(), LEFT), ARM_HP - GUARDED_DOSE, "普通路使用结时后的 revision");
            close(context, hp(direct.target(), LEFT), ARM_HP - GUARDED_DOSE, "直接路使用结时后的 revision");
            close(context, hp(inactive.target(), "head"), PART_HP - DOSE, "没有守的宿主查询也可能结时");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void normalAndDirectHitsRedirectAndReduceOnce(GameTestHelper context) {
        try (var ordinary = guarding(context, ARM_HP, ARM_HP);
             var direct = guarding(context, LOW_HP, ARM_HP)) {
            var events = new ArrayList<VanillaDamageFabric.DamageEvent>();
            VanillaDamageFabric.registerDamageObserver(event -> {
                if (event.target() == ordinary.target() || event.target() == direct.target()) {
                    events.add(event);
                }
            });
            ordinary.normal();
            direct.direct("chest");
            close(context, hp(ordinary.target(), LEFT), ARM_HP - GUARDED_DOSE, "普通路落在同血量的首臂");
            close(context, hp(direct.target(), RIGHT), ARM_HP - GUARDED_DOSE, "直接路落在血量更多的臂");
            close(context, hp(ordinary.target(), "head"), PART_HP, "无溢伤时原头部不掉血");
            close(context, hp(direct.target(), "chest"), PART_HP, "无溢伤时原胸部不掉血");
            context.assertTrue(events.size() == 2, "每击只通知一次观察者");
            context.assertTrue(events.get(0).input().hit().orElseThrow().getPartId().equals(LEFT), "观察者看到新落点");
            close(context, events.get(1).input().damageAfterEffects(), GUARDED_DOSE, "观察者看到减伤后的剂量");
            context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(ordinary.target().getUUID()) == 0,
                    "挡住的普通伤害不写 HIT");
            context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(direct.target().getUUID()) == 0,
                    "挡住的直接伤害不写 HIT");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void directionsIncludeBothEdgesAndRejectRearAndUnknown(GameTestHelper context) {
        for (double angle : List.of(0.0, HALF_ANGLE, -HALF_ANGLE)) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                scene.moveAttacker(angle);
                scene.direct("head");
                close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE, "前方和扇面边界可挡");
            }
        }
        for (double angle : List.of(OUTSIDE_ANGLE, -OUTSIDE_ANGLE, REAR_ANGLE)) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                scene.moveAttacker(angle);
                scene.direct("head");
                assertUnblocked(context, scene, "head");
            }
        }
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            DamageSource source = source(scene.target(), "guard_head");
            damage(scene.target(), source, "head", VanillaDamageFabric.DirectExtras.NONE);
            assertUnblocked(context, scene, "head");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void arrowUsesVelocityEvenWhenShooterIsBehindDefender(GameTestHelper context) {
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            scene.moveAttacker(REAR_ANGLE);
            var arrow = EntityTypes.ARROW.create(context.getLevel(), EntitySpawnReason.COMMAND);
            arrow.setPos(scene.target().position().add(0, 0, -1));
            arrow.setDeltaMovement(0, 0, -1);
            DamageSource source = source(scene.target(), "guard_arrow", arrow, scene.attacker());
            scene.target().hurtServer(context.getLevel(), source, DOSE);
            close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE, "箭的反向速度决定前方来向");
        }
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            var arrow = EntityTypes.ARROW.create(context.getLevel(), EntitySpawnReason.COMMAND);
            arrow.setPos(scene.target().position().add(0, 0, 1));
            arrow.setDeltaMovement(0, 0, 1);
            damage(scene.target(), source(scene.target(), "guard_arrow", arrow, scene.attacker()), "head",
                    VanillaDamageFabric.DirectExtras.NONE);
            assertUnblocked(context, scene, "head");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void onlyHeadAndTorsoAnatomyGroupsAreCovered(GameTestHelper context) {
        for (String part : List.of("head", "neck", "chest", "abdomen", "pelvis")) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                scene.direct(part);
                close(context, hp(scene.target(), part), PART_HP, "保护部位不直接掉血：" + part);
                close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE, "头与躯干都转前臂");
            }
        }
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            scene.direct("left_thigh");
            assertUnblocked(context, scene, "left_thigh");
        }
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            scene.direct(RIGHT);
            close(context, hp(scene.target(), RIGHT), ARM_HP - DOSE, "手臂原命中照旧");
            close(context, hp(scene.target(), LEFT), ARM_HP, "不能把手臂命中改去另一条臂");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void excludedAndUnlistedTypesRemainUnblocked(GameTestHelper context) {
        for (String type : List.of("minecraft:on_fire", "minecraft:explosion", "minecraft:fall",
                "minecraft:magic", "minecraft:indirect_magic", "minecraft:sonic_boom",
                "minecraft:cactus", "meridian_damage_test:guard_fire_arrow")) {
            try (var scene = guarding(context, ARM_HP, ARM_HP)) {
                damage(scene.target(), typedSource(scene.target(), type, scene.attacker()), "head",
                        VanillaDamageFabric.DirectExtras.NONE);
                close(context, hp(scene.target(), "head"), PART_HP - DOSE, "排除类型或白名单外来源不挡：" + type);
                close(context, hp(scene.target(), LEFT), ARM_HP, "排除类型不能扣前臂：" + type);
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void armorFollowsRedirectedPartAndMissesKeepOriginalArmor(GameTestHelper context) {
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            scene.target().setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            scene.target().setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            scene.normal();
            close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE, "原部位盔甲不能减轻前臂伤害");
            context.assertTrue(scene.target().getItemBySlot(EquipmentSlot.HEAD).getDamageValue() > 0,
                    "普通受伤的原版护甲磨损仍发生");
        }
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            scene.target().setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            scene.moveAttacker(REAR_ANGLE);
            scene.direct("head");
            context.assertTrue(hp(scene.target(), "head") > PART_HP - DOSE, "未挡住时继续读取头盔减伤");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void absorptionIsConsumedAtForearmAndPreservedAtOriginalHead(GameTestHelper context) {
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            scene.target().addEffect(new MobEffectInstance(MobEffects.ABSORPTION, EFFECT_TICKS, 0));
            scene.direct("head");
            close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE + LOCAL_ABSORPTION,
                    "只消耗前臂的局部黄心");
            scene.moveAttacker(REAR_ANGLE);
            scene.direct("head");
            close(context, hp(scene.target(), "head"), PART_HP - DOSE + LOCAL_ABSORPTION,
                    "原头部的局部黄心仍可吸收后续命中");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void bothPathsSpillFromForearmAndEndGuardAfterCommit(GameTestHelper context) {
        try (var ordinary = guarding(context, LOW_HP, 0); var direct = guarding(context, LOW_HP, 0)) {
            int activities = activities(direct.target());
            ordinary.normal();
            var result = direct.direct("head");
            double expectedSpill = (GUARDED_DOSE - LOW_HP) * SPILL_MULTIPLIER;
            for (String part : List.of("head", "neck", "chest", "abdomen", "pelvis", "left_thigh", LEFT, RIGHT)) {
                close(context, hp(ordinary.target(), part), hp(direct.target(), part), "两条路溢伤分布一致：" + part);
            }
            close(context, hp(direct.target(), LEFT), 0, "最后可用前臂归零");
            close(context, totalLoss(result), LOW_HP + expectedSpill, "溢伤只乘一次人体来源系数");
            context.assertTrue(TechniqueFabric.service().activeGuard(direct.target().getUUID()).isEmpty(), "毁尽后撤防");
            context.assertTrue(activities(direct.target()) == activities + 1, "提交后只收势一次");
            var observed = TechniqueFabric.service().observe(direct.target().getUUID()).orElseThrow();
            context.assertTrue(observed.cast().orElseThrow().phase() == CastPhase.RECOVERING, "撤防进入收势");
            var rejected = TechniqueFabric.service().cast(direct.target().getUUID(), request(direct.target()));
            context.assertTrue(!successful(rejected), "毁尽后不能立刻重新起式");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lastArmDestroyedByUncoveredHitAlsoEndsGuard(GameTestHelper context) {
        try (var scene = guarding(context, LOW_HP, 0)) {
            int before = activities(scene.target());
            scene.direct(LEFT);
            context.assertTrue(TechniqueFabric.service().activeGuard(scene.target().getUUID()).isEmpty(),
                    "没有挡住的前臂伤也必须触发毁尽撤防");
            context.assertTrue(activities(scene.target()) == before + 1, "撤防只提交一次活动");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void restoredArmsRequireNewCastAndTotemIsConsumedOnce(GameTestHelper context) {
        try (var scene = guarding(context, LOW_HP, 0)) {
            scene.direct("head");
            scene.target().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            var lethal = new VanillaDamageFabric.DirectDamage(scene.target(), scene.source(), "head", LETHAL_DOSE);
            var result = VanillaDamageFabric.damageDirect(lethal).orElseThrow();
            context.assertTrue(result.deathPrevented(), "图腾仍由同一次健康事务判定保命");
            context.assertTrue(scene.target().getOffhandItem().isEmpty(), "图腾只消费一次");
            context.assertTrue(hp(scene.target(), LEFT) > 0 && hp(scene.target(), RIGHT) > 0, "保命恢复挡位下限");
            context.assertTrue(TechniqueFabric.service().activeGuard(scene.target().getUUID()).isEmpty(),
                    "恢复挡位不能自动恢复旧守");
            advance(scene.target(), WARMUP + RECOVERY + STAGGER_LIMIT);
            TechniqueFabric.service().tick();
            context.assertTrue(TechniqueFabric.service().activeGuard(scene.target().getUUID()).isEmpty(),
                    "收势结束后也不自动重起");
            var outcome = TechniqueFabric.service().cast(scene.target().getUUID(), request(scene.target()));
            context.assertTrue(successful(outcome), "恢复后可重新发起守式");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void noArmsOrNoConfiguredTemplateCannotStart(GameTestHelper context) {
        try (var scene = scene(context, 0, 0)) {
            scene.target().setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            var outcome = TechniqueFabric.service().cast(scene.target().getUUID(), request(scene.target()));
            context.assertTrue(!successful(outcome), "双臂归零不能起式");
            context.assertTrue(outcome.toString().contains(VanillaDamageErrors.GUARD_UNAVAILABLE), "返回文案键占位");
            context.assertTrue(activities(scene.target()) == 0, "准入拒绝不提交活动");
        }
        var unknown = context.makeMockPlayer(GameType.SURVIVAL);
        bind(unknown, "meridian_damage_test:unknown", ARM_HP, ARM_HP);
        var outcome = TechniqueFabric.service().cast(unknown.getUUID(), request(unknown));
        context.assertTrue(!successful(outcome), "无模板配置不能借人体挡位");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void penetratingHitKeepsOriginalPartDoseStaggerAndGuard(GameTestHelper context) {
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            var sword = new ItemStack(Items.IRON_SWORD);
            scene.target().setItemSlot(EquipmentSlot.MAINHAND, sword);
            var extras = new VanillaDamageFabric.DirectExtras(OptionalDouble.of(ORGAN_SHARE), HALF_ARMOR, List.of());
            damage(scene.target(), scene.source(), "chest", extras);
            assertUnblocked(context, scene, "chest");
            context.assertTrue(sword.getDamageValue() == 0, "透劲不磨损守方兵器");
            context.assertTrue(TechniqueFabric.service().activeGuard(scene.target().getUUID()).isPresent(),
                    "透劲整下绕过，不撤守");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void defenderPaysNoWorkAndArmInjuryReducesLaterStrength(GameTestHelper context) {
        try (var scene = guarding(context, ARM_HP, ARM_HP)) {
            var before = body(scene.target());
            double strengthBefore = VanillaDamageFabric.armCoefficient(scene.target(), HumanoidArm.LEFT);
            scene.normal();
            var after = body(scene.target());
            context.assertTrue(before.meridianStates().equals(after.meridianStates()), "格挡不扣真元");
            close(context, after.tissueStates().stream().mapToDouble(state -> state.fatigue()).sum(), 0,
                    "格挡不给守方记做功");
            context.assertTrue(VanillaDamageFabric.armCoefficient(scene.target(), HumanoidArm.LEFT) < strengthBefore,
                    "前臂受伤降低随后出力");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void attackerStillPaysOneStrikeOnBothPaths(GameTestHelper context) {
        try (var ordinary = guarding(context, ARM_HP, ARM_HP);
             var direct = guarding(context, ARM_HP, ARM_HP)) {
            bind(ordinary.attacker(), ARM_HP, ARM_HP);
            bind(direct.attacker(), ARM_HP, ARM_HP);
            ordinary.normal();
            direct.direct("head");
            for (var scene : List.of(ordinary, direct)) {
                double fatigue = body(scene.attacker()).tissueStates().stream()
                        .mapToDouble(state -> state.fatigue()).sum();
                close(context, fatigue, ATTACK_FATIGUE, "攻击者一次命中只支付一次既有做功");
                close(context, hp(scene.target(), LEFT), ARM_HP - GUARDED_DOSE, "做功前的肌力用于同一下命中");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void vanillaShieldStillBlocksBeforeArmDamage(GameTestHelper context) {
        try (var scene = scene(context, ARM_HP, ARM_HP)) {
            scene.target().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            scene.target().startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
            for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
                scene.target().tick();
            }
            scene.target().setYRot(0);
            scene.moveAttacker(0);
            long revision = body(scene.target()).revision();
            scene.normal();
            context.assertTrue(body(scene.target()).revision() == revision, "盾全挡不提交身体伤害");
            close(context, hp(scene.target(), LEFT), ARM_HP, "举盾时前臂不受伤");
        }
        context.succeed();
    }

    private static void assertUnblocked(GameTestHelper context, Scene scene, String part) {
        close(context, hp(scene.target(), part), PART_HP - DOSE, "未挡住保留原部位剂量");
        close(context, hp(scene.target(), LEFT), ARM_HP, "未挡住不扣前臂");
        context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(scene.target().getUUID()) > 0,
                "未挡住仍写 HIT");
    }

    private static double totalLoss(HealthResult result) {
        return result.getDamageResolution().getLossesList().stream().filter(loss -> loss.getTarget().hasPartId())
                .mapToDouble(loss -> loss.getHp()).sum();
    }

    private static Scene guarding(GameTestHelper context, double leftHp, double rightHp) {
        return guarding(context, leftHp, rightHp, GUARD_ID);
    }

    private static Scene guarding(GameTestHelper context, double leftHp, double rightHp, String technique) {
        Scene scene = scene(context, leftHp, rightHp);
        startGuard(context, scene, technique);
        return scene;
    }

    private static void startGuard(GameTestHelper context, Scene scene, String technique) {
        var outcome = TechniqueFabric.service().cast(scene.target().getUUID(), request(scene.target(), technique));
        context.assertTrue(successful(outcome), "守式应成功起手：" + outcome);
        advance(scene.target(), WARMUP);
        TechniqueFabric.service().tick();
        context.assertTrue(TechniqueFabric.service().activeGuard(scene.target().getUUID()).isPresent(), "已进入守段");
    }

    private static Scene scene(GameTestHelper context, double leftHp, double rightHp) {
        var target = context.makeMockPlayer(GameType.SURVIVAL);
        Vec3 position = context.absoluteVec(new Vec3(ORIGIN, ORIGIN, ORIGIN));
        target.setPos(position.add(target.getId() * SPACING, 0, 0));
        return scene(context, target, leftHp, rightHp);
    }

    private static Scene scene(GameTestHelper context, LivingEntity target, double leftHp, double rightHp) {
        target.setYRot(0);
        bind(target, leftHp, rightHp);
        var attacker = context.spawn(EntityTypes.ZOMBIFIED_PIGLIN, ORIGIN, ORIGIN, ORIGIN);
        attacker.setNoAi(true /* noAi */);
        attacker.setPos(target.position().add(0, 0, DISTANCE));
        return new Scene(target, attacker);
    }

    private static CastRequest request(LivingEntity entity) {
        return request(entity, GUARD_ID);
    }

    private static CastRequest request(LivingEntity entity, String technique) {
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId(technique)
                .setVersion(1)
                .build();
        return CastRequest.newBuilder()
                .setBodyId(entity.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .build();
    }

    private static boolean successful(CastOutcome outcome) {
        return outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED;
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardParametersComeFromCurrentTechniqueIncludingZeroMultiplier(GameTestHelper context) {
        try (var scene = guarding(context, ARM_HP, ARM_HP, CUSTOM_GUARD_ID)) {
            long before = body(scene.target()).revision();
            damage(scene.target(), source(scene.target(), "head_neck_strike", scene.attacker()), "head",
                    VanillaDamageFabric.DirectExtras.NONE);
            close(context, hp(scene.target(), LEFT), ARM_HP, "零倍率不扣前臂");
            context.assertTrue(body(scene.target()).revision() == before, "完全减免不提交零剂量");
            scene.moveAttacker(CUSTOM_OUTSIDE_ANGLE);
            damage(scene.target(), source(scene.target(), "head_neck_strike", scene.attacker()), "head",
                    VanillaDamageFabric.DirectExtras.NONE);
            close(context, hp(scene.target(), "head"), PART_HP - DOSE, "扇面来自当前守式");
        }
        try (var scene = guarding(context, ARM_HP, ARM_HP, CUSTOM_GUARD_ID)) {
            scene.direct("head");
            assertUnblocked(context, scene, "head");
        }
        context.succeed();
    }

    private static DamageSource source(LivingEntity target, String path, Entity... entities) {
        return typedSource(target, "meridian_damage_test:" + path, entities);
    }

    private static DamageSource typedSource(LivingEntity target, String id, Entity... entities) {
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(id));
        var type = target.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
        if (entities.length == 0) {
            return new DamageSource(type);
        }
        if (entities.length == 1) {
            return new DamageSource(type, entities[0]);
        }
        return new DamageSource(type, entities[0], entities[1]);
    }

    private static HealthResult damage(LivingEntity target, DamageSource source, String part,
                                        VanillaDamageFabric.DirectExtras extras) {
        var damage = new VanillaDamageFabric.DirectDamage(target, source, part, DOSE, extras);
        return VanillaDamageFabric.damageDirect(damage).orElseThrow().health();
    }

    private static void close(GameTestHelper context, double actual, double expected, String message) {
        context.assertTrue(Math.abs(actual - expected) < EPSILON,
                message + ": actual=" + actual + ", expected=" + expected);
    }

    private record Scene(LivingEntity target, LivingEntity attacker) implements AutoCloseable {
        DamageSource source() {
            return GuardGameTest.source(target, "guard_head", attacker);
        }

        void normal() {
            target.hurtServer((net.minecraft.server.level.ServerLevel) target.level(), source(), DOSE);
        }

        HealthResult direct(String part) {
            return damage(target, source(), part, VanillaDamageFabric.DirectExtras.NONE);
        }

        void moveAttacker(double degrees) {
            double radians = Math.toRadians(degrees);
            attacker.setPos(target.position().add(Math.sin(radians) * DISTANCE, 0, Math.cos(radians) * DISTANCE));
        }

        @Override
        public void close() {
            TechniqueFabric.service().cancelOnUnload(target.getUUID());
            target.discard();
            attacker.discard();
        }
    }
}
