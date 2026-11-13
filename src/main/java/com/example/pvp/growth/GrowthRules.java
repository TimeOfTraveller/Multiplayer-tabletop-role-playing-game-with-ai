package com.example.pvp.growth;

/**
 * 成长规则常量：经验阈值、升级收益与经验来源数值集中在这里，避免散落魔法数字。
 *
 * <p>调整平衡时只改本文件：升级阈值、每级 maxHp / 攻击加值收益、击倒与解谜的经验奖励。</p>
 */
public final class GrowthRules {

    /** 每级所需经验（默认 100 EXP 一级）。 */
    public static final int DEFAULT_EXP_PER_LEVEL = 100;

    /** 初始等级。 */
    public static final int INITIAL_LEVEL = 1;

    /** 每次升级增加的最大生命值。 */
    public static final int LEVEL_UP_MAX_HP_BONUS = 5;

    /** 每次升级增加的攻击加值。 */
    public static final int LEVEL_UP_ATTACK_BONUS = 1;

    /** 击倒对手（目标从存活变为倒下）获得的经验。 */
    public static final int EXP_COMBAT_VICTORY = 150;

    /** 首次解开关键谜题（拿到 found_key）获得的经验。 */
    public static final int EXP_PUZZLE_KEY = 60;

    /** 经验来源：战斗获胜。 */
    public static final String SOURCE_COMBAT_VICTORY = "combat_victory";

    /** 经验来源：首次解开关键谜题。 */
    public static final String SOURCE_PUZZLE_KEY = "puzzle_key";

    private GrowthRules() {
    }
}
