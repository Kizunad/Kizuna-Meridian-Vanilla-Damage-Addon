# 仓库协作约定

- 独立的原版伤害适配 Addon；怪物命中偏好与原版 DamageSource 映射归此仓库，人体内部损伤归 Human，权威账本归 Framework。
- Meridian 的 Framework、Human、UI、Items、Vanilla-Damage、Vanilla-Healing、Human-UI、Progression 和 Kizuna-Players-Animation 在各自适用端均为整套必装模块；拆仓仅用于代码管理与职责划分。不得添加缺少某个 Addon 时的替代路径、降级、可选依赖判断或对应测试/smoke；缺少必需接线应明确报错。运行时必装不意味着跨越客户端/服务端边界或让底层源码依赖具体人体实现。
- `server/` 保存与 Minecraft 无关的配置和选点规则；`fabric/` 接入原版结算事件。跨附属通信使用 Framework 的 Protobuf 与 `BodyHealthHost`，不得依赖 Human 内部类。
- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。代码按读取、校验、计算、请求、结果展开，简单 Stream 可以保留。
- 错误及异常文案集中于 `server/.../VanillaDamageErrors.java`。伤害来源、随机选点和数值不能由客户端上报。
- 仅保留必要的整体回归，临时验证通过后清理。交付执行构建、文档链接检查和 diff 检查。
- 文档唯一编辑源为 `docs/wiki/`，README 仅作入口；使用 patch 修改源码，保留其他仓库的未提交改动。
- 未获授权不提交、推送或远端建仓。许可为 PolyForm Noncommercial 1.0.0，保留第三方许可。
