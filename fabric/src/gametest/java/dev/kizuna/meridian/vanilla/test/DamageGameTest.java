package dev.kizuna.meridian.vanilla.test;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageTypeTags;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.vanilla.AttackEffectProtobuf;
import dev.kizuna.meridian.vanilla.AttackEffectProvider;
import dev.kizuna.meridian.vanilla.MuscleRules;
import dev.kizuna.meridian.vanilla.protocol.pb.AttackEffectMessages;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.kizuna.meridian.vanilla.test.DamageGameTestTicks.DEFAULT_TIMEOUT;

/**
 * 整体回归：真实 Player/LivingEntity、Mixin、Fabric 死亡事件与核心账本。
 * 伤害直接调用 {@code hurtServer}：26.2 弃用的 {@code Entity.hurt} 在服务端只转调它，入口与断言不变。
 */
public final class DamageGameTest implements BodyHealthHost {
    private static final InMemoryBodyStore STORE = new InMemoryBodyStore(List.of(), List.of());
    private static volatile String attackEffectTarget;
    private static volatile String attackEffectFailureTarget;
    private static final AtomicInteger ATTACK_EFFECT_CALLS = new AtomicInteger();
    private static final double MUSCLE_HP = 100;
    private static final double MUSCLE_STRENGTH = 300;
    private static final double MUSCLE_POWER = 1000;
    private static final double MUSCLE_ENDURANCE = 1000;
    private static final double BOOST = 1.3;
    private static final double COLLAPSE = 0.85;
    private static final float STRIKE_DAMAGE = 4;
    private static final float EXHAUSTED_DAMAGE = 1;
    private static final long MODIFIER_MILLIS = 1000;
    private static final double EPSILON = 1e-5;
    private static final float CHEST_ARMOR = 8;
    private static final float CHEST_TOUGHNESS = 2;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final int STATUS_DURATION_TICKS = 20;
    private static final int ATTACKER_OFFSET = 6;
    private static final int TARGET_OFFSET = 2;
    private static final int SPAWN_Y = 2;
    private static final int MOCK_PLAYER_SPACING = 2;
    private static final int SWEEPING_LEVEL = 3;
    private static final double SWEEPING_MULTIPLIER = 0.75;
    private static final double ATTACK_DISTANCE = 1.5;
    private static final int SHIELD_READY_TICKS = 6;
    private static final double ARM_WORK_JOULES_PER_HP = 20;
    private static final double ARM_STRIKE_SECONDS = 0.5;
    private static final BodyCommandHandler<String> COMMANDS = new BodyCommandHandler<>(
            (caller, command) -> Optional.of(caller), (caller, command, body) -> true,
            (caller, body) -> TimeAdvancePlan.newBuilder()
                    .setTarget(BodyProtobuf.toTime(body.settledAt()))
                    .setMode(TimeAdvanceMode.ACTIVE)
                    .setBiologicalSecondsPerSimulationSecond(1)
                    .build(),
            STORE, (caller, receipt, previous, updated) -> { /* 由断言读取权威提交。 */ });

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void mapsFinalLossAndBothDeathDirections(GameTestHelper context) {
        // 玩家实现有独立 actuallyHurt，吸收生命挡住时身体必须完全不变。
        var player = context.makeMockPlayer(GameType.SURVIVAL);
        bind(player, "test:creature", "shell", 100);
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        player.hurtServer(context.getLevel(), player.damageSources().generic(), 2);
        context.assertTrue(body(player).revision() == 0, "吸收生命不应产生身体伤害");

        // 未登记模板没有覆盖，即使穿胸甲也不能借用原版的全局护甲保护。
        player.invulnerableTime = 0;
        player.removeEffect(MobEffects.ABSORPTION);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        // 原版在实体 tick 应用装备属性；同一测试函数内穿装备后先完成该边界。
        player.tick();
        var attacker = context.spawn(EntityTypes.ZOMBIE, 6, 2, 6);
        var meleeDamage = player.damageSources().mobAttack(attacker);
        float before = player.getHealth();
        player.hurtServer(context.getLevel(), meleeDamage, 4);
        double lost = displayedBodyLoss(player, before);
        assertClose(context, lost, 4, "未覆盖部位不能享受全局护甲减伤");
        assertClose(context, body(player).partStates().get(0).hp(), 100 - lost,
                "玩家实际扣血必须映射一次");
        long revision = body(player).revision();
        player.hurtServer(context.getLevel(), meleeDamage, 4);
        context.assertTrue(body(player).revision() == revision, "无敌帧不能重复伤害身体");

        // 外部死亡回调保留局部伤势；kill() 会先造成真实致死伤害，不适合验证此边界。
        player.invulnerableTime = 0;
        double beforeDeath = body(player).partStates().get(0).hp();
        player.die(player.damageSources().genericKill());
        context.assertTrue(body(player).vitality().dead(), "原版死亡必须通知身体");
        context.assertTrue(body(player).partStates().get(0).hp() > 0, "死亡确认应保留局部伤势");
        context.assertTrue(body(player).partStates().get(0).hp() == beforeDeath,
                "外部死亡确认不能伪造局部伤害");

        var cow = context.spawn(EntityTypes.COW, 2, 2, 2);
        bind(cow, "test:creature", "shell", 100);
        cow.hurtServer(context.getLevel(), cow.damageSources().generic(), 3);
        context.assertTrue(body(cow).partStates().get(0).hp() == 97, "普通生物扣血必须映射一次");

        // 身体先判死时，等待伤害返回后同步原版死亡，不再生成一笔伪造伤害。
        var fragile = context.spawn(EntityTypes.COW, 4, 2, 4);
        bind(fragile, "test:creature", "shell", 1);
        fragile.hurtServer(context.getLevel(), fragile.damageSources().generic(), 2);
        context.assertTrue(body(fragile).vitality().dead() && !fragile.isAlive(), "身体死亡必须同步原版实体");
        context.assertTrue(body(fragile).revision() == 1, "反向同步不能重复提交伤害或死亡");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void attackEffectProviderAddsDamageAndStatusOnce(GameTestHelper context) {
        var attacker = context.spawn(EntityTypes.ZOMBIE, 6, 2, 6);
        var target = context.makeMockPlayer(GameType.SURVIVAL);
        bind(attacker, "test:creature", "shell", 100);
        bind(target, "test:creature", "shell", 100);
        attackEffectTarget = target.getStringUUID();
        ATTACK_EFFECT_CALLS.set(0);
        try {
            float before = target.getHealth();
            target.hurtServer(context.getLevel(), target.damageSources().mobAttack(attacker), 2);
            assertClose(context, displayedBodyLoss(target, before), 3, "测试攻击附效应与原版伤害合并一次");
            assertClose(context, 100 - body(target).partStates().get(0).hp(), 3,
                    "附加伤害必须进入同一身体事务");
            context.assertTrue(target.hasEffect(MobEffects.STRENGTH),
                    "攻击附效状态应在 hurt 返回后施加");

            target.invulnerableTime = 0;
            before = target.getHealth();
            target.hurtServer(context.getLevel(), target.damageSources().mobAttack(attacker), 2);
            assertClose(context, displayedBodyLoss(target, before), 2,
                    "同一 effect_id 对同一目标不能重复附加伤害");
            assertClose(context, 100 - body(target).partStates().get(0).hp(), 5,
                    "去重后原版伤害仍应正常提交");
            context.assertTrue(ATTACK_EFFECT_CALLS.get() == 2, "每次近战命中都应只询问一次测试提供方");
            context.succeed();
        } finally {
            attackEffectTarget = null;
        }
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void attackEffectProviderFailureKeepsOriginalDamage(GameTestHelper context) {
        var attacker = context.spawn(EntityTypes.ZOMBIE, 6, 2, 6);
        var target = context.makeMockPlayer(GameType.SURVIVAL);
        bind(attacker, "test:creature", "shell", 100);
        bind(target, "test:creature", "shell", 100);
        attackEffectFailureTarget = target.getStringUUID();
        try {
            float before = target.getHealth();
            target.hurtServer(context.getLevel(), target.damageSources().mobAttack(attacker), 2);
            assertClose(context, displayedBodyLoss(target, before), 2,
                    "提供方异常不能改变原版伤害剂量");
            assertClose(context, 100 - body(target).partStates().get(0).hp(), 2,
                    "提供方异常不能跳过原版身体事务");
            context.assertTrue(!target.hasEffect(MobEffects.STRENGTH),
                    "提供方异常不能施加附加状态");
            context.succeed();
        } finally {
            attackEffectFailureTarget = null;
        }
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void localArmorRetainsVanillaEffectsAndCooldown(GameTestHelper context) {
        // 单部位样例使选点确定，依次验证每个默认槽位及没有覆盖的前臂。
        var attacker = context.spawn(EntityTypes.ZOMBIE, 6, 2, 6);
        for (String part : List.of("head", "neck", "chest", "abdomen", "left_upper_arm", "pelvis",
                "left_thigh", "left_lower_leg", "left_foot", "left_forearm")) {
            var player = context.makeMockPlayer(GameType.SURVIVAL);
            bind(player, "human:adult", part, 100);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            player.tick();
            EquipmentSlot slot = switch (part) {
                case "head", "neck" -> EquipmentSlot.HEAD;
                case "chest", "abdomen", "left_upper_arm" -> EquipmentSlot.CHEST;
                case "pelvis", "left_thigh", "left_lower_leg" -> EquipmentSlot.LEGS;
                case "left_foot" -> EquipmentSlot.FEET;
                default -> null;
            };
            float expected = 4;
            var source = player.damageSources().mobAttack(attacker);
            if (slot != null) {
                expected = vanillaArmorDamage(player, source, 4, player.getItemBySlot(slot), slot);
            }
            float before = player.getHealth();
            player.hurtServer(context.getLevel(), source, 4);
            assertClose(context, displayedBodyLoss(player, before), expected, part + " 只应使用覆盖该部位的单件护甲");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expected, part + " 身体剂量应一致");
            long revision = body(player).revision();
            player.hurtServer(context.getLevel(), source, 4);
            context.assertTrue(body(player).revision() == revision, "无敌帧不应重新选点或扣血");
            float strongerExpected = 2;
            if (slot != null) {
                var stack = player.getItemBySlot(slot);
                float baseDamage = vanillaArmorDamage(player, source, 2, stack, slot);
                // 前次受击已经磨损；满耐久至半耐久这一段每损耗 1 比例，减免系数下降 0.3。
                double durabilityMultiplier = 1 - 0.3 * stack.getDamageValue() / stack.getMaxDamage();
                strongerExpected = (float) (2 - (2 - baseDamage) * durabilityMultiplier);
            }
            before = player.getHealth();
            player.hurtServer(context.getLevel(), source, 6);
            assertClose(context, displayedBodyLoss(player, before), strongerExpected, "无敌帧内更强攻击只结算超过前次的差额");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expected + strongerExpected,
                    "无敌帧差额只追加一次身体剂量");
        }

        var emptySlot = context.makeMockPlayer(GameType.SURVIVAL);
        bind(emptySlot, "human:adult", "head", 100);
        emptySlot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        emptySlot.tick();
        float headBefore = emptySlot.getHealth();
        emptySlot.hurtServer(context.getLevel(), emptySlot.damageSources().mobAttack(attacker), 4);
        assertClose(context, displayedBodyLoss(emptySlot, headBefore), 4, "未戴头盔的头部不能借用胸甲保护");

        // 护甲之后仍用原版保护附魔和抗性，局部吸收由同套治疗集成继续处理。
        var protectedPlayer = context.makeMockPlayer(GameType.SURVIVAL);
        bind(protectedPlayer, "human:adult", "head", 100);
        protectedPlayer.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        var enchantedChest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        enchantedChest.enchant(enchantment(context, Enchantments.PROTECTION), 4);
        protectedPlayer.setItemSlot(EquipmentSlot.CHEST, enchantedChest);
        protectedPlayer.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 0));
        protectedPlayer.tick();
        protectedPlayer.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        float before = protectedPlayer.getHealth();
        var source = protectedPlayer.damageSources().mobAttack(attacker);
        protectedPlayer.hurtServer(context.getLevel(), source, 4);
        assertClose(context, displayedBodyLoss(protectedPlayer, before), 0, "局部黄心完全抵挡剩余剂量");
        assertClose(context, 100 - body(protectedPlayer).partStates().get(0).hp(), 0, "吸收不能留下局部伤势");

        protectedPlayer.invulnerableTime = 0;
        protectedPlayer.removeEffect(MobEffects.ABSORPTION);
        protectedPlayer.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        long revision = body(protectedPlayer).revision();
        protectedPlayer.hurtServer(context.getLevel(), source, 4);
        context.assertTrue(body(protectedPlayer).revision() == revision, "局部护甲后完全吸收不能留下伤势");

        // 原版 bypass armor 标签依旧绕过装备，但不强制绕过其余减免。
        protectedPlayer.invulnerableTime = 0;
        protectedPlayer.removeAllEffects();
        protectedPlayer.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        before = protectedPlayer.getHealth();
        protectedPlayer.hurtServer(context.getLevel(), protectedPlayer.damageSources().fall(), 2);
        assertClose(context, displayedBodyLoss(protectedPlayer, before), 2, "坠落必须保留绕过护甲的原版语义");

        // 物品自定义属性属于该件护甲，不能被材料的固定护甲值覆盖。
        var custom = context.makeMockPlayer(GameType.SURVIVAL);
        bind(custom, "human:adult", "head", 100);
        var customHelmet = new ItemStack(Items.DIAMOND_HELMET);
        replaceArmor(customHelmet, EquipmentSlot.HEAD, "custom_armor", 10);
        custom.setItemSlot(EquipmentSlot.HEAD, customHelmet);
        custom.tick();
        before = custom.getHealth();
        var customSource = custom.damageSources().mobAttack(attacker);
        custom.hurtServer(context.getLevel(), customSource, 4);
        assertClose(context, displayedBodyLoss(custom, before),
                CombatRules.getDamageAfterAbsorb(custom, 4, customSource, 10, 0), "物品组件护甲属性必须参与局部减伤");

        var husk = context.spawn(EntityTypes.HUSK, 4, 2, 4);
        husk.setNoAi(true);
        bind(husk, "human:adult", "chest", 100);
        husk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        husk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        husk.tick();
        before = husk.getHealth();
        var huskSource = husk.damageSources().mobAttack(attacker);
        husk.hurtServer(context.getLevel(), huskSource, 4);
        float huskExpected = CombatRules.getDamageAfterAbsorb(husk, 4, huskSource, 8, 2);
        assertClose(context, displayedBodyLoss(husk, before), huskExpected, "普通生物也只读取命中槽位护甲");
        assertClose(context, 100 - body(husk).partStates().get(0).hp(), huskExpected,
                "普通生物局部减伤后只提交一次");

        // 未绑定生物仍走原版全局护甲，不让这个 Addon 改写未接入身体的战斗规则。
        var unbound = context.makeMockPlayer(GameType.SURVIVAL);
        unbound.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        unbound.tick();
        before = unbound.getHealth();
        var unboundSource = unbound.damageSources().mobAttack(attacker);
        unbound.hurtServer(context.getLevel(), unboundSource, 4);
        assertClose(context, before - unbound.getHealth(),
                CombatRules.getDamageAfterAbsorb(unbound, 4, unboundSource, 8, 2), "未绑定玩家应保留原版护甲");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void durabilityAndReductionCapUseHitArmorBeforeWear(GameTestHelper context) {
        // 铁胸甲耐久 240，使各个默认端点和段中值都能由真实物品精确表示。
        var attacker = context.spawn(EntityTypes.ZOMBIE, 6, 2, 6);
        double[][] samples = {{1, 1}, {0.75, 0.925}, {0.5, 0.85}, {0.375, 0.725},
                {0.25, 0.6}, {0.175, 0.475}, {0.1, 0.35}, {0.05, 0.175}, {0, 0}};
        for (double[] sample : samples) {
            var player = context.makeMockPlayer(GameType.SURVIVAL);
            bind(player, "human:adult", "chest", 100);
            var chestplate = new ItemStack(Items.IRON_CHESTPLATE);
            int maxDurability = chestplate.getMaxDamage();
            int initialDamage = maxDurability - (int) Math.round(maxDurability * sample[0]);
            chestplate.setDamageValue(initialDamage);
            player.setItemSlot(EquipmentSlot.CHEST, chestplate);
            player.tick();
            float before = player.getHealth();
            player.hurtServer(context.getLevel(), player.damageSources().mobAttack(attacker), 4);
            double expectedDamage = 4 - 0.64 * sample[1];
            assertClose(context, displayedBodyLoss(player, before), expectedDamage, "耐久曲线须使用本次磨损前的耐久比例");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expectedDamage,
                    "耐久衰减后身体与原版应接收相同剂量");
            context.assertTrue(chestplate.isEmpty() || chestplate.getDamageValue() > initialDamage,
                    "新增耐久衰减不能取消原版装备磨损");
        }

        // 自定义高护甲在半耐久时基础减免为 80%×0.85，最后才限到 60%。
        for (double remainingRatio : new double[]{1, 0.5, 0.25}) {
            var player = context.makeMockPlayer(GameType.SURVIVAL);
            bind(player, "human:adult", "chest", 100);
            var chestplate = new ItemStack(Items.IRON_CHESTPLATE);
            replaceArmor(chestplate, EquipmentSlot.CHEST, "high_local_armor", 100);
            chestplate.setDamageValue((int) Math.round(chestplate.getMaxDamage() * (1 - remainingRatio)));
            player.setItemSlot(EquipmentSlot.CHEST, chestplate);
            player.tick();
            float before = player.getHealth();
            player.hurtServer(context.getLevel(), player.damageSources().mobAttack(attacker), 4);
            double expectedDamage = 1.6;
            if (remainingRatio == 0.25) {
                expectedDamage = 2.08;
            }
            assertClose(context, displayedBodyLoss(player, before), expectedDamage, "耐久衰减后才应用最终护甲减免上限");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expectedDamage,
                    "高护甲不能突破身体部位的减免上限");
        }

        // 不可损坏物品即使残留耐久损耗值，也按无耐久物品使用系数 1。
        var unbreakablePlayer = context.makeMockPlayer(GameType.SURVIVAL);
        bind(unbreakablePlayer, "human:adult", "chest", 100);
        var unbreakable = new ItemStack(Items.IRON_CHESTPLATE);
        unbreakable.setDamageValue(unbreakable.getMaxDamage() * 9 / 10);
        unbreakable.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        unbreakablePlayer.setItemSlot(EquipmentSlot.CHEST, unbreakable);
        unbreakablePlayer.tick();
        float before = unbreakablePlayer.getHealth();
        unbreakablePlayer.hurtServer(context.getLevel(), unbreakablePlayer.damageSources().mobAttack(attacker), 4);
        assertClose(context, displayedBodyLoss(unbreakablePlayer, before), 3.36, "无耐久物品不受耐久曲线削弱");
        assertClose(context, 100 - body(unbreakablePlayer).partStates().get(0).hp(), 3.36,
                "无耐久物品的身体剂量应一致");
        context.succeed();
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 测试宿主仅绑定此测试登记的实体；其余实体保留原版行为。
        var request = HealthHostProtobuf.decodeRequest(payload);
        var response = HealthHostResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        var found = STORE.find(new BodyId(request.getSubjectId()));
        if (found.isEmpty()) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
        }
        if (request.hasExertion()) {
            var result = MeridianProtocol.decodeResponse(COMMANDS.handle("test", request.getExertion().toByteArray()));
            if (result.hasFailure()) {
                return HealthHostProtobuf.encodeResponse(response.setFailure(result.getFailure()).build());
            }
        }
        if (request.hasEffect()) {
            var result = MeridianProtocol.decodeResponse(COMMANDS.handleHealthEffect(
                    "test", request.getEffect().toByteArray()));
            if (result.hasFailure()) {
                return HealthHostProtobuf.encodeResponse(response.setFailure(result.getFailure()).build());
            }
            STORE.transact(found.get().id(), transaction -> {
                // 完整集成依据同一请求回执消费效果，不凭最终血量猜测结算结果。
                response.setHealth(transaction.receipt("test", request.getRequestId()).orElseThrow().getHealth());
                return null;
            });
        }
        return HealthHostProtobuf.encodeResponse(response.setBody(BodyProtobuf.toMessage(
                STORE.find(found.get().id()).orElseThrow())).build());
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void muscleScalesBeforeArmorOnceForPlayerAndUnboundTargets(GameTestHelper context) {
        // 两条 actuallyHurt 实现都必须在护甲前乘一次，目标无身体仍受攻击者系数影响。
        var attacker = context.spawn(EntityTypes.ZOMBIE, ATTACKER_OFFSET, SPAWN_Y, ATTACKER_OFFSET);
        bindMuscles(attacker, BOOST);
        var player = context.makeMockPlayer(GameType.SURVIVAL);
        bind(player, "human:adult", "chest", MUSCLE_HP);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        player.tick();
        float before = player.getHealth();
        var source = player.damageSources().mobAttack(attacker);
        player.hurtServer(context.getLevel(), source, STRIKE_DAMAGE);
        double expected = CombatRules.getDamageAfterAbsorb(player, (float) (STRIKE_DAMAGE * BOOST), source,
                CHEST_ARMOR, CHEST_TOUGHNESS);
        assertClose(context, displayedBodyLoss(player, before), expected, "玩家路径必须在护甲前只乘一次");
        var attackerBody = body(attacker);
        context.assertTrue(attackerBody.revision() == 1, "一次命中只能提交一次肌肉做功");
        context.assertTrue(attackerBody.tissueStates().get(1).fatigue() > 0, "出手右臂没有记做功");
        context.assertTrue(attackerBody.tissueStates().get(0).fatigue() == 0, "主手攻击错误消耗左臂");

        var otherAttacker = context.spawn(EntityTypes.ZOMBIE, ATTACKER_OFFSET, SPAWN_Y, ATTACKER_OFFSET);
        bindMuscles(otherAttacker, BOOST);
        var cow = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET);
        cow.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        cow.tick();
        float cowBefore = cow.getHealth();
        cow.hurtServer(context.getLevel(), cow.damageSources().mobAttack(otherAttacker), STRIKE_DAMAGE);
        assertClose(context, cowBefore - cow.getHealth(), expected,
                "无身体目标须先乘系数再走原版全身护甲");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void sameSwordUsesPeakCollapseAndExhaustedStillHits(GameTestHelper context) {
        // 使用真实剑攻击，直接能力修正模拟蓄力和虚脱，不依赖功法仓。
        for (double multiplier : new double[]{1, BOOST, COLLAPSE}) {
            var attacker = context.makeMockPlayer(GameType.SURVIVAL);
            attacker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            for (int tick = 0; tick < ATTACK_COOLDOWN_TICKS; tick++) {
                attacker.tick();
            }
            attacker.setOnGround(true /* onGround */);
            attacker.setSprinting(true /* sprinting */);
            bindMuscles(attacker, multiplier);
            var cow = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET);
            float before = cow.getHealth();
            double vanillaDamage = attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
            attacker.attack(cow);
            assertClose(context, before - cow.getHealth(), vanillaDamage * multiplier,
                    "同剑伤害必须跟随力量修正");
        }
        var exhausted = context.spawn(EntityTypes.ZOMBIE, ATTACKER_OFFSET, SPAWN_Y, ATTACKER_OFFSET);
        bindMuscles(exhausted, 0);
        var cow = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET);
        float before = cow.getHealth();
        cow.hurtServer(context.getLevel(), cow.damageSources().mobAttack(exhausted), STRIKE_DAMAGE);
        assertClose(context, before - cow.getHealth(), EXHAUSTED_DAMAGE, "力竭应按配置系数继续造成伤害");
        context.assertTrue(body(exhausted).revision() == 0, "做功被拒不能留下任何部分疲劳");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void playerSweepUsesOneExertionAndOnePreStrikeCoefficient(GameTestHelper context) {
        // 真实剑横扫命中三个目标，逐目标缩放复用同一笔做功前系数。
        Player attacker = context.makeMockPlayer(GameType.SURVIVAL);
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(enchantment(context, Enchantments.SWEEPING_EDGE), SWEEPING_LEVEL);
        prepareSword(attacker, sword);
        bindMuscles(attacker, BOOST);
        var primary = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET);
        var firstSweep = context.spawn(EntityTypes.COW, TARGET_OFFSET + 1, SPAWN_Y, TARGET_OFFSET);
        var secondSweep = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET + 1);
        attacker.setPos(primary.position().add(0, 0, -ATTACK_DISTANCE));
        double vanillaDamage = attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float primaryHealth = primary.getHealth();
        float firstHealth = firstSweep.getHealth();
        float secondHealth = secondSweep.getHealth();
        attacker.attack(primary);
        double sweepDamage = (1 + SWEEPING_MULTIPLIER * vanillaDamage) * BOOST;
        assertClose(context, primaryHealth - primary.getHealth(), vanillaDamage * BOOST, "主目标系数错误");
        assertClose(context, firstHealth - firstSweep.getHealth(), sweepDamage, "第一横扫目标系数错误");
        assertClose(context, secondHealth - secondSweep.getHealth(), sweepDamage, "第二横扫目标系数错误");
        context.assertTrue(body(attacker).revision() == 1, "一次横扫重复提交做功");
        assertClose(context, body(attacker).tissueStates().get(1).fatigue(),
                fatigueForVanillaDamage(vanillaDamage), "横扫疲劳非单笔 vanilla=" + vanillaDamage);
        primary.invulnerableTime = 0;
        attacker.attack(primary);
        context.assertTrue(body(attacker).revision() == 2, "出手上下文没有释放");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shieldBlockStillRecordsOneExertion(GameTestHelper context) {
        // 盾挡在 actuallyHurt 前退出，出手账仍应完成且只记一次。
        Player attacker = context.makeMockPlayer(GameType.SURVIVAL);
        prepareSword(attacker, new ItemStack(Items.IRON_SWORD));
        bindMuscles(attacker, BOOST);
        Player defender = context.makeMockPlayer(GameType.SURVIVAL);
        defender.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.startUsingItem(InteractionHand.OFF_HAND);
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            defender.tick();
        }
        defender.setYRot(0);
        attacker.setPos(defender.position().add(0, 0, ATTACK_DISTANCE));
        float health = defender.getHealth();
        attacker.attack(defender);
        assertClose(context, defender.getHealth(), health, "盾没有实际挡住伤害");
        context.assertTrue(body(attacker).revision() == 1, "盾挡未按出手记一笔做功");
        double vanillaDamage = attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        assertClose(context, body(attacker).tissueStates().get(1).fatigue(),
                fatigueForVanillaDamage(vanillaDamage), "盾挡疲劳错误");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void handSwingPacketRecordsMissAndDoesNotChargeAttackTwice(GameTestHelper context) {
        // 使用正式网络处理器接收挥手包，主手挥空收费。
        // 实体攻击后的动画包不重复收费。
        var attacker = TestPlayers.detached(context.getLevel(), "damage_swing");
        bindMuscles(attacker, BOOST);
        var mainSwing = new ServerboundSwingPacket(InteractionHand.MAIN_HAND);
        attacker.connection.handleAnimate(mainSwing);
        context.assertTrue(body(attacker).revision() == 1, "主手挥空未记做功");
        attacker.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.OFF_HAND));
        context.assertTrue(body(attacker).revision() == 1, "副手动画被记成主手出手");
        var cow = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET);
        attacker.attack(cow);
        attacker.connection.handleAnimate(mainSwing);
        context.assertTrue(body(attacker).revision() == 2, "攻击和动画包重复做功");
        attacker.connection.handleAnimate(mainSwing);
        context.assertTrue(body(attacker).revision() == 3, "下一次挥空被旧实体攻击吞掉");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void miningAndItemUseSwingsDoNotCountAsEmptyAttacks(GameTestHelper context) {
        // 挖掘期间的连续动画和物品使用动画都不能伪造一次近战挥空。
        var attacker = TestPlayers.detached(context.getLevel(), "damage_swing_actions");
        bindMuscles(attacker, BOOST);
        var startMining = new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, BlockPos.ZERO, Direction.UP);
        attacker.connection.handlePlayerAction(startMining);
        attacker.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        context.assertTrue(body(attacker).revision() == 0, "挖掘动画不应记近战做功");
        var abortMining = new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, BlockPos.ZERO, Direction.UP);
        attacker.connection.handlePlayerAction(abortMining);
        attacker.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        context.assertTrue(body(attacker).revision() == 1, "结束挖掘后的真正挥空应记做功");
        var useItem = new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0);
        attacker.connection.handleUseItem(useItem);
        attacker.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        context.assertTrue(body(attacker).revision() == 1, "物品使用动画不应记近战做功");
        attacker.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        context.assertTrue(body(attacker).revision() == 2, "交互后的下一次挥空应恢复计费");
        var offHandUse = new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 0, 0, 0);
        attacker.connection.handleUseItem(offHandUse);
        attacker.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        context.assertTrue(body(attacker).revision() == 3, "副手交互不应抑制主手挥空");
        context.succeed();
    }

    private static float vanillaArmorDamage(LivingEntity victim, DamageSource source, float damage,
                                            ItemStack armor, EquipmentSlot slot) {
        // 26.2 没有盔甲物品类，单件的护甲与韧性只记在物品属性组件里，取出后交原版公式。
        return CombatRules.getDamageAfterAbsorb(victim, damage, source, armorAttribute(armor, slot, Attributes.ARMOR),
                armorAttribute(armor, slot, Attributes.ARMOR_TOUGHNESS));
    }

    private static float armorAttribute(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attribute) {
        // 原版盔甲每种属性只有一条加值修饰，按槽位累加即为该件的数值。
        double[] total = {0};
        stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).forEach(slot,
                (holder, modifier) -> {
                    if (holder.equals(attribute)) {
                        total[0] += modifier.amount();
                    }
                });
        return (float) total[0];
    }

    private static void replaceArmor(ItemStack stack, EquipmentSlot slot, String id, double armor) {
        // 整个属性组件换成一条护甲修饰，与 1.20.1 写入属性 NBT 后不再读材料默认值相同。
        var modifier = new AttributeModifier(Identifier.fromNamespaceAndPath("meridian_damage_test", id), armor,
                AttributeModifier.Operation.ADD_VALUE);
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                .add(Attributes.ARMOR, modifier, EquipmentSlotGroup.bySlot(slot))
                .build());
    }

    private static Holder<Enchantment> enchantment(GameTestHelper context, ResourceKey<Enchantment> key) {
        // 26.2 附魔是数据驱动的注册项，从服务端注册表取引用。
        return context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    private static void prepareSword(Player attacker, ItemStack sword) {
        // 完成原版装备属性及攻击冷却边界，保持非疾跑以启用横扫。
        attacker.setItemSlot(EquipmentSlot.MAINHAND, sword);
        for (int tick = 0; tick < ATTACK_COOLDOWN_TICKS; tick++) {
            attacker.tick();
        }
        attacker.setOnGround(true /* onGround */);
        attacker.setSprinting(false /* sprinting */);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void defaultMeleeSourcesAndLeftMainHandRemainSupported(GameTestHelper context) {
        // 默认三个原版来源都接入近战；左撇子主手不能读取被强化的右臂。
        var attacker = context.makeMockPlayer(GameType.SURVIVAL);
        attacker.setMainArm(HumanoidArm.LEFT);
        var sources = attacker.damageSources();
        for (DamageSource source : List.of(sources.playerAttack(attacker), sources.mobAttack(attacker),
                sources.noAggroMobAttack(attacker))) {
            context.assertTrue(source.is(VanillaDamageTypeTags.MELEE), "原版近战来源遗漏：" + source);
        }
        bindMuscles(attacker, BOOST);
        var cow = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET);
        float before = cow.getHealth();
        cow.hurtServer(context.getLevel(), sources.noAggroMobAttack(attacker), STRIKE_DAMAGE);
        assertClose(context, before - cow.getHealth(), STRIKE_DAMAGE, "左主手不应读取右臂力量");
        var attackerBody = body(attacker);
        context.assertTrue(attackerBody.tissueStates().get(0).fatigue() > 0, "左主手没有记做功");
        context.assertTrue(attackerBody.tissueStates().get(1).fatigue() == 0, "左主手错误消耗右臂");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void taggedMeleeSelectsHandsOverridesKindAndKeepsExtraUnscaled(GameTestHelper context) {
        // 自定义来源只用数据包标签接入，附效的额外 1 HP 不乘攻击者肌肉系数。
        var attacker = context.makeMockPlayer(GameType.SURVIVAL);
        attacker.setMainArm(HumanoidArm.LEFT);
        bindMuscles(attacker, BOOST);
        var target = context.makeMockPlayer(GameType.SURVIVAL);
        bind(target, "test:creature", "shell", MUSCLE_HP);
        var source = customSource(context, attacker, "off_strike");
        context.assertTrue(VanillaDamageTypeTags.kind(source).orElseThrow() == DamageKind.CUTTING,
                "自定义 kind 标签没有覆盖默认分类");
        attackEffectTarget = target.getStringUUID();
        ATTACK_EFFECT_CALLS.set(0);
        try {
            float before = target.getHealth();
            target.hurtServer(context.getLevel(), source, STRIKE_DAMAGE);
            assertClose(context, displayedBodyLoss(target, before), STRIKE_DAMAGE * BOOST + 1,
                    "副手应取右臂，附效额外伤害不乘系数");
            context.assertTrue(ATTACK_EFFECT_CALLS.get() == 1, "自定义 melee 没有触发附效");
            var attackerBody = body(attacker);
            context.assertTrue(attackerBody.tissueStates().get(1).fatigue() > 0, "左撇子副手没有消耗右臂");
            context.assertTrue(attackerBody.tissueStates().get(0).fatigue() == 0, "副手误消耗主手");
        } finally {
            attackEffectTarget = null;
        }
        var bothAttacker = context.makeMockPlayer(GameType.SURVIVAL);
        bindMuscles(bothAttacker, 1);
        var cow = context.spawn(EntityTypes.COW, TARGET_OFFSET, SPAWN_Y, TARGET_OFFSET);
        cow.hurtServer(context.getLevel(), customSource(context, bothAttacker, "two_strike"), STRIKE_DAMAGE);
        context.assertTrue(body(bothAttacker).tissueStates().stream().allMatch(tissue -> tissue.fatigue() > 0),
                "双手标签必须给两臂都记做功");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void taggedLegSourcesSelectLegsAndAimAnatomyGroup(GameTestHelper context) {
        var attacker = context.makeMockPlayer(GameType.SURVIVAL);
        attacker.setMainArm(HumanoidArm.LEFT);
        bindLegMuscles(attacker);
        var target = context.makeMockPlayer(GameType.SURVIVAL);
        bind(target, "human:adult", "head", MUSCLE_HP);

        var offHandSource = customSource(context, attacker, "leg_off_strike");
        context.assertTrue(offHandSource.is(VanillaDamageTypeTags.LEG), "腿法来源遗漏 leg 标签");
        context.assertTrue(VanillaDamageTypeTags.grip(offHandSource) == MuscleRules.Grip.OFF_HAND,
                "腿法副手标签没有取副手侧腿");
        float before = target.getHealth();
        target.hurt(offHandSource, STRIKE_DAMAGE);
        assertClose(context, displayedBodyLoss(target, before), STRIKE_DAMAGE,
                "腿法来源应保留普通伤害剂量");
        var attackerBody = body(attacker);
        context.assertTrue(attackerBody.tissueStates().stream()
                .filter(tissue -> tissue.id().value().startsWith("right_"))
                .allMatch(tissue -> tissue.fatigue() > 0), "副手侧腿没有原子记账");
        context.assertTrue(attackerBody.tissueStates().stream()
                .filter(tissue -> tissue.id().value().startsWith("left_"))
                .allMatch(tissue -> tissue.fatigue() == 0), "副手侧腿误消耗主手侧腿");

        var twoHandedAttacker = context.makeMockPlayer(GameType.SURVIVAL);
        bindLegMuscles(twoHandedAttacker);
        var twoHandedTarget = context.makeMockPlayer(GameType.SURVIVAL);
        bind(twoHandedTarget, "human:adult", "head", MUSCLE_HP);
        var twoHandedSource = customSource(context, twoHandedAttacker, "leg_two_strike");
        context.assertTrue(VanillaDamageTypeTags.grip(twoHandedSource) == MuscleRules.Grip.TWO_HANDED,
                "腿法双手标签没有合并两条腿");
        twoHandedTarget.hurt(twoHandedSource, STRIKE_DAMAGE);
        context.assertTrue(body(twoHandedAttacker).tissueStates().stream()
                .allMatch(tissue -> tissue.fatigue() > 0), "双腿标签没有给两条腿都记做功");

        // 该来源没有默认攻击者偏好，aim 仍应直接均匀命中声明的 anatomy 组。
        var aimAttacker = context.spawn(EntityTypes.COW, ATTACKER_OFFSET, SPAWN_Y, ATTACKER_OFFSET);
        bindLegMuscles(aimAttacker);
        var aimSource = customSource(context, aimAttacker, "leg_aim_strike");
        context.assertTrue(VanillaDamageTypeTags.aim(aimSource).orElseThrow().equals("head"),
                "aim/head 标签没有解析为头部组");
        context.assertTrue(VanillaDamageTypeTags.aim(aimSource).isPresent(), "腿法来源遗漏 aim 标签");
        target.invulnerableTime = 0;
        target.hurt(aimSource, STRIKE_DAMAGE);
        context.assertTrue(body(target).revision() == 2, "aim/head 没有完成固定部位结算");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void conflictingKindTagsAreRejectedOnLoadAndUse(GameTestHelper context) {
        // 临时复用真实注册表成员校验加载入口，完成后恢复标签，不污染其他测试。
        var registry = context.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        var original = registry.getTags().collect(java.util.stream.Collectors.toMap(
                tag -> tag.key(), tag -> tag.stream().toList()));
        var conflicting = new java.util.HashMap<>(original);
        var type = registry.getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE,
                Identifier.fromNamespaceAndPath("meridian_damage_test", "off_strike")));
        TagKey<DamageType> blunt = TagKey.create(Registries.DAMAGE_TYPE,
                Identifier.fromNamespaceAndPath("meridian", "kind/blunt"));
        conflicting.put(blunt, List.of(type));
        try {
            // 26.2 换标签走数据包重载的同一入口：先准备整套标签，再一次应用。
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.DAMAGE_TYPE, conflicting)).apply();
            assertKindConflict(context, () -> VanillaDamageTypeTags.validate(registry));
            assertKindConflict(context, () -> VanillaDamageTypeTags.kind(new DamageSource(type)));
        } finally {
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.DAMAGE_TYPE, original)).apply();
        }
        context.succeed();
    }

    private static void assertKindConflict(GameTestHelper context, Runnable action) {
        // 分别保护加载校验和运行时分类，冲突不能静默选择其中一种标签。
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        context.fail("致伤类型标签冲突未被拒绝");
    }

    private static DamageSource customSource(GameTestHelper context, LivingEntity attacker, String path) {
        // 来源完全由服务端注册表构造，客户端不能上报出手侧与伤害分类。
        var registry = context.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        var key = ResourceKey.create(Registries.DAMAGE_TYPE,
                Identifier.fromNamespaceAndPath("meridian_damage_test", path));
        return new DamageSource(registry.getOrThrow(key), attacker);
    }

    private static void bindMuscles(LivingEntity entity, double rightMultiplier) {
        // 两臂共用固定构型；零值表示右臂疲劳耗尽，正值只修改右臂力量。
        var left = new Anatomy.PartId("left_upper_arm");
        var right = new Anatomy.PartId("right_upper_arm");
        var leftMuscle = testMuscle("left_muscle", left);
        var rightMuscle = testMuscle("right_muscle", right);
        double rightFatigue = 0;
        if (rightMultiplier == 0) {
            rightFatigue = MUSCLE_HP;
        }
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(left, "左臂", MUSCLE_HP),
                new Anatomy.PartDefinition(right, "右臂", MUSCLE_HP)), List.of(leftMuscle, rightMuscle),
                List.of(), List.of(), List.of());
        var snapshot = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId(entity.getStringUUID()),
                new BodyTemplate.Reference(new DefinitionId("test:arms"), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(left, MUSCLE_HP), new AnatomyState.Part(right, MUSCLE_HP)),
                List.of(new AnatomyState.Tissue(leftMuscle.id(), MUSCLE_HP, 0),
                        new AnatomyState.Tissue(rightMuscle.id(), MUSCLE_HP, rightFatigue)),
                List.of(), List.of(), List.of(), new VitalityState(0, MUSCLE_ENDURANCE, 0, false /* dead */),
                new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0);
        var message = BodyProtobuf.toMessage(snapshot).toBuilder();
        if (rightMultiplier > 0) {
            message.addModifiers(strengthModifier(rightMultiplier));
        }
        STORE.registerBody(BodyProtobuf.fromMessage(message.build()));
    }

    private static void bindLegMuscles(LivingEntity entity) {
        var pelvis = new Anatomy.PartId("pelvis");
        var leftThigh = new Anatomy.PartId("left_thigh");
        var leftLowerLeg = new Anatomy.PartId("left_lower_leg");
        var rightThigh = new Anatomy.PartId("right_thigh");
        var rightLowerLeg = new Anatomy.PartId("right_lower_leg");
        var parts = List.of(pelvis, leftThigh, leftLowerLeg, rightThigh, rightLowerLeg);
        var tissues = List.of(
                testMuscle("left_gluteus_maximus", pelvis, "human:leg_muscle"),
                testMuscle("left_rectus_femoris", leftThigh, "human:leg_muscle"),
                testMuscle("left_gastrocnemius", leftLowerLeg, "human:leg_muscle"),
                testMuscle("right_gluteus_maximus", pelvis, "human:leg_muscle"),
                testMuscle("right_rectus_femoris", rightThigh, "human:leg_muscle"),
                testMuscle("right_gastrocnemius", rightLowerLeg, "human:leg_muscle"));
        var structure = new BodyStructure(parts.stream()
                .map(part -> new Anatomy.PartDefinition(part, part.value(), MUSCLE_HP)).toList(), tissues,
                List.of(), List.of(), List.of());
        var snapshot = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION,
                new BodyId(entity.getStringUUID()), new BodyTemplate.Reference(new DefinitionId("test:legs"), 1),
                structure, Realm.WANDERING_QI,
                parts.stream().map(part -> new AnatomyState.Part(part, MUSCLE_HP)).toList(),
                tissues.stream().map(tissue -> new AnatomyState.Tissue(tissue.id(), MUSCLE_HP, 0)).toList(),
                List.of(), List.of(), List.of(), new VitalityState(0, MUSCLE_ENDURANCE, 0, false /* dead */),
                new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0);
        STORE.registerBody(BodyProtobuf.fromMessage(BodyProtobuf.toMessage(snapshot)));
    }

    private static BodyMessages.CapabilityModifier strengthModifier(double multiplier) {
        // 常值曲线覆盖整个做功时段，不通过伪造做功结果模拟峰值。
        var start = BodyMessages.CurvePoint.newBuilder().setValue(1).build();
        var end = start.toBuilder().setOffsetMillis(MODIFIER_MILLIS).build();
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId("test:strength")
                .setSource("test:source")
                .setTissueId("right_muscle")
                .addCurve(start)
                .addCurve(end)
                .setStrengthPeak(multiplier)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .build();
    }

    private static Anatomy.TissueDefinition testMuscle(String id, Anatomy.PartId part) {
        return testMuscle(id, part, "human:arm_muscle");
    }

    private static Anatomy.TissueDefinition testMuscle(String id, Anatomy.PartId part, String tag) {
        // 不依赖 Human 内部类，组织选择只使用公开标签和 Framework 定义。
        var capacity = new Anatomy.TissueCapacity(MUSCLE_STRENGTH, MUSCLE_POWER, MUSCLE_ENDURANCE);
        return new Anatomy.TissueDefinition(new Anatomy.TissueId(id), "肌肉", new DefinitionId("test:muscle"),
                part, MUSCLE_HP, MUSCLE_HP, List.of(), capacity, 0, List.of(new DefinitionId(tag)));
    }

    /** GameTest 专用提供方，真实走 meridian_attack_effect entrypoint 和 Protobuf 校验。 */
    public static final class TestAttackEffectProvider implements AttackEffectProvider {
        @Override
        public byte[] exchange(byte[] payload) {
            var context = AttackEffectProtobuf.decodeContext(payload);
            if (context.getTargetSubjectId().equals(attackEffectFailureTarget)) {
                throw new IllegalStateException("test provider failure");
            }
            if (!context.getTargetSubjectId().equals(attackEffectTarget)) {
                var unhandled = AttackEffectMessages.AttackEffectPlan.newBuilder()
                        .setEffectId("test:unhandled")
                        .build();
                return AttackEffectProtobuf.encodePlan(unhandled);
            }
            ATTACK_EFFECT_CALLS.incrementAndGet();
            var status = AttackEffectMessages.StatusEffect.newBuilder()
                    .setEffectId("minecraft:strength")
                    .setDurationTicks(STATUS_DURATION_TICKS)
                    .setAmplifier(0)
                    .build();
            var plan = AttackEffectMessages.AttackEffectPlan.newBuilder()
                    .setHandled(true /* handled */)
                    .setExtraDamageHp(1)
                    .setEffectId("test:attack-cast")
                    .addStatusEffects(status)
                    .build();
            return AttackEffectProtobuf.encodePlan(plan);
        }
    }

    private static void bind(LivingEntity entity, String templateId, String partId, double hp) {
        // 与人体无关的单部位构型，专门保护原版桥接与框架账本之间的边界。
        var part = new Anatomy.PartId(partId);
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(part, "外壳", 100)),
                List.of(), List.of(), List.of(), List.of());
        STORE.registerBody(new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId(entity.getStringUUID()),
                new BodyTemplate.Reference(new DefinitionId(templateId), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(part, hp)), List.of(), List.of(), List.of(), List.of(),
                new VitalityState(0, 1000, 0, false), new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0));
        // 模拟玩家也必须先入世，治疗集成只处理服务器世界中已登记的实体。
        if (entity.level().getEntity(entity.getId()) != entity) {
            // 按实体编号错开位置，避免多个样本在原点堆积触发挤压伤害和无敌帧。
            entity.setPos(entity.getX() + entity.getId() * MOCK_PLAYER_SPACING, entity.getY(), entity.getZ());
            entity.level().addFreshEntity(entity);
        }
    }

    private static BodySnapshot body(LivingEntity entity) {
        // 断言直接读取核心的不可变事实，避免只验证桥接返回值。
        return STORE.find(new BodyId(entity.getStringUUID())).orElseThrow();
    }

    private static double displayedBodyLoss(LivingEntity entity, float previousHealth) {
        // 本测试的单部位上限为 100；原版血条只显示对应比例。
        return (previousHealth - entity.getHealth()) * 100.0 / entity.getMaxHealth();
    }

    private static void assertClose(GameTestHelper context, double actual, double expected, String message) {
        // 原版使用 float，身体使用 double，只容许对应的微小表示误差。
        context.assertTrue(Math.abs(actual - expected) < EPSILON,
                message + ": actual=" + actual + ", expected=" + expected);
    }

    private static double fatigueForVanillaDamage(double vanillaDamage) {
        // 单臂做功按原版剂量换算；功率与时长相乘后得到每次命中的焦耳数。
        double powerWatts = vanillaDamage * ARM_WORK_JOULES_PER_HP / ARM_STRIKE_SECONDS;
        double workJoules = powerWatts * ARM_STRIKE_SECONDS;
        return workJoules / MUSCLE_ENDURANCE * MUSCLE_HP;
    }
}
