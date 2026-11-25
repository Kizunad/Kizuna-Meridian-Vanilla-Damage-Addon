package dev.kizuna.meridian.vanilla.test;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttack;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.vanilla.test.DamageGameTest.UNBOUND_MOB;
import static dev.kizuna.meridian.vanilla.test.GuardTestHost.bind;
import static dev.kizuna.meridian.vanilla.test.GuardTestHost.body;

/** Damage 产生的阻挡事实必须写入攻击者自己的功法进度。 */
public final class BlockedAttackGameTest {
    private static final int DEFAULT_TIMEOUT = 100;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int ORIGIN = 2;
    private static final int ATTACKER_OFFSET = 4;
    private static final int TARGET_OFFSET = 2;
    private static final double BODY_HP = 12;
    private static final float BLOCKED_DAMAGE = 10;
    private static final float UNBLOCKED_DAMAGE = 1;
    private static final long GUARD_WARMUP_MILLIS = 200;
    private static final String GUARD_ID = "meridian_damage_test:guard";
    private static final String GUARD_DAMAGE_TYPE = "meridian_damage_test:head_neck_strike";

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shieldBlockRecordsShieldFactOnAttacker(GameTestHelper context) {
        Fixture fixture = fixture(context);
        fixture.target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        fixture.target.startUsingItem(InteractionHand.OFF_HAND);
        tickShield(fixture.target);
        fixture.target.hurtServer(fixture.level(), fixture.guardSource(), BLOCKED_DAMAGE);
        BlockedAttack blocked = blockedAttack(fixture.attacker);
        context.assertTrue(blocked.getSource().name().equals("SHIELD"),
                "盾挡没有写入 SHIELD 来源：" + blocked);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardBlockRecordsGuardFactOnAttacker(GameTestHelper context) {
        Fixture fixture = fixture(context);
        startGuard(context, fixture.target);
        fixture.target.hurtServer(fixture.level(), fixture.guardSource(), BLOCKED_DAMAGE);
        BlockedAttack blocked = blockedAttack(fixture.attacker);
        context.assertTrue(blocked.getSource().name().equals("GUARD"),
                "34 格挡没有写入 GUARD 来源：" + blocked);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void hardObjectBlockRecordsHardObjectFactOnAttacker(GameTestHelper context) {
        ServerPlayer attacker = TestPlayers.detached(context.getLevel(), "hard_object_block");
        bind(attacker, BODY_HP, BODY_HP);
        attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        attacker.snapTo(context.absoluteVec(new Vec3(2.5, ORIGIN, 1.5)), 0, 0);
        context.setBlock(new net.minecraft.core.BlockPos(2, 3, 3), Blocks.STONE);
        attacker.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        BlockedAttack blocked = blockedAttack(attacker);
        context.assertTrue(blocked.getSource().name().equals("HARD_OBJECT"),
                "硬物阻挡没有写入 HARD_OBJECT 来源：" + blocked);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unblockedAttackDoesNotCreateBlockedFact(GameTestHelper context) {
        Fixture fixture = fixture(context);
        fixture.target.hurtServer(fixture.level(), fixture.source(), UNBLOCKED_DAMAGE);
        context.assertTrue(!hasBlockedAttack(fixture.attacker), "未阻挡攻击错误写入阻挡事实");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void blockedFactsStayOnTheirOwnAttackers(GameTestHelper context) {
        Fixture first = fixture(context);
        Fixture second = fixture(context);
        first.target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        second.target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        first.target.startUsingItem(InteractionHand.OFF_HAND);
        second.target.startUsingItem(InteractionHand.OFF_HAND);
        tickShield(first.target);
        tickShield(second.target);
        first.target.hurtServer(first.level(), first.source(), BLOCKED_DAMAGE);
        second.target.hurtServer(second.level(), second.source(), BLOCKED_DAMAGE);
        context.assertTrue(hasBlockedAttack(first.attacker), "第一攻击者没有自己的阻挡事实");
        context.assertTrue(hasBlockedAttack(second.attacker), "第二攻击者没有自己的阻挡事实");
        context.assertTrue(!blockedAttack(first.attacker).getEventId()
                        .equals(blockedAttack(second.attacker).getEventId()),
                "不同攻击者复用了阻挡事件身份");
        context.succeed();
    }

    private static Fixture fixture(GameTestHelper context) {
        Player target = context.makeMockPlayer(GameType.SURVIVAL);
        var attacker = context.spawn(UNBOUND_MOB, ORIGIN, ORIGIN, ATTACKER_OFFSET);
        attacker.setNoAi(true /* noAi */);
        target.setPos(context.absoluteVec(new Vec3(TARGET_OFFSET, ORIGIN, TARGET_OFFSET)));
        bind(target, BODY_HP, BODY_HP);
        bind(attacker, BODY_HP, BODY_HP);
        return new Fixture(target, attacker);
    }

    private static void tickShield(Player target) {
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            target.tick();
        }
    }

    private static void startGuard(GameTestHelper context, Player target) {
        TechniqueReference technique = TechniqueReference.newBuilder()
                .setId(GUARD_ID)
                .setVersion(1)
                .build();
        CastRequest request = CastRequest.newBuilder()
                .setBodyId(target.getStringUUID())
                .setCastId("blocked-attack-guard")
                .setTechnique(technique)
                .build();
        CastOutcome outcome = TechniqueFabric.service().cast(target.getUUID(), request);
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "测试前臂格挡起手失败：" + outcome);
        GuardTestHost.advance(target, GUARD_WARMUP_MILLIS);
        TechniqueFabric.service().tick();
    }

    private static BlockedAttack blockedAttack(LivingEntity attacker) {
        return TechniqueState.read(body(attacker)).getBlockedAttack();
    }

    private static boolean hasBlockedAttack(LivingEntity attacker) {
        return TechniqueState.read(body(attacker)).hasBlockedAttack();
    }

    private record Fixture(Player target, LivingEntity attacker) {
        net.minecraft.server.level.ServerLevel level() {
            return (net.minecraft.server.level.ServerLevel) target.level();
        }

        net.minecraft.world.damagesource.DamageSource source() {
            return target.damageSources().mobAttack(attacker);
        }

        DamageSource guardSource() {
            var key = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(GUARD_DAMAGE_TYPE));
            var type = level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
            return new DamageSource(type, attacker);
        }
    }
}
