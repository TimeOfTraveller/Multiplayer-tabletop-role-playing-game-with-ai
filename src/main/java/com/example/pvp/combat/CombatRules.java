package com.example.pvp.combat;

/**
 * 战斗规则常量：默认数值与骰子规格集中在这里，避免散落在裁决代码里。
 *
 * <p>剧本可以为每个玩家覆盖 AC / 攻击加值 / 伤害骰；未配置时使用本类的默认值。</p>
 */
public final class CombatRules {

    /** 默认护甲等级。 */
    public static final int DEFAULT_ARMOR_CLASS = 10;

    /** 默认攻击加值（命中检定 d20 + attackBonus）。 */
    public static final int DEFAULT_ATTACK_BONUS = 2;

    /** 默认伤害骰。 */
    public static final String DEFAULT_DAMAGE_DICE = "1d6";

    /** 命中检定使用 d20。 */
    public static final int ATTACK_DIE_SIDES = 20;

    /** HP 下限：任何伤害都不能把 HP 打到 0 以下。 */
    public static final int MIN_HP = 0;

    /** AC 合法下限，剧本校验使用。 */
    public static final int MIN_ARMOR_CLASS = 1;

    /** 攻击加值合法下限，剧本校验使用。 */
    public static final int MIN_ATTACK_BONUS = -5;

    /** 攻击加值合法上限，剧本校验使用。 */
    public static final int MAX_ATTACK_BONUS = 20;

    /** 伤害骰数量的合法范围（如 1d6 的 1）。 */
    public static final int MIN_DICE_COUNT = 1;

    public static final int MAX_DICE_COUNT = 10;

    /** 伤害骰面数的合法范围（如 1d6 的 6）。 */
    public static final int MIN_DICE_SIDES = 2;

    public static final int MAX_DICE_SIDES = 100;

    private CombatRules() {
    }
}
