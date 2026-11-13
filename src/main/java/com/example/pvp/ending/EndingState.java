package com.example.pvp.ending;

import java.util.List;

/**
 * 一局结束时的服务器侧结算快照（仅服务器持有，按玩家投影后才推送）。
 *
 * @param condition  决定胜负的主条件：collect_items / last_standing / max_turns
 * @param conditions 本轮同时满足的全部条件，固定按 collectItems → lastStanding → maxTurns 排序
 * @param winnerId   胜者玩家标识；平局（例如 maxTurns 时全员倒下）为 null
 * @param turn       触发结算的轮次
 * @param players    结束时全体玩家的公开状态快照，按玩家标识排序
 */
public record EndingState(String condition, List<String> conditions, String winnerId, int turn,
                          List<FinalPlayer> players) {

    /** 集齐指定物品。 */
    public static final String CONDITION_COLLECT_ITEMS = "collect_items";

    /** 仅剩一名未倒下玩家。 */
    public static final String CONDITION_LAST_STANDING = "last_standing";

    /** 达到轮数上限。 */
    public static final String CONDITION_MAX_TURNS = "max_turns";

    /**
     * 是否为平局。
     *
     * @return true 表示没有胜者
     */
    public boolean draw() {
        return winnerId == null;
    }

    /**
     * 条件的中文说明，供结算面板与模板叙述使用。
     *
     * @param condition 条件标识
     * @return 自然语言说明
     */
    public static String label(String condition) {
        return switch (condition) {
            case CONDITION_COLLECT_ITEMS -> "集齐指定物品";
            case CONDITION_LAST_STANDING -> "成为唯一没有倒下的玩家";
            case CONDITION_MAX_TURNS -> "达到轮数上限后按剩余生命判定";
            default -> condition;
        };
    }

    /**
     * 单个玩家的最终公开状态。阵营只在结算时公开，用于复盘；进行中的视角仍不暴露阵营。
     *
     * @param playerId   玩家标识
     * @param level      最终等级
     * @param hp         最终 HP
     * @param maxHp      最终最大 HP
     * @param armorClass 护甲等级
     * @param defeated   是否倒下
     * @param faction    阵营；未声明时为 null
     */
    public record FinalPlayer(String playerId, int level, int hp, int maxHp, int armorClass,
                              boolean defeated, String faction) {
    }
}
