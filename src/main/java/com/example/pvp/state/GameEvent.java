package com.example.pvp.state;

import java.util.List;

/**
 * 服务器侧的一条游戏事件（单一真相源的一部分，仅服务器可见）。
 *
 * <p>注意：这个对象不会直接推给玩家；玩家只能收到 {@link PlayerView} 里投影出来的版本。
 * 战斗事件带完整的可审计字段（骰子点数、AC、伤害、剩余 HP），供日志复核与叙事层引用。</p>
 *
 * @param eventId   事件 ID
 * @param type      事件类型：action / attack / down
 * @param actor     发起该事件的玩家；down 事件为倒下者
 * @param intent    行动意图（攻击固定为 "attack"，观望为 "idle"）
 * @param result    裁决结果标记：found_key / nothing / hit / miss / target_down / idle / already_down
 * @param isPublic  是否为公共事件
 * @param visibleTo 非公共事件可见的玩家列表（public=true 时为空）
 * @param combat    战斗审计信息；非战斗事件为 null
 * @param growth    经验/升级审计信息；本次未结算成长时为 null，且只对成长归属玩家可见
 */
public record GameEvent(
        String eventId,
        String type,
        String actor,
        String intent,
        String result,
        boolean isPublic,
        List<String> visibleTo,
        Combat combat,
        Growth growth) {

    /** 普通行动事件。 */
    public static final String TYPE_ACTION = "action";

    /** 攻击事件。 */
    public static final String TYPE_ATTACK = "attack";

    /** 目标倒下事件。 */
    public static final String TYPE_DOWN = "down";

    /** 本轮未提交行动，按观望占位。 */
    public static final String INTENT_IDLE = "idle";

    /** 命中了目标。 */
    public static final String RESULT_FOUND_KEY = "found_key";

    /** 命中。 */
    public static final String RESULT_HIT = "hit";

    /** 未命中。 */
    public static final String RESULT_MISS = "miss";

    /** 目标 HP 归零。 */
    public static final String RESULT_TARGET_DOWN = "target_down";

    /** 本轮观望 / 未提交。 */
    public static final String RESULT_IDLE = "idle";

    /** 攻击目标在本轮开始时已经倒下，本次攻击不产生伤害。 */
    public static final String RESULT_ALREADY_DOWN = "already_down";

    /** 无实际效果。 */
    public static final String RESULT_NOTHING = "nothing";

    /**
     * 战斗审计信息：一次攻击的全部判定输入与结果。
     *
     * @param attacker         攻击者标识
     * @param target           目标标识
     * @param attackRoll       d20 点数
     * @param attackBonus      攻击加值
     * @param attackTotal      命中检定总值（attackRoll + attackBonus）
     * @param targetAc         目标 AC
     * @param hit              是否命中
     * @param damageDice       伤害骰写法（如 1d6）
     * @param damageRoll       伤害骰总点数
     * @param damage           实际造成的伤害
     * @param targetHpRemaining 目标剩余 HP
     * @param targetDefeated   目标是否倒下
     */
    public record Combat(String attacker, String target, int attackRoll, int attackBonus, int attackTotal,
                         int targetAc, boolean hit, String damageDice, int damageRoll, int damage,
                         int targetHpRemaining, boolean targetDefeated) {
    }

    /**
     * 成长审计信息：一次经验结算的全部输入与前后数值。
     *
     * @param playerId          经验归属玩家
     * @param source            经验来源（combat_victory / puzzle_key）
     * @param expGained         本次获得的经验
     * @param expBefore         结算前经验
     * @param exp               结算后剩余经验（已扣除升级消耗）
     * @param levelBefore       结算前等级
     * @param level             结算后等级
     * @param levelsGained      本次提升的级数（可能 > 1）
     * @param leveledUp         本次是否升级
     * @param maxHpBefore       结算前 maxHp
     * @param maxHpAfter        结算后 maxHp
     * @param attackBonusBefore 结算前攻击加值
     * @param attackBonusAfter  结算后攻击加值
     * @param hpBefore          结算前 HP
     * @param hpAfter           结算后 HP（有升级时为回满值）
     */
    public record Growth(String playerId, String source, int expGained, int expBefore, int exp,
                         int levelBefore, int level, int levelsGained, boolean leveledUp,
                         int maxHpBefore, int maxHpAfter, int attackBonusBefore, int attackBonusAfter,
                         int hpBefore, int hpAfter) {
    }

    /**
     * 是否为攻击事件。
     *
     * @return true 表示攻击事件
     */
    public boolean isAttack() {
        return TYPE_ATTACK.equals(type);
    }
}
