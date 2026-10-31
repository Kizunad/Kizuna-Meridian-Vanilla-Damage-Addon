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

## 可信 Protobuf 入口

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

近战在进入护甲阶段前，另对攻击者使用同样的唯一宿主查询规则。`exertStrike` 从做功前快照计算系数，再向同一宿主提交 `exertion`，内含普通 `ExertTissues` 命令；保留身体 ID、预期 revision 与请求 ID。做功成功只要求返回同一身体，能力不足按力竭系数命中，其他错误抛出。完整规则、标签与配置见[肌肉出力](Damage-Rules.md#肌肉出力)，交付证据见[实现验证](Muscle-Validation.md)。

## 攻击方附效入口

伤害附属提供 [AttackEffectProvider](../../server/src/main/java/dev/kizuna/meridian/vanilla/AttackEffectProvider.java)，并在自己的 `fabric.mod.json` 中注册 `meridian_attack_effect`。Damage 在服务端主线程只对已绑定身体的近战攻击者查询快照，然后按 [attack_effect.proto](../../server/src/main/proto/kizuna/meridian/vanilla/v1/attack_effect.proto) 编解码上下文和方案。提供方不依赖 Damage 的内部 Java 类之外的身体实现，也不能上传伤害来源、目标部位或原版剂量。

`handled` 方案最多一个；附加剂量与原版剂量共用一次局部护甲和身体事务，状态效果在 `hurt` 返回时施加。远程来源、未绑定攻击者、提供方异常、非法方案和重复认领均跳过附效并保留原版伤害。`effect_id` 在同一目标上去重，`max_extra_ratio` 默认 4.0，配置可关闭整个入口。

提供方示例（只用于仓库 GameTest）在 `fabric/src/gametest/resources/fabric.mod.json` 注册；功法库等其他仓库应依赖本仓库 server JAR 并自行实现入口，本仓库不会修改它们。

死亡通过 `confirm_body_death` 确认；它不生成伪造的大额伤害，不覆盖局部 HP。身体、释放账户和回执的保存仍属于宿主唯一状态，本附属不另存一套身体。

必装 Vanilla Healing 注册 `meridian_damage_integration`，实现 Framework `BodyDamageIntegration` 的 Protobuf 准备与提交通知。`PreparedHit.commitIntegrated` 在固定命中后读取只减伤的方案，将受保护的剩余伤害提交到同一宿主；效果成功响应必须附带同请求固定 `HealthResult`，其格式和命令一致性经框架校验后才消费图腾。已绑定的命中必须由唯一效果集成认领，没有认领直接报错；完全局部吸收无需 HP 事务。原版治疗与局部黄心详见[伤害规则](Damage-Rules.md)。

## 验证范围与失败行为

必要整体回归覆盖配置及选点、覆盖表与公式修改、伤害单位换算、耐久曲线端点及插值、无耐久物品、减免比例上限与执行顺序、异常配置拒绝、真实框架请求链、拒绝与唯一宿主、单次命中提交和死亡保留伤势。服务端单元回归覆盖攻击附效的无认领、单认领、多认领、未绑定攻击者、远程跳过、上限截断、异常和 `effect_id` 去重；Fabric GameTest 在真实 Minecraft 服务端使用仓库内测试提供方验证僵尸近战命中合并附加剂量和状态效果，并继续验证玩家和普通生物的局部护甲路径、未覆盖与未绑定分支、物品属性组件、磨损前耐久曲线、耐久衰减后限幅、不可损坏护甲、原版耐久磨损、抗性、全身保护附魔、吸收生命、绕甲来源、无敌帧、超出原版剩余血量的完整剂量及两种死亡方向；测试宿主和样例身体不进入安装 JAR。挥手包用例的假玩家取自功法库 `fabric` 的 `testFixtures`（`TestPlayers.detached`：正式网络处理器、不进玩家列表），与功法 fabric 同经源码组合解析。

Minecraft 实体状态与身体 Store 之间目前没有跨系统持久化事务。同步失败记录日志并抛错，不继续原版扣血，不宣称可靠补偿或恰好一次世界副作用；框架内部的身体变更与成功回执仍为原子提交。Windows 手动游玩、专服多人及其他战斗模组兼容需要独立验收。
