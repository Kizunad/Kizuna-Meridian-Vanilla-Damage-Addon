package dev.kizuna.meridian.vanilla.test;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.vanilla.AttackEffectProtobuf;
import dev.kizuna.meridian.vanilla.AttackEffectProvider;
import dev.kizuna.meridian.vanilla.protocol.pb.AttackEffectMessages;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 整体回归：真实 PlayerEntity/LivingEntity、Mixin、Fabric 死亡事件与核心账本。 */
public final class DamageGameTest implements FabricGameTest, BodyHealthHost {
    private static final InMemoryBodyStore STORE = new InMemoryBodyStore(List.of(), List.of());
    private static volatile String attackEffectTarget;
    private static volatile String attackEffectFailureTarget;
    private static final AtomicInteger ATTACK_EFFECT_CALLS = new AtomicInteger();
    private static final BodyCommandHandler<String> COMMANDS = new BodyCommandHandler<>(
            (caller, command) -> Optional.of(caller), (caller, command, body) -> true,
            (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(body.settledAt()))
                    .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build(),
            STORE, (caller, receipt, previous, updated) -> { /* 由断言读取权威提交。 */ });

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void mapsFinalLossAndBothDeathDirections(TestContext context) {
        // 玩家实现有独立 applyDamage，吸收生命挡住时身体必须完全不变。
        var player = context.createMockSurvivalPlayer();
        bind(player, "test:creature", "shell", 100);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 200, 0));
        player.damage(player.getDamageSources().generic(), 2);
        context.assertTrue(body(player).revision() == 0, "吸收生命不应产生身体伤害");

        // 未登记模板没有覆盖，即使穿胸甲也不能借用原版的全局护甲保护。
        player.timeUntilRegen = 0;
        player.removeStatusEffect(StatusEffects.ABSORPTION);
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        // 原版在实体 tick 应用装备属性；同一测试函数内穿装备后先完成该边界。
        player.tick();
        var attacker = context.spawnEntity(EntityType.ZOMBIE, 6, 2, 6);
        var meleeDamage = player.getDamageSources().mobAttack(attacker);
        float before = player.getHealth();
        player.damage(meleeDamage, 4);
        double lost = displayedBodyLoss(player, before);
        assertClose(context, lost, 4, "未覆盖部位不能享受全局护甲减伤");
        assertClose(context, body(player).partStates().get(0).hp(), 100 - lost,
                "玩家实际扣血必须映射一次");
        long revision = body(player).revision();
        player.damage(meleeDamage, 4);
        context.assertTrue(body(player).revision() == revision, "无敌帧不能重复伤害身体");

        // 外部死亡回调保留局部伤势；kill() 会先造成真实致死伤害，不适合验证此边界。
        player.timeUntilRegen = 0;
        double beforeDeath = body(player).partStates().get(0).hp();
        player.onDeath(player.getDamageSources().genericKill());
        context.assertTrue(body(player).vitality().dead(), "原版死亡必须通知身体");
        context.assertTrue(body(player).partStates().get(0).hp() > 0, "死亡确认应保留局部伤势");
        context.assertTrue(body(player).partStates().get(0).hp() == beforeDeath,
                "外部死亡确认不能伪造局部伤害");

        var cow = context.spawnEntity(EntityType.COW, 2, 2, 2);
        bind(cow, "test:creature", "shell", 100);
        cow.damage(cow.getDamageSources().generic(), 3);
        context.assertTrue(body(cow).partStates().get(0).hp() == 97, "普通生物扣血必须映射一次");

        // 身体先判死时，等待伤害返回后同步原版死亡，不再生成一笔伪造伤害。
        var fragile = context.spawnEntity(EntityType.COW, 4, 2, 4);
        bind(fragile, "test:creature", "shell", 1);
        fragile.damage(fragile.getDamageSources().generic(), 2);
        context.assertTrue(body(fragile).vitality().dead() && !fragile.isAlive(), "身体死亡必须同步原版实体");
        context.assertTrue(body(fragile).revision() == 1, "反向同步不能重复提交伤害或死亡");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void attackEffectProviderAddsDamageAndStatusOnce(TestContext context) {
        var attacker = context.spawnEntity(EntityType.ZOMBIE, 6, 2, 6);
        var target = context.createMockSurvivalPlayer();
        bind(attacker, "test:creature", "shell", 100);
        bind(target, "test:creature", "shell", 100);
        attackEffectTarget = target.getUuidAsString();
        ATTACK_EFFECT_CALLS.set(0);
        try {
            float before = target.getHealth();
            target.damage(target.getDamageSources().mobAttack(attacker), 2);
            assertClose(context, displayedBodyLoss(target, before), 3, "测试攻击附效应与原版伤害合并一次");
            assertClose(context, 100 - body(target).partStates().get(0).hp(), 3,
                    "附加伤害必须进入同一身体事务");
            context.assertTrue(target.hasStatusEffect(StatusEffects.STRENGTH),
                    "攻击附效状态应在 damage 返回后施加");

            target.timeUntilRegen = 0;
            before = target.getHealth();
            target.damage(target.getDamageSources().mobAttack(attacker), 2);
            assertClose(context, displayedBodyLoss(target, before), 2,
                    "同一 effect_id 对同一目标不能重复附加伤害");
            assertClose(context, 100 - body(target).partStates().get(0).hp(), 5,
                    "去重后原版伤害仍应正常提交");
            context.assertTrue(ATTACK_EFFECT_CALLS.get() == 2, "每次近战命中都应只询问一次测试提供方");
            context.complete();
        } finally {
            attackEffectTarget = null;
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void attackEffectProviderFailureKeepsOriginalDamage(TestContext context) {
        var attacker = context.spawnEntity(EntityType.ZOMBIE, 6, 2, 6);
        var target = context.createMockSurvivalPlayer();
        bind(attacker, "test:creature", "shell", 100);
        bind(target, "test:creature", "shell", 100);
        attackEffectFailureTarget = target.getUuidAsString();
        try {
            float before = target.getHealth();
            target.damage(target.getDamageSources().mobAttack(attacker), 2);
            assertClose(context, displayedBodyLoss(target, before), 2,
                    "提供方异常不能改变原版伤害剂量");
            assertClose(context, 100 - body(target).partStates().get(0).hp(), 2,
                    "提供方异常不能跳过原版身体事务");
            context.assertTrue(!target.hasStatusEffect(StatusEffects.STRENGTH),
                    "提供方异常不能施加附加状态");
            context.complete();
        } finally {
            attackEffectFailureTarget = null;
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void localArmorRetainsVanillaEffectsAndCooldown(TestContext context) {
        // 单部位样例使选点确定，依次验证每个默认槽位及没有覆盖的前臂。
        var attacker = context.spawnEntity(EntityType.ZOMBIE, 6, 2, 6);
        for (String part : List.of("head", "neck", "chest", "abdomen", "left_upper_arm", "pelvis",
                "left_thigh", "left_lower_leg", "left_foot", "left_forearm")) {
            var player = context.createMockSurvivalPlayer();
            bind(player, "human:adult", part, 100);
            player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            player.tick();
            EquipmentSlot slot = switch (part) {
                case "head", "neck" -> EquipmentSlot.HEAD;
                case "chest", "abdomen", "left_upper_arm" -> EquipmentSlot.CHEST;
                case "pelvis", "left_thigh", "left_lower_leg" -> EquipmentSlot.LEGS;
                case "left_foot" -> EquipmentSlot.FEET;
                default -> null;
            };
            float expected = 4;
            if (slot != null) {
                var armor = (ArmorItem) player.getEquippedStack(slot).getItem();
                expected = DamageUtil.getDamageLeft(4, armor.getProtection(), armor.getToughness());
            }
            float before = player.getHealth();
            var source = player.getDamageSources().mobAttack(attacker);
            player.damage(source, 4);
            assertClose(context, displayedBodyLoss(player, before), expected, part + " 只应使用覆盖该部位的单件护甲");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expected, part + " 身体剂量应一致");
            long revision = body(player).revision();
            player.damage(source, 4);
            context.assertTrue(body(player).revision() == revision, "无敌帧不应重新选点或扣血");
            float strongerExpected = 2;
            if (slot != null) {
                var stack = player.getEquippedStack(slot);
                var armor = (ArmorItem) stack.getItem();
                float baseDamage = DamageUtil.getDamageLeft(2, armor.getProtection(), armor.getToughness());
                // 前次受击已经磨损；满耐久至半耐久这一段每损耗 1 比例，减免系数下降 0.3。
                double durabilityMultiplier = 1 - 0.3 * stack.getDamage() / stack.getMaxDamage();
                strongerExpected = (float) (2 - (2 - baseDamage) * durabilityMultiplier);
            }
            before = player.getHealth();
            player.damage(source, 6);
            assertClose(context, displayedBodyLoss(player, before), strongerExpected, "无敌帧内更强攻击只结算超过前次的差额");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expected + strongerExpected,
                    "无敌帧差额只追加一次身体剂量");
        }

        var emptySlot = context.createMockSurvivalPlayer();
        bind(emptySlot, "human:adult", "head", 100);
        emptySlot.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        emptySlot.tick();
        float headBefore = emptySlot.getHealth();
        emptySlot.damage(emptySlot.getDamageSources().mobAttack(attacker), 4);
        assertClose(context, displayedBodyLoss(emptySlot, headBefore), 4, "未戴头盔的头部不能借用胸甲保护");

        // 护甲之后仍用原版保护附魔和抗性，局部吸收由同套治疗集成继续处理。
        var protectedPlayer = context.createMockSurvivalPlayer();
        bind(protectedPlayer, "human:adult", "head", 100);
        protectedPlayer.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        var enchantedChest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        enchantedChest.addEnchantment(Enchantments.PROTECTION, 4);
        protectedPlayer.equipStack(EquipmentSlot.CHEST, enchantedChest);
        protectedPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 200, 0));
        protectedPlayer.tick();
        protectedPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 200, 0));
        float before = protectedPlayer.getHealth();
        var source = protectedPlayer.getDamageSources().mobAttack(attacker);
        protectedPlayer.damage(source, 4);
        assertClose(context, displayedBodyLoss(protectedPlayer, before), 0, "局部黄心完全抵挡剩余剂量");
        assertClose(context, 100 - body(protectedPlayer).partStates().get(0).hp(), 0, "吸收不能留下局部伤势");

        protectedPlayer.timeUntilRegen = 0;
        protectedPlayer.removeStatusEffect(StatusEffects.ABSORPTION);
        protectedPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 200, 0));
        long revision = body(protectedPlayer).revision();
        protectedPlayer.damage(source, 4);
        context.assertTrue(body(protectedPlayer).revision() == revision, "局部护甲后完全吸收不能留下伤势");

        // 原版 bypass armor 标签依旧绕过装备，但不强制绕过其余减免。
        protectedPlayer.timeUntilRegen = 0;
        protectedPlayer.clearStatusEffects();
        protectedPlayer.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        before = protectedPlayer.getHealth();
        protectedPlayer.damage(protectedPlayer.getDamageSources().fall(), 2);
        assertClose(context, displayedBodyLoss(protectedPlayer, before), 2, "坠落必须保留绕过护甲的原版语义");

        // 物品自定义属性属于该件护甲，不能被材料的固定护甲值覆盖。
        var custom = context.createMockSurvivalPlayer();
        bind(custom, "human:adult", "head", 100);
        var customHelmet = new ItemStack(Items.DIAMOND_HELMET);
        customHelmet.addAttributeModifier(EntityAttributes.GENERIC_ARMOR,
                new EntityAttributeModifier(UUID.randomUUID(), "custom armor", 10,
                        EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.HEAD);
        custom.equipStack(EquipmentSlot.HEAD, customHelmet);
        custom.tick();
        before = custom.getHealth();
        custom.damage(custom.getDamageSources().mobAttack(attacker), 4);
        assertClose(context, displayedBodyLoss(custom, before), DamageUtil.getDamageLeft(4, 10, 0), "物品 NBT 护甲属性必须参与局部减伤");

        var husk = context.spawnEntity(EntityType.HUSK, 4, 2, 4);
        husk.setAiDisabled(true);
        bind(husk, "human:adult", "chest", 100);
        husk.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        husk.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        husk.tick();
        before = husk.getHealth();
        husk.damage(husk.getDamageSources().mobAttack(attacker), 4);
        assertClose(context, displayedBodyLoss(husk, before), DamageUtil.getDamageLeft(4, 8, 2), "普通生物也只读取命中槽位护甲");
        assertClose(context, 100 - body(husk).partStates().get(0).hp(), DamageUtil.getDamageLeft(4, 8, 2),
                "普通生物局部减伤后只提交一次");

        // 未绑定生物仍走原版全局护甲，不让这个 Addon 改写未接入身体的战斗规则。
        var unbound = context.createMockSurvivalPlayer();
        unbound.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        unbound.tick();
        before = unbound.getHealth();
        unbound.damage(unbound.getDamageSources().mobAttack(attacker), 4);
        assertClose(context, before - unbound.getHealth(), DamageUtil.getDamageLeft(4, 8, 2), "未绑定玩家应保留原版护甲");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void durabilityAndReductionCapUseHitArmorBeforeWear(TestContext context) {
        // 铁胸甲耐久 240，使各个默认端点和段中值都能由真实物品精确表示。
        var attacker = context.spawnEntity(EntityType.ZOMBIE, 6, 2, 6);
        double[][] samples = {{1, 1}, {0.75, 0.925}, {0.5, 0.85}, {0.375, 0.725},
                {0.25, 0.6}, {0.175, 0.475}, {0.1, 0.35}, {0.05, 0.175}, {0, 0}};
        for (double[] sample : samples) {
            var player = context.createMockSurvivalPlayer();
            bind(player, "human:adult", "chest", 100);
            var chestplate = new ItemStack(Items.IRON_CHESTPLATE);
            int maxDurability = chestplate.getMaxDamage();
            int initialDamage = maxDurability - (int) Math.round(maxDurability * sample[0]);
            chestplate.setDamage(initialDamage);
            player.equipStack(EquipmentSlot.CHEST, chestplate);
            player.tick();
            float before = player.getHealth();
            player.damage(player.getDamageSources().mobAttack(attacker), 4);
            double expectedDamage = 4 - 0.64 * sample[1];
            assertClose(context, displayedBodyLoss(player, before), expectedDamage, "耐久曲线须使用本次磨损前的耐久比例");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expectedDamage,
                    "耐久衰减后身体与原版应接收相同剂量");
            context.assertTrue(chestplate.isEmpty() || chestplate.getDamage() > initialDamage,
                    "新增耐久衰减不能取消原版装备磨损");
        }

        // 自定义高护甲在半耐久时基础减免为 80%×0.85，最后才限到 60%。
        for (double remainingRatio : new double[]{1, 0.5, 0.25}) {
            var player = context.createMockSurvivalPlayer();
            bind(player, "human:adult", "chest", 100);
            var chestplate = new ItemStack(Items.IRON_CHESTPLATE);
            chestplate.addAttributeModifier(EntityAttributes.GENERIC_ARMOR,
                    new EntityAttributeModifier(UUID.randomUUID(), "high local armor", 100,
                            EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.CHEST);
            chestplate.setDamage((int) Math.round(chestplate.getMaxDamage() * (1 - remainingRatio)));
            player.equipStack(EquipmentSlot.CHEST, chestplate);
            player.tick();
            float before = player.getHealth();
            player.damage(player.getDamageSources().mobAttack(attacker), 4);
            double expectedDamage = 1.6;
            if (remainingRatio == 0.25) {
                expectedDamage = 2.08;
            }
            assertClose(context, displayedBodyLoss(player, before), expectedDamage, "耐久衰减后才应用最终护甲减免上限");
            assertClose(context, 100 - body(player).partStates().get(0).hp(), expectedDamage,
                    "高护甲不能突破身体部位的减免上限");
        }

        // 不可损坏物品即使残留 Damage NBT，也按无耐久物品使用系数 1。
        var unbreakablePlayer = context.createMockSurvivalPlayer();
        bind(unbreakablePlayer, "human:adult", "chest", 100);
        var unbreakable = new ItemStack(Items.IRON_CHESTPLATE);
        unbreakable.setDamage(unbreakable.getMaxDamage() * 9 / 10);
        unbreakable.getOrCreateNbt().putBoolean("Unbreakable", true);
        unbreakablePlayer.equipStack(EquipmentSlot.CHEST, unbreakable);
        unbreakablePlayer.tick();
        float before = unbreakablePlayer.getHealth();
        unbreakablePlayer.damage(unbreakablePlayer.getDamageSources().mobAttack(attacker), 4);
        assertClose(context, displayedBodyLoss(unbreakablePlayer, before), 3.36, "无耐久物品不受耐久曲线削弱");
        assertClose(context, 100 - body(unbreakablePlayer).partStates().get(0).hp(), 3.36,
                "无耐久物品的身体剂量应一致");
        context.complete();
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 测试宿主仅绑定此测试登记的实体；其余实体保留原版行为。
        var request = HealthHostProtobuf.decodeRequest(payload);
        var response = HealthHostResponse.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId());
        var found = STORE.find(new BodyId(request.getSubjectId()));
        if (found.isEmpty()) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        if (request.hasEffect()) {
            var result = MeridianProtocol.decodeResponse(COMMANDS.handleHealthEffect("test", request.getEffect().toByteArray()));
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

    /** GameTest 专用提供方，真实走 meridian_attack_effect entrypoint 和 Protobuf 校验。 */
    public static final class TestAttackEffectProvider implements AttackEffectProvider {
        @Override
        public byte[] exchange(byte[] payload) {
            var context = AttackEffectProtobuf.decodeContext(payload);
            if (context.getTargetSubjectId().equals(attackEffectFailureTarget)) {
                throw new IllegalStateException("test provider failure");
            }
            if (!context.getTargetSubjectId().equals(attackEffectTarget)) {
                return AttackEffectProtobuf.encodePlan(AttackEffectMessages.AttackEffectPlan.getDefaultInstance());
            }
            ATTACK_EFFECT_CALLS.incrementAndGet();
            return AttackEffectProtobuf.encodePlan(AttackEffectMessages.AttackEffectPlan.newBuilder()
                    .setHandled(true).setExtraDamageHp(1).setEffectId("test:attack-cast")
                    .addStatusEffects(AttackEffectMessages.StatusEffect.newBuilder()
                            .setEffectId("minecraft:strength").setDurationTicks(20).setAmplifier(0)).build());
        }
    }

    private static void bind(LivingEntity entity, String templateId, String partId, double hp) {
        // 与人体无关的单部位构型，专门保护原版桥接与框架账本之间的边界。
        var part = new Anatomy.PartId(partId);
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(part, "外壳", 100)),
                List.of(), List.of(), List.of(), List.of());
        STORE.registerBody(new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId(entity.getUuidAsString()),
                new BodyTemplate.Reference(new DefinitionId(templateId), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(part, hp)), List.of(), List.of(), List.of(), List.of(),
                new VitalityState(0, 1000, 0, false), new TimeCursor(new DefinitionId("test:world"), "epoch", 0), 0));
    }

    private static BodySnapshot body(LivingEntity entity) {
        // 断言直接读取核心的不可变事实，避免只验证桥接返回值。
        return STORE.find(new BodyId(entity.getUuidAsString())).orElseThrow();
    }

    private static double displayedBodyLoss(LivingEntity entity, float previousHealth) {
        // 本测试的单部位上限为 100；原版血条只显示对应比例。
        return (previousHealth - entity.getHealth()) * 100.0 / entity.getMaxHealth();
    }

    private static void assertClose(TestContext context, double actual, double expected, String message) {
        // 原版使用 float，身体使用 double，只容许对应的微小表示误差。
        context.assertTrue(Math.abs(actual - expected) < 1e-5, message + ": actual=" + actual + ", expected=" + expected);
    }
}
