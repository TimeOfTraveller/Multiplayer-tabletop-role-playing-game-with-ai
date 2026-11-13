package com.example.pvp.state;

import java.util.List;

/**
 * 玩家视角投影，按 PROJECT_RULES.md 5.2 逐字段显式构造后才会推给该玩家。
 *
 * <p>绝不包含其他玩家的 inventory、私有事件或任何未授权字段。
 * 战斗事件默认公开，因此双方都能看到同一份战斗审计信息。</p>
 *
 * @param room          房间标识
 * @param playerId      视角所属玩家
 * @param hp            当前 HP
 * @param maxHp         最大 HP
 * @param exp           当前经验（已扣除升级消耗）
 * @param level         当前等级
 * @param expPerLevel   每级所需经验，前端经验条分母
 * @param title         剧本主题/标题，页面顶部与浏览器标题使用
 * @param worldview     世界观设定
 * @param background    开场描述
 * @param secretGoal    该玩家的秘密目标，只出现在本人视角
 * @param myInventory   该玩家自己的物品列表
 * @param visibleEvents 该玩家有权看到的事件（含战斗与成长审计字段）
 * @param publicPlayers 本房间所有玩家的公开状态（名称、等级、HP、maxHp、AC）
 * @param turn          当前轮次号，从 1 开始
 * @param onlinePlayers 本房间当前在线的玩家标识（按字典序）
 * @param submittedPlayers 本轮已提交行动的玩家标识（按字典序），前端用于「已提交 x/y」
 * @param narrativeText 该玩家专属的叙事文本
 * @param levelUpNarrative 升级专属叙事；未升级时为空字符串
 * @param ending        对局结算结果；未结束时为 null
 */
public record PlayerView(String room, String playerId, int hp, int maxHp, int exp, int level, int expPerLevel,
                         String title, String worldview, String background, String secretGoal,
                         List<String> myInventory, List<VisibleEvent> visibleEvents,
                         List<PublicPlayer> publicPlayers, int turn, List<String> onlinePlayers,
                         List<String> submittedPlayers, String narrativeText, String levelUpNarrative,
                         EndingView ending) {

    /**
     * 投影后的事件：暴露事件标识、类型、意图、结果、可选战斗审计信息与成长审计信息。
     *
     * @param eventId 事件 ID
     * @param type    事件类型
     * @param actor   行动发起者
     * @param intent  行动意图
     * @param result  裁决结果标记
     * @param combat  战斗审计信息；非战斗事件为 null
     * @param growth  成长审计信息；未结算成长或非成长归属玩家时为 null
     */
    public record VisibleEvent(String eventId, String type, String actor, String intent, String result,
                               GameEvent.Combat combat, GameEvent.Growth growth) {
    }

    /**
     * 公开可见的玩家状态，用于前端显示双方等级与 HP 条。
     *
     * @param playerId   玩家标识
     * @param name       展示名（当前与 playerId 相同，剧本暂无角色名）
     * @param level      当前等级
     * @param hp         当前 HP
     * @param maxHp      最大 HP
     * @param armorClass 护甲等级
     * @param defeated   是否已倒下
     */
    public record PublicPlayer(String playerId, String name, int level, int hp, int maxHp, int armorClass,
                               boolean defeated) {
    }

    /**
     * 对局结束时的结算视图（每个玩家各自一份）。
     *
     * @param condition  决定胜负的主条件：collect_items / last_standing / max_turns
     * @param conditions 本局满足的全部条件，按固定优先级排序
     * @param outcome    win / lose / draw
     * @param winnerId   胜者玩家标识；平局为 null
     * @param youWin     该视角玩家是否胜出
     * @param narrative  结算叙述（确定性模板，不依赖 LLM）
     * @param players    结束时全体玩家的公开状态（含阵营，仅在结算时公开）
     */
    public record EndingView(String condition, List<String> conditions, String outcome, String winnerId,
                             boolean youWin, String narrative, List<EndingPlayer> players) {
    }

    /**
     * 结算面板里展示的玩家最终状态。
     *
     * @param playerId   玩家标识
     * @param name       展示名
     * @param level      最终等级
     * @param hp         最终 HP
     * @param maxHp      最终最大 HP
     * @param armorClass 护甲等级
     * @param defeated   是否倒下
     * @param faction    阵营；未声明时为 null
     */
    public record EndingPlayer(String playerId, String name, int level, int hp, int maxHp, int armorClass,
                               boolean defeated, String faction) {
    }

    /**
     * 返回携带叙事文本的新投影（record 不可变，不修改原对象）。
     *
     * @param narrative         已通过校验的本轮普通叙事
     * @param levelUpNarrative  已通过校验的升级专属叙事；未升级传空字符串
     * @return 带叙事的新 PlayerView
     */
    public PlayerView withNarrative(String narrative, String levelUpNarrative) {
        return new PlayerView(room, playerId, hp, maxHp, exp, level, expPerLevel, title, worldview, background,
                secretGoal, myInventory, visibleEvents, publicPlayers, turn, onlinePlayers, submittedPlayers,
                narrative, levelUpNarrative, ending);
    }
}
