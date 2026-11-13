package dev.kizuna.meridian.vanilla.fabric;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** 伤害规则给玩家的说明：护甲提示里的覆盖部位，和第一次受伤时只说一次的那句。 */
public final class DamageHints {
    /** 说过以后记在玩家身上的原版实体标签；{@code /tag <玩家> remove <标签>} 可以让它再说一遍。 */
    public static final String FIRST_INJURY_TAG = "meridian.hint.damage.first_injury";
    public static final String FIRST_INJURY = "message.meridian_vanilla_damage.first_injury";
    public static final String ARMOR_HEAD = "tooltip.meridian_vanilla_damage.armor.head";
    public static final String ARMOR_CHEST = "tooltip.meridian_vanilla_damage.armor.chest";
    public static final String ARMOR_LEGS = "tooltip.meridian_vanilla_damage.armor.legs";
    public static final String ARMOR_FEET = "tooltip.meridian_vanilla_damage.armor.feet";
    /** 打开工作台的按键登记在库存界面框架里，这里只引用键名，显示玩家实际绑定的键。 */
    private static final String WORKSPACE_KEY = "key.kizuna_inventory_ui.workspace";

    private DamageHints() {
        // 只有静态入口。
    }

    /** 护甲按原版四个盔甲标签认定，和减伤规则用的是同一套；文字写的是默认配置的覆盖部位。 */
    public static Optional<Component> armorCoverage(ItemStack stack) {
        // 一件只护自己那一段，前臂和手没有护甲管。
        String key = null;
        if (stack.is(ItemTags.HEAD_ARMOR)) {
            key = ARMOR_HEAD;
        } else if (stack.is(ItemTags.CHEST_ARMOR)) {
            key = ARMOR_CHEST;
        } else if (stack.is(ItemTags.LEG_ARMOR)) {
            key = ARMOR_LEGS;
        } else if (stack.is(ItemTags.FOOT_ARMOR)) {
            key = ARMOR_FEET;
        }
        return Optional.ofNullable(key).map(Component::translatable);
    }

    static void injured(LivingEntity target) {
        // 玩家第一次受伤：红心和各部位血量是什么关系、什么时候会死、去哪看。
        if (!(target instanceof ServerPlayer player) || !player.addTag(FIRST_INJURY_TAG)) {
            return;
        }
        player.sendSystemMessage(Component.translatable(FIRST_INJURY, Component.keybind(WORKSPACE_KEY)));
    }
}
