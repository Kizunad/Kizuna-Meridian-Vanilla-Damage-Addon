package dev.kizuna.meridian.vanilla.fabric;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** 每次直接伤害独立进入上下文，支持嵌套调用和异常退出。 */
final class DamageScopes implements AutoCloseable {
    private static final List<VanillaDamageFabric.DamageScope> registered = new CopyOnWriteArrayList<>();
    private final LivingEntity entity;
    private final List<VanillaDamageFabric.DamageScope> entered = new ArrayList<>();

    private DamageScopes(LivingEntity entity) {
        // 实体引用仅存活于这次同步调用栈。
        this.entity = entity;
    }

    static void register(VanillaDamageFabric.DamageScope scope) {
        // 登记在初始化阶段完成，不能用空提供方表示缺少接线。
        registered.add(Objects.requireNonNull(scope));
    }

    static DamageScopes enter(LivingEntity entity) {
        // 进入中途失败也退出此前已进入的上下文，并保留原始异常。
        var frame = new DamageScopes(entity);
        try {
            for (var scope : registered) {
                scope.enter(entity);
                frame.entered.add(scope);
            }
            return frame;
        } catch (RuntimeException | Error failure) {
            try {
                frame.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        // 某个退出失败仍继续释放其他上下文，最后统一抛出而不静默吞错。
        Throwable failure = null;
        for (var scope : entered.reversed()) {
            try {
                scope.exit(entity);
            } catch (RuntimeException | Error rejected) {
                if (failure == null) {
                    failure = rejected;
                } else {
                    failure.addSuppressed(rejected);
                }
            }
        }
        entered.clear();
        if (failure instanceof RuntimeException rejected) {
            throw rejected;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
