package dev.kizuna.meridian.vanilla.test;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge.CommitResult;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.LethalHitVeto;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

public final class DeathLootGameTest {
    private static final BlockPos SPAWN = new BlockPos(1, 1, 1);
    private static final float LETHAL = 10_000;
    private static final int DROP_RADIUS = 2;
    private static final int COPPER_CASH = 7;
    private static final int MAX_MELEE_HITS = 30;
    private static final Map<LivingEntity, Integer> DEATHS = new IdentityHashMap<>();
    private static final String DUEL = "meridian_damage_test.duel";
    private static final String SPARED = "meridian_damage_test.spared";

    static {
        LethalHitVeto.register(new LethalHitVeto() {
            public boolean protects(Hit hit) {
                return hit.target().entityTags().contains(DUEL) && hit.attacker() != null;
            }

            public void spared(Hit hit) {
                hit.target().addTag(SPARED);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
                DEATHS.computeIfPresent(entity, (victim, count) -> count + 1));
    }

    @GameTest
    public void meleeVetoLeavesLivingPlayer(GameTestHelper test) {
        withPlayer(test, player -> {
            player.addTag(DUEL);
            var attacker = test.spawn(EntityTypes.COW, SPAWN);
            var source = player.damageSources().mobAttack(attacker);
            for (int hit = 0; hit < MAX_MELEE_HITS && !player.entityTags().contains(SPARED); hit++) {
                player.invulnerableTime = 0;
                player.hurtServer(player.level(), source, LETHAL);
            }
            test.assertTrue(player.isAlive() && player.entityTags().contains(SPARED), "比试致死未转认输");
            test.assertTrue(DEATHS.get(player) == 0, "认输产生了死亡事件");
            assertInventory(test, player, 1, 0);
        });
    }

    @GameTest
    public void directVetoLeavesLivingOpponent(GameTestHelper test) {
        withPlayer(test, player -> {
            var mob = test.spawn(EntityTypes.ZOMBIE, SPAWN);
            mob.addTag(DUEL);
            mob.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            var result = directHit(mob, mob.damageSources().playerAttack(player));
            test.assertTrue(result.deathPrevented() && mob.isAlive() && mob.entityTags().contains(SPARED),
                    "直接伤害没有通知认输");
            test.assertTrue(mob.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "比试消费了图腾");
        });
    }

    @GameTest
    public void unclaimedLethalHitStillDrops(GameTestHelper test) {
        withPlayer(test, player -> withInventoryRule(player, InventoryRule.DROP, () -> {
            var attacker = test.spawn(EntityTypes.COW, SPAWN);
            directHit(player, player.damageSources().mobAttack(attacker));
            assertDeath(test, player);
            assertInventory(test, player, 0, 1);
        }));
    }

    @GameTest
    public void directDeathDropsInventoryOnce(GameTestHelper test) {
        withPlayer(test, player -> withInventoryRule(player, InventoryRule.DROP, () -> {
            directHit(player, player.damageSources().generic());
            assertInventory(test, player, 0, 1);
            assertDeath(test, player);
        }));
    }

    @GameTest
    public void directDeathKeepsInventory(GameTestHelper test) {
        withPlayer(test, player -> withInventoryRule(player, InventoryRule.KEEP, () -> {
            directHit(player, player.damageSources().generic());
            assertDeath(test, player);
            assertInventory(test, player, 1, 0);
        }));
    }

    @GameTest
    public void directTotemSavesPlayer(GameTestHelper test) {
        withPlayer(test, player -> withInventoryRule(player, InventoryRule.DROP, () -> {
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            var result = directHit(player, player.damageSources().generic());
            test.assertTrue(result.deathPrevented() && player.isAlive(), "图腾未救下玩家");
            test.assertTrue(player.getOffhandItem().isEmpty(), "图腾未消耗");
            test.assertTrue(DEATHS.get(player) == 0, "图腾保命仍触发死亡事件");
            assertInventory(test, player, 1, 0);
        }));
    }

    @GameTest
    public void meleeDeathDropsInventoryOnce(GameTestHelper test) {
        withPlayer(test, player -> withInventoryRule(player, InventoryRule.DROP, () -> {
            var attacker = test.spawn(EntityTypes.COW, SPAWN);
            var source = player.damageSources().mobAttack(attacker);
            for (int hit = 0; hit < MAX_MELEE_HITS && player.isAlive(); hit++) {
                player.invulnerableTime = 0;
                player.hurtServer(player.level(), source, LETHAL);
            }
            assertDeath(test, player);
            assertInventory(test, player, 0, 1);
        }));
    }

    @GameTest
    public void directMobDeathDropsEquipmentCoinsAndExperience(GameTestHelper test) {
        withPlayer(test, attacker -> {
            var mob = test.spawn(EntityTypes.ZOMBIE, SPAWN);
            var coin = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_currency:copper_cash"));
            var pack = MobInventories.open(mob);
            int inserted = pack.insert(new ItemStack(coin, COPPER_CASH));
            test.assertTrue(inserted == COPPER_CASH, "铜钱未入背囊");
            int balance = count(pack.snapshot(), coin);
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            mob.setGuaranteedDrop(EquipmentSlot.HEAD);
            DEATHS.put(mob, 0);
            try {
                directHit(mob, mob.damageSources().playerAttack(attacker));
                assertDeath(test, mob);
                var ground = drops(mob);
                test.assertTrue(count(ground, Items.CARVED_PUMPKIN) == 1, "原版装备掉落数量错");
                test.assertTrue(count(ground, coin) == balance, "背囊铜钱掉落数量错");
                test.assertTrue(count(pack.snapshot(), coin) == 0, "铜钱掉落后仍在背囊里");
                var area = mob.getBoundingBox().inflate(DROP_RADIUS);
                var experience = attacker.level().getEntitiesOfClass(ExperienceOrb.class, area);
                test.assertTrue(!experience.isEmpty(), "击杀未掉经验");
            } finally {
                DEATHS.remove(mob);
            }
        });
    }

    private static void withPlayer(GameTestHelper test, Consumer<ServerPlayer> check) {
        var level = test.getLevel();
        var connection = new RecordingConnection(List.of());
        var profile = new GameProfile(UUID.randomUUID(), "death_test");
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        TestPlayers.logIn(player, connection);
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.snapTo(Vec3.atBottomCenterOf(test.absolutePos(SPAWN)));
            player.getInventory().add(new ItemStack(Items.NETHER_STAR));
            DEATHS.put(player, 0);
            check.accept(player);
        } finally {
            DEATHS.remove(player);
            connection.disconnectFromClient();
        }
        test.succeed();
    }

    private enum InventoryRule { DROP, KEEP }

    private static void withInventoryRule(ServerPlayer player, InventoryRule rule, Runnable check) {
        var level = player.level();
        var rules = level.getGameRules();
        var server = level.getServer();
        boolean previous = rules.get(GameRules.KEEP_INVENTORY);
        try {
            rules.set(GameRules.KEEP_INVENTORY, rule == InventoryRule.KEEP, server);
            check.run();
        } finally {
            rules.set(GameRules.KEEP_INVENTORY, previous, server);
        }
    }

    private static CommitResult directHit(LivingEntity target, DamageSource source) {
        var hit = new VanillaDamageFabric.DirectDamage(target, source, "head", LETHAL);
        return VanillaDamageFabric.damageDirect(hit).orElseThrow();
    }

    private static void assertDeath(GameTestHelper test, LivingEntity target) {
        var body = VanillaDamageFabric.inspectBody(target).orElseThrow();
        test.assertTrue(body.getVitality().getDead() && target.getHealth() == 0, "身体或原版实体未死");
        VanillaDamageFabric.finishDamage(target);
        int deaths = DEATHS.get(target);
        test.assertTrue(deaths == 1, "死亡事件次数 " + deaths);
    }

    private static void assertInventory(GameTestHelper test, ServerPlayer player, int held, int ground) {
        test.assertTrue(player.getInventory().countItem(Items.NETHER_STAR) == held, "玩家背包数量错");
        test.assertTrue(count(drops(player), Items.NETHER_STAR) == ground, "玩家掉落数量错");
    }

    private static List<ItemStack> drops(LivingEntity entity) {
        var level = (ServerLevel) entity.level();
        var area = entity.getBoundingBox().inflate(DROP_RADIUS);
        return level.getEntitiesOfClass(ItemEntity.class, area).stream().map(ItemEntity::getItem).toList();
    }

    private static int count(List<ItemStack> stacks, Item item) {
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
