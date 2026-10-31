package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** 一次出手的做功前系数；嵌套攻击各自持有上下文，异常退出也释放。 */
public final class VanillaStrikeContext implements AutoCloseable {
    private static final Map<LivingEntity, Deque<VanillaStrikeContext>> strikes = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Long> pendingSwings = new IdentityHashMap<>();
    private static final long SWING_PACKET_GRACE_TICKS = 1;
    private final LivingEntity attacker;
    private final DamageSource source;
    private final double coefficient;

    private VanillaStrikeContext(LivingEntity attacker, DamageSource source) {
        // 在目标结算前读取一次身体并原子做功，后续所有目标复用结果。
        this.attacker = attacker;
        this.source = source;
        this.coefficient = VanillaDamageFabric.strikeCoefficient(attacker, source);
        strikes.computeIfAbsent(attacker, ignored -> new ArrayDeque<>()).push(this);
    }

    public static VanillaStrikeContext attack(LivingEntity attacker, DamageSource source) {
        // 客户端只预测动画，不读取服务端身体或记账。
        if (attacker.level().isClientSide()) {
            return null;
        }
        return new VanillaStrikeContext(attacker, source);
    }

    public static VanillaStrikeContext damage(DamageSource source) {
        // 直接调用 hurt 的自定义近战也建立独立出手，盾挡之前完成记账。
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker.level().isClientSide()
                || !source.is(VanillaDamageTypeTags.MELEE) || current(attacker, source) != null) {
            return null;
        }
        return new VanillaStrikeContext(attacker, source);
    }

    static double coefficient(LivingEntity attacker, DamageSource source) {
        // 原版攻击上下文或直接伤害上下文都应已在护甲阶段之前建立。
        VanillaStrikeContext context = current(attacker, source);
        if (context == null) {
            throw new IllegalStateException(VanillaDamageErrors.STRIKE_CONTEXT_MISSING);
        }
        return context.coefficient;
    }

    private static VanillaStrikeContext current(LivingEntity attacker, DamageSource source) {
        // 不同伤害类型的嵌套近战不能借用外层的手臂和系数。
        Deque<VanillaStrikeContext> stack = strikes.get(attacker);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        VanillaStrikeContext context = stack.peek();
        if (!context.source.typeHolder().equals(source.typeHolder())) {
            return null;
        }
        return context;
    }

    public static void attacked(ServerPlayer player) {
        // 实体攻击包后通常紧接主手挥手包，后者只播放动画而不重复收费。
        pendingSwings.put(player, player.level().getGameTime());
    }

    public static void swing(ServerPlayer player, InteractionHand hand) {
        // 没有对应实体攻击的主手挥手视为挥空出手，副手使用动画不计近战做功。
        if (hand != InteractionHand.MAIN_HAND) {
            return;
        }
        Long attackTick = pendingSwings.remove(player);
        long now = player.level().getGameTime();
        if (attackTick != null && now - attackTick <= SWING_PACKET_GRACE_TICKS) {
            return;
        }
        try (var ignored = attack(player, player.damageSources().playerAttack(player))) {
            // 没有受击目标，建立上下文本身已经完成出手记账。
        }
    }

    static void expireSwings(long now) {
        // 超过相邻包的窗口后释放实体引用，不让旧攻击吞掉后续挥空。
        pendingSwings.entrySet().removeIf(entry -> now - entry.getValue() > SWING_PACKET_GRACE_TICKS);
    }

    static void clear() {
        // 世界停止后不保留实体、调用栈或挥手包身份。
        strikes.clear();
        pendingSwings.clear();
    }

    @Override
    public void close() {
        // 包装入口的 finally 保证目标结算异常也不会污染下一次攻击。
        Deque<VanillaStrikeContext> stack = strikes.get(attacker);
        if (stack == null || stack.pop() != this) {
            throw new IllegalStateException(VanillaDamageErrors.STRIKE_CONTEXT_ORDER);
        }
        if (stack.isEmpty()) {
            strikes.remove(attacker);
        }
    }
}
