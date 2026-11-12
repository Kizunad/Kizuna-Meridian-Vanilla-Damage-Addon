package dev.kizuna.meridian.vanilla;

import java.io.IOException;

/** 原版伤害适配的诊断；核心失败仍使用 Protobuf 失败码。 */
public final class VanillaDamageErrors {
    public static final String MUSCLE = "muscle 必须声明合法标签、互不重复的左右部位、"
            + "正数系数及做功参数";
    public static final String STAGGER = "stagger 必须声明 (0, 1] 内的门槛与移动倍率、正数时长比例、"
            + "不超过 60000 且下限不大于上限的整毫秒，以及非负的致伤类型系数";
    public static final String MUSCLE_CAPACITY = "出手肌肉的基础力量或可用力量无效";
    public static final String MUSCLE_PROJECTION_TIME = "肌肉系数预估时刻不得为负或早于当前身体时刻";
    public static final String STRIKE_CONTEXT_MISSING = "近战伤害缺少出手上下文";
    public static final String STRIKE_CONTEXT_ORDER = "近战出手上下文顺序错误";
    public static final String DAMAGE_KIND_TAGS = "同一伤害类型不能属于多个 meridian:kind 标签";
    public static final String DAMAGE_AIM_TAGS = "同一伤害类型不能属于多个 meridian:aim 标签";
    public static final String INVALID_INTEGRATION = "伤害效果集成认领重复、方案无效或宿主缺少健康回执";
    public static final String CONFIG = "原版伤害配置无效";
    public static final String NOT_INITIALIZED = "原版伤害附属尚未初始化，不能读取伤害规则";
    public static final String CONFIG_IO = "无法读取或创建原版伤害配置";
    public static final String HOST_RESPONSE = "健康宿主返回了不匹配的响应或丢失了身体绑定";
    public static final String DAMAGE_FAILED = "原版伤害或死亡未能同步到身体";
    public static final String DUPLICATE_HOST = "同一实体被多个身体宿主认领，已拒绝伤害同步";
    public static final String NO_HOST = "缺少必需的 meridian_health_host 身体宿主";
    public static final String NO_INTEGRATION = "缺少认领本次身体伤害的必需效果集成";
    public static final String DEFAULT_RESOURCE = "缺少默认配置资源";
    public static final String SCALE = "hp_per_vanilla_hp 必须是 (0, 1000] 内的数值";
    public static final String ANATOMIES = "anatomies 必须是表";
    public static final String PART_GROUPS = "身体部位分组必须是表";
    public static final String PART_ARRAY = "部位分组必须是字符串数组";
    public static final String DUPLICATE_PART = "部位 ID 为空或重复";
    public static final String ATTACKERS = "attackers 必须是表";
    public static final String ATTACKER_PROFILE = "攻击者偏好必须是表";
    public static final String WEIGHT = "权重必须是 [0, 1000] 内的数值";
    public static final String DAMAGE = "映射伤害必须是有限正数";
    public static final String ROLL = "随机值必须在 [0, 1) 内";
    public static final String ARMOR_FORMULA = "armor.formula 必须声明 toughness 公式与合法系数，护甲上限不得超过减伤分母";
    public static final String ARMOR_COVERAGE = "armor.coverage 必须按模板和装备槽声明不重复的部位字符串数组";
    public static final String ARMOR_SLOT = "护甲槽只允许 head、chest、legs、feet";
    public static final String ARMOR_INPUT = "护甲伤害、护甲值与韧性必须是有限非负数";
    public static final String ARMOR_REDUCTION_CAP = "armor.maximum_reduction_ratio 必须是 [0, 1] 内的有限数，表示最终护甲减免比例上限";
    public static final String ARMOR_DURABILITY_CURVE = "armor.durability_curve 至少需要两个 [剩余耐久比例, 系数] 点，数值须在 [0, 1]，耐久严格递增并覆盖 0 到 1，系数不得递减";
    public static final String ARMOR_DURABILITY_INPUT = "剩余耐久比例必须是 [0, 1] 内的有限数";
    public static final String HIT_COMMITTED = "同一次命中已经提交，不能重复结算";
    public static final String ATTACK_EFFECTS = "attack_effects 必须是表";
    public static final String ATTACK_EFFECTS_ENABLED = "attack_effects.enabled 必须是布尔值";
    public static final String ATTACK_EFFECTS_RATIO = "attack_effects.max_extra_ratio 必须是 [0, 1000] 内的有限数";
    public static final String ATTACK_EFFECT_CONTEXT = "攻击方附效上下文无效";
    public static final String ATTACK_EFFECT_PLAN = "攻击方附效方案无效";
    public static final String ATTACK_EFFECT_STATUS = "攻击方状态效果无效";
    public static final String ATTACK_EFFECT_PROVIDER = "攻击方附效提供方抛出异常或重复认领";
    public static final String INFUSION = "五行附伤取值失败，本次附伤丢弃";
    public static final String INFUSION_DAMAGE_TYPE = "附行的伤害类型没有注册，本次附伤丢弃";

    private VanillaDamageErrors() {
        // 诊断集中维护，不解析文案来决定游戏行为。
    }

    public static IllegalArgumentException invalidConfig(String detail) {
        // 配置位置用于启动诊断，不作为对外协议的一部分。
        return new IllegalArgumentException(CONFIG + ": " + detail);
    }

    public static IllegalStateException configReadFailed(IOException cause) {
        // 文件错误保留原始原因，便于区分权限、路径与读取故障。
        return new IllegalStateException(CONFIG_IO, cause);
    }

    public static IllegalStateException hostRejected(Object reason) {
        // 保留公开失败码或绑定诊断，便于宿主定位真实拒绝原因。
        return new IllegalStateException(HOST_RESPONSE + ": " + reason);
    }
}
