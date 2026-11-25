# 构建与宿主接入

## 本地构建

要求 Java 25，Minecraft 26.2、Fabric Loader 0.20.1、Fabric API 0.161.0+26.2。相邻 Framework 仓库通过 Gradle composite build 接入：`settings.gradle` 应用其 `scripts/workspace.gradle`，按装配清单组合框架并取得版本目录 `libs`，也可用 `-Pkizuna.dir.framework="/path/to/framework"` 指定位置；`local.meridian:server` 仅为本地替换键，不是已发布的 Maven 坐标。

```sh
./gradlew build
./gradlew :fabric:runGametest
python3 scripts/check_docs.py
```

安装使用 `fabric/build/libs/` 内不带 `sources` 后缀的 JAR（26.2 不混淆，构建不重映射），并安装 Fabric API。构建会嵌入纯 Java 规则、Framework 服务端库与所需运行依赖。伤害结算只在服务端执行，单机由集成服务器执行；客户端同样安装本模组，伤害类型的[显示名键](Damage-Rules.md#显示名键)由它提供。

本附属不创建身体，玩家身体由必装 Human Addon 提供。八仓按适用端整套安装，开发运行统一应用 Framework 的 `scripts/full-assembly.gradle`。健康宿主和伤害效果集成都必须装配，缺接线启动报错；所有宿主明确返回该实体未绑定时才保留原版行为。本包不依赖 Human 内部类，其他生物附属采用相同接口。

Technique 功法库是本附属的硬依赖：命中后的[受击僵直](Damage-Rules.md#受击僵直)直接调用 `TechniqueFabric.service().stagger`。功法库与框架一样在 settings 中以源码组合（`includeSource`，替换键用功法工程的真实坐标 `dev.kizuna.meridian.technique:*`，与依赖声明一致），编译与开发运行使用其 fabric、server、shared 工程的当前输出，相邻仓库缺失时组合构建直接报错；`fabric.mod.json` 声明 `meridian_technique`。Damage 源码不依赖具体功法实现。

AI 预估用两个只读入口，不查询宿主、不提交做功：`VanillaDamageFabric.preferences()` 返回已校验的整份规则（例如 `stagger().millis(...)` 估算一击能否造成僵直），`VanillaDamageFabric.estimateStrikeCoefficient(snapshot, entity, grip)` 用调用方已有的攻击者快照按[肌力系数](Damage-Rules.md)同一公式与夹取给出系数。虚脱由宿主在真实做功时判定，预估不包含这一项。

## 登记模板默认配置

附属在 `ModInitializer.onInitialize` 中调用 `VanillaDamageFabric.registerTemplateDefaults(templateId, bundledSourceId)`。来源取自随包 TOML，例如 `human:adult`。目标模板分别继承 `armor.coverage`、`anatomies`、`guard`、`aims`。服主已为目标写出的某一段整体优先，其余段仍继承。启动时合并，已有配置文件保持原样。同一目标重复登记、来源缺段或配置已安装后再登记都会报错。

## 可信 Protobuf 入口

普通受击与直接伤害在有效部位的护甲结算后都调用 `IncomingDamage.register` 登记的承伤倍率。
回调可读目标、来源、出手装备、伤害分类和落点。多个倍率相乘，零表示吸收本次伤害。
功法的真元附伤可使用 `IncomingDamage.QiSource` 保留来源身份，伤害类型和所属实体照常由原版提供。

### 可信指定部位伤害与局部护甲修正

服务端主线程可调用 `VanillaDamageFabric.damageDirect(DirectDamage)`，参数为目标、真实 `DamageSource`、
实际 `partId` 和正数身体 HP。来源的 `kind/*`、`bypasses_armor` 等标签沿用正常伤害映射。
入口固定唯一宿主和原部位，经过[格挡判定](Damage-Rules.md#通用格挡)后读取有效落点的局部护甲。
成功格挡改点时沿用身体宿主的溢伤规则；其余直接伤害裁到该部位剩余 HP，避免过量药伤转成其他部位的溢伤。
未绑定、死体或归零部位返回空；未知部位和已认领宿主失败明确报错。
入口先执行原版 `isInvulnerableTo`、已死/正在死检查及玩家 abilities 无敌检查。
创造与旁观玩家的无敌仍遵守 `bypasses_invulnerability`，绕过无敌帧不等于绕过无敌状态。

此入口直接提交身体事务，不经过原版 `hurtServer`，因此不吃无敌帧、盾挡，不产生击退；
也不另走原版抗性和保护附魔阶段。局部黄心、图腾复用必装 Healing 的伤害上下文和已有集成协议。
`registerDamageScope(DamageScope)` 接受上层附属的 `enter(entity)` / `exit(entity)` 登记；
直接伤害逐个进入、逆序退出，提交或进入失败也释放此前已进入的上下文。
Healing 初始化时登记自己的伤害上下文，Damage 源码与编译产物均不依赖 Healing。
成功后记录攻击者、击杀归属、伤害观察者和僵直，死亡与图腾消费沿已有实体同步路径执行。
输入单位是身体 HP，内部按 Damage 配置转换后再共用结算。它不触发攻击方附效（附伤等）。

带 `meridian:melee` 标签、来源实体是活物的直接伤害按近战出手处理：过护甲之前先乘出手肢体的肌肉系数，并给出手肢体记做功，
规则和普通受伤入口相同（出手侧、`leg` 标签、力竭系数都一样）。调用方可以先用 `VanillaStrikeContext.damage(source)` 开一个出手上下文，
在里面连续提交多次直接伤害或混用普通入口，整次出手只算一次系数、只记一笔做功；没开的话每次直接伤害各算一次出手。
不带近战标签的直接伤害（药、毒、附伤）不乘系数、不记做功。

持物招式若已在起手通过身体宿主付清手臂做功，可给专用伤害类型同时加上
`#meridian:melee` 和 `#meridian:prepaid_melee`。有效拍按原握法取当前肌力系数，跳过出手者的第二笔做功。
近战格挡、护甲、命中附效和守方的格挡费用照常结算。该标签仅用于已经付费的手臂打击。

`DirectDamage` 的第五个参数 `DirectExtras` 是这一下另带的三样，都可以不给（`DirectExtras.NONE`）：

| 字段 | 含义 |
|---|---|
| `organShare` | 透进器官的份额 [0, 1]，原样写进 `DamageBody.organ_share`，怎么分层由生物附属的伤害策略决定 |
| `armorMultiplier` | 只对这一下生效的局部护甲倍数（非负），和 `registerPartArmorModifier` 登记的倍数相乘；0 表示这一下不看护甲 |
| `modifiers` | 和扣血同一笔加到目标身上的能力修正（如麻）；来源统一写成 `meridian_vanilla:damage`，开始时刻由 Framework 写 |

- 修正加上以后会超过身体的 32 条上限时，这一下只提交伤害和份额，修正整批不加，并记一条警告，不让整下伤害被拒。
- 伤害被黄心完全吸收、没有提交身体事务时，修正也不会加。
- 这一下打死了目标时不留修正。

`VanillaDamageFabric.presentHurt(target, source)` 给刚挨了直接伤害的活物补原版的受伤表现：服务端记受伤动作时长、
广播伤害事件（客户端的红闪、受伤动作）、播原版受伤声。直接伤害入口自己不调它：拳脚这类「打在身上」的调用方提交成功后调一次，
药、毒、附伤照旧不闪。它不动无敌帧、不击退；目标已死或正在死时什么也不做。

附属通过 `registerPartArmorModifier` 注册只读回调，接收目标、部位、覆盖槽和真实物品，返回非负有限倍率。
所有提供方的倍率相乘，用于该件护甲值与韧性，再执行现有耐久曲线与减伤公式；无效结果直接报错。
`coveredEquipmentSlot(body, partId)` 公开实际模板的配置覆盖关系，附属无需复制人体覆盖表。
回调不改变物品耐久；蚀甲等内容须在自己的效果周期调用原版物品耐久入口。

### 撒放时的只读手臂系数

`VanillaDamageFabric.armCoefficient(LivingEntity entity, HumanoidArm arm)` 在服务端向唯一身体宿主
重新查询当前快照，按显式的左臂或右臂计算肌肉系数。纯 Java 对应入口是
`VanillaDamageBridge.armCoefficient(String subjectId, MuscleRules.Arm arm)`。
宿主按施放选定的持武器手与实体实际主手侧，传入另一条拉弦臂；接口不自行猜测手位。
每次撒放都重新查询，不复用起手或近战出手缓存。

计算复用近战的力量比例、伤势、疲劳、修正曲线和配置夹取；不构造或提交做功命令，
不增加疲劳、训练负荷或 revision。无身体、无匹配手臂肌肉时取 1；配置禁用时直接取 1，
不查询身体。宿主失败或重复认领仍报错，不当成无身体。拉弦做功由功法费用承担。
箭命中仍由原版速度结算，未进入 `#meridian:melee`，Damage 不再乘系数或记一笔手臂做功。

`VanillaDamageFabric.armCoefficientAt(entity, arm, elapsedMillis)` 供同时到达的弹道计算预估参考平射
时刻；纯 Java 对应 `VanillaDamageBridge.armCoefficientAt(subjectId, arm, elapsedMillis)`，已有快照
可直接用 `MuscleRules.armCoefficientAt(snapshot, arm, elapsedMillis)`。时刻必须属于当前身体时钟，
不得早于快照时刻；负值和回溯请求报错。查询会重新读取宿主，在内存候选上调用 Framework 的
`BodyTimeAdvancement`（`LIFESPAN_ONLY`、老化倍率 0），冻结当前伤势、疲劳与寿命，只读取已登记
曲线在参考时刻的值和到期状态，再复用同一肌力公式及夹取。候选不提交，权威 revision、身体时钟、
资源、任务和疲劳均不改变；不会预言后续受伤、做功或新加曲线。功法起手已经登记的未来曲线可直接
参与预估。实际撒放仍调用当前查询，不能把未来预估缓存作为实际初速系数。

`VanillaDamageFabric.inspectBody(entity)` 只读返回唯一宿主的当前身体快照或无身体结果。
需要预估功法自身尚未登记的修正时，内容宿主可以在该不可变快照上构造候选，随后调用同一
`VanillaDamageFabric.armCoefficientAt(candidate, arm, elapsedMillis)`。此重载复用
`MuscleRules.armCoefficientAt`，调用方无需依赖 Damage 的内部配置类型；候选不得提交，
也不能用于当前撒放系数。未知宿主错误仍向上传递。

Fabric 通过 `meridian_health_host` entrypoint 查找 Framework `BodyHealthHost`。宿主实现 `exchange(byte[])`，交换 protoc 生成的 `HealthHostRequest` / `HealthHostResponse`；`.proto` 位于 Framework 的 `shared/src/main/proto/kizuna/meridian/v1/health_host.proto`。

```json
{
  "entrypoints": {
    "meridian_health_host": ["example.CreatureHealthHost"]
  }
}
```

入口由可信服务端代码调用，不是客户端可以上传任意剂量的网络通道。本适配以实体 UUID 作为 `subject_id`；宿主验证真实绑定、线程、权限、时间和身体归属，返回完整当前权威快照。

每次事件在护甲阶段先查询所有宿主，确保恰好一个认领该身份。只有 `unbound = true` 表示可以继续寻找；明确失败、格式错误或多个认领立即拒绝，不尝试另一套账本。`prepare` 固定宿主、部位与 revision，供局部护甲结算使用；`PreparedHit.commit` 在原版抗性、附魔与吸收之后单次提交完整剂量。准备本身不扣身体 HP，完全吸收时直接丢弃。提交时使用同一宿主、固定请求 ID 和读到的身体 revision，框架继续校验、去重及原子提交。失败不重新随机选点，也不擅自刷新版本重试。

近战在进入护甲阶段前，另对攻击者使用同样的唯一宿主查询规则。`exertStrike` 从做功前快照计算系数，再按原版剂量和 `[muscle]` 的焦耳、时长配置构造普通 `ExertTissues`，向同一宿主提交 `exertion`；保留身体 ID、预期 revision 与请求 ID。做功成功只要求返回同一身体，能力不足按力竭系数命中，其他错误抛出。完整规则、标签与配置见[肌肉出力](Damage-Rules.md#肌肉出力)，交付证据见[实现验证](Muscle-Validation.md)。

## 伤后事实通知

成功改点且守式声明体力费用时，普通入口取护甲前的出手剂量，直接入口取出手折算后的剂量。二者均按 `剂量 × (1 − 守倍率)` 冻结守式免掉的伤害。`meridian_guard_cost` 的唯一提供方实现 `VanillaDamageFabric.GuardCost`，从该击的身体、守式与承力样式生成附带明细。正剂量随健康命令一次提交，完全吸收时只提交一次含该明细的身体活动。两种出口都在提交成功后结兵器耐久并通知观察方。

`registerDamageObserver` 接收普通受伤与直接肢击结算成功后的 `DamageEvent`，每击恰好一次。
`input.guarded()` 是 `Optional<GuardState>`：仅本击成功格挡改点时有值，带判定当刻冻结的身体和施放身份；
背后、透劲、原本命中前臂等未挡住的情况为空。表现宿主应消费这一事实，不根据当前守状态或最终落点推断。

`input.weaponGuarded()` 为本击判定时冻结的兵器挡标记。主手兵器承接时为真，不可损兵器同样为真。
兵器在本击耗尽时，`guarded()` 仍带守身份，整击由前臂承接，兵器标记为假。空手、主手物品未入标签和未挡住时也为假。

通知先于毁尽挡位后的 `endActive`。因此最后一条前臂毁掉时，宿主先收到受击反馈，再停保持和受击片段、进入收势。
守倍率为零或后续减免吃光剂量时仍通知；普通路若原版跳过扣血位置，在 `actuallyHurt` 正常返回处补通知，
已完成的命中不会补发。直接路仍在原结算出口通知。零剂量不另交健康命令，不消耗局部黄心或图腾。

## 攻击方附效入口

伤害附属提供 [AttackEffectProvider](../../server/src/main/java/dev/kizuna/meridian/vanilla/AttackEffectProvider.java)，并在自己的 `fabric.mod.json` 中注册 `meridian_attack_effect`。Damage 在服务端主线程只对已绑定身体的近战攻击者查询快照，然后按 [attack_effect.proto](../../server/src/main/proto/kizuna/meridian/vanilla/v1/attack_effect.proto) 编解码上下文和方案。上下文字段 7 `raw_damage_hp` 是乘肌肉系数后的原版剂量，字段 10 `vanilla_damage_hp` 是乘系数前的原版剂量，字段 11 `muscle_multiplier` 是本次系数。提供方不依赖 Damage 的内部 Java 类之外的身体实现，也不能上传伤害来源、目标部位或原版剂量。

`handled` 方案最多一个；附加剂量不再乘肌肉系数，和字段 7 的原版剂量共用一次局部护甲和身体事务，状态效果在 `hurt` 返回时施加。附加剂量上限按字段 10 的原版剂量乘 `max_extra_ratio` 计算，默认 4.0。远程来源、未绑定攻击者、提供方异常、非法方案和重复认领均跳过附效并保留原版伤害。`effect_id` 在同一目标上去重，配置可关闭整个入口。

提供方示例（只用于仓库 GameTest）在 `fabric/src/gametest/resources/fabric.mod.json` 注册；功法库等其他仓库应依赖本仓库 server JAR 并自行实现入口，本仓库不会修改它们。

## 五行附伤接入

附伤不走 `meridian_attack_effect` 提供方：功法库在主人身体的 `addonProgress`（键 `meridian_technique:state`）里写 `ActiveInfusion`，Damage 在主击打进去后读主人只读快照，交给功法库 `TechniqueInfusions.strike` 求值，再自己落伤害。内容附属要做的只有数据：

- 注册附伤用的 `damage_type`，并打上对应 `#meridian:kind/*`、`#minecraft:no_knockback`、`#minecraft:bypasses_cooldown`；不要放进 `#meridian:melee` 或 `#meridian:infusable`。
- 让自己的近战伤害类型带附伤：进 `#meridian:melee`（同时接入肌肉与附效）即可；只想带附伤不想吃肌肉系数时进 `#meridian:infusable`。
- 直接实体不是攻击者本人、伤害类型又不能整体收进来的（例如尖牙的 `indirect_magic`），把实体类型追加进 `#meridian:infusable_direct`；造成伤害的实体是召唤物、要按它的主人找附行的，追加进 `#meridian:infusion_via_owner`，召唤物须实现 `TraceableEntity`；没实现的仍按它自己找附行，通常就不带附伤。

规则与原版附带见[五行附伤](Damage-Rules.md#五行附伤)。

死亡通过 `confirm_body_death` 确认；它不生成伪造的大额伤害，不覆盖局部 HP。身体、释放账户和回执的保存仍属于宿主唯一状态，本附属不另存一套身体。

必装 Vanilla Healing 注册 `meridian_damage_integration`，实现 Framework `BodyDamageIntegration` 的 Protobuf 准备与提交通知。`PreparedHit.commitIntegrated` 在固定命中后读取只减伤的方案，将受保护的剩余伤害提交到同一宿主；效果成功响应必须附带同请求固定 `HealthResult`，其格式和命令一致性经框架校验后才消费图腾。已绑定的命中必须由唯一效果集成认领，没有认领直接报错；完全局部吸收无需 HP 事务。原版治疗与局部黄心详见[伤害规则](Damage-Rules.md)。

## 验证范围与失败行为

必要整体回归覆盖配置及选点、覆盖表与公式修改、伤害单位换算、耐久曲线端点及插值、无耐久物品、减免比例上限与执行顺序、异常配置拒绝、真实框架请求链、拒绝与唯一宿主、单次命中提交和死亡保留伤势。服务端单元回归覆盖攻击附效的无认领、单认领、多认领、未绑定攻击者、远程跳过、上限截断、异常和 `effect_id` 去重；Fabric GameTest 在真实 Minecraft 服务端使用仓库内测试提供方验证僵尸近战命中合并附加剂量和状态效果，并继续验证玩家和普通生物的局部护甲路径、未覆盖与未绑定分支、物品属性组件、磨损前耐久曲线、耐久衰减后限幅、不可损坏护甲、原版耐久磨损、抗性、全身保护附魔、吸收生命、绕甲来源、无敌帧、超出原版剩余血量的完整剂量及两种死亡方向；测试宿主和样例身体不进入安装 JAR。五行附伤由 `DamageInfusionGameTest` 覆盖：主人是绑了身体、`addonProgress` 里直接放 `ActiveInfusion` 的僵尸，附伤类型用测试数据包里的 `meridian_damage_test:infuse_*`。用例验证有身体目标的同部位、伤害类型、致伤机制、剂量、不击退与无敌帧不变，没身体目标的原版扣血与无敌帧恢复，着火、冻结上限、冻不了的跳过、追加击退的方向、主击击退之后的净增量与击退抗性折算，目标处的命中声包，没身体、没附行、到期、箭都不带，恼鬼按主人带、主人死了不带，尖牙有主人带、没主人不带，主击打死目标、盾挡、部位已是 0 HP 都没有附伤。相克倍率的端到端用例在五脏诀仓。挥手包用例的假玩家取自功法库 `fabric` 的 `testFixtures`（`TestPlayers.detached`：正式网络处理器、不进玩家列表），与功法 fabric 同经源码组合解析。

Minecraft 实体状态与身体 Store 之间目前没有跨系统持久化事务。同步失败记录日志并抛错，不继续原版扣血，不宣称可靠补偿或恰好一次世界副作用；框架内部的身体变更与成功回执仍为原子提交。Windows 手动游玩、专服多人及其他战斗模组兼容需要独立验收。

## 热兵的皮肤伤害

`TechniqueWeaponHeat.HeatedStrike` 复用主击命中记录。对身体宿主，按主击剂量和配置比例，在同一部位经过局部护甲结算后将热伤提交给皮肤组织。握持热伤按惯用手及出手侧选择手掌。实体火免和抗火状态均在热伤提交前生效。无身体的命中目标沿用原版附伤入口。

`TechniqueWeaponSplash.HIT` 在玩家与生物共用的出手上下文内结算一次泼洒。绑定身体的目标在存活部位中无放回选点，优先选择有出力组织的部位。热伤按实际手侧和局部护甲结算，有皮肤时落在皮肤上。同一身体事务对所选部位的出力组织附加 `tempo_peak` 曲线。普通原版目标使用原版火焰受伤入口。
