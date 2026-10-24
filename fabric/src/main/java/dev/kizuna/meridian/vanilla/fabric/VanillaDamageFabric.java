package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.BodyDamageIntegration;
import dev.kizuna.meridian.vanilla.fabric.mixin.TotemAccess;
import dev.kizuna.meridian.vanilla.ArmorRules;
import dev.kizuna.meridian.vanilla.AttackEffectProvider;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import dev.kizuna.meridian.vanilla.VanillaDamageKinds;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.vanilla.protocol.pb.AttackEffectMessages;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** 在原版护甲阶段固定部位，保留后续非护甲结算，并向身体提交一次伤害。 */
public final class VanillaDamageFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_vanilla_damage");
    private static final Map<LivingEntity, DamageSource> pendingDeaths = new IdentityHashMap<>();
    private static final Map<LivingEntity, Deque<AttackEffectMessages.StatusEffect>> pendingStatusEffects = new IdentityHashMap<>();
    private static final Map<LivingEntity, Deque<DamageFrame>> applications = new IdentityHashMap<>();
    private static VanillaDamageBridge bridge;

    @Override
    public void onInitialize() {
        // 服务入口是可信进程内协议，不为客户端开放任意伤害网络包。
        var hosts = FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class);
        var preferences = DamagePreferences.load(FabricLoader.getInstance().getConfigDir()
                .resolve("meridian-vanilla-damage.toml"));
        var integrations = FabricLoader.getInstance().getEntrypoints("meridian_damage_integration", BodyDamageIntegration.class);
        var attackEffectProviders = FabricLoader.getInstance().getEntrypoints(
                AttackEffectProvider.ENTRYPOINT, AttackEffectProvider.class);
        bridge = new VanillaDamageBridge(hosts, preferences, integrations, attackEffectProviders);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> confirmDeath(entity));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // 服务器生命周期结束后不持有旧实体与未完成调用的临时引用。
            pendingDeaths.clear();
            pendingStatusEffects.clear();
            applications.clear();
            bridge.clearAttackEffectState();
        });
    }

    public static void beginApplication(LivingEntity entity) {
        // 每层 applyDamage 持有自己的命中；嵌套伤害不能覆盖外层已选定的部位。
        if (entity.getWorld().isClient) {
            return;
        }
        applications.computeIfAbsent(entity, ignored -> new ArrayDeque<>()).push(new DamageFrame());
    }

    public static void endApplication(LivingEntity entity) {
        // 完全抵挡或被吸收的调用也会返回，此时丢弃尚未提交的命中上下文。
        Deque<DamageFrame> frames = applications.get(entity);
        if (frames == null) {
            return;
        }
        frames.pop();
        if (frames.isEmpty()) {
            applications.remove(entity);
        }
    }

    public static Optional<Float> prepareArmor(LivingEntity entity, DamageSource source, float rawDamage) {
        // 原版在进入这里前已处理无敌帧与盾；未绑定身体时继续原版全局护甲。
        Deque<DamageFrame> frames = applications.get(entity);
        if (frames == null || rawDamage <= 0) {
            return Optional.empty();
        }
        String sourceId = source.getTypeRegistryEntry().getKey().map(key -> key.getValue().toString())
                .orElse("meridian_vanilla:unregistered");
        Entity attacker = source.getAttacker();
        String attackerId = "minecraft:unknown";
        boolean edgedWeapon = false;
        if (attacker != null) {
            attackerId = Registries.ENTITY_TYPE.getId(attacker.getType()).toString();
            if (attacker instanceof LivingEntity living) {
                var weapon = living.getMainHandStack().getItem();
                edgedWeapon = weapon instanceof SwordItem || weapon instanceof AxeItem;
            }
        }

        try {
            DamageKind kind = VanillaDamageKinds.classify(sourceId, attackerId, edgedWeapon);
            Optional<VanillaDamageBridge.PreparedHit> prepared = bridge.prepare(entity.getUuidAsString(), sourceId, attackerId,
                    kind, entity.getRandom().nextDouble());
            if (prepared.isEmpty()) {
                return Optional.empty();
            }
            VanillaDamageBridge.PreparedHit hit = prepared.get();
            VanillaDamageBridge.AttackEffectResolution attackEffect = null;
            boolean melee = isMeleeSource(sourceId);
            if (melee && attacker instanceof LivingEntity livingAttacker) {
                try {
                    attackEffect = bridge.prepareAttackEffect(entity.getUuidAsString(), livingAttacker.getUuidAsString(),
                            sourceId, kind, rawDamage, true, entity.getWorld().getTime()).orElse(null);
                    if (attackEffect != null && attackEffect.truncated()) {
                        LOGGER.warn("{} target={} source={} rawHp={} extraHp={} maxRatio={}",
                                VanillaDamageErrors.ATTACK_EFFECT_PLAN, entity.getUuidAsString(), sourceId,
                                rawDamage, attackEffect.extraDamageHp(), "configured");
                    }
                } catch (RuntimeException failure) {
                    // 攻击方附效是可选增益；提供方故障不能旁路原版伤害。
                    LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_PROVIDER + " target="
                            + entity.getUuidAsString() + " source=" + sourceId, failure);
                    attackEffect = null;
                }
            }
            double rawWithAttackEffect = rawDamage;
            if (attackEffect != null) {
                rawWithAttackEffect += attackEffect.extraDamageHp();
                if (!Double.isFinite(rawWithAttackEffect) || rawWithAttackEffect <= 0
                        || rawWithAttackEffect > Float.MAX_VALUE) {
                    LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_PLAN + " target=" + entity.getUuidAsString()
                            + " source=" + sourceId + " rawHp=" + rawDamage, new IllegalArgumentException());
                    attackEffect = null;
                    rawWithAttackEffect = rawDamage;
                }
            }
            float remainingDamage = (float) rawWithAttackEffect;
            if (!source.isIn(DamageTypeTags.BYPASSES_ARMOR)) {
                // 只读取正确槽位中这件护甲的数值，不读取实体全身属性或其他装备。
                Optional<ArmorRules.Slot> coveredSlot = hit.coveredSlot();
                if (coveredSlot.isPresent()) {
                    EquipmentSlot slot = switch (coveredSlot.get()) {
                        case HEAD -> EquipmentSlot.HEAD;
                        case CHEST -> EquipmentSlot.CHEST;
                        case LEGS -> EquipmentSlot.LEGS;
                        case FEET -> EquipmentSlot.FEET;
                    };
                    var stack = entity.getEquippedStack(slot);
                    if (stack.getItem() instanceof ArmorItem armor && armor.getSlotType() == slot) {
                        // 读取本次磨损前的剩余比例；无耐久及不可损坏物品使用固定系数 1。
                        OptionalDouble remainingDurability = OptionalDouble.empty();
                        if (stack.isDamageable()) {
                            double remainingRatio = 1 - stack.getDamage() / (double) stack.getMaxDamage();
                            remainingDurability = OptionalDouble.of(Math.max(0, Math.min(1, remainingRatio)));
                        }
                        remainingDamage = (float) hit.remainingVanillaDamage(rawWithAttackEffect,
                                armorAttribute(stack, slot, EntityAttributes.GENERIC_ARMOR),
                                armorAttribute(stack, slot, EntityAttributes.GENERIC_ARMOR_TOUGHNESS),
                                remainingDurability);
                    }
                }
            }
            frames.peek().hit = hit;
            frames.peek().attackEffect = attackEffect;
            return Optional.of(remainingDamage);
        } catch (RuntimeException failure) {
            // 必需接线失败不能改走原版 HP；记录上下文后向调用方暴露错误。
            LOGGER.error(VanillaDamageErrors.DAMAGE_FAILED + " subject=" + entity.getUuidAsString()
                    + " source=" + sourceId + " rawHp=" + rawDamage, failure);
            throw failure;
        }
    }

    public static boolean applyBodyDamage(LivingEntity entity, DamageSource source, float damageAfterEffects) {
        // 这里收到局部护甲、抗性、附魔和吸收之后的剂量，命中部位不能再随机一次。
        Deque<DamageFrame> frames = applications.get(entity);
        if (frames == null || frames.peek().hit == null || damageAfterEffects <= 0) {
            if (frames != null && frames.peek().hit != null && damageAfterEffects <= 0) {
                acceptAttackEffect(entity, frames.peek());
            }
            return false;
        }
        try {
            DamageFrame frame = frames.peek();
            var result = frame.hit.commitIntegrated(damageAfterEffects,
                    source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY));
            acceptAttackEffect(entity, frame);
            var body = result.body();
            if (result.deathPrevented()) {
                // 身体尚未死亡及清账；随后复用原版扣物品、统计、音画与效果授予流程。
                ((TotemAccess) entity).meridian$tryUseTotem(source);
            }
            if (body.getVitality().getDead()) {
                // 等原版伤害调用结束后再同步死亡，避免在 applyDamage 中途重入 onDeath。
                pendingDeaths.put(entity, source);
            }
            return true;
        } catch (RuntimeException failure) {
            LOGGER.error(VanillaDamageErrors.DAMAGE_FAILED + " subject=" + entity.getUuidAsString()
                    + " hpAfterEffects=" + damageAfterEffects, failure);
            throw failure;
        }
    }

    private static double armorAttribute(ItemStack stack, EquipmentSlot slot, EntityAttribute attribute) {
        // 计算这件装备自己的属性修饰，兼容物品 NBT；不把其他槽位或实体固有护甲算进来。
        var localAttribute = new EntityAttributeInstance(attribute, ignored -> { /* 临时计算无需发布实体属性。 */ });
        localAttribute.setBaseValue(0);
        for (var modifier : stack.getAttributeModifiers(slot).get(attribute)) {
            localAttribute.addTemporaryModifier(modifier);
        }
        return localAttribute.getValue();
    }

    public static void finishDamage(LivingEntity entity) {
        // 原版已致死时 AFTER_DEATH 已完成通知；只有仍存活的实体需要反向同步身体死亡。
        DamageSource source = pendingDeaths.remove(entity);
        if (source != null && entity.isAlive()) {
            entity.setHealth(0);
            entity.onDeath(source);
        }
        if (!entity.isAlive()) {
            pendingStatusEffects.remove(entity);
            return;
        }
        Deque<AttackEffectMessages.StatusEffect> statusEffects = pendingStatusEffects.remove(entity);
        if (statusEffects != null) {
            while (!statusEffects.isEmpty()) {
                applyStatusEffect(entity, statusEffects.removeFirst());
            }
        }
    }

    private static void confirmDeath(LivingEntity entity) {
        // 外部死亡保留当前局部 HP，重生也不自动治疗或生成一具新身体。
        if (entity.getWorld().isClient) {
            return;
        }
        pendingDeaths.remove(entity);
        pendingStatusEffects.remove(entity);
        try {
            bridge.died(entity.getUuidAsString());
        } catch (RuntimeException failure) {
            LOGGER.error(VanillaDamageErrors.DAMAGE_FAILED + " subject=" + entity.getUuidAsString(), failure);
            throw failure;
        }
    }

    private static final class DamageFrame {
        private VanillaDamageBridge.PreparedHit hit;
        private VanillaDamageBridge.AttackEffectResolution attackEffect;
        private boolean attackEffectAccepted;
    }

    private static boolean isMeleeSource(String sourceId) {
        // 首版只让原版近战来源进入攻击附效，箭、药水和其他投射物保留未来发射/命中语义。
        return sourceId.equals("minecraft:mob_attack") || sourceId.equals("minecraft:mob_attack_no_aggro")
                || sourceId.equals("minecraft:player_attack");
    }

    private static void acceptAttackEffect(LivingEntity entity, DamageFrame frame) {
        // 身体事务或零剂量命中完成后才记去重；状态效果留到原版 damage 返回阶段。
        if (frame.attackEffectAccepted || frame.attackEffect == null) {
            return;
        }
        frame.attackEffectAccepted = true;
        bridge.markAttackEffectApplied(entity.getUuidAsString(), frame.attackEffect.effectId());
        if (!frame.attackEffect.statusEffects().isEmpty()) {
            pendingStatusEffects.computeIfAbsent(entity, ignored -> new ArrayDeque<>())
                    .addAll(frame.attackEffect.statusEffects());
        }
    }

    private static void applyStatusEffect(LivingEntity entity, AttackEffectMessages.StatusEffect status) {
        try {
            Identifier id = new Identifier(status.getEffectId());
            var effect = Registries.STATUS_EFFECT.getOrEmpty(id);
            if (effect.isEmpty()) {
                LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_STATUS + " target=" + entity.getUuidAsString()
                        + " effect=" + status.getEffectId());
                return;
            }
            entity.addStatusEffect(new StatusEffectInstance(effect.get(), status.getDurationTicks(), status.getAmplifier()));
        } catch (RuntimeException failure) {
            // 协议已限制字符串和数值；注册表缺失仍只丢弃该可选状态，不影响已提交伤害。
            LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_STATUS + " target=" + entity.getUuidAsString()
                    + " effect=" + status.getEffectId(), failure);
        }
    }
}
