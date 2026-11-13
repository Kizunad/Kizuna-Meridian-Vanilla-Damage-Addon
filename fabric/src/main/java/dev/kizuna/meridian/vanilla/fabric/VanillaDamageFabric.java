package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.BodyDamageIntegration;
import dev.kizuna.meridian.vanilla.fabric.mixin.TotemAccess;
import dev.kizuna.meridian.vanilla.ArmorRules;
import dev.kizuna.meridian.vanilla.AttackEffectProvider;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.MuscleRules;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import dev.kizuna.meridian.vanilla.VanillaDamageKinds;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.protocol.pb.AttackEffectMessages;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;
import net.minecraft.world.entity.player.Player;

/** 在原版护甲阶段固定部位，保留后续非护甲结算，并向身体提交一次伤害。 */
public final class VanillaDamageFabric implements ModInitializer {
    private static final int PLAYER_DAMAGE_MEMORY_TICKS = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_vanilla_damage");
    private static final Map<LivingEntity, DamageSource> pendingDeaths = new IdentityHashMap<>();
    private static final Map<LivingEntity, Deque<AttackEffectMessages.StatusEffect>> pendingStatusEffects =
            new IdentityHashMap<>();
    private static final Map<LivingEntity, Deque<DamageFrame>> applications = new IdentityHashMap<>();
    private static final List<DamageObserver> damageObservers = new CopyOnWriteArrayList<>();
    private static final List<PartArmorModifier> armorModifiers = new CopyOnWriteArrayList<>();
    private static VanillaDamageBridge bridge;
    private static DamagePreferences preferences;

    /** 只读伤害通知；观察方不能改变当前结算。 */
    @FunctionalInterface
    public interface DamageObserver {
        void onDamage(DamageEvent event);
    }

    /** 身体事务提交后的固定伤害事实。 */
    public record DamageEvent(LivingEntity target, LivingEntity attacker, DamageSource source,
                              DamageInput input, VanillaDamageBridge.CommitResult result) { }

    public record DamageInput(DamageKind kind, double rawDamage, double damageAfterEffects,
                              Optional<HealthTarget> hit) { }

    /** 部位倍率只参与本次单件护甲计算，耐久仍由物品保存。 */
    @FunctionalInterface
    public interface PartArmorModifier {
        double multiplier(LivingEntity entity, String partId, EquipmentSlot slot, ItemStack stack);
    }

    public record DirectDamage(LivingEntity target, DamageSource source, String partId, double damageHp) {
        public DirectDamage {
            // 可信入口仍拒绝非法剂量和缺失来源，防止把坏数据带入身体事务。
            if (target == null || source == null || partId == null || partId.isBlank()
                    || !Double.isFinite(damageHp) || damageHp <= 0) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
        }
    }

    public static void registerPartArmorModifier(PartArmorModifier modifier) {
        // 提供方必须已安装；它的失败不能被解释成没有护甲修正。
        armorModifiers.add(Objects.requireNonNull(modifier));
    }

    /** 同步伤害的动态上下文；退出顺序与进入顺序相反。 */
    public interface DamageScope {
        void enter(LivingEntity entity);

        void exit(LivingEntity entity);
    }

    public static void registerDamageScope(DamageScope scope) {
        // Healing 等上层附属主动登记，Damage 不依赖提供方的类或构建产物。
        DamageScopes.register(scope);
    }

    public static Optional<VanillaDamageBridge.CommitResult> damageDirect(DirectDamage damage) {
        // 直接提交绕开原版伤害入口的无敌帧、盾挡和击退，局部护甲仍由伤害标签决定。
        LivingEntity target = damage.target();
        if (!(target.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException(VanillaDamageErrors.DAMAGE);
        }
        DamageSource source = damage.source();
        if (target.isDeadOrDying() || target.isInvulnerableTo(level, source)) {
            return Optional.empty();
        }
        if (target instanceof Player player && player.getAbilities().invulnerable
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return Optional.empty();
        }
        String sourceId = source.typeHolder().unwrapKey().orElseThrow().identifier().toString();
        DamageKind kind = attackSource(source, sourceId).kind();
        Optional<VanillaDamageBridge.PreparedHit> prepared = bridge.prepareDirect(
                target.getStringUUID(), sourceId, kind, damage.partId());
        if (prepared.isEmpty()) {
            return Optional.empty();
        }
        VanillaDamageBridge.PreparedHit hit = prepared.get();
        double rawVanillaHp = preferences().vanillaHp(damage.damageHp());
        double afterArmor = localArmorDamage(target, source, hit, rawVanillaHp);
        double dose = hit.directDose(afterArmor);
        try (var scope = DamageScopes.enter(target)) {
            var result = hit.commitIntegrated(dose, source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
            var input = new DamageInput(kind, rawVanillaHp, dose, hit.target());
            finishDirectDamage(new DamageEvent(target, attacker(source), source, input, result), hit);
            return Optional.of(result);
        }
    }

    private static void finishDirectDamage(DamageEvent event, VanillaDamageBridge.PreparedHit hit) {
        // 击杀归属和观察者只在提交成功后记录；原版死亡、图腾沿已有同步路径执行。
        LivingEntity target = event.target();
        LivingEntity attacker = event.attacker();
        if (attacker != null) {
            target.setLastHurtByMob(attacker);
            attacker.setLastHurtMob(target);
        }
        if (attacker instanceof Player player) {
            target.setLastHurtByPlayer(player, PLAYER_DAMAGE_MEMORY_TICKS);
        }
        target.getCombatTracker().recordDamage(event.source(), (float) event.input().damageAfterEffects());
        notifyDamageObservers(event);
        if (event.result().deathPrevented()) {
            ((TotemAccess) target).meridian$tryUseTotem(event.source());
        }
        if (event.result().body().getVitality().getDead()) {
            pendingDeaths.put(target, event.source());
            finishDamage(target);
        } else {
            stagger(target, hit.staggerMillis(event.input().damageAfterEffects()));
        }
    }

    public static Optional<EquipmentSlot> coveredEquipmentSlot(BodyMessages.BodySnapshot body, String partId) {
        // 用实际身体模板和当前 Damage 配置决定装备覆盖，药效不复制人体覆盖表。
        return preferences().armor().coveredSlot(body.getTemplate().getId(), partId)
                .map(VanillaDamageFabric::equipmentSlot);
    }

    private static EquipmentSlot equipmentSlot(ArmorRules.Slot slot) {
        // 将纯规则槽位映射到原版装备槽，所有调用共享此处。
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }

    public static DamagePreferences preferences() {
        // AI 预估复用伤害结算同一份已校验规则，只读，不能替换或改写配置。
        if (preferences == null) {
            throw new IllegalStateException(VanillaDamageErrors.NOT_INITIALIZED);
        }
        return preferences;
    }

    public static void registerDamageObserver(DamageObserver observer) {
        // 观察方只订阅提交后的事实，不能替换或短路原版伤害流程。
        damageObservers.add(java.util.Objects.requireNonNull(observer));
    }

    @Override
    public void onInitialize() {
        // 服务入口是可信进程内协议，不为客户端开放任意伤害网络包。
        var hosts = FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class);
        preferences = DamagePreferences.load(FabricLoader.getInstance().getConfigDir()
                .resolve("meridian-vanilla-damage.toml"));
        var integrations = FabricLoader.getInstance().getEntrypoints(
                "meridian_damage_integration", BodyDamageIntegration.class);
        var attackEffectProviders = FabricLoader.getInstance().getEntrypoints(
                AttackEffectProvider.ENTRYPOINT, AttackEffectProvider.class);
        bridge = new VanillaDamageBridge(hosts, preferences, integrations, attackEffectProviders);
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (!client) {
                VanillaDamageTypeTags.validate(registries.lookupOrThrow(Registries.DAMAGE_TYPE));
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> confirmDeath(entity));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server ->
                VanillaStrikeContext.expireSwings(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // 服务器生命周期结束后不持有旧实体与未完成调用的临时引用。
            pendingDeaths.clear();
            pendingStatusEffects.clear();
            applications.clear();
            bridge.clearAttackEffectState();
            VanillaStrikeContext.clear();
            VanillaInfusions.clear();
        });
    }

    public static float beginApplication(LivingEntity entity, DamageSource source, float rawDamage) {
        // 在任何护甲结算前乘一次；攻击者做功与目标有无身体无关。
        if (entity.level().isClientSide()) {
            return rawDamage;
        }
        double coefficient = 1;
        if (rawDamage > 0 && source.is(VanillaDamageTypeTags.MELEE)
                && source.getEntity() instanceof LivingEntity attacker) {
            coefficient = VanillaStrikeContext.coefficient(attacker, source, rawDamage);
        }
        double scaledDamage = rawDamage * coefficient;
        if (!Double.isFinite(scaledDamage) || scaledDamage > Float.MAX_VALUE) {
            throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
        }
        applications.computeIfAbsent(entity, ignored -> new ArrayDeque<>())
                .push(new DamageFrame(source, rawDamage, coefficient));
        return (float) scaledDamage;
    }

    static double strikeCoefficient(LivingEntity attacker, DamageSource source, double vanillaDamage) {
        // 出手侧由可信主手侧和伤害标签解释，不依赖目标有没有经脉身体。
        if (!source.is(VanillaDamageTypeTags.MELEE)) {
            return 1;
        }
        MuscleRules.Arm mainArm = mainArm(attacker);
        MuscleRules.Grip grip = VanillaDamageTypeTags.grip(source);
        if (source.is(VanillaDamageTypeTags.LEG)) {
            return bridge.exertLegStrike(attacker.getStringUUID(), mainArm, grip, vanillaDamage);
        }
        return bridge.exertStrike(attacker.getStringUUID(), mainArm, grip, vanillaDamage);
    }

    public static double estimateStrikeCoefficient(BodyMessages.BodySnapshot attacker, LivingEntity entity,
                                                   MuscleRules.Grip grip) {
        // AI 预估读调用方已有的快照，与命中结算同一份肌力规则和夹取；不查询宿主、不提交做功。
        return preferences().muscle().plan(attacker, mainArm(entity), grip).coefficient();
    }

    public static double armCoefficient(LivingEntity entity, net.minecraft.world.entity.HumanoidArm arm) {
        // 调用方明确传入拉弦臂的左右侧，不把持武器手或玩家主手侧误当出力臂。
        return bridge.armCoefficient(entity.getStringUUID(), anatomicalArm(arm));
    }

    public static Optional<BodyMessages.BodySnapshot> inspectBody(LivingEntity entity) {
        // 预估调用方读取同一唯一宿主快照，不能通过此入口提交或改写身体。
        return bridge.inspectBody(entity.getStringUUID());
    }

    public static double armCoefficientAt(LivingEntity entity, net.minecraft.world.entity.HumanoidArm arm,
                                          long elapsedMillis) {
        // 指定同一身体时钟中的未来参考时刻，预估候选不写回权威身体。
        return bridge.armCoefficientAt(entity.getStringUUID(), anatomicalArm(arm), elapsedMillis);
    }

    public static double armCoefficientAt(BodyMessages.BodySnapshot candidate,
                                          net.minecraft.world.entity.HumanoidArm arm, long elapsedMillis) {
        // 调用方提供只读候选，解剖侧、肌力配置与系数夹取仍由伤害附属统一解释。
        return preferences().muscle().armCoefficientAt(candidate, anatomicalArm(arm), elapsedMillis);
    }

    private static MuscleRules.Arm anatomicalArm(net.minecraft.world.entity.HumanoidArm arm) {
        // 原版解剖侧与规则侧一一对应，主副手的解释留在宿主。
        if (arm == net.minecraft.world.entity.HumanoidArm.LEFT) {
            return MuscleRules.Arm.LEFT;
        }
        return MuscleRules.Arm.RIGHT;
    }

    private static MuscleRules.Arm mainArm(LivingEntity attacker) {
        // 原版主手侧映射到肌力规则的左右臂分组。
        return anatomicalArm(attacker.getMainArm());
    }

    public static void endApplication(LivingEntity entity) {
        // 完全抵挡或被吸收的调用也会返回，此时丢弃尚未提交的命中上下文；打进去的主击留给附伤。
        Deque<DamageFrame> frames = applications.get(entity);
        if (frames == null) {
            return;
        }
        DamageFrame frame = frames.pop();
        if (frames.isEmpty()) {
            applications.remove(entity);
        }
        double doseHp = frame.rawDamage * frame.muscleMultiplier;
        VanillaInfusions.record(entity, frame.source, doseHp, Optional.ofNullable(frame.hit));
    }

    public static Optional<Float> prepareArmor(LivingEntity entity, DamageSource source, float rawDamage) {
        // 原版在进入这里前已处理无敌帧与盾；未绑定身体时继续原版全局护甲。
        Deque<DamageFrame> frames = applications.get(entity);
        if (frames == null || rawDamage <= 0) {
            return Optional.empty();
        }
        String sourceId = source.typeHolder().unwrapKey().map(key -> key.identifier().toString())
                .orElse("meridian_vanilla:unregistered");
        try {
            AttackSource attack = attackSource(source, sourceId);
            Optional<VanillaDamageBridge.PreparedHit> prepared = bridge.prepare(entity.getStringUUID(),
                    sourceId, attack.attackerId(), attack.kind(), entity.getRandom().nextDouble(), attack.aimGroup());
            if (prepared.isEmpty()) {
                return Optional.empty();
            }
            VanillaDamageBridge.PreparedHit hit = prepared.get();
            DamageFrame frame = frames.peek();
            var effect = attackEffect(entity, source, attack, frame.rawDamage, frame.muscleMultiplier);
            AttackDose dose = attackDose(entity, sourceId, rawDamage, effect);
            float remainingDamage = localArmorDamage(entity, source, hit, dose.amount());
            frame.hit = hit;
            frame.kind = attack.kind();
            frame.attackEffect = dose.effect();
            return Optional.of(remainingDamage);
        } catch (RuntimeException failure) {
            // 必需接线失败不能改走原版 HP；记录上下文后向调用方暴露错误。
            LOGGER.error(VanillaDamageErrors.DAMAGE_FAILED + " subject=" + entity.getStringUUID()
                    + " source=" + sourceId + " rawHp=" + rawDamage, failure);
            throw failure;
        }
    }

    private record AttackSource(String id, String attackerId, Entity attacker, DamageKind kind,
                                Optional<String> aimGroup) { }
    private record AttackDose(double amount, VanillaDamageBridge.AttackEffectResolution effect) { }

    private static AttackSource attackSource(DamageSource source, String sourceId) {
        // 显式标签优先；没有标签时保留原版来源和主手武器分类。
        Entity attacker = source.getEntity();
        String attackerId = "minecraft:unknown";
        boolean edgedWeapon = false;
        if (attacker != null) {
            attackerId = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString();
            if (attacker instanceof LivingEntity living) {
                // 26.2 没有剑的物品类，剑按原版剑标签认定（原版剑全部在内）。
                var weapon = living.getMainHandItem();
                edgedWeapon = weapon.is(ItemTags.SWORDS) || weapon.getItem() instanceof AxeItem;
            }
        }
        Optional<DamageKind> taggedKind = VanillaDamageTypeTags.kind(source);
        DamageKind kind;
        if (taggedKind.isPresent()) {
            kind = taggedKind.get();
        } else {
            kind = VanillaDamageKinds.classify(sourceId, attackerId, edgedWeapon);
        }
        return new AttackSource(sourceId, attackerId, attacker, kind, VanillaDamageTypeTags.aim(source));
    }

    private static VanillaDamageBridge.AttackEffectResolution attackEffect(
            LivingEntity target, DamageSource source, AttackSource attack, float vanillaDamage,
            double muscleMultiplier) {
        // 附效同时读取乘系数前后的原版剂量，故障保持原版伤害路径。
        if (!source.is(VanillaDamageTypeTags.MELEE) || !(attack.attacker() instanceof LivingEntity attacker)) {
            return null;
        }
        try {
            double scaledDamage = vanillaDamage * muscleMultiplier;
            var effect = bridge.prepareAttackEffect(target.getStringUUID(), attacker.getStringUUID(),
                    attack.id(), attack.kind(), scaledDamage, vanillaDamage, muscleMultiplier,
                    true /* melee */, target.level().getGameTime()).orElse(null);
            if (effect != null && effect.truncated()) {
                LOGGER.warn("{} target={} source={} rawHp={} extraHp={} maxRatio={}",
                        VanillaDamageErrors.ATTACK_EFFECT_PLAN, target.getStringUUID(), attack.id(),
                        vanillaDamage, effect.extraDamageHp(), "configured");
            }
            return effect;
        } catch (RuntimeException failure) {
            LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_PROVIDER + " target="
                    + target.getStringUUID() + " source=" + attack.id(), failure);
            return null;
        }
    }

    private static AttackDose attackDose(LivingEntity target, String sourceId, float scaledDamage,
                                        VanillaDamageBridge.AttackEffectResolution effect) {
        // 附加伤害在肌肉系数之后合并，非法合计量只丢弃附效。
        if (effect == null) {
            return new AttackDose(scaledDamage, null);
        }
        double total = scaledDamage + effect.extraDamageHp();
        if (!Double.isFinite(total) || total <= 0 || total > Float.MAX_VALUE) {
            LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_PLAN + " target=" + target.getStringUUID()
                    + " source=" + sourceId + " rawHp=" + scaledDamage, new IllegalArgumentException());
            return new AttackDose(scaledDamage, null);
        }
        return new AttackDose(total, effect);
    }

    private static float localArmorDamage(LivingEntity entity, DamageSource source,
                                          VanillaDamageBridge.PreparedHit hit, double damage) {
        // 只读取命中槽位的单件护甲，不叠加其他装备或实体固有属性。
        Optional<ArmorRules.Slot> coveredSlot = hit.coveredSlot();
        if (source.is(DamageTypeTags.BYPASSES_ARMOR) || coveredSlot.isEmpty()) {
            return (float) damage;
        }
        EquipmentSlot slot = equipmentSlot(coveredSlot.get());
        var stack = entity.getItemBySlot(slot);
        if (!stack.is(armorTag(slot))) {
            return (float) damage;
        }
        OptionalDouble remainingDurability = OptionalDouble.empty();
        if (stack.isDamageableItem()) {
            double remainingRatio = 1 - stack.getDamageValue() / (double) stack.getMaxDamage();
            remainingDurability = OptionalDouble.of(Math.max(0, Math.min(1, remainingRatio)));
        }
        String partId = hit.target().orElseThrow().getPartId();
        double multiplier = partArmorMultiplier(entity, partId, slot, stack);
        return (float) hit.remainingVanillaDamage(damage,
                armorAttribute(stack, slot, Attributes.ARMOR) * multiplier,
                armorAttribute(stack, slot, Attributes.ARMOR_TOUGHNESS) * multiplier, remainingDurability);
    }

    private static double partArmorMultiplier(LivingEntity entity, String partId,
                                              EquipmentSlot slot, ItemStack stack) {
        // 合并全部已注册附属；非法值明确失败，不能静默恢复为完整护甲。
        double product = 1;
        for (PartArmorModifier modifier : armorModifiers) {
            double multiplier = modifier.multiplier(entity, partId, slot, stack);
            if (!Double.isFinite(multiplier) || multiplier < 0) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
            product *= multiplier;
        }
        if (!Double.isFinite(product)) {
            throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
        }
        return product;
    }

    private static TagKey<Item> armorTag(EquipmentSlot slot) {
        // 26.2 没有盔甲物品类，按原版各槽位盔甲标签认定，成员与 1.20.1 的盔甲物品一致（另有新增的铜甲）。
        return switch (slot) {
            case HEAD -> ItemTags.HEAD_ARMOR;
            case CHEST -> ItemTags.CHEST_ARMOR;
            case LEGS -> ItemTags.LEG_ARMOR;
            default -> ItemTags.FOOT_ARMOR;
        };
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
                    source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
            acceptAttackEffect(entity, frame);
            var body = result.body();
            var input = new DamageInput(frame.kind, frame.rawDamage, damageAfterEffects, frame.hit.target());
            notifyDamageObservers(new DamageEvent(entity, attacker(source), source, input, result));
            if (!body.getVitality().getDead()) {
                stagger(entity, frame.hit.staggerMillis(damageAfterEffects));
            }
            if (result.deathPrevented()) {
                // 身体尚未死亡及清账；随后复用原版扣物品、统计、音画与效果授予流程。
                ((TotemAccess) entity).meridian$tryUseTotem(source);
            }
            if (body.getVitality().getDead()) {
                // 等原版伤害调用结束后再同步死亡，避免在 actuallyHurt 中途重入 die。
                pendingDeaths.put(entity, source);
            }
            return true;
        } catch (RuntimeException failure) {
            LOGGER.error(VanillaDamageErrors.DAMAGE_FAILED + " subject=" + entity.getStringUUID()
                    + " hpAfterEffects=" + damageAfterEffects, failure);
            throw failure;
        }
    }

    private static void stagger(LivingEntity entity, long millis) {
        // 玩家与类人走同一功法入口；是否打断起手由功法库按施放阶段和可打断标志判定。
        if (millis == 0) {
            return;
        }
        var stagger = new TechniqueService.Stagger(millis, preferences.stagger().movementMultiplier(),
                TechniqueService.StaggerKind.HIT);
        TechniqueFabric.service().stagger(entity.getUUID(), stagger);
    }

    private static LivingEntity attacker(DamageSource source) {
        // 伤害来源没有生物攻击者时保留空值，日志观察方可区分环境伤害。
        return source.getEntity() instanceof LivingEntity living ? living : null;
    }

    private static void notifyDamageObservers(DamageEvent event) {
        // 调试观察失败不能回滚已经提交的身体；每个观察方独立隔离异常。
        if (!event.result().body().getVitality().getDead()) {
            DamageHints.injured(event.target());
        }
        for (DamageObserver observer : damageObservers) {
            try {
                observer.onDamage(event);
            } catch (RuntimeException failure) {
                LOGGER.error("伤害观察方执行失败", failure);
            }
        }
    }

    private static double armorAttribute(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attribute) {
        // 计算这件装备自己的属性修饰，读物品的属性修饰组件；不把其他槽位、附魔或实体固有护甲算进来。
        var localAttribute = new AttributeInstance(attribute, ignored -> { /* 临时计算无需发布实体属性。 */ });
        localAttribute.setBaseValue(0);
        stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .forEach(slot, (holder, modifier) -> {
                    if (holder.equals(attribute)) {
                        localAttribute.addTransientModifier(modifier);
                    }
                });
        return localAttribute.getValue();
    }

    public static void finishDamage(LivingEntity entity) {
        // 原版已致死时 AFTER_DEATH 已完成通知；只有仍存活的实体需要反向同步身体死亡。
        DamageSource source = pendingDeaths.remove(entity);
        if (source != null && entity.isAlive()) {
            entity.setHealth(0);
            entity.die(source);
        }
        if (!entity.isAlive()) {
            pendingStatusEffects.remove(entity);
            VanillaInfusions.discard(entity);
            return;
        }
        Deque<AttackEffectMessages.StatusEffect> statusEffects = pendingStatusEffects.remove(entity);
        if (statusEffects != null) {
            while (!statusEffects.isEmpty()) {
                applyStatusEffect(entity, statusEffects.removeFirst());
            }
        }
        VanillaInfusions.land(entity);
    }

    private static void confirmDeath(LivingEntity entity) {
        // 外部死亡只确认死态并保留局部 HP，玩家重生恢复由 Human 显式命令处理。
        if (entity.level().isClientSide()) {
            return;
        }
        pendingDeaths.remove(entity);
        pendingStatusEffects.remove(entity);
        VanillaInfusions.discard(entity);
        try {
            bridge.died(entity.getStringUUID());
        } catch (RuntimeException failure) {
            LOGGER.error(VanillaDamageErrors.DAMAGE_FAILED + " subject=" + entity.getStringUUID(), failure);
            throw failure;
        }
    }

    private static final class DamageFrame {
        private final DamageSource source;
        private final float rawDamage;
        private final double muscleMultiplier;
        private VanillaDamageBridge.PreparedHit hit;
        private DamageKind kind = DamageKind.CUSTOM;
        private VanillaDamageBridge.AttackEffectResolution attackEffect;
        private boolean attackEffectAccepted;

        private DamageFrame(DamageSource source, float rawDamage, double muscleMultiplier) {
            // 附效上限仍以乘系数前的原版剂量为准，额外伤害不重复乘肌肉系数。
            this.source = source;
            this.rawDamage = rawDamage;
            this.muscleMultiplier = muscleMultiplier;
        }
    }

    private static void acceptAttackEffect(LivingEntity entity, DamageFrame frame) {
        // 身体事务或零剂量命中完成后才记去重；状态效果留到原版 hurt 返回阶段。
        if (frame.attackEffectAccepted || frame.attackEffect == null) {
            return;
        }
        frame.attackEffectAccepted = true;
        bridge.markAttackEffectApplied(entity.getStringUUID(), frame.attackEffect.effectId());
        if (!frame.attackEffect.statusEffects().isEmpty()) {
            pendingStatusEffects.computeIfAbsent(entity, ignored -> new ArrayDeque<>())
                    .addAll(frame.attackEffect.statusEffects());
        }
    }

    private static void applyStatusEffect(LivingEntity entity, AttackEffectMessages.StatusEffect status) {
        try {
            Identifier id = Identifier.parse(status.getEffectId());
            var effect = BuiltInRegistries.MOB_EFFECT.get(id);
            if (effect.isEmpty()) {
                LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_STATUS + " target=" + entity.getStringUUID()
                        + " effect=" + status.getEffectId());
                return;
            }
            entity.addEffect(new MobEffectInstance(effect.get(), status.getDurationTicks(), status.getAmplifier()));
        } catch (RuntimeException failure) {
            // 协议已限制字符串和数值；注册表缺失仍只丢弃该可选状态，不影响已提交伤害。
            LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_STATUS + " target=" + entity.getStringUUID()
                    + " effect=" + status.getEffectId(), failure);
        }
    }
}
