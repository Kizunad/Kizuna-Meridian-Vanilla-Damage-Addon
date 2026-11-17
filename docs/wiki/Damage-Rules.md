# 伤害、命中偏好与死亡

## 部位护甲与伤害顺序

服务端接入 `LivingEntity.actuallyHurt` 与玩家覆盖的 `Player.actuallyHurt`。原版先处理免疫、盾牌和无敌帧；进入 `actuallyHurt` 时先应用攻击者肌肉系数，随后在 `getDamageAfterArmorAbsorb` 查询唯一目标身体宿主，并按怪物偏好固定一次命中部位与身体版本。原版无敌帧内较强攻击仍只对超出的伤害继续结算。

1. 护甲前伤害按 `hp_per_vanilla_hp` 换算为身体 HP；默认 1:1，原版 1 HP 为半颗心。
2. 按身体模板与命中部位查覆盖槽位；只读取该槽正确穿戴的单件护甲（原版该槽位盔甲标签内的物品）的护甲值、韧性与本次受击磨损前的剩余耐久比例，包括该物品属性修饰组件里自带的修饰。不叠加其他装备或实体固有护甲，也不放大单件护甲的强度。
3. 用 TOML 公式计算基础减免比例，乘耐久曲线系数，再应用最终减免比例上限，得到局部护甲后的伤害并换回原版 HP 单位。没有覆盖、槽位为空、物品不是对应护甲或来源带 `bypasses_armor` 标签时不减伤。已绑定身体的这条路径取消原版全局护甲数值结算，避免重复。
4. 后续仍由原版执行抗性、保护附魔；保护附魔继续取全身装备，保持原版的各类绕过标签和效果逻辑。必装 Vanilla Healing 将吸收生命通过下述可信集成按命中部位结算，原版全局吸收不会再次减免。
5. 在原版扣血位置，将上述机制结算后的完整剂量提交给原先固定的身体部位。身体仅提交一次；完全抵挡或吸收时不提交。原版剩余 HP 的归零钳制不截断身体剂量，例如原版只剩 1 HP 时受到 4 点结算伤害，身体仍接收 4 点的对应剂量。

这五步先计算再原子提交身体，不先扣一笔原始伤害再补回减免。Healing 按身体剩余 HP 比例同步原版血条，拦截原版随后尝试的直接扣血；Human 继续决定皮肤、骨骼、肌肉与内脏的连带损伤，内部账户不产生第二次原版扣血。

Human 在这一次身体事务内将命中部位的过量伤害按模板系数分配给其他未毁部位，按当前实际上限 HP 加权并迭代处理饱和。已毁部位仍可命中，整次伤害按来源系数蔓延。护甲只在原命中部位计算一次，接收部位不再经过自己的护甲；其他减伤同样不会因接收者数量而重复。观察器提供固定提交前后快照和同一请求的 `HealthResult`，调试库直接展示回执里的余量、系数、接收部位及剂量。

未绑定身体的实体继续使用原版全局护甲，也不应用此处新增的耐久曲线和比例上限。装备耐久磨损、保护附魔、反伤等仍沿用原版规则；本次未将耐久磨损改成只损耗命中的护甲。本次伤害先读取剩余耐久，再执行原版磨损；本次打坏装备会影响下一次命中的保护。

## 可配置覆盖与公式

`config/meridian-vanilla-damage.toml` 在首次启动导出，修改后重启生效。已有配置完全缺少 `armor` 节时，使用内置该节默认值且不重写文件；需要自定义时加入下面完整的 `armor` 配置。已有显式 `armor` 配置需要补上新增的 `maximum_reduction_ratio` 和 `durability_curve`；缺字段、出现重复覆盖或参数非法，启动时明确拒绝。默认 60% 上限和耐久曲线均为可调占位参数，尚未经过完整游玩平衡校准。

| 槽位 | 默认覆盖 |
|---|---|
| `head` 头盔 | 头、颈 |
| `chest` 胸甲 | 胸、腹、左右上臂 |
| `legs` 护腿 | 骨盆、左右大腿和小腿 |
| `feet` 靴子 | 左右足 |

前臂、手以及新增未登记部位不受普通护甲覆盖。覆盖表按模板独立配置，一个部位最多对应一个槽位；未知模板不套用人体覆盖。

```toml
[armor]
# 最终护甲减免比例上限：60%，不是护甲点数。
maximum_reduction_ratio = 0.60
# [剩余耐久比例, 减免系数]，按剩余耐久严格递增。
durability_curve = [[0.0, 0.0], [0.1, 0.35], [0.25, 0.6], [0.5, 0.85], [1.0, 1.0]]

[armor.formula]
type = "toughness"
damage_divisor_base = 2.0
toughness_divisor = 4.0
minimum_armor_fraction = 0.2
effective_armor_cap = 20.0 # 有效护甲值上限，单位为护甲点数。
reduction_divisor = 25.0

[armor.coverage."human:adult"]
head = ["head", "neck"]
chest = ["chest", "abdomen", "left_upper_arm", "right_upper_arm"]
legs = ["pelvis", "left_thigh", "right_thigh", "left_lower_leg", "right_lower_leg"]
feet = ["left_foot", "right_foot"]
```

公式以数值参数配置，不执行任意脚本。设换算后的身体伤害为 `D`，单件护甲值为 `A`，韧性为 `T`：

```text
削弱分母 = damage_divisor_base + T / toughness_divisor
护甲下限 = min(A × minimum_armor_fraction, effective_armor_cap)
有效护甲 = clamp(A - D / 削弱分母, 护甲下限, effective_armor_cap)
基础减免比例 = 有效护甲 / reduction_divisor
耐久系数 = 按剩余耐久比例在 durability_curve 相邻点之间线性插值
最终护甲减免比例 = min(基础减免比例 × 耐久系数, maximum_reduction_ratio)
护甲后伤害 = D × (1 - 最终护甲减免比例)
```

默认基础公式仍对应原版韧性公式，只输入一件装备。三个分母参数须大于零且不超过 1000；最低比例在 `[0, 1]`，有效护甲上限在 `[0, 1000]` 且不超过减伤分母。`effective_armor_cap` 限制公式里的有效护甲点数；`maximum_reduction_ratio` 单独限制本部位本次最终护甲减免的百分比，范围为 `[0, 1]`，设为 `0` 即取消护甲减伤。这个上限只约束护甲，后续抗性、保护附魔和吸收生命仍按原版继续计算。

### 耐久曲线

| 剩余耐久 | 默认减免系数 |
|---|---|
| 100% | 1.0 |
| 50% | 0.85 |
| 25% | 0.6 |
| 10% | 0.35 |
| 0% | 0.0 |

配置至少包含两个点，必须覆盖横坐标 `0` 和 `1`，横坐标严格递增，系数单调不减；所有数值均为 `[0, 1]` 内的有限数。段内线性插值，例如剩余耐久 75% 时系数为 `0.925`。端点系数也可调整，但不能用大于 `1` 的系数放大基础减免。没有耐久的物品系数恒为 `1`；Minecraft 标记为 `Unbreakable` 的物品也按此规则处理，即使残留 `Damage` NBT。耐久比例由服务端物品状态读取，不接受客户端指定。

例如 `D=4`、单件满耐久钻石胸甲 `A=8/T=2`，基础减免为 `25.6%`，仍受到 `2.976`；半耐久时乘 `0.85`，受到 `3.1296`。击中未覆盖前臂仍受到 `4`。基础减免为 `80%` 的高护甲在半耐久时先得 `68%`，再限为 `60%`，所以受到 `1.6`，不能先限为 `60%` 再乘耐久系数。以上数值之后才应用非护甲减免。

## 怪物命中偏好

首次启动导出 `config/meridian-vanilla-damage.toml`，后续只读取已有配置，修改后重启。权重为一组部位的总份额，再均分给组内实际存在的部位，包括 HP 已归零的部位；它们是相对权重，归一化后才是概率。

```toml
hp_per_vanilla_hp = 1.0

[anatomies."human:adult"]
head = ["head"]
torso = ["neck", "chest", "abdomen", "pelvis"]
arms = ["left_upper_arm", "right_upper_arm", "left_forearm", "right_forearm", "left_hand", "right_hand"]
legs = ["left_thigh", "right_thigh", "left_lower_leg", "right_lower_leg", "left_foot", "right_foot"]

[aims."human:adult"]
head_neck = ["head", "neck"]

[attackers."minecraft:spider"]
head = 5
torso = 15
arms = 10
legs = 70
other = 5
```

默认蜘蛛、洞穴蜘蛛、蠹虫、末影螨、史莱姆、岩浆怪及狼偏向下肢；骷髅、流浪者偏头部及躯干；僵尸、尸壳、溺尸、凋灵骷髅、末影人、铁傀儡和劫掠兽偏躯干；幻翼偏头部及上身。具体权重以导出的配置为准。

劫掠者、卫道士、唤魔者、幻术师和女巫也有独立配置：劫掠者与幻术师沿用弓/弩的远程分布，卫道士偏向躯干和手臂，唤魔者尖牙偏向腿部，女巫药水较均匀。村民和流浪商人不攻击，不添加攻击者条目。

这是一次成功攻击后的身体选点，不改变怪物 AI、箭矢轨迹或碰撞箱。远程攻击取 `DamageSource.getEntity()` 的射手类型。未知模板、未知攻击者或所有可用组权重为零时，在实际存在的部位间等概率选取；新增未分组部位使用 `other`。只有从实际构型移除的部位才退出候选。没有可用部位时由核心策略按当前构型处理，不生成虚构肢体。

伤害类型可以声明 `#meridian:aim/head`、`#meridian:aim/head_neck`、`#meridian:aim/torso`、`#meridian:aim/legs`、`#meridian:aim/arms` 或 `#meridian:aim/other`，把候选收敛到目标模板的对应分组，再从实际存在的部位中均匀选取。模板没有该分组或该组没有实际部位时，退回无 `aim` 的攻击者偏好路径；未声明 `aim` 时继续使用上面的攻击者偏好。一个伤害类型最多属于一个 `aim/*` 标签，冲突会在标签加载和使用时拒绝。

瞄准的分组先查 `[aims."<模板>"]`，这张表里没有这个组名时再查 `[anatomies."<模板>"]` 的同名分组。`aims` 只给瞄准标签用，不参与攻击者偏好，所以里面的部位可以和 `anatomies`、和别的瞄准组重叠：随包的 `head_neck = ["head", "neck"]` 让「打头颈」的伤害在头、颈之间各一半，而颈在攻击者偏好里仍然算躯干的一份。旧配置文件里没有 `[aims]` 这一节时用随包的默认值，不改写服主的文件；写了 `[aims]` 就以文件里的为准。

## 致伤类型

先读取下述 `kind/*` 标签；未指定时使用本表分类。

| 原版来源 | Framework 机制 |
|---|---|
| 剑、斧近战 | `CUTTING` 切割 |
| 箭、三叉戟、尖刺，以及部分怪物咬击 | `PIERCING` 穿刺 |
| 普通近战、坠落、碰撞、挤压、爆炸 | `BLUNT` 钝击 |
| 火焰、熔岩、高温方块与火球 | `HEAT` 灼热 |
| 冻结 | `COLD` 寒冷 |
| 雷电 | `ELECTRIC` 电击 |
| 龙息 | `CORROSIVE` 腐蚀 |
| 凋零 | `TOXIC` 毒性 |
| 唤魔者尖牙（`indirect_magic`） | `PIERCING` 穿刺 |
| 女巫伤害药水（`indirect_magic`） | `TOXIC` 毒性 |
| 其他 `indirect_magic` | `CUSTOM` |
| 其他或不能可靠细分的来源 | `CUSTOM`，保留原版 damage type ID |

分类描述本次致伤机制，不自动建立中毒、流血或灼烧周期。其他模组添加的来源默认保留其 ID，由生物策略解释。

### 显示名键

九种机制的显示名是公开语言键 `damage_kind.meridian.<kind>`，`<kind>` 为 Framework `DamageKind` 枚举名的小写（`blunt`、`cutting`、`piercing`、`heat`、`cold`、`electric`、`corrosive`、`toxic`、`custom`），由本模组的简体中文与英文语言文件提供。其他模组显示伤害类型时直接引用这些键，不另写一份类型名。

## 肌肉出力

带 `#meridian:melee` 标签的命中，在护甲、抗性、保护附魔和吸收之前，将原版已计算的冷却、暴击、横扫伤害乘一次肌肉系数。系数属于攻击者，因此目标没有身体也生效；攻击者未绑定身体时系数为 `1`，不提交做功。

```text
肌肉系数 = clamp(出手肌肉当前可用力量之和 / 同一组肌肉基础力量之和,
                 min_coefficient, max_coefficient)
```

当前可用力量直接使用 Framework `TissueCapabilities.available`，包含部位与组织伤势、疲劳和能力修正曲线。分母只取同一组肌肉定义中的基础力量，不使用功率。健康且无疲劳、无修正时系数为 `1`；人类单臂基础力量合计为 `830 N`。

远程宿主可在撒放时调用[只读手臂系数查询](Integration.md#撒放时的只读手臂系数)，将拉弦臂系数乘到箭的初速。
查询与近战使用同一算法，但不记做功；箭命中不带 `#meridian:melee`，因此不再缩放命中伤害或提交手臂做功。

默认按攻击者实际主手侧选组织，左主手取左臂；`#meridian:off_hand` 取相反侧，`#meridian:two_handed` 合并两臂。两标签同时存在时双手优先。组织须同时匹配配置部位及组织标签。已绑定身体没有匹配组织时系数为 `1`，不提交空做功；这允许不具备该解剖结构的身体使用同一宿主接口。

带 `#meridian:leg` 的来源改用腿部配置。腿部组织按组织 ID 选择左、右两组，因此臀大肌等共享骨盆部位时仍能区分左右；`#meridian:off_hand` 取主手相反侧的腿，`#meridian:two_handed` 合并两条腿，双手标签优先。腿部每次做功按原版伤害 HP 换算焦耳，在选中组织间均分；每块组织的功率为分得的焦耳数除以 `leg_swing_seconds`，力量字段为 `0`。

一次出手先读取身体计算系数，再将所有选中肌肉作为一组 `ExertTissues`，经同一攻击者的
`BodyHealthHost.exertion` 原子提交。玩家 `attack` 与生物 `doHurtTarget` 的整个调用共用一份出手上下文，
横扫主目标和所有附带目标复用同一笔做功前系数；每个目标进入自己的护甲结算前只缩放一次。
手臂做功量按原版剂量换算：`总焦耳 = 原版伤害 HP × work_joules_per_vanilla_hp`，
`总功率 = 总焦耳 ÷ strike_seconds`。每块选中肌肉按基础爆发功率占比分摊总功率，力量字段为 `0`，
时长统一为 `strike_seconds`；双手因此按两臂基础爆发功率分摊同一笔做功。默认配置注释说明当前焦耳口径。
请求固定读取时的身体 ID、revision 和幂等 ID，宿主使用可信身份与时钟走 Framework 普通命令入口。

出力比保持线性，不额外取指数。出手肌肉的可用力量仍高于力竭系数时，命中系数按比例连续下降；
只有功率或耐力不足导致 Framework 拒绝整段做功时，才使用 `exhausted_coefficient`。

做功按“出手就记”：目标被盾挡、免疫或拒绝命中仍记该次做功；无目标的主手挥手包记为挥空。
被 Fabric `AttackEntityCallback` 拒绝的攻击（例如施放中的玩家）没有进入原版出手，不读身体、不记做功，
也不与随后的挥手包配对；26.2 起该回调注入在 `Player.attack` 头部，出手上下文因此在方法体开始时建立。
实体攻击后的首个主手动画包在同 tick 或下一 tick 内与攻击配对，不重复做功；副手动画不计主手出手。
挖掘期间的动画、物品使用、方块交互和实体交互产生的动画包都会被抑制；只有没有这些交互标记、
也没有攻击配对的主手动画才按挥空处理。
直接调用 `LivingEntity.hurt` 的带近战标签伤害若没有外层出手上下文，则该调用单独算一次出手；
多目标自定义攻击应在外层用 `VanillaStrikeContext.attack` 包围全部目标，不能逐目标重新建立上下文。
调用以栈隔离嵌套出手，正常返回和异常退出都会释放；下一次攻击重新读取系数并做功。

Framework 按整个做功时段的最低能力倍率校验。`TISSUE_LOAD_EXCEEDED` 或 `MODULE_UNAVAILABLE` 表示本次力竭，
整组做功不扣账，这一下仍按 `exhausted_coefficient` 命中；其他拒绝作为错误暴露。成功时使用提交前的系数，
避免同一下因自身刚产生的疲劳重复变弱。动作栏不额外提示。

配置修改后重启生效。已有文件完全缺少 `muscle` 节时读取内置默认值，不重写用户配置；显式配置必须完整，部位组非空、不重复且左右不重叠，标签为命名空间 ID，数值为有限正数，且 `min_coefficient <= exhausted_coefficient <= max_coefficient`。

```toml
[muscle]
enabled = true
tissue_tag = "human:arm_muscle"
left_parts = ["left_upper_arm", "left_forearm", "left_hand"]
right_parts = ["right_upper_arm", "right_forearm", "right_hand"]
min_coefficient = 0.25
max_coefficient = 2.0
exhausted_coefficient = 0.25
work_joules_per_vanilla_hp = 20.0
strike_seconds = 0.5
leg_tissue_tag = "human:leg_muscle"
left_leg = ["left_gluteus_maximus", "left_rectus_femoris", "left_gastrocnemius"]
right_leg = ["right_gluteus_maximus", "right_rectus_femoris", "right_gastrocnemius"]
leg_work_joules_per_vanilla_hp = 30.0
leg_swing_seconds = 0.4
```

`enabled = false` 时系数恒为 `1`，不查询攻击者身体、不做功；这是服主平衡开关，完整附属装配仍是必需条件。默认数值尚未经过游玩平衡校准。

## 受击僵直

每次已绑定身体的命中在身体事务提交、观察者通知之后、死亡处理之前换算一次僵直；死亡的命中不写僵直。

```text
比例 = 护甲后剂量（身体 HP） / 命中模块（部位、组织或器官）HP 上限 × 类型系数
比例 < threshold → 无僵直
否则 僵直毫秒 = clamp(round(比例 × scale_millis), minimum_millis, maximum_millis)
```

剂量是护甲、抗性、附魔与吸收之后实际提交的身体 HP（`hp_per_vanilla_hp` 换算），分母取命中模块自己的上限，
不用整身上限。类型系数先乘到比例上再比较门槛，所以同样剂量的钝击比切割更早越过门槛。
未列出的致伤类型（灼热、寒冷、电击、腐蚀、毒性、`CUSTOM`）不产生僵直。

结果经 `TechniqueFabric.service().stagger` 写入功法库；玩家、类人和其他宿主走同一入口。多次命中取较晚到期，
不累加。僵直期间移动速度乘 `move_factor`，新起式被拒（「僵直中」），出手和收势照常；
只有起手中声明可打断的功法会被打断。

```toml
[stagger]
threshold = 0.05
scale_millis = 4000.0
minimum_millis = 200
maximum_millis = 1000
move_factor = 0.5

[stagger.kind_factors]
blunt = 1.5
cutting = 1.0
piercing = 0.75
```

人类胸部上限 40：切割 2 HP 恰为门槛，僵直 200 ms；4 HP 为 400 ms；钝击 4 HP 为 600 ms；
手部上限 8，4 HP 切割即夹到 1000 ms。已有文件完全缺少 `stagger` 节时读取内置默认值；
显式配置必须完整，门槛在 (0, 1]，`move_factor` 在 (0, 1]，上下限为 (0, 60000] 的整毫秒且下限不大于上限，
类型键为小写协议名，系数为 [0, 1000] 的有限数。默认数值尚未经过游玩平衡校准。

## 伤害类型标签

Damage 自带以下 `damage_type` 数据包标签，其他模组可用 `replace: false` 追加自己的伤害类型，无需改 Java 分类器。

| 标签 | 含义 | 默认成员 |
|---|---|---|
| `#meridian:melee` | 肌肉系数、做功和攻击方附效入口 | `minecraft:player_attack`、`minecraft:mob_attack`、`minecraft:mob_attack_no_aggro`、`minecraft:spear`、`minecraft:mace_smash` |
| `#meridian:infusable` | 主击打进去后补[五行附伤](#五行附伤) | `#meridian:melee` |
| `#meridian:leg` | 使用腿部肌肉组和腿部做功换算 | 空 |
| `#meridian:off_hand` | 副手侧出手；带 `leg` 时取副手侧腿 | 空 |
| `#meridian:two_handed` | 双臂出手；带 `leg` 时合并两条腿 | 空 |
| `#meridian:aim/head`、`aim/head_neck`、`aim/torso`、`aim/legs`、`aim/arms`、`aim/other` | 在目标模板对应分组内均匀选取部位（先查 `aims`，再查 `anatomies`）；缺组时退回普通偏好 | 空 |
| `#meridian:kind/blunt` | 指定钝击 | `minecraft:mace_smash` |
| `#meridian:kind/cutting` | 指定切割 | 空 |
| `#meridian:kind/piercing` | 指定穿刺 | `minecraft:spear` |
| `#meridian:kind/heat`、`cold`、`electric`、`corrosive`、`toxic` | 指定对应 Framework 致伤类型 | 空 |

另有两个 `entity_type` 标签只给五行附伤用：`#meridian:infusable_direct`（直接实体是这些类型时也带附伤，默认 `minecraft:evoker_fangs`）和 `#meridian:infusion_via_owner`（造成伤害的实体是这些类型时，按 `TraceableEntity.getOwner()` 再找一跳主人，默认 `minecraft:vex`）。

例如在 `data/meridian/tags/damage_type/melee.json` 的 `values` 中追加 `other_mod:slash`，再在 `kind/cutting.json` 追加同一 ID，即可同时接入肌肉、附效与切割分类。出手侧和 `leg` 标签仅在近战做功时生效。标签在加载、重载时校验；同一伤害类型进入两个 `kind/*` 标签或两个 `aim/*` 标签会报错，使用时也拒绝歧义，不按遍历顺序选择。

## 攻击方附效

当攻击者是已绑定 Meridian 身体的 `LivingEntity`，且来源带 `#meridian:melee` 标签，服务端才会查询攻击者只读身体并询问 `meridian_attack_effect` 提供方。默认箭、药水和其他投射物不会调用此入口；攻击者未绑定时也不会调用。入口使用 [attack_effect.proto](../../server/src/main/proto/kizuna/meridian/vanilla/v1/attack_effect.proto) 的 Protobuf 字节。上下文的字段 7 `raw_damage_hp` 是乘肌肉系数后的原版护甲前剂量，字段 10 `vanilla_damage_hp` 是乘系数前的原版剂量，字段 11 `muscle_multiplier` 是本次肌肉系数；三者和服务端生成的攻击者快照、目标 UUID、原版来源、分类及世界 tick 一起提供给附效方。

最多一个提供方可以返回 `handled = true`。提供方返回的 `extra_damage_hp` 不乘肌肉系数，先与字段 7 的已缩放原版剂量相加，再经过同一件局部护甲、抗性、保护附魔和吸收，最后和原伤害一起向同一部位提交一次身体事务。附加剂量上限使用字段 10 的原版剂量乘 `max_extra_ratio`，默认不能超过 4 倍，可在配置中修改；超出部分截断并记录日志。提供方抛错、返回非法方案或重复认领时，丢弃全部附效，原版伤害仍照常结算。

`status_effects` 暂存到当前伤害帧，在原版 `hurt` 返回阶段对仍存活的目标施加；目标已死亡则丢弃。同一目标上的非空 `effect_id` 只接受一次，用于横扫和多段命中去重。配置节如下：

```toml
[attack_effects]
enabled = true
max_extra_ratio = 4.0
```

## 五行附伤

主击真的打进去以后，如果主人身体上有没到期的附行（功法库 `elemental_infusion` 算子写入的 `ActiveInfusion`），Damage 在同一目标上再补一记本行伤害。数值由功法库 `TechniqueInfusions.strike` 给出：附伤 = 剂量 × 比例 × 曲线值 × 相克倍率，Damage 只负责判来源、找主人、落伤害和原版附带。

**哪些伤害带附伤**：伤害类型在 `#meridian:infusable` 里（默认就是 `#meridian:melee`），或直接实体的类型在 `#meridian:infusable_direct` 里（默认唤魔者尖牙；尖牙的 `indirect_magic` 也是喷溅药水的类型，不能整个收进来）。箭、三叉戟等投射物与非近战伤害默认不带。附伤类型不应放进这两个标签；即使配错，附伤自己打出的伤害也不再记附伤。

**主人**：取 `source.getEntity()`。它的类型在 `#meridian:infusion_via_owner` 里时再查一跳主人；只认标签白名单，不对所有 `TraceableEntity` 生效，免得驯服动物也带上。主人必须是活着的 `LivingEntity`、和目标在同一世界、绑定了身体，且身体上的附行没到期；附行在命中时才查，所以先召出的恼鬼照样带附伤，主人死了就不带。

**剂量**是主击过肌肉、未过护甲的原版伤害，即攻击方附效上下文字段 7 的含义；无敌帧内补伤只取补上的差额。尖牙不吃肌肉系数，剂量就是原版值。附伤不走攻击方附效提供方，不受 `max_extra_ratio` 限制，也不合并进主击的身体事务。

**什么算打进去**：主击进入了原版 `actuallyHurt`，且过肌肉后的剂量大于 0。目标无敌、无敌帧内没超过上次、盾整个挡下（原版仍以 0 调用 `actuallyHurt`）都不算。附伤在原版 `hurt` 返回阶段、主击的死亡与状态效果结算之后执行；主击已打死目标时丢弃。

**有身体的目标**走[可信指定部位伤害](Integration.md#可信指定部位伤害与局部护甲修正)：打在主击固定的同一部位，过该部位局部护甲与部位倍率，不经原版受伤入口，因此不吃也不改无敌帧、不击退、不播受伤动画；该部位已是 0 HP 时这一记丢弃，也不向别处溢出。相克倍率按选点时读到的目标身体算。

**没身体的目标**按 ×1 照样吃附伤：用本行伤害类型走一次原版受伤，原版护甲照常算。附伤类型应进 `#minecraft:no_knockback` 与 `#minecraft:bypasses_cooldown`，这次受伤才不击退、不被无敌帧挡；打完 `invulnerableTime` 与 `lastHurt` 恢复成主击之后的值，下一击的门槛不变。这条路会多一次原版受伤动画和声音。

两条路的附伤源都是造成伤害的实体为主人（击杀归主人）、直接实体沿用主击的（攻击者本人、尖牙或恼鬼）；致伤机制照常按 `kind/*` 标签。附伤真的落下且目标还活着时，再做附行声明的小附带：

| 附带 | 做法 |
|---|---|
| 灼伤 | 给目标挂状态效果 `meridian_vanilla_damage:burn`，见下面[灼伤与寒侵](#灼伤与寒侵) |
| 寒侵 | 给目标加一截状态效果 `meridian_vanilla_damage:chill`，同上 |
| 追加击退 | 按目标击退抗性折算后直接加到水平速度上，方向从直接实体指向目标；不用原版 `knockback`，它会先把已有速度减半，主击已击退时只净增一小截 |

附行带命中声时在目标位置播放，相克与被克时的音量、音高调整已由功法库算好。声音类别与功法施放声一致：主人是玩家归 `PLAYERS`，敌对生物归 `HOSTILE`，其余归 `NEUTRAL`。主人附行解析失败、伤害类型没有注册时记录日志并丢弃这一记附伤，主击已经提交，不回滚。

### 灼伤与寒侵

两个状态效果由本仓注册（`VanillaAfflictions`），都是有害、不出粒子、有图标和倒计时。它们替掉了原来的点着和加冻结刻：不碰原版的着火刻、冻结刻，所以目标身上没有火焰贴图、不引燃方块、屏幕不结霜。现在只有五行附伤会给，别的来源要用也是这两个。

图标在 `assets/meridian_vanilla_damage/textures/mob_effect/`（`burn.png`、`chill.png`），18 × 18 的占位素材：经 CPA 用 `gpt-image-2.5-sunburst` 出 1024 × 1024 原稿，按不透明范围裁紧后最近邻缩小、透明度以 128 为界二值化、无抖动 12 色。

**灼伤**（`burn_ticks`、`burn_damage`）：

- 每 10 tick 掉一次血，按目标自己的 tick 计数取整十。一次掉 `burn_damage ÷ (burn_ticks ÷ 10)`：20 tick、0.5 HP 就是两次各 0.25 HP（五脏诀灼火填的就是这组数）。
- 掉的血算附伤那一行的伤害类型、记在主人名下。有身体的目标掉在附伤落下的那个部位，过局部护甲，不吃也不改无敌帧；没身体的目标走一次原版受伤，打完把无敌帧恢复原样。它不再触发附伤。
- 再挨一下不叠层：时长取剩余和新值的较大者，每次掉多少换成新的。连着挨打也是每 10 tick 一次，刷不出额外的掉血。
- 点不着的生物（烈焰人这类）和带着抗火药水效果的不挨。
- 泡在水里、淋着雨、陷在细雪里当场熄掉。
- 谁烧的、烧在哪个部位只记在内存里。存档重进以后图标还在走完，但不再掉血。

为什么不照原版点着：原来点着 20 tick，烧的那一下正落在主击的无敌帧里，1 HP 不大于主击就被挡掉，实际一点血不掉（2026-11-17 实测）。

**寒侵**（`chill_ticks`）：

- 每挨一下加 `chill_ticks`，叠到 69 tick 封顶，没再挨就自己走完。
- 移速减去 `0.05 × 剩余 tick ÷ 70`（加法），剩得越少越轻。玩家基础移速 0.1，封顶时慢一半；僵尸 0.23，封顶时慢两成多。逐 tick 和原来加冻结刻（每下 50 刻、封顶 139、每 tick 退 2 刻）的减速一样。
- 冻不了的生物（流浪者、雪傀儡、北极熊这类）和穿着皮甲的不挨，沿用原版 `canFreeze()`。

本仓给的其他状态效果（攻击方附效里的 `status_effect`）也一律不出粒子。

## 死亡与保留伤势

可信指定部位伤害另有 [damageDirect 接入](Integration.md#可信指定部位伤害与局部护甲修正)：
按同一局部护甲与必需保命集成结算，绕过无敌帧和击退，且不向别的部位转移超额伤害。
局部护甲还会乘可信附属返回的部位倍率，物品的真实耐久磨损保持独立。

原版实体死亡后，向身体发送死亡确认；伤害策略先判身体死亡时，待原版本次伤害调用结束，再将实体 HP 置零并调用原版死亡流程。已经死亡的不重复执行；框架只释放一次真元、停止动作与任务，并保留局部 HP、疲劳及伤势。

原版重生沿用已有身体，不自动治疗、补真元或重建构型。死体不再接受伤害映射；原版重生本身不视为框架复活，复活和后续可用状态另定。必装 Vanilla Healing 将局部黄心与图腾在本次死亡清账前处理，图腾消费仍复用原版；已经死亡的身体不获得事后复活授权。

## 必需局部吸收与保命集成

通过 Framework `BodyDamageIntegration` 与 `meridian_damage_integration` entrypoint 发现可信效果附属，使用 `health_host.proto` 的 `DamageIntegrationContext / Plan / Commit` 交换。命中和局部护甲、抗性、保护附魔完成后才准备效果方案，效果只能削减该次剂量。同次最多一个集成方认领；重复认领或非法方案拒绝，不隐式叠加。

准备阶段不扣效果资源。完全吸收时不提交零伤害；其余情况向原身体宿主提交一次，并要求同请求 `HealthResult`，核验其与命令一致。成功后通知效果附属扣除本部位余额并同步显示；`death_prevented` 为真时调用原版图腾流程，消费物品并授予原版效果、统计和音画。带 `bypasses_invulnerability` 标签的来源不能取得保命方案。身体 HP、死亡保护与回执原子保存，效果余额、图腾物品及世界副作用仍不属于跨系统崩溃事务。
