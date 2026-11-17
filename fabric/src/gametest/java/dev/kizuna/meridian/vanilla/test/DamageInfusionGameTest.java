package dev.kizuna.meridian.vanilla.test;

import com.google.protobuf.Any;
import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ElementalInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaAfflictions;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.mixin.HurtCooldownAccess;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.kizuna.meridian.vanilla.test.DamageGameTestTicks.DEFAULT_TIMEOUT;

/** 五行附伤接在原版伤害链上的边界：主击打进去才补一记，落点、伤害源、无敌帧、原版附带和命中声。 */
public final class DamageInfusionGameTest {
    private static final List<VanillaDamageFabric.DamageEvent> EVENTS = new ArrayList<>();
    private static final String TEST_NAMESPACE = "meridian_damage_test";
    private static final String HEAT = "infuse_heat";
    private static final String COLD = "infuse_cold";
    private static final String EARTH = "infuse_earth";
    private static final String STILL_STRIKE = "still_strike";
    private static final String TEMPLATE = "test:creature";
    private static final String SHELL = "shell";
    private static final String LIMB = "limb";
    private static final String CAST_ID = "infusion-test";
    private static final String TECHNIQUE_ID = "meridian_damage_test:infusion";
    private static final String ATTRIBUTE = "meridian:fire";
    private static final String HIT_SOUND = "minecraft:block.chain.place";
    private static final double HIT_VOLUME = 0.5;
    private static final double RATIO = 0.25;
    private static final long RAMP_MILLIS = 100;
    private static final long PLATEAU_END_MILLIS = 59_000;
    private static final long CURVE_END_MILLIS = 60_000;
    private static final long PLATEAU_CLOCK_MILLIS = 1_000;
    private static final long MUSCLE_CLOCK_MILLIS = 500;
    private static final double MUSCLE_BOOST = 1.3;
    private static final double PART_HP = 100;
    private static final double ENDURANCE = 1000;
    private static final double FRAGILE_HP = 3;
    private static final float STRIKE = 4;
    private static final float WEAKER_STRIKE = 3;
    private static final float LIGHT_STRIKE = 1;
    private static final float FANG_DAMAGE = 6;
    private static final int BURN_TICKS = 20;
    private static final double BURN_DAMAGE = 0.5;
    private static final int REFRESHED_PULSES = 3;
    private static final int CHILL_TICKS = 25;
    private static final int CHILL_HITS_TO_CAP = 3;
    private static final float STURDY_HEALTH = 40;
    private static final int CHILL_WAIT_TICKS = 5;
    private static final int OUTSIDE_TICKS = 100;
    private static final int SETTLE_TICKS = 3;
    private static final double EXTRA_KNOCKBACK = 0.3;
    private static final double HALF_RESISTANCE = 0.5;
    private static final int COOLDOWN_TICKS = 20;
    private static final int SHIELD_READY_TICKS = 6;
    private static final double SHIELD_DISTANCE = 1.5;
    private static final int PART_PROBES = 24;
    private static final int SPAWN_Y = 2;
    private static final int OWNER_X = 1;
    private static final int TARGET_X = 4;
    private static final int LANE_SPACING = 2;
    private static final double EPSILON = 1e-5;
    private static final double POSITION_TOLERANCE = 0.13;
    private static final List<String> LISTENER_CHANNELS = List.of("meridian:body_view",
            "meridian:gathering_response", "meridian:opening_response", "meridian_human:control",
            "meridian_progression:event", "kizuna_players_animation:play", "kizuna_players_animation:stop");

    static {
        // 观察方只读身体提交后的事实；主击与附伤各是一条，按目标筛出本用例的记录。
        VanillaDamageFabric.registerDamageObserver(EVENTS::add);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void bodiedTargetTakesInfusionOnTheSamePart(GameTestHelper context) {
        // 自定义近战类型带附伤：同一部位、本行伤害类型与致伤机制、记在主人名下；不击退、不改无敌帧。
        Zombie owner = infusedOwner(context, burning().build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity target = bodiedTarget(context, 0, PART_HP);
        Vec3 velocity = target.getDeltaMovement();
        target.hurtServer(context.getLevel(), source(context, owner, STILL_STRIKE), STRIKE);
        List<VanillaDamageFabric.DamageEvent> events = events(target);
        context.assertTrue(events.size() == 2, "主击之后应该恰好补一记附伤: " + events.size());
        VanillaDamageFabric.DamageEvent infusion = events.get(1);
        context.assertTrue(infusion.source().is(damageType(HEAT)), "附伤没有用本行伤害类型");
        context.assertTrue(infusion.input().kind() == DamageKind.HEAT, "附伤的致伤机制没有按 kind 标签");
        context.assertTrue(infusion.input().hit().equals(events.get(0).input().hit()), "附伤没有落在主击同一部位");
        context.assertTrue(infusion.attacker() == owner && infusion.source().getDirectEntity() == owner,
                "附伤的主人或直接实体不对");
        assertClose(context, infusion.input().rawDamage(), STRIKE * RATIO, "附伤剂量不是主击的比例");
        assertClose(context, DamageGameTest.body(target).partStates().get(0).hp(), PART_HP - STRIKE - STRIKE * RATIO,
                "身体没有扣掉主击加附伤");
        context.assertTrue(target.getDeltaMovement().equals(velocity), "附伤不应击退");
        context.assertTrue(target.invulnerableTime == COOLDOWN_TICKS, "附伤改写了无敌帧");
        assertClose(context, ((HurtCooldownAccess) target).meridian$lastHurt(), STRIKE, "附伤改写了上次伤害");
        MobEffectInstance burn = target.getEffect(VanillaAfflictions.burnEffect());
        context.assertTrue(burn != null && burn.getDuration() == BURN_TICKS, "灼火附伤没有给目标挂上灼伤");
        context.assertTrue(!burn.isVisible() && burn.showIcon(), "灼伤要不出粒子、有图标");
        context.assertTrue(target.getRemainingFireTicks() <= 0, "灼火附伤不该再把目标点着");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void doseIsTheMainHitAfterMuscle(GameTestHelper context) {
        // 剂量取过肌肉、未过护甲的主击：右臂力量 1.3 倍的僵尸打 4，附伤是 4 × 1.3 × 0.25。
        // 身体时钟停在 0.5 s：附行已在平台期，力量修正（覆盖 0–1 s）仍有效。
        Zombie owner = context.spawn(EntityTypes.ZOMBIE, OWNER_X, SPAWN_Y, 0);
        var body = DamageGameTest.armsBody(owner, MUSCLE_BOOST);
        body.getSettledAtBuilder().setElapsedMillis(MUSCLE_CLOCK_MILLIS);
        body.putAddonProgress(TechniqueState.KEY, infusionProgress(heat().build()));
        DamageGameTest.register(BodyProtobuf.fromMessage(body.build()));
        LivingEntity target = bodiedTarget(context, 0, PART_HP);
        target.hurtServer(context.getLevel(), owner.damageSources().mobAttack(owner), STRIKE);
        List<VanillaDamageFabric.DamageEvent> events = events(target);
        context.assertTrue(events.size() == 2, "有肌肉的主人主击之后没有附伤: " + events.size());
        assertClose(context, events.get(1).input().rawDamage(), STRIKE * MUSCLE_BOOST * RATIO,
                "附伤剂量没有取过肌肉后的主击");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unbodiedTargetTakesInfusionAndKeepsItsCooldown(GameTestHelper context) {
        // 没身体的目标走一次原版受伤，打完无敌帧状态放回主击之后，下一击的门槛不被压低。
        Zombie owner = infusedOwner(context, heat().build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity cow = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, 0);
        DamageSource melee = owner.damageSources().mobAttack(owner);
        float maxHealth = cow.getMaxHealth();
        cow.hurtServer(context.getLevel(), melee, STRIKE);
        assertClose(context, cow.getHealth(), maxHealth - STRIKE - STRIKE * RATIO, "无身体目标没有吃到附伤");
        context.assertTrue(cow.invulnerableTime == COOLDOWN_TICKS, "无敌帧没有放回主击之后的值");
        assertClose(context, ((HurtCooldownAccess) cow).meridian$lastHurt(), STRIKE, "上次伤害没有放回主击的值");
        float health = cow.getHealth();
        cow.hurtServer(context.getLevel(), melee, WEAKER_STRIKE);
        assertClose(context, cow.getHealth(), health, "附伤压低了下一击的无敌帧门槛");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void burnDealsItsTotalOverItsDurationOnTheStruckPart(GameTestHelper context) {
        // 灼伤 20 tick 一共掉 1 HP，分两次打在主击那个部位上，用附伤的伤害类型、记在主人名下；烧完图标自己没。
        Zombie owner = infusedOwner(context, burning().build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity target = bodiedTarget(context, 0, PART_HP);
        target.hurtServer(context.getLevel(), source(context, owner, STILL_STRIKE), STRIKE);
        double afterStrike = DamageGameTest.body(target).partStates().get(0).hp();
        context.runAfterDelay(BURN_TICKS + SETTLE_TICKS, () -> {
            List<VanillaDamageFabric.DamageEvent> pulses = events(target).subList(2, events(target).size());
            context.assertTrue(pulses.size() == BURN_TICKS / VanillaAfflictions.BURN_PULSE_TICKS,
                    "灼伤掉血的次数不对: " + pulses.size());
            for (VanillaDamageFabric.DamageEvent pulse : pulses) {
                context.assertTrue(pulse.source().is(damageType(HEAT)) && pulse.attacker() == owner,
                        "灼伤没有用附伤的伤害类型，或者没记在主人名下");
                context.assertTrue(pulse.input().hit().equals(events(target).get(0).input().hit()),
                        "灼伤没有烧在主击那个部位");
            }
            assertClose(context, DamageGameTest.body(target).partStates().get(0).hp(), afterStrike - BURN_DAMAGE,
                    "灼伤一共掉的血不是目录写的量");
            context.assertTrue(!target.hasEffect(VanillaAfflictions.burnEffect()), "灼伤到时间没有消掉");
            context.assertTrue(target.getRemainingFireTicks() <= 0, "灼伤期间目标被点着了");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void burnOnABodilessTargetGoesThroughVanillaHurt(GameTestHelper context) {
        // 没身体的牛：灼伤走原版受伤，20 tick 掉 1 HP，不被主击留下的无敌帧挡掉。
        Zombie owner = infusedOwner(context, burning().build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity cow = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, 0);
        cow.hurtServer(context.getLevel(), owner.damageSources().mobAttack(owner), STRIKE);
        float afterStrike = cow.getHealth();
        context.runAfterDelay(BURN_TICKS + SETTLE_TICKS, () -> {
            assertClose(context, cow.getHealth(), afterStrike - BURN_DAMAGE, "没身体的目标灼伤掉的血不对");
            context.assertTrue(cow.getRemainingFireTicks() <= 0, "没身体的目标被点着了");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void burnRefreshesWithoutStackingOrExtraDamage(GameTestHelper context) {
        // 灼伤中再挨一下：时长回到 20，不叠成 30；掉血按固定节拍，两次灼伤连成 30 tick，一共三次。
        Zombie owner = infusedOwner(context, burning().build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity target = bodiedTarget(context, 0, PART_HP);
        DamageSource strike = source(context, owner, STILL_STRIKE);
        target.hurtServer(context.getLevel(), strike, STRIKE);
        context.runAfterDelay(VanillaAfflictions.BURN_PULSE_TICKS, () -> {
            target.invulnerableTime = 0;
            target.hurtServer(context.getLevel(), strike, STRIKE);
            int duration = target.getEffect(VanillaAfflictions.burnEffect()).getDuration();
            context.assertTrue(duration == BURN_TICKS, "再挨一下，灼伤的时长应该回到 20: " + duration);
        });
        context.runAfterDelay(VanillaAfflictions.BURN_PULSE_TICKS + BURN_TICKS + SETTLE_TICKS, () -> {
            double pulseDamage = BURN_DAMAGE * VanillaAfflictions.BURN_PULSE_TICKS / BURN_TICKS;
            long pulses = events(target).stream()
                    .filter(event -> event.source().is(damageType(HEAT)))
                    .filter(event -> Math.abs(event.input().rawDamage() - pulseDamage) < EPSILON)
                    .count();
            context.assertTrue(pulses == REFRESHED_PULSES, "两次灼伤连成 30 tick，应该掉三次血: " + pulses);
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void burnSkipsFireproofTargetsAndGoesOutInWater(GameTestHelper context) {
        // 点不着的（烈焰人）和喝了抗火药水的不挨灼伤；泡在水里的下一 tick 就灭，一下也烧不着。
        Zombie owner = infusedOwner(context, burning().build(), PLATEAU_CLOCK_MILLIS);
        DamageSource strike = owner.damageSources().mobAttack(owner);
        LivingEntity blaze = context.spawn(EntityTypes.BLAZE, TARGET_X, SPAWN_Y, 0);
        LivingEntity potioned = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, lane(1));
        potioned.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DEFAULT_TIMEOUT));
        // 一格水，四面用玻璃围住，不流到别的样本脚下。
        BlockPos pool = new BlockPos(TARGET_X, SPAWN_Y, lane(2));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            context.setBlock(pool.relative(side), Blocks.GLASS);
        }
        context.setBlock(pool, Blocks.WATER);
        LivingEntity soaked = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, lane(2));
        context.runAfterDelay(SETTLE_TICKS, () -> {
            blaze.hurtServer(context.getLevel(), strike, STRIKE);
            potioned.hurtServer(context.getLevel(), strike, STRIKE);
            soaked.hurtServer(context.getLevel(), strike, STRIKE);
            float soakedHealth = soaked.getHealth();
            context.assertTrue(!blaze.hasEffect(VanillaAfflictions.burnEffect()), "点不着的生物挨了灼伤");
            context.assertTrue(!potioned.hasEffect(VanillaAfflictions.burnEffect()), "喝了抗火药水的挨了灼伤");
            context.runAfterDelay(BURN_TICKS + SETTLE_TICKS, () -> {
                context.assertTrue(!soaked.hasEffect(VanillaAfflictions.burnEffect()), "泡在水里灼伤没有灭");
                assertClose(context, soaked.getHealth(), soakedHealth, "泡在水里还是被灼伤掉了血");
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void chillStacksToTheCapSlowsByWhatIsLeftAndWearsOff(GameTestHelper context) {
        // 寒侵每中一下加 25 tick，封顶 69；减速按剩余时长算（加法），不动冻结刻；冻不了的不挨；到时间修正摘干净。
        Zombie owner = infusedOwner(context, cold().build(), PLATEAU_CLOCK_MILLIS);
        DamageSource strike = source(context, owner, STILL_STRIKE);
        LivingEntity once = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, 0);
        LivingEntity thrice = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, lane(1));
        // 牛只有 10 HP，三下主击加附伤会打死，打死了附带不落：这一头加厚到挨得住。
        thrice.getAttribute(Attributes.MAX_HEALTH).setBaseValue(STURDY_HEALTH);
        thrice.setHealth(STURDY_HEALTH);
        LivingEntity immune = context.spawn(EntityTypes.STRAY, TARGET_X, SPAWN_Y, lane(2));
        double baseSpeed = once.getAttributeValue(Attributes.MOVEMENT_SPEED);
        once.hurtServer(context.getLevel(), strike, STRIKE);
        for (int hit = 0; hit < CHILL_HITS_TO_CAP; hit++) {
            // 连着打三下：清掉无敌帧，免得后两下主击被挡、附伤跟着不落。
            thrice.invulnerableTime = 0;
            thrice.hurtServer(context.getLevel(), strike, STRIKE);
        }
        immune.hurtServer(context.getLevel(), strike, STRIKE);
        MobEffectInstance chill = once.getEffect(VanillaAfflictions.chillEffect());
        context.assertTrue(chill != null && chill.getDuration() == CHILL_TICKS, "寒水附伤没有挂上一截寒侵");
        context.assertTrue(!chill.isVisible() && chill.showIcon(), "寒侵要不出粒子、有图标");
        context.assertTrue(thrice.getEffect(VanillaAfflictions.chillEffect()).getDuration()
                == VanillaAfflictions.CHILL_CAP_TICKS, "寒侵没有封顶在 69 tick");
        context.assertTrue(!immune.hasEffect(VanillaAfflictions.chillEffect()), "冻不了的生物挨了寒侵");
        context.assertTrue(once.getTicksFrozen() == 0 && thrice.getTicksFrozen() == 0, "寒侵不该再动冻结刻");
        assertClose(context, once.getAttributeValue(Attributes.MOVEMENT_SPEED),
                baseSpeed - slow(CHILL_TICKS), "一下寒侵的减速不对");
        assertClose(context, thrice.getAttributeValue(Attributes.MOVEMENT_SPEED),
                baseSpeed - slow(VanillaAfflictions.CHILL_CAP_TICKS), "封顶寒侵的减速不对");
        context.runAfterDelay(CHILL_WAIT_TICKS, () -> {
            int left = once.getEffect(VanillaAfflictions.chillEffect()).getDuration();
            context.assertTrue(left < CHILL_TICKS, "寒侵没有在倒计时");
            // 减速在效果这一 tick 走之前按当时的剩余时长写，读到的剩余已经少了 1。
            assertClose(context, once.getAttributeValue(Attributes.MOVEMENT_SPEED), baseSpeed - slow(left + 1),
                    "寒侵的减速没有跟着剩余时长变轻");
        });
        context.runAfterDelay(CHILL_TICKS + SETTLE_TICKS, () -> {
            context.assertTrue(!once.hasEffect(VanillaAfflictions.chillEffect()), "寒侵到时间没有消掉");
            assertClose(context, once.getAttributeValue(Attributes.MOVEMENT_SPEED), baseSpeed, "寒侵消掉以后移速没有复原");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void afflictionsGivenFromOutsideStillShowNoParticles(GameTestHelper context) {
        // 指令 /effect give 给的状态默认带粒子：从外面这样给的灼伤、寒侵也照样不出粒子、有图标。
        LivingEntity cow = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, 0);
        for (var effect : List.of(VanillaAfflictions.burnEffect(), VanillaAfflictions.chillEffect())) {
            cow.addEffect(new MobEffectInstance(effect, OUTSIDE_TICKS, 0, false /* ambient */,
                    true /* showParticles */, true /* showIcon */));
            MobEffectInstance given = cow.getEffect(effect);
            context.assertTrue(given != null && !given.isVisible() && given.showIcon(),
                    "从外面带着粒子给的 " + effect.getRegisteredName() + " 也要不出粒子、有图标");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void earthInfusionPushesAwayFromTheDirectEntity(GameTestHelper context) {
        // 追加击退从直接实体推开，只算水平。
        Zombie earthOwner = infusedOwner(context, earth().build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity pushed = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, 0);
        pushed.hurtServer(context.getLevel(), source(context, earthOwner, STILL_STRIKE), STRIKE);
        Vec3 horizontal = pushed.getDeltaMovement().multiply(1, 0, 1);
        Vec3 away = pushed.position().subtract(earthOwner.position()).multiply(1, 0, 1);
        assertClose(context, horizontal.length(), EXTRA_KNOCKBACK, "震土追加击退的水平强度不对");
        context.assertTrue(horizontal.dot(away) > 0, "追加击退没有朝远离直接实体的方向");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void earthPushAddsOnTopOfTheMainKnockback(GameTestHelper context) {
        // 主击自带原版击退时，震土追加击退整份叠上去，净增 0.3，不被原版的速度减半吃掉；击退抗性照样折算。
        Zombie earthOwner = infusedOwner(context, earth().build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity infused = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, 0);
        Zombie plainOwner = context.spawn(EntityTypes.ZOMBIE, OWNER_X, SPAWN_Y, lane(1));
        LivingEntity control = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, lane(1));
        infused.hurtServer(context.getLevel(), earthOwner.damageSources().mobAttack(earthOwner), STRIKE);
        control.hurtServer(context.getLevel(), plainOwner.damageSources().mobAttack(plainOwner), STRIKE);
        double added = horizontalSpeed(infused) - horizontalSpeed(control);
        assertClose(context, added, EXTRA_KNOCKBACK, "主击击退之后，震土追加击退的净增量不对");

        LivingEntity sturdy = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, lane(2));
        sturdy.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(HALF_RESISTANCE);
        sturdy.hurtServer(context.getLevel(), source(context, earthOwner, STILL_STRIKE), STRIKE);
        double resisted = EXTRA_KNOCKBACK * (1 - HALF_RESISTANCE);
        assertClose(context, horizontalSpeed(sturdy), resisted, "追加击退没有按击退抗性折算");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void hitSoundPlaysAtTheTarget(GameTestHelper context) {
        // 命中声在目标脚下播放，附近玩家收到声音包；主人是敌对生物，类别归敌对。
        Zombie owner = infusedOwner(context, heat().setHitSound(hitSound()).build(), PLATEAU_CLOCK_MILLIS);
        LivingEntity target = bodiedTarget(context, 0, PART_HP);
        var connection = new RecordingConnection(LISTENER_CHANNELS.stream().map(Identifier::parse).toList());
        var listener = new ServerPlayer(context.getLevel().getServer(), context.getLevel(),
                new GameProfile(UUID.randomUUID(), "infusion_ear"), ClientInformation.createDefault());
        TestPlayers.logIn(listener, connection);
        try {
            listener.setGameMode(GameType.SPECTATOR);
            listener.setPos(target.position());
            connection.packets().clear();
            target.hurtServer(context.getLevel(), source(context, owner, STILL_STRIKE), STRIKE);
            List<ClientboundSoundPacket> heard = hitSounds(connection, target);
            context.assertTrue(heard.size() == 1, "目标处应该恰好有一次命中声: " + heard.size());
            ClientboundSoundPacket packet = heard.getFirst();
            context.assertTrue(packet.getSource() == SoundSource.HOSTILE, "敌对主人的命中声类别不对");
            assertClose(context, packet.getVolume(), HIT_VOLUME, "命中声音量不对");
        } finally {
            connection.disconnectFromClient();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void noInfusionWithoutActiveInfusionOrAfterExpiry(GameTestHelper context) {
        // 主人没身体、身体上没附行、附行已到期，以及箭这类投射物：都只有主击。
        LivingEntity plain = context.spawn(EntityTypes.ZOMBIE, OWNER_X, SPAWN_Y, 0);
        LivingEntity unlearned = context.spawn(EntityTypes.ZOMBIE, OWNER_X, SPAWN_Y, lane(1));
        DamageGameTest.bind(unlearned, TEMPLATE, SHELL, PART_HP);
        Zombie expired = infusedOwner(context, heat().build(), CURVE_END_MILLIS);
        Zombie archer = infusedOwner(context, heat().build(), PLATEAU_CLOCK_MILLIS);
        var arrow = new Arrow(context.getLevel(), archer, new ItemStack(Items.ARROW), null /* weapon */);
        List<DamageSource> sources = List.of(plain.damageSources().mobAttack(plain),
                unlearned.damageSources().mobAttack(unlearned), expired.damageSources().mobAttack(expired),
                archer.damageSources().arrow(arrow, archer));
        for (int index = 0; index < sources.size(); index++) {
            LivingEntity target = bodiedTarget(context, lane(index), PART_HP);
            target.hurtServer(context.getLevel(), sources.get(index), STRIKE);
            context.assertTrue(events(target).size() == 1, "第 " + index + " 种来源不该带附伤");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void summonsCarryTheirOwnersInfusion(GameTestHelper context) {
        // 恼鬼查一跳找到主人，尖牙的伤害源本来就记着主人；附伤的直接实体仍是恼鬼或尖牙。
        // 原版没主人的尖牙打的是 magic()；直接实体是尖牙但没记主人的来源也不带。
        Zombie owner = infusedOwner(context, heat().build(), PLATEAU_CLOCK_MILLIS);
        var vex = context.spawn(EntityTypes.VEX, OWNER_X, SPAWN_Y, lane(2));
        vex.setOwner(owner);
        LivingEntity vexTarget = bodiedTarget(context, 0, PART_HP);
        vexTarget.hurtServer(context.getLevel(), vex.damageSources().mobAttack(vex), STRIKE);
        assertInfused(context, vexTarget, owner, vex, STRIKE);

        LivingEntity fangTarget = bodiedTarget(context, lane(1), PART_HP);
        var fangs = fangsAt(fangTarget, owner);
        fangTarget.hurtServer(context.getLevel(), owner.damageSources().indirectMagic(fangs, owner), FANG_DAMAGE);
        assertInfused(context, fangTarget, owner, fangs, FANG_DAMAGE);

        LivingEntity ownerlessTarget = bodiedTarget(context, lane(2), PART_HP);
        var ownerless = fangsAt(ownerlessTarget, null);
        ownerlessTarget.hurtServer(context.getLevel(), owner.damageSources().indirectMagic(ownerless, null),
                FANG_DAMAGE);
        context.assertTrue(events(ownerlessTarget).size() == 1, "没主人的尖牙不该带附伤");
        LivingEntity magicTarget = bodiedTarget(context, lane(4), PART_HP);
        magicTarget.hurtServer(context.getLevel(), owner.damageSources().magic(), FANG_DAMAGE);
        context.assertTrue(events(magicTarget).size() == 1, "原版无主尖牙的 magic 伤害不该带附伤");

        owner.kill(context.getLevel());
        LivingEntity orphanTarget = bodiedTarget(context, lane(3), PART_HP);
        orphanTarget.hurtServer(context.getLevel(), vex.damageSources().mobAttack(vex), STRIKE);
        context.assertTrue(!owner.isAlive() && events(orphanTarget).size() == 1, "主人死了恼鬼还带附伤");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void noInfusionWhenMainHitKillsOrIsBlocked(GameTestHelper context) {
        // 主击打死目标、被盾整个挡下，都没有附伤；盾挡的主击只按原版走一次零伤害。
        Zombie owner = infusedOwner(context, heat().build(), PLATEAU_CLOCK_MILLIS);
        DamageSource melee = owner.damageSources().mobAttack(owner);
        LivingEntity fragile = bodiedTarget(context, 0, FRAGILE_HP);
        fragile.hurtServer(context.getLevel(), melee, STRIKE);
        context.assertTrue(!fragile.isAlive() && events(fragile).size() == 1, "主击已打死目标还补了附伤");

        Player defender = context.makeMockPlayer(GameType.SURVIVAL);
        DamageGameTest.bind(defender, TEMPLATE, SHELL, PART_HP);
        defender.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.startUsingItem(InteractionHand.OFF_HAND);
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            defender.tick();
        }
        defender.setYRot(0);
        owner.setPos(defender.position().add(0, 0, SHIELD_DISTANCE));
        float health = defender.getHealth();
        defender.hurtServer(context.getLevel(), melee, STRIKE);
        assertClose(context, defender.getHealth(), health, "盾没有实际挡住主击");
        boolean infused = events(defender).stream().anyMatch(event -> event.source().is(damageType(HEAT)));
        context.assertTrue(!infused, "盾整个挡下的主击不该有附伤");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void destroyedPartDropsTheInfusion(GameTestHelper context) {
        // 已毁部位照样能被主击打中，但附伤落不下去，这一记丢弃；打在完好部位上的照常补。
        Zombie owner = infusedOwner(context, heat().build(), PLATEAU_CLOCK_MILLIS);
        DamageSource melee = owner.damageSources().mobAttack(owner);
        LivingEntity target = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, 0);
        var parts = List.of(new AnatomyState.Part(new Anatomy.PartId(SHELL), PART_HP),
                new AnatomyState.Part(new Anatomy.PartId(LIMB), 0));
        DamageGameTest.register(body(target, parts, 0, Map.of()));
        int destroyedHits = 0;
        int intactHits = 0;
        for (int probe = 0; probe < PART_PROBES; probe++) {
            int before = events(target).size();
            target.invulnerableTime = 0;
            target.hurtServer(context.getLevel(), melee, LIGHT_STRIKE);
            List<VanillaDamageFabric.DamageEvent> all = events(target);
            List<VanillaDamageFabric.DamageEvent> added = all.subList(before, all.size());
            context.assertTrue(!added.isEmpty(), "第 " + probe + " 次主击没有提交");
            String part = added.getFirst().input().hit().orElseThrow().getPartId();
            if (part.equals(LIMB)) {
                destroyedHits++;
                context.assertTrue(added.size() == 1, "已毁部位上补了附伤");
            } else {
                intactHits++;
                context.assertTrue(added.size() == 2, "完好部位上没有附伤");
            }
        }
        context.assertTrue(destroyedHits > 0 && intactHits > 0,
                "抽样没有覆盖两种部位: destroyed=" + destroyedHits + ", intact=" + intactHits);
        context.succeed();
    }

    private static void assertInfused(GameTestHelper context, LivingEntity target, LivingEntity owner,
                                      Entity direct, float strike) {
        // 召唤物打出的附伤：剂量按主击，造成伤害的是主人，直接实体是召唤物。
        List<VanillaDamageFabric.DamageEvent> events = events(target);
        context.assertTrue(events.size() == 2, "召唤物的主击之后没有附伤: " + events.size());
        VanillaDamageFabric.DamageEvent infusion = events.get(1);
        context.assertTrue(infusion.attacker() == owner, "召唤物附伤没有记在主人名下");
        context.assertTrue(infusion.source().getDirectEntity() == direct, "召唤物附伤的直接实体不对");
        assertClose(context, infusion.input().rawDamage(), strike * RATIO, "召唤物附伤的剂量不对");
    }

    private static EvokerFangs fangsAt(LivingEntity target, LivingEntity owner) {
        // 尖牙不入世，只作伤害源的直接实体；放在目标脚下，不预热。
        return new EvokerFangs(target.level(), target.getX(), target.getY(), target.getZ(), 0, 0, owner);
    }

    private static Zombie infusedOwner(GameTestHelper context, ElementalInfusion infusion, long clockMillis) {
        // 主人身体直接带一个已生效的附行，起点 0；测试宿主不推进身体时钟，停在给定时刻。
        Zombie owner = context.spawn(EntityTypes.ZOMBIE, OWNER_X, SPAWN_Y, 0);
        Map<String, Any> addonProgress = Map.of(TechniqueState.KEY, infusionProgress(infusion));
        var parts = List.of(new AnatomyState.Part(new Anatomy.PartId(SHELL), PART_HP));
        DamageGameTest.register(body(owner, parts, clockMillis, addonProgress));
        return owner;
    }

    private static Any infusionProgress(ElementalInfusion infusion) {
        // 功法进度里只有一个从 0 起算的附行，不走施放。
        ActiveInfusion active = ActiveInfusion.newBuilder()
                .setCastId(CAST_ID)
                .setTechniqueId(TECHNIQUE_ID)
                .setStartedMillis(0)
                .setInfusion(infusion)
                .build();
        TechniqueProgress progress = TechniqueState.empty().toBuilder().setActiveInfusion(active).build();
        return TechniqueState.pack(progress);
    }

    private static double horizontalSpeed(LivingEntity entity) {
        // 击退只看水平分量，竖直方向有重力和原版击退的上抬。
        return entity.getDeltaMovement().horizontalDistance();
    }

    private static int lane(int index) {
        // 同一用例里的样本沿 z 方向隔两格排开，互不挤压。
        return index * LANE_SPACING;
    }

    private static LivingEntity bodiedTarget(GameTestHelper context, int z, double hp) {
        // 单部位的牛：没有护甲覆盖，扣多少就是主击与附伤的原值。
        LivingEntity target = context.spawn(EntityTypes.COW, TARGET_X, SPAWN_Y, z);
        DamageGameTest.bind(target, TEMPLATE, SHELL, hp);
        return target;
    }

    private static BodySnapshot body(LivingEntity entity, List<AnatomyState.Part> parts, long clockMillis,
                                     Map<String, Any> addonProgress) {
        // 与 DamageGameTest 同一个测试模板，部位上限都是 100，只换部位、时钟和附属进度。
        List<Anatomy.PartDefinition> definitions = parts.stream()
                .map(part -> new Anatomy.PartDefinition(part.id(), "外壳", PART_HP))
                .toList();
        var structure = new BodyStructure(definitions, List.of(), List.of(), List.of(), List.of());
        var template = new BodyTemplate.Reference(new DefinitionId(TEMPLATE), 1);
        var clock = new TimeCursor(new DefinitionId("test:world"), "epoch", clockMillis);
        return new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId(entity.getStringUUID()), template,
                structure, Realm.WANDERING_QI, parts, List.of(), List.of(), List.of(), List.of(),
                new VitalityState(0, ENDURANCE, 0, false /* dead */), clock, 0, List.of(), null, addonProgress);
    }

    private static ElementalInfusion.Builder heat() {
        // 灼火样本：0.1 s 爬满，平台到 59 s，60 s 到期；附伤是主击的四分之一。
        return infusion(HEAT);
    }

    private static ElementalInfusion.Builder burning() {
        // 灼火样本带灼伤：20 tick 一共掉 1 HP。
        return heat().setBurnTicks(BURN_TICKS).setBurnDamage(Quantity.newBuilder()
                .setValue(BURN_DAMAGE).setUnit(Unit.DAMAGE_POINT));
    }

    private static ElementalInfusion.Builder cold() {
        // 寒水样本：每记附伤加 25 tick 寒侵。
        return infusion(COLD).setChillTicks(CHILL_TICKS);
    }

    private static double slow(int remainingTicks) {
        // 寒侵减掉的移速：剩 70 tick 以上减 0.05，剩得少按比例。
        return VanillaAfflictions.CHILL_FULL_SLOW * Math.min(remainingTicks, VanillaAfflictions.CHILL_FULL_TICKS)
                / VanillaAfflictions.CHILL_FULL_TICKS;
    }

    private static ElementalInfusion.Builder earth() {
        // 震土样本：每记附伤另加 0.3 的原版击退。
        return infusion(EARTH).setExtraKnockback(ratio(EXTRA_KNOCKBACK));
    }

    private static ElementalInfusion.Builder infusion(String damageType) {
        // 各样本只换伤害类型与附带，曲线和比例相同。
        return ElementalInfusion.newBuilder()
                .setAttribute(ATTRIBUTE)
                .setDamageTypeId(TEST_NAMESPACE + ":" + damageType)
                .setRatio(ratio(RATIO))
                .addCurve(point(0, 0))
                .addCurve(point(RAMP_MILLIS, 1))
                .addCurve(point(PLATEAU_END_MILLIS, 1))
                .addCurve(point(CURVE_END_MILLIS, 0));
    }

    private static SoundCue hitSound() {
        // 命中声对准目标，不抖音调，便于核对音量。
        return SoundCue.newBuilder()
                .setSoundId(HIT_SOUND)
                .setVolume(ratio(HIT_VOLUME))
                .setAnchor(SoundAnchor.TARGET)
                .build();
    }

    private static CurvePoint point(long millis, double value) {
        // 附行曲线沿用 Framework 的曲线点协议。
        return CurvePoint.newBuilder()
                .setOffsetMillis(millis)
                .setValue(value)
                .build();
    }

    private static Quantity ratio(double value) {
        // 附行的比例、击退与音量都是无量纲值。
        return Quantity.newBuilder()
                .setValue(value)
                .setUnit(Unit.RATIO)
                .build();
    }

    private static DamageSource source(GameTestHelper context, LivingEntity attacker, String path) {
        // 测试伤害类型由本测试模组的数据包注册，造成伤害的实体就是攻击者本人。
        var registry = context.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getOrThrow(damageType(path)), attacker);
    }

    private static ResourceKey<DamageType> damageType(String path) {
        // 测试伤害类型都在本测试模组的命名空间下。
        return ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(TEST_NAMESPACE, path));
    }

    private static List<VanillaDamageFabric.DamageEvent> events(LivingEntity target) {
        // 观察记录跨用例累积，只取打在这个目标身上的。
        return EVENTS.stream().filter(event -> event.target() == target).toList();
    }

    private static List<ClientboundSoundPacket> hitSounds(RecordingConnection connection, LivingEntity target) {
        // 只认命中声的事件 ID，并且坐标在目标脚下；声音包坐标按 float 解码，容差随坐标放大。
        return connection.packets().stream()
                .filter(ClientboundSoundPacket.class::isInstance)
                .map(ClientboundSoundPacket.class::cast)
                .filter(packet -> packet.getSound().value().location().toString().equals(HIT_SOUND))
                .filter(packet -> near(packet.getX(), target.getX()) && near(packet.getY(), target.getY())
                        && near(packet.getZ(), target.getZ()))
                .toList();
    }

    private static boolean near(double received, double expected) {
        // GameTest 结构常在很远的坐标上，float 的最小步长可能接近一格。
        return Math.abs(received - expected) < POSITION_TOLERANCE + Math.ulp((float) expected);
    }

    private static void assertClose(GameTestHelper context, double actual, double expected, String message) {
        // 原版用 float，身体用 double，只容许表示误差。
        context.assertTrue(Math.abs(actual - expected) < EPSILON,
                message + ": actual=" + actual + ", expected=" + expected);
    }
}
