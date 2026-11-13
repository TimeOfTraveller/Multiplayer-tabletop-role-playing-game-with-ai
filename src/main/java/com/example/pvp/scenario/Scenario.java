package com.example.pvp.scenario;

import java.util.List;
import java.util.Map;

/**
 * 剧本初始设定（scenarios/default.json 的内存模型）。
 *
 * <p>所有字段都是房间初始化与 /admin/reload 重建房间的唯一来源；
 * 缺失字段在加载阶段就会被拒绝，不会用空值兜底。</p>
 *
 * <p>文案字段的职责划分（避免重复语义）：</p>
 * <ul>
 *   <li>{@code title}：玩家页面顶部展示的主题/标题，也是页面 title；</li>
 *   <li>{@code worldview}：世界观设定，交代时代、地点与规则；</li>
 *   <li>{@code background}：开场描述，只负责“此刻你们在哪里、看到什么”。</li>
 * </ul>
 *
 * @param title      剧本主题/标题，玩家页面顶部与浏览器标题展示
 * @param worldview  世界观设定，展示给玩家并进入叙事提示词
 * @param background 开场描述，展示给玩家并进入叙事提示词
 * @param players    玩家标识 -> 该玩家的初始设定
 * @param endings    胜负结算配置；至少启用一种条件
 */
public record Scenario(String title, String worldview, String background,
                       Map<String, PlayerSpec> players, Endings endings) {

    /**
     * 单个玩家的初始设定。
     *
     * @param secretGoal  秘密目标，展示给本人，不进入他人视角
     * @param hp          初始当前 HP
     * @param maxHp       初始最大 HP
     * @param skills      初始技能（本阶段只保存与展示，不参与结算）
     * @param items       初始物品（直接进入该玩家的 inventory）
     * @param armorClass  护甲等级，缺省时用战斗规则默认值
     * @param attackBonus 命中检定加值，缺省时用战斗规则默认值
     * @param damageDice  伤害骰写法（如 1d6），缺省时用战斗规则默认值
     * @param faction     可选阵营；同阵营玩家互相可见对方的私有事件
     */
    public record PlayerSpec(String secretGoal, int hp, int maxHp, List<String> skills, List<String> items,
                             Integer armorClass, Integer attackBonus, String damageDice, String faction) {
    }

    /**
     * 胜负结算配置：三种条件可单独启用，也可组合启用。
     *
     * <p>组合启用时的判定优先级固定为 collectItems → lastStanding → maxTurns，
     * 这样同一轮同时满足多个条件时结果也是稳定的。</p>
     *
     * @param lastStanding 仅剩一名未倒下玩家时该玩家胜
     * @param maxTurns     达到轮数上限后的判定
     * @param collectItems 集齐指定物品者胜
     */
    public record Endings(LastStanding lastStanding, MaxTurns maxTurns, CollectItems collectItems) {

        /**
         * @param enabled 是否启用；缺省视为不启用
         */
        public record LastStanding(Boolean enabled) {
        }

        /**
         * @param enabled 是否启用；缺省视为不启用
         * @param turns   轮数上限，启用时必须 >= 1
         */
        public record MaxTurns(Boolean enabled, Integer turns) {
        }

        /**
         * @param enabled 是否启用；缺省视为不启用
         * @param items   需要集齐的物品标识；启用时必须非空
         */
        public record CollectItems(Boolean enabled, List<String> items) {
        }
    }

    /**
     * 取指定玩家的初始设定。
     *
     * @param playerId 玩家标识
     * @return 该玩家的初始设定；不存在时抛异常（调用方保证玩家来自本剧本 players 的键）
     */
    public PlayerSpec player(String playerId) {
        PlayerSpec spec = players == null ? null : players.get(playerId);
        if (spec == null) {
            throw new IllegalArgumentException("剧本缺少玩家 " + playerId + " 的初始设定");
        }
        return spec;
    }

    /**
     * lastStanding 是否启用。
     *
     * @return true 表示启用
     */
    public boolean lastStandingEnabled() {
        return endings != null && endings.lastStanding() != null
                && Boolean.TRUE.equals(endings.lastStanding().enabled());
    }

    /**
     * maxTurns 是否启用。
     *
     * @return true 表示启用
     */
    public boolean maxTurnsEnabled() {
        return endings != null && endings.maxTurns() != null
                && Boolean.TRUE.equals(endings.maxTurns().enabled());
    }

    /**
     * collectItems 是否启用。
     *
     * @return true 表示启用
     */
    public boolean collectItemsEnabled() {
        return endings != null && endings.collectItems() != null
                && Boolean.TRUE.equals(endings.collectItems().enabled());
    }
}
