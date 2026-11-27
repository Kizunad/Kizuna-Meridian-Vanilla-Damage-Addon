package dev.kizuna.meridian.vanilla.fabric;

import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponHeat;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponBinds;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponSplash;

import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.BodyDamageIntegration;
import dev.kizuna.meridian.vanilla.fabric.mixin.HurtSoundAccess;
import dev.kizuna.meridian.vanilla.fabric.mixin.TotemAccess;
import dev.kizuna.meridian.vanilla.ArmorRules;
import dev.kizuna.meridian.vanilla.AttackEffectProvider;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.TemplateDamageDefaults;
import dev.kizuna.meridian.vanilla.MuscleRules;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge;
import dev.kizuna.meridian.vanilla.VanillaDamageErrors;
import dev.kizuna.meridian.vanilla.VanillaDamageKinds;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.DamageAttachment;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.GuardState;
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
import net.minecraft.world.entity.Mob;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/** 在原版护甲阶段固定部位，保留后续非护甲结算，并向身体提交一次伤害。 */
public final class VanillaDamageFabric implements ModInitializer {
    private static final int PLAYER_DAMAGE_MEMORY_TICKS = 100;
    private static final double MAX_DAMAGE_SHARE = 1;
    /** 原版受伤动作的时长（tick）。 */
    private static final int HURT_PRESENTATION_TICKS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_vanilla_damage");
    private static final Map<LivingEntity, DamageSource> pendingDeaths = new IdentityHashMap<>();
    private static final Map<LivingEntity, Deque<AttackEffectMessages.StatusEffect>> pendingStatusEffects =
            new IdentityHashMap<>();
    private static final Map<LivingEntity, Deque<DamageFrame>> applications = new IdentityHashMap<>();
    private static final List<DamageObserver> damageObservers = new CopyOnWriteArrayList<>();
    private static final List<MobBodyReader> mobBodyReaders = new CopyOnWriteArrayList<>();
    private static final List<PartArmorModifier> armorModifiers = new CopyOnWriteArrayList<>();
    private static final List<DamageProtection> damageProtections = new CopyOnWriteArrayList<>();
    private static final List<GuardHitCostScale> guardHitCostScales = new CopyOnWriteArrayList<>();
    private static VanillaDamageBridge bridge;
    private static final TemplateDamageDefaults templateDefaults = new TemplateDamageDefaults();
    private static DamagePreferences preferences;

    /** 只读伤害通知；观察方不能改变当前结算。 */
    @FunctionalInterface
    public interface DamageObserver {
        void onDamage(DamageEvent event);
    }

    @FunctionalInterface
    public interface MobBodyReader {
        /** 只返回归属此实体的已有快照，不建身、不推进身体时间。 */
        Optional<BodyMessages.BodySnapshot> read(Mob mob);
    }

    public static void registerMobBodyReader(MobBodyReader reader) {
        mobBodyReaders.add(Objects.requireNonNull(reader));
    }

    public static Optional<BodyMessages.BodySnapshot> existingMobBody(Mob mob) {
        // 已绑定身体和暂存快照都归宿主读取；不向健康宿主发送会建身的 inspect 请求。
        if (!(mob.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException(VanillaDamageErrors.MOB_BODY_THREAD);
        }
        for (var reader : mobBodyReaders) {
            var body = reader.read(mob);
            if (body.isPresent()) {
                return body;
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface DamageProtection {
        /** 根据命中前的实体状态返回本击保护份额；空值表示策略不处理本击。 */
        OptionalDouble protectedDamageShare(LivingEntity target, DamageSource source,
                                            BodyMessages.BodySnapshot body);
    }

    /** 结算成功后的固定伤害事实；完全挡住时可以没有身体变更。 */
    public record DamageEvent(LivingEntity target, LivingEntity attacker, DamageSource source,
                              DamageInput input, VanillaDamageBridge.CommitResult result) {
        public boolean bodyDead() {
            return result.body().getVitality().getDead();
        }
    }

    /** 结算前可查询的伤害提供方，武器是查询时的不可变副本。 */
    public record DamageProvider(Optional<LivingEntity> attacker, Optional<Entity> directEntity,
                                 ItemStack weapon, DamageSource source) {
        public DamageProvider(Optional<LivingEntity> attacker, Optional<Entity> directEntity,
                              ItemStack weapon, DamageSource source) {
            this.attacker = Objects.requireNonNull(attacker);
            this.directEntity = Objects.requireNonNull(directEntity);
            ItemStack nonNullWeapon = Objects.requireNonNull(weapon);
            ItemStack weaponCopy = nonNullWeapon.copy();
            this.weapon = weaponCopy;
            this.source = Objects.requireNonNull(source);
        }

        @Override
        public ItemStack weapon() {
            return weapon.copy();
        }
    }

    public enum GuardTool { UNARMED, WEAPON }

    @FunctionalInterface
    public interface GuardCost {
        /** 宿主从同一命中前快照计算费用与可能的撤防，不在准备阶段写身体。 */
        DamageAttachment payment(BodyMessages.BodySnapshot body, GuardState guard,
                                 double preventedDamage, GuardTool tool);
    }

    static GuardCost guardCost() {
        // 正式守式使用人体内容提供的唯一纯计算入口。
        return GuardCostProvider.INSTANCE;
    }

    private static final class GuardCostProvider {
        private static final GuardCost INSTANCE = load();

        private static GuardCost load() {
            // 所有模组完成加载后才解析跨仓入口，避免初始化顺序影响认领。
            var providers = FabricLoader.getInstance().getEntrypoints("meridian_guard_cost", GuardCost.class);
            if (providers.size() != 1) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.INVALID_INTEGRATION);
            }
            return providers.getFirst();
        }
    }

    @FunctionalInterface
    public interface GuardHitCostScale {
        /** 守式真实承接本击时查询本击命中费的倍数；只读当前事实，本击可能随后不提交。 */
        double scale(LivingEntity defender, DamageSource source, GuardState guard);
    }

    public static void registerGuardHitCostScale(GuardHitCostScale scale) {
        // 内容模组登记招式对守方命中费的加收；多个来源相乘。
        guardHitCostScales.add(Objects.requireNonNull(scale));
    }

    static double guardHitCostScale(LivingEntity defender, DamageSource source, GuardState guard) {
        // 倍数只作用于本击的命中费；持守费、剩余伤害和兵器磨损仍按原剂量。
        double total = 1;
        for (var scale : guardHitCostScales) {
            double factor = scale.scale(defender, source, guard);
            if (!Double.isFinite(factor) || factor < 0) {
                throw VanillaDamageErrors.hostRejected(VanillaDamageErrors.INVALID_INTEGRATION);
            }
            total *= factor;
        }
        return total;
    }

    /** 判定当刻的守身份和真正承接了本击的兵器；碎裂回落到前臂时按徒手记录。 */
    public record GuardContact(Optional<GuardState> state, GuardTool tool) {
        static GuardContact unarmed(Optional<GuardState> state) {
            return new GuardContact(state, GuardTool.UNARMED);
        }
    }

    public record DamageInput(DamageKind kind, double rawDamage, double damageAfterEffects,
                              Optional<HealthTarget> hit, GuardContact contact) {
        public DamageInput(DamageKind kind, double rawDamage, double damageAfterEffects,
                           Optional<HealthTarget> hit, Optional<GuardState> guarded) {
            this(kind, rawDamage, damageAfterEffects, hit, GuardContact.unarmed(guarded));
        }

        public Optional<GuardState> guarded() {
            return contact.state();
        }

        public boolean weaponGuarded() {
            return contact.tool() == GuardTool.WEAPON;
        }
    }

    /** 部位倍率只参与本次单件护甲计算，耐久仍由物品保存。 */
    @FunctionalInterface
    public interface PartArmorModifier {
        double multiplier(LivingEntity entity, String partId, EquipmentSlot slot, ItemStack stack);
    }

    public record DirectDamage(LivingEntity target, DamageSource source, String partId, double damageHp,
                               DirectExtras extras) {
        public DirectDamage(LivingEntity target, DamageSource source, String partId, double damageHp) {
            // 不带透劲、护甲倍数和附带的普通直接伤害。
            this(target, source, partId, damageHp, DirectExtras.NONE);
        }

        public DirectDamage {
            // 可信入口仍拒绝非法剂量和缺失来源，防止把坏数据带入身体事务。
            if (target == null || source == null || partId == null || partId.isBlank() || extras == null
                    || !Double.isFinite(damageHp) || damageHp <= 0) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
        }
    }

    /**
     * 直接伤害这一下另带的三样：透进器官的份额、只对这一下生效的局部护甲倍数、和扣血同一笔加到目标身上的能力修正。
     * 护甲倍数和 {@link #registerPartArmorModifier} 登记的倍数相乘；修正装不下（身体最多 32 条）时这一下只算伤害。
     */
    public record DirectExtras(OptionalDouble organShare, double armorMultiplier,
                               Map<String, LocalArmor> localArmor, Optional<WeaponDamageCap> weaponCap,
                               List<BodyMessages.CapabilityModifier> modifiers) {
        public static final DirectExtras NONE = new DirectExtras(OptionalDouble.empty(), 1, Map.of(),
                Optional.empty(), List.of());

        public DirectExtras(OptionalDouble organShare, double armorMultiplier,
                            List<BodyMessages.CapabilityModifier> modifiers) {
            this(organShare, armorMultiplier, Map.of(), Optional.empty(), modifiers);
        }

        public DirectExtras(OptionalDouble organShare, double armorMultiplier,
                            Map<String, LocalArmor> localArmor, Optional<WeaponDamageCap> weaponCap,
                            List<BodyMessages.CapabilityModifier> modifiers) {
            // 护甲倍数是非负有限数；份额的范围由桥接层的附带检查。
            if (organShare == null || !Double.isFinite(armorMultiplier) || armorMultiplier < 0
                    || localArmor == null || weaponCap == null) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
            Map<String, LocalArmor> armorCopy = new LinkedHashMap<>();
            localArmor.forEach((partId, armor) -> {
                if (partId == null || partId.isBlank() || armor == null) {
                    throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
                }
                armorCopy.put(partId, armor);
            });
            Map<String, LocalArmor> immutableArmor = Collections.unmodifiableMap(armorCopy);
            List<BodyMessages.CapabilityModifier> modifierCopy = List.copyOf(modifiers);
            this.organShare = organShare;
            this.armorMultiplier = armorMultiplier;
            this.localArmor = immutableArmor;
            this.weaponCap = weaponCap;
            this.modifiers = modifierCopy;
        }

        public DirectExtras(OptionalDouble organShare, double armorMultiplier,
                            Map<String, LocalArmor> localArmor, WeaponDamageCap weaponCap,
                            List<BodyMessages.CapabilityModifier> modifiers) {
            this(organShare, armorMultiplier, localArmor, Optional.ofNullable(weaponCap), modifiers);
        }
    }

    /** 一次伤害对命中部位使用的护甲与韧性。 */
    public record LocalArmor(double armor, double toughness) {
        public LocalArmor {
            if (!Double.isFinite(armor) || armor < 0 || !Double.isFinite(toughness) || toughness < 0) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
        }
    }

    /** 一次伤害对指定武器生效的伤害上限。 */
    public record WeaponDamageCap(ItemStack weapon, double maximumDamage) {
        public WeaponDamageCap(ItemStack weapon, double maximumDamage) {
            if (weapon == null || weapon.isEmpty() || !Double.isFinite(maximumDamage) || maximumDamage < 0) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
            ItemStack weaponCopy = weapon.copy();
            this.weapon = weaponCopy;
            this.maximumDamage = maximumDamage;
        }

        @Override
        public ItemStack weapon() {
            return weapon.copy();
        }
    }

    public static void registerPartArmorModifier(PartArmorModifier modifier) {
        // 提供方必须已安装；它的失败不能被解释成没有护甲修正。
        armorModifiers.add(Objects.requireNonNull(modifier));
    }

    public static void registerDamageProtection(DamageProtection protection) {
        // 伤害策略由内容附属登记，结算时按注册顺序取唯一处理结果。
        damageProtections.add(Objects.requireNonNull(protection));
    }

    public static DamageProvider damageProvider(DamageSource source) {
        // 伤害标签标明副手时，武器快照取该手当前持物。
        Objects.requireNonNull(source);
        Entity directEntity = source.getDirectEntity();
        Entity sourceEntity = source.getEntity();
        LivingEntity attacker = sourceEntity instanceof LivingEntity living ? living : null;
        ItemStack weapon;
        if (attacker == null) {
            weapon = ItemStack.EMPTY;
        } else if (source.is(VanillaDamageTypeTags.OFF_HAND)) {
            weapon = attacker.getOffhandItem();
        } else {
            weapon = attacker.getMainHandItem();
        }
        return new DamageProvider(Optional.ofNullable(attacker), Optional.ofNullable(directEntity), weapon, source);
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

    /** 原版血量按本附属的配置换成身体 HP；只依赖 Fabric 模块的附属给直接伤害填剂量时用。 */
    public static double bodyHp(double vanillaHp) {
        return preferences().damageHp(vanillaHp);
    }

    /**
     * 这一下照伤害来源的部位组会落在目标的哪个部位：和普通受伤入口同一套权重，随机数取目标自己的。
     * 目标没绑身体、已死或选不出部位时为空。给目录只写了部位组、却要走直接伤害的打击用。
     */
    public static Optional<String> aimedPart(LivingEntity target, DamageSource source) {
        String sourceId = source.typeHolder().unwrapKey().orElseThrow().identifier().toString();
        AttackSource attack = attackSource(source, sourceId);
        return bridge.prepare(target.getStringUUID(), sourceId, attack.attackerId(), attack.kind(),
                        target.getRandom().nextDouble(), attack.aimGroup())
                .flatMap(VanillaDamageBridge.PreparedHit::target)
                .filter(HealthTarget::hasPartId)
                .map(HealthTarget::getPartId);
    }

    public static Optional<VanillaDamageBridge.CommitResult> damageDirect(DirectDamage damage) {
        // 指定部位仍使用身体正常的分层策略。
        return damageDirect(damage, DirectLayer.PART);
    }

    enum DirectLayer { PART, SKIN }

    static Optional<VanillaDamageBridge.CommitResult> damageDirect(DirectDamage damage, DirectLayer layer) {
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
        Optional<GuardState> guard = TechniqueGuardDefense.active(target);
        Optional<VanillaDamageBridge.PreparedHit> prepared = bridge.prepareDirect(
                target.getStringUUID(), sourceId, kind, damage.partId());
        if (prepared.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(commitDirect(damage, prepared.get(), kind, guard, layer));
    }

    /**
     * 给刚挨了一下直接伤害的活物补原版的受伤表现：红闪、受伤动作、受伤声。
     * 直接伤害不经原版受伤入口，这些都没有；拳脚这类「打在身上」的调用方提交成功后调一次，药、毒照旧不闪。
     * 不动无敌帧，也不击退；目标已经死了或正在死时不做，交给死亡同步。
     */
    public static void presentHurt(LivingEntity target, DamageSource source) {
        if (!(target.level() instanceof ServerLevel level) || target.isDeadOrDying()) {
            return;
        }
        // 服务端自己也记上受伤动作的时长，和原版受伤入口记的一样；客户端的红闪和动作靠这条伤害事件。
        target.hurtDuration = HURT_PRESENTATION_TICKS;
        target.hurtTime = target.hurtDuration;
        level.broadcastDamageEvent(target, source);
        ((HurtSoundAccess) target).meridian$playHurtSound(source);
    }

    private static VanillaDamageBridge.CommitResult commitDirect(DirectDamage damage,
                                                                 VanillaDamageBridge.PreparedHit hit, DamageKind kind,
                                                                 Optional<GuardState> guard, DirectLayer layer) {
        // 近战类型先按出手的肌肉系数折算（和普通路同一个出手上下文，一次出手只记一次做功），
        // 再判断格挡并过新落点的局部护甲；只有未格挡的直接伤害裁到部位剩余。
        LivingEntity target = damage.target();
        DamageSource source = damage.source();
        DirectExtras extras = damage.extras();
        try (var strike = VanillaStrikeContext.damage(source); var scope = DamageScopes.enter(target)) {
            double rawVanillaHp = preferences().vanillaHp(damage.damageHp());
            double coefficientScaledDamage = rawVanillaHp * directCoefficient(source, rawVanillaHp);
            double cappedDamage = DamageEquipmentOverrides.cappedDamage(source, coefficientScaledDamage,
                    extras.weaponCap());
            var defense = TechniqueGuardDefense.prepare(target, source, hit, extras.organShare(), guard);
            var weapon = WeaponGuardHit.prepare(target, defense, cappedDamage);
            TechniqueGuardDefense.attach(new TechniqueGuardDefense.GuardedHit(target, source, hit, cappedDamage),
                    defense, weapon.contact());
            double afterArmor = localArmorDamage(target, source, hit, weapon.armDamage(), extras.armorMultiplier(),
                    extras.localArmor());
            var part = hit.target().filter(HealthTarget::hasPartId).map(HealthTarget::getPartId);
            double incoming = IncomingDamage.apply(target, source, kind, part, afterArmor);
            double dose;
            if (layer == DirectLayer.SKIN) {
                dose = hit.directSkinDose(incoming);
            } else {
                dose = hit.directDose(incoming);
            }
            OptionalDouble protectedDamageShare = protectedDamageShare(target, source, hit.body());
            var contact = new LethalHitVeto.Hit(target, attacker(source), source);
            var resolution = commit(contact, hit, dose, attachable(target, hit, extras, protectedDamageShare));
            var result = resolution.result();
            weapon.commitWear();
            var input = new DamageInput(kind, rawVanillaHp, dose, hit.target(), weapon.contact());
            finishDirectDamage(new DamageEvent(target, attacker(source), source, input, result), hit,
                    protectedDamageShare, resolution);
            defense.afterCommit(target, result.body());
            return result;
        }
    }

    private static double directCoefficient(DamageSource source, double rawVanillaHp) {
        // 只有带近战标签、由活物打出的直接伤害才折算；药、毒这类照旧不看谁的手臂。
        if (!source.is(VanillaDamageTypeTags.MELEE) || !(source.getEntity() instanceof LivingEntity attacker)) {
            return 1;
        }
        return VanillaStrikeContext.coefficient(attacker, source, rawVanillaHp);
    }

    private static VanillaDamageBridge.DamageExtras attachable(LivingEntity target,
                                                               VanillaDamageBridge.PreparedHit hit,
                                                               DirectExtras extras,
                                                               OptionalDouble protectedDamageShare) {
        // 修正装不下时不让整下伤害被拒：只提交伤害和透劲份额，记一条警告。
        if (hit.fits(extras.modifiers())) {
            return new VanillaDamageBridge.DamageExtras(extras.organShare(), protectedDamageShare,
                    extras.modifiers());
        }
        LOGGER.warn("直接伤害的附带修正装不下，这一下只算伤害：target={} modifiers={}",
                target.getStringUUID(), extras.modifiers().size());
        return new VanillaDamageBridge.DamageExtras(extras.organShare(), protectedDamageShare, List.of());
    }

    private record Resolution(VanillaDamageBridge.CommitResult result, LethalHitVeto veto,
                              LethalHitVeto.Hit contact) {
        void applyProtection() {
            if (!result.deathPrevented()) {
                return;
            }
            if (veto != null) {
                veto.spared(contact);
            } else {
                ((TotemAccess) contact.target()).meridian$tryUseTotem(contact.source());
            }
        }
    }

    private static Resolution commit(LethalHitVeto.Hit contact, VanillaDamageBridge.PreparedHit hit,
                                     double dose, VanillaDamageBridge.DamageExtras extras) {
        boolean bypass = contact.source().is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        var veto = LethalHitVeto.Registered.first(contact);
        var floor = Optional.ofNullable(veto).map(rule -> VanillaDamageBridge.survivalFloor(hit.body()));
        var result = hit.commitIntegrated(dose, bypass, extras, floor);
        return new Resolution(result, veto, contact);
    }

    private static void finishDirectDamage(DamageEvent event, VanillaDamageBridge.PreparedHit hit,
                                           OptionalDouble protectedDamageShare, Resolution resolution) {
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
        DamageSource source = event.source();
        double dose = event.input().damageAfterEffects();
        float amount = (float) dose;
        target.getCombatTracker().recordDamage(source, amount);
        notifyDamageObservers(event);
        resolution.applyProtection();
        if (event.bodyDead()) {
            // 直接伤害没有外层 hurtServer 负责死亡检查，沿用同一伤害来源和死亡事件。
            if (ServerLivingEntityEvents.ALLOW_DEATH.invoker().allowDeath(target, source, amount)) {
                target.die(source);
            }
            // 已绑定实体的 setter 会投影回调结束时的权威身体，包括监听器处理过的保命。
            target.setHealth(0);
        } else {
            stagger(target, hit.staggerMillis(dose, protectedDamageShare));
        }
    }

    private static OptionalDouble protectedDamageShare(LivingEntity target, DamageSource source,
                                                       BodyMessages.BodySnapshot body) {
        // 只有近战和箭类投射物属于实打；每个来源最多由一个内容策略认领。
        if (!isProtectedSource(source)) {
            return OptionalDouble.empty();
        }
        OptionalDouble selected = OptionalDouble.empty();
        for (DamageProtection protection : damageProtections) {
            OptionalDouble candidate = protection.protectedDamageShare(target, source, body);
            if (candidate.isEmpty()) {
                continue;
            }
            double share = candidate.getAsDouble();
            if (!Double.isFinite(share) || share < 0 || share > MAX_DAMAGE_SHARE) {
                throw new IllegalArgumentException(VanillaDamageErrors.DAMAGE);
            }
            if (selected.isPresent()) {
                throw new IllegalStateException(VanillaDamageErrors.DAMAGE);
            }
            selected = candidate;
        }
        return selected;
    }

    private static boolean isProtectedSource(DamageSource source) {
        // 三叉戟在当前映射中继承 AbstractArrow，其他带投射物标签的来源不算实打。
        return source.is(VanillaDamageTypeTags.MELEE)
                || (source.is(DamageTypeTags.IS_PROJECTILE) && source.getDirectEntity() instanceof AbstractArrow);
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

    public static synchronized void registerTemplateDefaults(String template, String bundledSource) {
        if (bridge != null) {
            throw new IllegalStateException("伤害配置已安装，须在模组初始化时登记模板");
        }
        templateDefaults.register(template, bundledSource);
    }

    public static void registerDamageObserver(DamageObserver observer) {
        // 观察方只订阅提交后的事实，不能替换或短路原版伤害流程。
        damageObservers.add(java.util.Objects.requireNonNull(observer));
    }

    public static boolean recordShieldBlocked(LivingEntity target, LivingEntity attacker, DamageSource source) {
        // 盾挡在原版伤害早退前发生，统一桥负责保存攻击者身体时钟。
        return BlockedAttackBridge.observeShield(target, attacker, source);
    }

    public static boolean isVanillaShield(LivingEntity target) {
        return BlockedAttackBridge.isVanillaShield(target);
    }

    @Override
    public void onInitialize() {
        // 服务入口是可信进程内协议，不为客户端开放任意伤害网络包。
        VanillaAfflictions.register();
        registerDamageObserver(BlockedAttackBridge::observeDamage);
        TechniqueWeaponHeat.GRIP_BURN.register(VanillaWeaponHeat::burnHand);
        TechniqueWeaponBinds.register(new VanillaBoundArm());
        TechniqueWeaponSplash.HIT.register(VanillaWeaponSplash::hit);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> installBridge());
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

    private static synchronized void installBridge() {
        if (bridge != null) {
            return;
        }
        var loader = FabricLoader.getInstance();
        var hosts = loader.getEntrypoints("meridian_health_host", BodyHealthHost.class);
        var file = loader.getConfigDir().resolve("meridian-vanilla-damage.toml");
        preferences = DamagePreferences.load(file, templateDefaults);
        var integrations = loader.getEntrypoints("meridian_damage_integration", BodyDamageIntegration.class);
        var providers = loader.getEntrypoints(AttackEffectProvider.ENTRYPOINT, AttackEffectProvider.class);
        bridge = new VanillaDamageBridge(hosts, preferences, integrations, providers);
        TechniqueGuardDefense.register(bridge);
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
        double cappedDamage = DamageEquipmentOverrides.cappedDamage(source, scaledDamage, Optional.empty());
        applications.computeIfAbsent(entity, ignored -> new ArrayDeque<>())
                .push(new DamageFrame(source, rawDamage, coefficient, cappedDamage));
        return (float) cappedDamage;
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
        if (source.is(VanillaDamageTypeTags.PREPAID_MELEE)) {
            // 持物招式已在起手时付过臂力，打击仍使用该手此刻的肌力系数。
            return bridge.strikeCoefficient(attacker.getStringUUID(), mainArm, grip);
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
        // 正常返回时补齐完全抵挡的事实通知；打进去的主击仍留给附伤。
        Deque<DamageFrame> frames = applications.get(entity);
        if (frames == null) {
            return;
        }
        DamageFrame frame = frames.pop();
        if (frames.isEmpty()) {
            applications.remove(entity);
        }
        notifyAbsorbedGuard(entity, frame);
        VanillaInfusions.record(entity, frame.source, frame.cappedDamage, Optional.ofNullable(frame.hit));
    }

    private static void notifyAbsorbedGuard(LivingEntity entity, DamageFrame frame) {
        // 原版零剂量可跳过 setHealth；正常返回时补一次事实通知，不向身体提交零伤害。
        if (frame.completed || frame.defense == null) {
            return;
        }
        Optional<GuardState> guarded = frame.defense.guarded();
        if (guarded.isEmpty()) {
            return;
        }
        var result = frame.hit.commitIntegrated(0, frame.source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
        frame.completed = true;
        frame.weapon.commitWear();
        var input = new DamageInput(frame.kind, frame.rawDamage, 0, frame.hit.target(), frame.weapon.contact());
        notifyDamageObservers(new DamageEvent(entity, attacker(frame.source), frame.source, input, result));
        frame.defense.afterCommit(entity, result.body());
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
            Optional<GuardState> guard = TechniqueGuardDefense.active(entity);
            var boundPart = TechniqueWeaponBinds.aimedPart(entity, source);
            Optional<VanillaDamageBridge.PreparedHit> prepared;
            if (boundPart.isPresent()) {
                prepared = bridge.prepareDirect(
                        entity.getStringUUID(), sourceId, attack.kind(), boundPart.orElseThrow());
            } else {
                prepared = bridge.prepare(entity.getStringUUID(), sourceId, attack.attackerId(), attack.kind(),
                        entity.getRandom().nextDouble(), attack.aimGroup());
            }
            if (prepared.isEmpty()) {
                return Optional.empty();
            }
            VanillaDamageBridge.PreparedHit hit = prepared.get();
            DamageFrame frame = frames.peek();
            var effect = attackEffect(entity, source, attack, frame.rawDamage, frame.muscleMultiplier);
            AttackDose dose = attackDose(entity, sourceId, rawDamage, effect);
            var defense = TechniqueGuardDefense.prepare(entity, source, hit, OptionalDouble.empty(), guard);
            var weapon = WeaponGuardHit.prepare(entity, defense, dose.amount());
            TechniqueGuardDefense.attach(new TechniqueGuardDefense.GuardedHit(entity, source, hit, dose.amount()),
                    defense, weapon.contact());
            float remainingDamage = localArmorDamage(entity, source, hit, weapon.armDamage());
            frame.hit = hit;
            frame.protectedDamageShare = protectedDamageShare(entity, source, hit.body());
            frame.defense = defense;
            frame.weapon = weapon;
            frame.kind = attack.kind();
            frame.attackEffect = dose.effect();
            var part = hit.target().filter(HealthTarget::hasPartId).map(HealthTarget::getPartId);
            return Optional.of((float) IncomingDamage.apply(entity, source, frame.kind, part, remainingDamage));
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
        // 没有本次护甲倍数的普通结算。
        return localArmorDamage(entity, source, hit, damage, 1 /* hitMultiplier */);
    }

    private static float localArmorDamage(LivingEntity entity, DamageSource source,
                                          VanillaDamageBridge.PreparedHit hit, double damage, double hitMultiplier) {
        return localArmorDamage(entity, source, hit, damage, hitMultiplier, Map.of());
    }

    private static float localArmorDamage(LivingEntity entity, DamageSource source,
                                          VanillaDamageBridge.PreparedHit hit, double damage, double hitMultiplier,
                                          Map<String, LocalArmor> overrides) {
        // 只读取命中槽位的单件护甲，不叠加其他装备或实体固有属性；本次倍数（透劲）和登记的部位倍数相乘。
        if (damage == 0 || source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            return (float) damage;
        }
        String partId = hit.target().orElseThrow().getPartId();
        Optional<ArmorRules.Slot> coveredSlot = hit.coveredSlot();
        LocalArmor override = Optional.ofNullable(overrides.get(partId)).orElseGet(() ->
                DamageEquipmentOverrides.armor(entity, partId, coveredSlot.map(VanillaDamageFabric::equipmentSlot))
                        .orElse(null));
        if (coveredSlot.isEmpty()) {
            if (override == null) {
                return (float) damage;
            }
            double overrideMultiplier = hitMultiplier;
            return (float) hit.remainingVanillaDamage(damage,
                    override.armor() * overrideMultiplier,
                    override.toughness() * overrideMultiplier,
                    OptionalDouble.empty());
        }
        EquipmentSlot slot = equipmentSlot(coveredSlot.get());
        var stack = entity.getItemBySlot(slot);
        boolean hasOverride = override != null;
        boolean hasVanillaArmor = stack.is(armorTag(slot));
        if (!hasOverride && !hasVanillaArmor) {
            return (float) damage;
        }
        OptionalDouble remainingDurability = OptionalDouble.empty();
        if (hasVanillaArmor && stack.isDamageableItem()) {
            double remainingRatio = 1 - stack.getDamageValue() / (double) stack.getMaxDamage();
            remainingDurability = OptionalDouble.of(Math.max(0, Math.min(1, remainingRatio)));
        }
        double armor;
        double toughness;
        if (hasOverride) {
            armor = override.armor();
            toughness = override.toughness();
        } else {
            armor = armorAttribute(stack, slot, Attributes.ARMOR);
            toughness = armorAttribute(stack, slot, Attributes.ARMOR_TOUGHNESS);
        }
        double multiplier = partArmorMultiplier(entity, partId, slot, stack) * hitMultiplier;
        return (float) hit.remainingVanillaDamage(damage,
                armor * multiplier, toughness * multiplier, remainingDurability);
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
            var damageExtras = new VanillaDamageBridge.DamageExtras(OptionalDouble.empty(),
                    frame.protectedDamageShare, List.of());
            var contact = new LethalHitVeto.Hit(entity, attacker(source), source);
            var resolution = commit(contact, frame.hit, damageAfterEffects, damageExtras);
            var result = resolution.result();
            frame.completed = true;
            var body = result.body();
            boolean bodyDead = body.getVitality().getDead();
            if (bodyDead) {
                // 原版 hurtServer 随后读取血量，调用图腾检查、死亡事件与 die。
                entity.setHealth(0);
            }
            frame.weapon.commitWear();
            acceptAttackEffect(entity, frame);
            var input = new DamageInput(frame.kind, frame.rawDamage, damageAfterEffects,
                    frame.hit.target(), frame.weapon.contact());
            notifyDamageObservers(new DamageEvent(entity, attacker(source), source, input, result));
            frame.defense.afterCommit(entity, body);
            if (!bodyDead) {
                stagger(entity, frame.hit.staggerMillis(damageAfterEffects, frame.protectedDamageShare));
            }
            resolution.applyProtection();
            if (bodyDead) {
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
        if (event.input().damageAfterEffects() > 0 && !event.result().body().getVitality().getDead()) {
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
        private final double cappedDamage;
        private VanillaDamageBridge.PreparedHit hit;
        private OptionalDouble protectedDamageShare = OptionalDouble.empty();
        private TechniqueGuardDefense.Defense defense;
        private WeaponGuardHit weapon;
        private boolean completed;
        private DamageKind kind = DamageKind.CUSTOM;
        private VanillaDamageBridge.AttackEffectResolution attackEffect;
        private boolean attackEffectAccepted;

        private DamageFrame(DamageSource source, float rawDamage, double muscleMultiplier, double cappedDamage) {
            // 附效上限仍以乘系数前的原版剂量为准，额外伤害不重复乘肌肉系数。
            this.source = source;
            this.rawDamage = rawDamage;
            this.muscleMultiplier = muscleMultiplier;
            this.cappedDamage = cappedDamage;
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
            // 我们给的状态效果不出粒子，图标照常显示。
            entity.addEffect(new MobEffectInstance(effect.get(), status.getDurationTicks(), status.getAmplifier(),
                    false /* ambient */, false /* showParticles */, true /* showIcon */));
        } catch (RuntimeException failure) {
            // 协议已限制字符串和数值；注册表缺失仍只丢弃该可选状态，不影响已提交伤害。
            LOGGER.error(VanillaDamageErrors.ATTACK_EFFECT_STATUS + " target=" + entity.getStringUUID()
                    + " effect=" + status.getEffectId(), failure);
        }
    }
}
