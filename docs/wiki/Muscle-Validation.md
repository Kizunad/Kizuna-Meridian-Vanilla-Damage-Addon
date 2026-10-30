# 肌肉出力与伤害类型标签实现验证

日期：2026-10-28。依据远程交接 `technique-damage-s5.md` 及 `damage-s5-muscle-plan-2026-10-28.md`。

## 结果与边界

Damage 已实现肌肉力量系数、实际出手侧、护甲前单次缩放、整组原子做功、力竭继续命中、伤害类型标签和完整配置；Human 与 Threatening 的健康宿主转发普通做功。职责保持为 Damage 计算攻击系数、宿主管理可信身份与时间、Framework 管理身体账本和能力结算，未新增独立账本或缺模块降级。本文原始验证记录保留了各自执行时的结果；本轮 D1/D2 与当前跨仓结果见文末返工记录。

未修改 Framework 或 Technique 工作区，未提交、未推送。Damage 与 Threatening 使用各自当前源码和完整安装产物装配验证。Human 默认构建被相邻 Technique 工作区新增的接口阻塞：`HumanPlayerBody` 尚未实现 `TechniqueRecoveryHost.submitEffect(EffectSubmission)`。此接口属于另一会话的功法接线，不在本次普通做功转发范围。

为独立验证本次 Human 改动，只读导出 Technique 已提交基线 `89e37ec0a35748297d7ffd281231d2eb9537065f` 到 `/tmp/meridian-technique-s5-7QRAlZ`，构建该临时目录，再用现有 `meridianTechniqueDir` 参数验证 Human。该结果不能代表当前 Technique 工作区兼容通过，也没有修改原 Technique 源码。

## 逐仓库文件

以下路径均相对于对应仓库；标签集合逐项列出，避免将测试数据包误认为正式默认标签。

### Damage

| 文件 | 改动 |
|---|---|
| `server/src/main/java/dev/kizuna/meridian/vanilla/MuscleRules.java` | 新增纯规则、配置校验、出手肌肉选择、能力系数与负载构造 |
| `server/src/main/java/dev/kizuna/meridian/vanilla/DamagePreferences.java` | 加载肌肉规则，复用缺节默认值读取，分离配置解析阶段 |
| `server/src/main/java/dev/kizuna/meridian/vanilla/VanillaDamageBridge.java` | 查询攻击者、向同一宿主提交做功、区分力竭与其他错误 |
| `server/src/main/java/dev/kizuna/meridian/vanilla/VanillaDamageErrors.java` | 集中肌肉配置、能力与标签冲突错误文案 |
| `server/src/main/resources/meridian-vanilla-damage.toml` | 新增完整 `[muscle]` 默认节 |
| `fabric/src/main/java/dev/kizuna/meridian/vanilla/fabric/VanillaDamageFabric.java` | 护甲前缩放，保存原始附效基数，标签优先分类，拆分结算阶段 |
| `fabric/src/main/java/dev/kizuna/meridian/vanilla/fabric/VanillaDamageTypeTags.java` | 新增标签、出手侧、分类覆盖及加载/使用冲突校验 |
| `fabric/src/main/java/dev/kizuna/meridian/vanilla/fabric/mixin/LivingEntityDamageMixin.java` | 普通生物 `applyDamage` 参数缩放 |
| `fabric/src/main/java/dev/kizuna/meridian/vanilla/fabric/mixin/PlayerEntityDamageMixin.java` | 玩家覆盖实现的 `applyDamage` 参数缩放 |
| `fabric/build.gradle` | 完整开发装配补齐必需 Technique 产物并校验存在性 |
| `server/src/test/java/dev/kizuna/meridian/vanilla/MuscleDamageTest.java` | 新增 5 项真实 Framework 规则回归 |
| `fabric/src/gametest/java/dev/kizuna/meridian/vanilla/test/DamageGameTest.java` | 增加肌肉/标签 GameTest 与测试宿主普通做功分支 |
| `docs/wiki/Damage-Rules.md` | 肌肉、标签、附效时序与完整配置 |
| `docs/wiki/Integration.md` | 做功协议与必需开发装配说明 |
| `docs/wiki/Home.md` | 功能入口与验证报告链接 |
| `docs/wiki/Muscle-Validation.md` | 本报告 |

正式默认标签位于 `fabric/src/main/resources/data/meridian/tags/damage_type/`，共 11 个：

```text
melee.json
off_hand.json
two_handed.json
kind/blunt.json
kind/cutting.json
kind/piercing.json
kind/heat.json
kind/cold.json
kind/electric.json
kind/corrosive.json
kind/toxic.json
```

测试数据共 7 个文件：

```text
fabric/src/gametest/resources/data/meridian_damage_test/damage_type/off_strike.json
fabric/src/gametest/resources/data/meridian_damage_test/damage_type/two_strike.json
fabric/src/gametest/resources/data/meridian/tags/damage_type/melee.json
fabric/src/gametest/resources/data/meridian/tags/damage_type/off_hand.json
fabric/src/gametest/resources/data/meridian/tags/damage_type/two_handed.json
fabric/src/gametest/resources/data/meridian/tags/damage_type/kind/blunt.json
fabric/src/gametest/resources/data/meridian/tags/damage_type/kind/cutting.json
```

### Human

| 文件 | 改动 |
|---|---|
| `fabric/src/main/java/dev/kizuna/meridian/human/fabric/HumanFabric.java` | 核对绑定身体，分别转发做功和健康效果，保留业务失败码 |
| `fabric/src/main/java/dev/kizuna/meridian/human/fabric/HumanPlayerBody.java` | 新增 `applyExertion`，使用可信时间与普通命令入口 |
| `fabric/src/gametest/java/dev/kizuna/meridian/human/fabric/HumanExertionGameTest.java` | 新增真实在线玩家宿主做功回归 |
| `fabric/src/gametest/resources/fabric.mod.json` | 登记上述 GameTest |
| `docs/wiki/Integration.md` | 做功协议及回归说明 |

### Threatening

| 文件 | 改动 |
|---|---|
| `fabric/src/main/java/dev/kizuna/meridian/humanoid/fabric/HumanoidBodyState.java` | 新增绑定身体普通做功提交 |
| `fabric/src/main/java/dev/kizuna/meridian/humanoid/fabric/HumanoidHealthHost.java` | 转发 `exertion`，返回身体或原业务失败码 |
| `fabric/src/gametest/java/dev/kizuna/meridian/humanoid/fabric/HumanoidGameTest.java` | 增加真实村民宿主做功回归 |
| `fabric/build.gradle` | 补齐必需 Technique 开发运行产物并校验 |
| `docs/wiki/Implementation-Plan.md` | 做功、回归和开发装配说明 |

## 方案 §7 测试对应

`MuscleDamageTest` 为 Damage 服务端单元回归，`DamageGameTest` 为真实 Minecraft 服务端 GameTest。两个宿主的测试分别位于其本仓库。

| 方案项目 | 测试名与实际断言 |
|---|---|
| 系数 | `MuscleDamageTest.coefficientUsesHealthFatigueModifiersAndBounds`：健康 1、部位/组织半伤 0.5、疲劳下限、1.3/0.85 修正及上限 |
| 出手侧 | `MuscleDamageTest.handednessSelectsSameMusclesForCoefficientAndLoads`：左右主手、副手、不同基础力量的双手加权；`DamageGameTest.defaultMeleeSourcesAndLeftMainHandRemainSupported` 验证真实左主手；`taggedMeleeSelectsHandsOverridesKindAndKeepsExtraUnscaled` 验证标签副手和双臂扣账 |
| 乘的位置 | `DamageGameTest.muscleScalesBeforeArmorOnceForPlayerAndUnboundTargets`：玩家局部护甲和无身体目标原版护甲前只乘一次；`mapsFinalLossAndBothDeathDirections` 保留无身体攻击者原剂量；`taggedMeleeSelectsHandsOverridesKindAndKeepsExtraUnscaled` 验证额外伤害不乘 |
| 做功 | `MuscleDamageTest.strikeCommitsOncePerHitAndBothArmsAtomically`：每次单目标出手调用一笔、双臂原子与拒绝不留部分疲劳；一次多目标出手只记一笔由下方 D1 GameTest 验证；`minimumCurveAcrossStrikeCanRejectPeakAndOtherFailuresRemainErrors` 验证整个时段最低倍率及版本冲突仍报错；`DamageGameTest.sameSwordUsesPeakCollapseAndExhaustedStillHits` 验证力竭仍命中 |
| 标签 | `DamageGameTest.taggedMeleeSelectsHandsOverridesKindAndKeepsExtraUnscaled`：自定义 `melee` 接入、`kind/cutting` 覆盖和附效；`conflictingKindTagsAreRejectedOnLoadAndUse` 验证加载校验入口与使用时拒绝冲突；`defaultMeleeSourcesAndLeftMainHandRemainSupported` 核对三种默认来源 |
| 联动 | `DamageGameTest.sameSwordUsesPeakCollapseAndExhaustedStillHits`：真实铁剑、满攻击冷却，同一武器分别按 1、1.3、0.85 造成伤害，直接能力修正模拟，无需调用基础功法 |
| 配置 | `MuscleDamageTest.disabledAndUnboundDoNotExertAndConfigurationIsValidated`：关闭恒 1、不做功、无绑定不做功、非法数值/标签/左右重叠拒绝；其他规则测试也覆盖旧配置缺节默认值 |
| 玩家宿主 | `HumanExertionGameTest.exertionChargesOnceRejectsCapacityAndChecksBoundIdentity`：真实在线绑定、一次扣账、成功重放不重复、能力不足原码、拒绝不改身体、无健康效果回执、跨身体拒绝 |
| 生物宿主 | `HumanoidGameTest.exertionChargesOnceRejectsCapacityAndChecksBoundIdentity`：真实村民绑定，验证与玩家宿主相同的做功语义 |

## 实际命令与结果

命令均从对应仓库根目录执行，使用 Java 17。没有远端 CI、提交或推送操作。

| 仓库/环境 | 命令 | 结果 |
|---|---|---|
| Damage | `./gradlew test build :fabric:compileGametestJava` | 通过 |
| Damage 最终源码 | `./gradlew test build :fabric:runGametest` | 通过：10 项单元回归、10 项 GameTest |
| Threatening | `./gradlew test build :fabric:compileGametestJava` | 通过：11 项单元回归 |
| Threatening | `./gradlew :fabric:runGametest` | 通过：28 项 GameTest |
| Threatening 最终源码 | `./gradlew test build :fabric:runGametest` | 通过：11 项单元回归、28 项 GameTest |
| Human 当前默认依赖 | `./gradlew test build demo :fabric:runGametest` | 失败于 `:fabric:compileJava`：Technique 新增 `submitEffect` 接口未实现，后续任务未完成 |
| 临时 Technique 已提交基线 | `./gradlew build "-PmeridianFrameworkDir=/home/kiz/Code/Kizuna-Meridian-Mod-Framework"` | 通过；工作目录 `/tmp/meridian-technique-s5-7QRAlZ` |
| Human 隔离基线 | 见下方完整命令 | 通过：49 项单元回归、demo、30 项 GameTest |
| 三仓库 | `python3 "scripts/check_docs.py"` | 通过 |
| 三仓库 | `git diff --check` | 通过 |

Human 隔离基线命令：

```bash
./gradlew test build demo :fabric:runGametest \
  "-PmeridianTechniqueDir=/tmp/meridian-technique-s5-7QRAlZ" \
  "-PmeridianFrameworkDir=/home/kiz/Code/Kizuna-Meridian-Mod-Framework"
```

详细本地日志保存在 `/tmp/damage-s5-final-verify.log`、`/tmp/threatening-s5-build.log`、`/tmp/threatening-s5-gametest.log`、`/tmp/threatening-s5-final-verify.log`、`/tmp/human-s5-final-default.log`、`/tmp/technique-s5-baseline-build.log`、`/tmp/human-s5-baseline-verify.log`。负面回归会记录提供方异常、坏账本或缺接收通道等日志；结果以测试报告和进程退出码为准。

Damage 的旧 GameTest 运行目录配置含已移除的 `[particle]`，妨碍 Humanoid 加载；验证时清除了该过时节。该文件位于忽略的 `fabric/build/gametest/config/meridian-humanoid.toml`，不属于源码交付。

## 方案空白及实现选择

- 方案未定义已绑定但没有匹配肌肉的身体：系数取 1，不发空 `ExertTissues`；这是解剖选择结果，不是缺附属降级。
- `off_hand` 与 `two_handed` 同时存在时双手优先；两臂都纳入系数及做功。
- “做功拒绝视力竭”仅包括 Framework 能力不足的 `TISSUE_LOAD_EXCEEDED` 和耐力归零的 `MODULE_UNAVAILABLE`。身份、版本、格式或宿主错误仍明确报错，避免把接线故障误当疲劳。
- 能力瞬时峰值足够但覆盖整个做功时段的最低能力不足时，按 Framework 拒绝结果使用力竭系数。
- 当前完整装配脚本未包含 Human 新增的必需 Technique 产物；只在 Damage/Threatening 本仓库补齐运行装配，遵守不修改 Framework 的边界。

## 未检查的内容

- 当前 Technique 工作区与 Human 的新 `submitEffect` 接口集成，以及真实基础功法蓄力/调息流程。
- 真实客户端画面、动作栏或 UI 体验，Windows 手动游玩、专服多人、压力与长期存档运行。
- 实际游戏菜单执行数据包重载；已验证真实注册表上的加载校验方法和使用拒绝，未以独立错误数据包重启服务器。
- 第三方战斗模组兼容与数值平衡；基础攻击冷却、暴击、横扫沿原版计算，本次联动回归使用满冷却普通剑击。
- Minecraft 实体副作用与身体 Store 之间的跨系统崩溃事务；本次只复用已有身体原子提交及幂等回执。

## 代码形状自查

已修正：第 1/2 条，`DamageGameTest` 的测试方案、`HumanFabric.healthFailure` 的 Protobuf builder 分行并提取内层消息；第 3/5 条，`MuscleRules.selectedParts` 和 Fabric 剂量合并使用明确分支及新值名；第 4 条，`VanillaDamageBridge.exertStrike` 缓存做功前快照；第 6/7 条，新增第三方布尔实参补语义注释并命名倍率、时长、护甲与容差常量；第 9 条，拆分 `DamagePreferences.parse`、`VanillaDamageFabric.prepareArmor` 和 `HumanFabric.health`，配置构造参数改用 record，新增行不超过 120 列。新增 Human 方法均带内部中文意图注释。

## 交叉审阅返工 D1/D2 当前结论

D1 将做功与系数读取提升到一次完整出手上下文：玩家 `attack`、生物 `tryAttack` 的所有目标（含原版横扫）共享一次提交前系数和一笔原子做功；每个目标仍在自己的护甲结算前缩放一次。盾挡、免疫和没有目标的主手挥空按“出手就记”处理。新增 `DamageGameTest.playerSweepUsesOneExertionAndOnePreStrikeCoefficient`、`shieldBlockStillRecordsOneExertion`、`handSwingPacketRecordsMissAndDoesNotChargeAttackTwice` 覆盖实际玩家攻击/横扫、盾挡、挥空与动画包去重。D2 将 `MuscleDamageTest.java` 的半伤输入改为 `HALF_COEFFICIENT`。

本轮复验确认当前 Human 已实现 `TechniqueRecoveryHost.submitEffect(EffectSubmission)`，默认装配构建不再受该接口阻塞；对应 H1 的执行中重入、稳定回执和失败重试另见返工结果文件。旧“Human 当前默认构建失败”是本报告原始运行时的历史事实，不代表返工后的状态。

当前完整命令、JUnit/GameTest 数量、各仓文档检查及 diff 检查记录见
`~/Code/Kizuna-remote-session/handoff/cross-review-rework-2026-10-28-result.md`。
