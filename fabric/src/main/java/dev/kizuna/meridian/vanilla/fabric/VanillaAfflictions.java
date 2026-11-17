package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.technique.fabric.world.TechniqueQuietEffect;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 挨打以后身上的两个状态：灼伤（持续掉血）和寒侵（减速）。都是不出粒子的状态效果，有图标和倒计时。
 *
 * <p>它们替掉了原来的点着和加冻结刻：不碰原版的着火刻、冻结刻，所以没有火焰贴图、不引燃方块、没有结霜画面。
 * 现在只有五行附伤会给，别的来源要用也是这两个。</p>
 */
public final class VanillaAfflictions {
    public static final Identifier BURN_ID = Identifier.fromNamespaceAndPath("meridian_vanilla_damage", "burn");
    public static final Identifier CHILL_ID = Identifier.fromNamespaceAndPath("meridian_vanilla_damage", "chill");
    /** 灼伤每隔这么多 tick 掉一次血。 */
    public static final int BURN_PULSE_TICKS = 10;
    /** 寒侵叠到这么多 tick 封顶（原版冻结刻封在 139，每 tick 退 2 刻）。 */
    public static final int CHILL_CAP_TICKS = 69;
    /** 寒侵剩这么多 tick 时减速最重；剩得少就按比例轻。 */
    public static final int CHILL_FULL_TICKS = 70;
    /** 最重时移速减去的量（加法，和原版细雪一样）：玩家基础移速 0.1，就是慢一半。 */
    public static final double CHILL_FULL_SLOW = 0.05;
    private static final int BURN_COLOR = 0xD9541E;
    private static final int CHILL_COLOR = 0x8FD3E8;
    private static final Map<LivingEntity, Burning> burning = new WeakHashMap<>();
    private static Holder<MobEffect> burn;
    private static Holder<MobEffect> chill;

    /** 一处灼伤：谁烧的、烧在哪个部位（没身体的目标为空）、每次掉多少原版 HP。只记在内存里。 */
    private record Burning(DamageSource source, Optional<String> partId, double pulseHp) { }

    private VanillaAfflictions() {
        // 两个效果在模组初始化时注册，两端一致。
    }

    static void register() {
        // 注册表冻结前登记。
        burn = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, BURN_ID, new Burn());
        chill = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, CHILL_ID, new Chill());
    }

    public static Holder<MobEffect> burnEffect() {
        return burn;
    }

    public static Holder<MobEffect> chillEffect() {
        return chill;
    }

    /**
     * 给目标挂上灼伤。
     *
     * @param partId  有身体的目标烧在这个部位；没身体的目标为空，走原版受伤
     * @param ticks   灼伤的时长；已经在灼伤时取剩余和它的较大者，不叠层
     * @param totalHp 这么长的一次灼伤一共掉的原版 HP
     */
    static void burn(LivingEntity target, DamageSource source, Optional<String> partId, int ticks, double totalHp) {
        // 点不着的（烈焰人这类）和喝了抗火药水的不挨。
        if (ticks <= 0 || target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return;
        }
        int pulses = Math.max(1, ticks / BURN_PULSE_TICKS);
        burning.put(target, new Burning(source, partId, totalHp / pulses));
        target.addEffect(quiet(burn, ticks));
    }

    /**
     * 给目标加一截寒侵。
     *
     * @param ticks 这一下加的时长；叠到 {@link #CHILL_CAP_TICKS} 封顶
     */
    static void chill(LivingEntity target, int ticks) {
        // 冻不了的（流浪者、雪傀儡这类，和穿着皮甲的）不挨，沿用原版的判断。
        if (ticks <= 0 || !target.canFreeze()) {
            return;
        }
        MobEffectInstance current = target.getEffect(chill);
        int before = 0;
        if (current != null) {
            before = current.getDuration();
        }
        int after = Math.min(before + ticks, CHILL_CAP_TICKS);
        if (after > before) {
            target.addEffect(quiet(chill, after));
            slow(target, after);
        }
    }

    static void forget(LivingEntity target) {
        // 目标死了，烧它的那条记录不用留。
        burning.remove(target);
    }

    static void clear() {
        // 世界停止后不保留实体引用。
        burning.clear();
    }

    private static MobEffectInstance quiet(Holder<MobEffect> effect, int ticks) {
        return new MobEffectInstance(effect, ticks, 0, true /* ambient */, false /* showParticles */,
                true /* showIcon */);
    }

    private static void slow(LivingEntity target, int remainingTicks) {
        // 同一个身份的修正每 tick 换成新的量；效果结束时原版按这个身份摘掉。
        AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        double amount = -CHILL_FULL_SLOW * Math.min(remainingTicks, CHILL_FULL_TICKS) / CHILL_FULL_TICKS;
        speed.addOrUpdateTransientModifier(new AttributeModifier(CHILL_ID, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    private static final class Burn extends TechniqueQuietEffect {
        private Burn() {
            super(MobEffectCategory.HARMFUL, BURN_COLOR);
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
            // 每 tick 都看一眼有没有进水；掉血的节拍在下面按实体自己的 tick 计数取。
            return true;
        }

        @Override
        public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
            // 泡在水里、淋着雨、陷在细雪里就灭了：返回 false，原版当场摘掉这个效果。
            if (entity.isInWaterOrRain() || entity.isInPowderSnow) {
                burning.remove(entity);
                return false;
            }
            // 按实体的 tick 计数取整十：任何连续 20 tick 里正好两次，刷新时长刷不出额外的掉血。
            Burning record = burning.get(entity);
            if (record != null && entity.tickCount % BURN_PULSE_TICKS == 0) {
                VanillaInfusions.dealAffliction(level, entity, record.source(), record.partId(), record.pulseHp());
            }
            // 最后一 tick 走完就把记录放掉，不让它一直拿着烧它的那个实体。
            MobEffectInstance instance = entity.getEffect(burn);
            if (instance == null || instance.getDuration() <= 1) {
                burning.remove(entity);
            }
            return true;
        }
    }

    private static final class Chill extends TechniqueQuietEffect {
        private Chill() {
            super(MobEffectCategory.HARMFUL, CHILL_COLOR);
            // 登记一条量为 0 的移速修正：真正的量由下面每 tick 写，效果结束时原版按这个身份把它摘掉。
            addAttributeModifier(Attributes.MOVEMENT_SPEED, CHILL_ID, 0, AttributeModifier.Operation.ADD_VALUE);
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
            // 减速的量跟着剩余时长每 tick 变。
            return true;
        }

        @Override
        public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
            // 剩得越少越轻，和原版冻结刻每 tick 退 2 刻时的减速逐 tick 一样。
            MobEffectInstance instance = entity.getEffect(chill);
            if (instance != null) {
                slow(entity, instance.getDuration());
            }
            return true;
        }
    }
}
