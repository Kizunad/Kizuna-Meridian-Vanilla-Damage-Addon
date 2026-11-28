package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueHitFeel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttackSource;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将原版阻挡解析成攻击者身体上的可信事实。 */
public final class BlockedAttackBridge {
    private static final double BLOCK_RAY_DISTANCE = 4.5D;
    private static final int MAX_BODY_LOOKUP_ATTEMPTS = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_blocked_attack");
    private static final CopyOnWriteArrayList<Consumer<BlockedAttackEvent>> OBSERVERS =
            new CopyOnWriteArrayList<>();

    private BlockedAttackBridge() {
        // 阻挡事实只允许由 Damage 结算入口或受信宿主发布。
    }

    public record BlockedAttackEvent(LivingEntity target, LivingEntity attacker,
                                     DamageSource damage, BlockedAttackSource source,
                                     String eventId, long blockedMillis) {
    }

    public static void registerObserver(Consumer<BlockedAttackEvent> observer) {
        OBSERVERS.add(Objects.requireNonNull(observer));
    }

    public static void warmDamageAttacker(DamageSource source) {
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker.level().isClientSide()) {
            return;
        }
        // 伤害回传在目标提交后执行，先确认攻击者宿主路由，避免首次查询错过
        // 回传窗口。
        TechniqueFabric.service().statusHost(attacker.getUUID());
    }

    static void observeDamage(VanillaDamageFabric.DamageEvent event) {
        LOGGER.info("damage target={} attacker={} guarded={} dose={}", event.target().getStringUUID(),
                event.attacker() == null ? "none" : event.attacker().getStringUUID(),
                event.input().guarded().isPresent(), event.input().damageAfterEffects());
        if (event.input().guarded().isPresent()) {
            submit(event.target(), event.attacker(), event.source(), BlockedAttackSource.GUARD, eventId());
        }
    }

    static boolean observeShield(LivingEntity target, LivingEntity attacker, DamageSource source) {
        LivingEntity resolvedAttacker;
        if (attacker != null) {
            resolvedAttacker = attacker;
        } else {
            resolvedAttacker = attacker(source);
        }
        return submit(target, resolvedAttacker, source, BlockedAttackSource.SHIELD, eventId());
    }

    static boolean isVanillaShield(LivingEntity target) {
        var blockingItem = target.getItemBlockingWith();
        return blockingItem != null && blockingItem.is(Items.SHIELD);
    }

    public static boolean observeHardObject(ServerPlayer attacker) {
        // 未被实体攻击、挖掘或交互抑制的主手挥手才可能是一次挥剑撞上硬物。
        if (attacker.getMainHandItem().isEmpty() || !attacker.getMainHandItem().is(ItemTags.SWORDS)) {
            return false;
        }
        HitResult hit = attacker.pick(BLOCK_RAY_DISTANCE, 1.0F, false /* includeFluids */);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return submit(null, attacker, null, BlockedAttackSource.HARD_OBJECT, eventId());
    }

    private static boolean submit(LivingEntity target, LivingEntity attacker, BlockedAttackSource source) {
        return submit(target, attacker, null, source, eventId());
    }

    private static boolean submit(LivingEntity target, LivingEntity attacker,
                                  DamageSource damage, BlockedAttackSource source, String eventId) {
        LOGGER.info("submit source={} target={} attacker={}", source,
                target == null ? "none" : target.getStringUUID(),
                attacker == null ? "none" : attacker.getStringUUID());
        if (attacker == null || attacker.level().isClientSide()) {
            LOGGER.info("submit rejected missing attacker or client");
            return false;
        }
        try {
            OptionalLong blockedMillis = bodyMillis(attacker);
            if (blockedMillis.isEmpty()) {
                TechniqueService.BlockedAttackObservation fallback = new TechniqueService.BlockedAttackObservation(
                        attacker.getUUID(), eventId, source, 0);
                return TechniqueFabric.service().recordBlockedAttack(fallback);
            }
            BlockedAttackEvent event = new BlockedAttackEvent(
                    target, attacker, damage, source, eventId, blockedMillis.orElseThrow());
            publish(event);
            TechniqueService.BlockedAttackObservation observation = new TechniqueService.BlockedAttackObservation(
                    attacker.getUUID(), event.eventId(), event.source(), event.blockedMillis());
            boolean recorded = TechniqueFabric.service().recordBlockedAttack(observation);
            LOGGER.info("submit recorded={} source={} attacker={}", recorded, source, attacker.getStringUUID());
            return recorded;
        } catch (RuntimeException rejected) {
            LOGGER.info("submit rejected source={} attacker={} reason={}", source, attacker.getStringUUID(),
                    rejected.toString());
            return false;
        }
    }

    private static OptionalLong bodyMillis(LivingEntity attacker) {
        TechniqueService service = TechniqueFabric.service();
        for (int attempt = 0; attempt < MAX_BODY_LOOKUP_ATTEMPTS; attempt++) {
            Optional<TechniqueHost> host = service.statusHost(attacker.getUUID());
            if (host.isEmpty()) {
                continue;
            }
            try {
                return OptionalLong.of(host.orElseThrow().inspect().body().settledAt().elapsedMillis());
            } catch (RuntimeException rejected) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

    private static void publish(BlockedAttackEvent event) {
        if (event.target() != null && event.attacker() != null) {
            TechniqueHitFeel.blocked(event.target(), event.attacker(), event.damage(), event.source());
        }
        for (Consumer<BlockedAttackEvent> observer : OBSERVERS) {
            try {
                observer.accept(event);
            } catch (RuntimeException ignored) {
                // 阻挡观察不能中断原版的防御和伤害结算。
            }
        }
    }

    private static LivingEntity attacker(DamageSource source) {
        return source.getEntity() instanceof LivingEntity living ? living : null;
    }

    private static String eventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
