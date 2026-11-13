package dev.kizuna.meridian.vanilla.fabric.client;

import dev.kizuna.meridian.vanilla.fabric.DamageHints;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

/** 客户端入口：盔甲的物品提示加一行「护住哪些部位」。 */
public final class VanillaDamageClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 原版盔甲没有自己的物品类，统一在提示回调里按标签加。
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) ->
                DamageHints.armorCoverage(stack).ifPresent(lines::add));
    }
}
