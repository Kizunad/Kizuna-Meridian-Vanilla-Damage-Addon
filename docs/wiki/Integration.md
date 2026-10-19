# 构建与宿主接入

## 本地构建

要求 Java 17，Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1。相邻 Framework 仓库通过 Gradle composite build 接入，也可用 `-PmeridianFrameworkDir="/path/to/framework"` 指定位置；`local.meridian:server` 仅为本地替换键，不是已发布的 Maven 坐标。

```sh
./gradlew build
./gradlew :fabric:runGametest
python3 scripts/check_docs.py
```

安装使用 `fabric/build/libs/` 内不带 `sources` 或 `dev` 后缀的重映射 JAR，并安装 Fabric API。构建会嵌入纯 Java 规则、Framework 服务端库与所需运行依赖。模组本身运行在服务端，单机由集成服务器执行，不要求客户端安装伤害适配。

本附属不创建身体。玩家身体可由独立 Human Addon 提供；没有管理该实体的宿主时保留原版行为。它不依赖 Human 内部类，其他生物附属采用相同接口。

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

死亡通过 `confirm_body_death` 确认；它不生成伪造的大额伤害，不覆盖局部 HP。身体、释放账户和回执的保存仍属于宿主唯一状态，本附属不另存一套身体。

## 验证范围与失败行为

必要整体回归覆盖配置及选点、覆盖表与公式修改、伤害单位换算、耐久曲线端点及插值、无耐久物品、减免比例上限与执行顺序、异常配置拒绝、真实框架请求链、拒绝与唯一宿主、单次命中提交和死亡保留伤势。Fabric GameTest 在真实 Minecraft 服务端验证玩家和普通生物的局部护甲路径、未覆盖与未绑定分支、物品 NBT 属性、磨损前耐久曲线、耐久衰减后限幅、不可损坏护甲、原版耐久磨损、抗性、全身保护附魔、吸收生命、绕甲来源、无敌帧、超出原版剩余血量的完整剂量及两种死亡方向；测试宿主和样例身体不进入安装 JAR。

Minecraft 实体状态与身体 Store 之间目前没有跨系统持久化事务。同步失败保留原版结算并记录日志，不宣称可靠补偿或恰好一次世界副作用；框架内部的身体变更与成功回执仍为原子提交。Windows 手动游玩、专服多人及其他战斗模组兼容需要独立验收。
