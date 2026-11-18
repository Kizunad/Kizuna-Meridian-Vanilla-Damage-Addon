package dev.kizuna.meridian.vanilla.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Consumer;

/** 分开调用原版耐久判定与扣除，以便身体提交成功后才损坏兵器。 */
@Mixin(ItemStack.class)
public interface ItemDurabilityAccess {
    @Invoker("processDurabilityChange")
    int meridian$wear(int requested, ServerLevel level, ServerPlayer player);

    @Invoker("applyDamage")
    void meridian$applyWear(int damageValue, ServerPlayer player, Consumer<Item> onBreak);
}
