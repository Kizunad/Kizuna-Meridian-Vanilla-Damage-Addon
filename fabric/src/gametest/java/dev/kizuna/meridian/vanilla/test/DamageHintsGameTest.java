package dev.kizuna.meridian.vanilla.test;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.vanilla.fabric.DamageHints;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.kizuna.meridian.vanilla.test.DamageGameTestTicks.DEFAULT_TIMEOUT;

/** 给玩家看的说明：盔甲提示写明护住哪里，第一次受伤时讲一次红心和部位血量。 */
public final class DamageHintsGameTest {
    private static final float SMALL_HIT = 2;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void armorTooltipNamesTheCoveredParts(GameTestHelper context) {
        // 四个槽位各一句，语言文件里有对应的字；不是盔甲的穿戴物不加这一行。
        Map<Item, String> expected = Map.of(
                Items.IRON_HELMET, DamageHints.ARMOR_HEAD,
                Items.TURTLE_HELMET, DamageHints.ARMOR_HEAD,
                Items.DIAMOND_CHESTPLATE, DamageHints.ARMOR_CHEST,
                Items.LEATHER_LEGGINGS, DamageHints.ARMOR_LEGS,
                Items.GOLDEN_BOOTS, DamageHints.ARMOR_FEET);
        for (var entry : expected.entrySet()) {
            var line = DamageHints.armorCoverage(new ItemStack(entry.getKey()));
            context.assertTrue(line.isPresent() && key(line.get()).equals(entry.getValue()),
                    entry.getKey() + " 的提示不是 " + entry.getValue() + "：" + line);
            context.assertTrue(Language.getInstance().has(entry.getValue()), "语言文件缺 " + entry.getValue());
        }
        context.assertTrue(Language.getInstance().getOrDefault(DamageHints.ARMOR_HEAD).contains("neck"),
                "头盔那句没有写颈");
        for (Item plain : List.of(Items.ELYTRA, Items.CARVED_PUMPKIN, Items.IRON_SWORD)) {
            context.assertTrue(DamageHints.armorCoverage(new ItemStack(plain)).isEmpty(), plain + " 不该有护甲覆盖提示");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void firstInjuryIsExplainedOnce(GameTestHelper context) {
        // 第一次真的掉血时聊天里讲一次，键名按玩家绑定显示；再挨一下不再讲。
        var connection = new RecordingConnection(List.of());
        ServerPlayer player = player(context, connection);
        DamageGameTest.bind(player, "test:creature", "shell", 100);
        player.hurtServer(context.getLevel(), player.damageSources().generic(), SMALL_HIT);
        context.assertTrue(DamageGameTest.body(player).revision() > 0, "这一下没有伤到身体");
        List<TranslatableContents> told = injuries(connection);
        context.assertTrue(told.size() == 1, "第一次受伤没有说明，或说了不止一次：" + told.size());
        context.assertTrue(told.get(0).getArgs()[0] instanceof Component argument
                && argument.getContents() instanceof KeybindContents, "工作台的键名没有按玩家的绑定显示");
        context.assertTrue(player.entityTags().contains(DamageHints.FIRST_INJURY_TAG), "说过以后没有记在玩家身上");

        player.invulnerableTime = 0;
        long revision = DamageGameTest.body(player).revision();
        player.hurtServer(context.getLevel(), player.damageSources().generic(), SMALL_HIT);
        context.assertTrue(DamageGameTest.body(player).revision() > revision, "第二下没有伤到身体");
        context.assertTrue(injuries(connection).size() == 1, "第二次受伤又说明了一遍");
        player.discard();
        context.succeed();
    }

    private static ServerPlayer player(GameTestHelper context, RecordingConnection connection) {
        // 不进玩家列表的生存玩家，发给它的包都记在连接里。
        var level = context.getLevel();
        var profile = new GameProfile(UUID.randomUUID(), "damage_hint");
        var cookie = CommonListenerCookie.createInitial(profile, false /* transferred */);
        var player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
        new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        // 客户端上报加载完世界之前，原版视玩家无敌。
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        return player;
    }

    private static List<TranslatableContents> injuries(RecordingConnection connection) {
        // 只数聊天栏里那句首次受伤的说明。
        return connection.packets().stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && !chat.overlay())
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getContents())
                .filter(contents -> contents instanceof TranslatableContents translated
                        && translated.getKey().equals(DamageHints.FIRST_INJURY))
                .map(contents -> (TranslatableContents) contents)
                .toList();
    }

    private static String key(Component line) {
        // 提示行是一条可翻译文字，取它的语言键。
        return ((TranslatableContents) line.getContents()).getKey();
    }
}
