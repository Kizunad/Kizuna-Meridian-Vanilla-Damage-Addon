package dev.kizuna.meridian.vanilla.fabric;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.technique.fabric.world.TechniqueSounds;
import dev.kizuna.meridian.technique.server.TechniqueInfusions;
import dev.kizuna.meridian.technique.server.TechniqueInfusions.InfusionStrike;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge.PreparedHit;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import dev.kizuna.meridian.vanilla.fabric.mixin.HurtCooldownAccess;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 五行附伤：主击真的打进去以后，按主人身体上的附行在同一目标上补一记本行伤害。 */
final class VanillaInfusions {
    static final TagKey<EntityType<?>> INFUSABLE_DIRECT = entityTag("infusable_direct");
    static final TagKey<EntityType<?>> INFUSION_VIA_OWNER = entityTag("infusion_via_owner");
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_vanilla_damage");
    private static final Map<LivingEntity, MainHit> pending = new IdentityHashMap<>();
    private static final Set<LivingEntity> landing = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 打进去的主击：剂量是过肌肉、未过护甲的原版 HP；有身体的目标带着已固定的选点。 */
    private record MainHit(DamageSource source, double doseHp, Optional<PreparedHit> prepared) { }

    private VanillaInfusions() {
        // 只有待落附伤表和正在落附伤的目标，随服务器停止清空。
    }

    private static TagKey<EntityType<?>> entityTag(String path) {
        // 与伤害类型标签同用 meridian 命名空间。
        return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("meridian", path));
    }

    static void record(LivingEntity target, DamageSource source, double doseHp, Optional<PreparedHit> prepared) {
        // 原版 actuallyHurt 结束时记下；盾整个挡下时剂量为 0，正在落附伤的目标上附伤自己打出的伤害不再记。
        if (landing.contains(target) || !(doseHp > 0) || !infusable(source)) {
            return;
        }
        pending.put(target, new MainHit(source, doseHp, prepared));
    }

    private static boolean infusable(DamageSource source) {
        // 伤害类型在 infusable 里，或直接实体（尖牙）的类型在 infusable_direct 里。
        if (source.is(VanillaDamageTypeTags.INFUSABLE)) {
            return true;
        }
        Entity direct = source.getDirectEntity();
        return direct != null && direct.is(INFUSABLE_DIRECT);
    }

    static void discard(LivingEntity target) {
        // 主击打死了目标，或目标在别处死了，这一记附伤不再落，身上的灼伤也不用再记。
        pending.remove(target);
        VanillaAfflictions.forget(target);
    }

    static void clear() {
        // 世界停止后不保留实体引用。
        pending.clear();
        landing.clear();
        VanillaAfflictions.clear();
    }

    static void land(LivingEntity target) {
        // 原版 hurtServer 返回时调用，主击的死亡与状态效果已结算完，目标仍活着。
        MainHit main = pending.remove(target);
        if (main == null) {
            return;
        }
        Optional<LivingEntity> owner = owner(main.source(), target);
        if (owner.isEmpty()) {
            return;
        }
        LivingEntity resolved = owner.get();
        evaluate(resolved, target, main).ifPresent(strike -> execute(target, resolved, main, strike));
    }

    private static Optional<LivingEntity> owner(DamageSource source, LivingEntity target) {
        // 造成伤害的实体就是主人，白名单实体再查一跳；主人得活着，并且和目标在同一个世界。
        Entity owner = throughOwner(source.getEntity());
        if (owner instanceof LivingEntity living && living.isAlive() && living.level() == target.level()) {
            return Optional.of(living);
        }
        return Optional.empty();
    }

    private static Entity throughOwner(Entity causing) {
        // 只认标签白名单：恼鬼也实现 OwnableEntity，泛泛地查主人会把驯服动物也带进来。
        if (causing != null && causing.is(INFUSION_VIA_OWNER)
                && causing instanceof TraceableEntity traced) {
            return traced.getOwner();
        }
        return causing;
    }

    private static Optional<InfusionStrike> evaluate(LivingEntity owner, LivingEntity target, MainHit main) {
        // 主人身体上有附行才整份换成核心快照；取值失败只丢这一记附伤，主击已经提交。
        try {
            Optional<BodyMessages.BodySnapshot> inspected = VanillaDamageFabric.inspectBody(owner);
            if (inspected.isEmpty()) {
                return Optional.empty();
            }
            BodyMessages.BodySnapshot ownerBody = inspected.get();
            if (!infused(ownerBody)) {
                return Optional.empty();
            }
            Optional<BodySnapshot> targetBody = main.prepared().map(hit -> BodyProtobuf.fromMessage(hit.body()));
            return TechniqueInfusions.strike(BodyProtobuf.fromMessage(ownerBody), targetBody, main.doseHp());
        } catch (RuntimeException failure) {
            LOGGER.error(VanillaDamageErrors.INFUSION + " owner=" + owner.getStringUUID()
                    + " target=" + target.getStringUUID(), failure);
            return Optional.empty();
        }
    }

    private static boolean infused(BodyMessages.BodySnapshot body) {
        // 多数近战的主人没有功法进度或没有附行，先只解功法进度，免掉整份身体的转换。
        Any payload = body.getAddonProgressMap().get(TechniqueState.KEY);
        return payload != null && TechniqueState.decode(payload.getValue().toByteArray()).hasActiveInfusion();
    }

    private static void execute(LivingEntity target, LivingEntity owner, MainHit main, InfusionStrike strike) {
        // 造成伤害的实体记主人，击杀归主人；直接实体沿用主击的（攻击者本人、尖牙或恼鬼）。
        ServerLevel level = (ServerLevel) target.level();
        Optional<Holder.Reference<DamageType>> type = damageType(level, strike.damageTypeId());
        if (type.isEmpty()) {
            LOGGER.error(VanillaDamageErrors.INFUSION_DAMAGE_TYPE + " owner=" + owner.getStringUUID()
                    + " type=" + strike.damageTypeId());
            return;
        }
        Entity direct = Objects.requireNonNullElse(main.source().getDirectEntity(), owner);
        DamageSource source = new IncomingDamage.QiSource(type.get(), direct, owner);
        Optional<String> partId = main.prepared().flatMap(PreparedHit::target).map(HealthTarget::getPartId);
        if (!deal(level, target, source, main.prepared().isPresent(), partId, strike.damageHp())) {
            return;
        }
        strike.sound().ifPresent(sound -> TechniqueSounds.playAll(owner, target, List.of(sound)));
        if (target.isAlive()) {
            applyExtras(target, source, partId, strike);
        }
    }

    /** 灼伤的一次掉血：打在记下的部位上（没身体的走原版受伤），不再触发附伤。 */
    static void dealAffliction(ServerLevel level, LivingEntity target, DamageSource source, Optional<String> partId,
                               double damageHp) {
        // 灼伤只挂在附伤打进去了的目标上：有部位就是有身体的。
        if (damageHp > 0 && target.isAlive()) {
            deal(level, target, source, partId.isPresent(), partId, damageHp);
        }
    }

    private static Optional<Holder.Reference<DamageType>> damageType(ServerLevel level, String id) {
        // 伤害类型由内容附属的数据包注册，功法目录只校验了 ID 的格式。
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(id));
        return level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(key);
    }

    private static boolean deal(ServerLevel level, LivingEntity target, DamageSource source, boolean bodied,
                                Optional<String> partId, double damageHp) {
        // 附伤打出的伤害不再记附伤；有身体的打在主击同一部位，没身体的走一次原版受伤。
        landing.add(target);
        try {
            if (bodied) {
                return dealToPart(target, source, partId, damageHp);
            }
            return dealToVanilla(level, target, source, damageHp);
        } finally {
            landing.remove(target);
        }
    }

    private static boolean dealToPart(LivingEntity target, DamageSource source, Optional<String> partId,
                                      double damageHp) {
        // 不经原版受伤入口，不吃也不改无敌帧、不击退；部位已是 0 HP 时不提交，这一记附伤丢弃。
        if (partId.isEmpty()) {
            return false;
        }
        double bodyHp = VanillaDamageFabric.preferences().damageHp(damageHp);
        var damage = new VanillaDamageFabric.DirectDamage(target, source, partId.get(), bodyHp);
        return VanillaDamageFabric.damageDirect(damage).isPresent();
    }

    private static boolean dealToVanilla(ServerLevel level, LivingEntity target, DamageSource source,
                                         double damageHp) {
        // 附伤类型不击退、不被无敌帧挡；打完把无敌帧状态放回主击之后的值，免得压低下一击的门槛。
        var cooldown = (HurtCooldownAccess) target;
        int invulnerableTime = target.invulnerableTime;
        float lastHurt = cooldown.meridian$lastHurt();
        try {
            return target.hurtServer(level, source, (float) damageHp);
        } finally {
            target.invulnerableTime = invulnerableTime;
            cooldown.meridian$setLastHurt(lastHurt);
        }
    }

    private static void applyExtras(LivingEntity target, DamageSource source, Optional<String> partId,
                                    InfusionStrike strike) {
        // 灼伤、寒侵是不出粒子的状态效果，叠加规则在 VanillaAfflictions；追加击退从直接实体推开。
        VanillaAfflictions.burn(target, source, partId, strike.burnTicks(), strike.burnDamageHp());
        VanillaAfflictions.chill(target, strike.chillTicks());
        double extraKnockback = strike.extraKnockback();
        if (extraKnockback > 0) {
            pushAway(target, source.getDirectEntity(), extraKnockback);
        }
    }

    private static void pushAway(LivingEntity target, Entity direct, double strength) {
        // 按击退抗性折算后直接叠在现有水平速度上，不用原版 knockback：它会先把主击留下的速度减半。
        // 方向背离直接实体，只算水平；两者重合时方向为零，不推。
        double resisted = strength * (1 - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        if (!(resisted > 0)) {
            return;
        }
        Vec3 away = new Vec3(target.getX() - direct.getX(), 0, target.getZ() - direct.getZ());
        Vec3 push = away.normalize().scale(resisted);
        target.push(push.x, 0, push.z);
    }
}
