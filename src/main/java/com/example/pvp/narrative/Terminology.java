package com.example.pvp.narrative;

import java.util.List;
import java.util.Map;

/**
 * 机制标识与自然语言说法的对照表。
 *
 * <p>提示词渲染与输出校验都依赖这里：提示词里只出现自然语言，不出现机制 token；
 * 校验层用别名表判断“对方私有物品”是否被说漏（中英文别名都要覆盖）。</p>
 *
 * <p>新增物品 / 意图 / 结果时必须在这里登记，否则校验只能退化为直接匹配机制 token。</p>
 */
public final class Terminology {

    /**
     * 物品别名表：机制标识 -> 允许出现在叙事里的说法（第一个是展示名，其余是别名）。
     * 例：found_key 在叙事里可能被模型写成“钥匙”，所以必须把“钥匙”也列为别名。
     */
    private static final Map<String, List<String>> ITEM_ALIASES = Map.of(
            "found_key", List.of("一把钥匙", "钥匙")
    );

    /** 结果的自然语言描述：机制标识 -> 一句话。 */
    private static final Map<String, String> RESULT_DESCRIPTIONS = Map.of(
            "found_key", "找到了一把钥匙",
            "nothing", "什么也没发生",
            "hit", "命中了目标",
            "miss", "没有命中",
            "target_down", "目标倒下了",
            "idle", "本轮没有行动",
            "already_down", "目标已经倒下，没有造成伤害"
    );

    /** 意图的自然语言描述：机制标识 -> 动作短语（接在“你刚刚/你”后面）。 */
    private static final Map<String, String> INTENT_DESCRIPTIONS = Map.of(
            "search", "搜索了四周",
            "shout", "朝四周喊了一声",
            "attack", "发起了一次攻击",
            "down", "倒下了",
            "idle", "选择了观望"
    );

    private Terminology() {
    }

    /**
     * 物品在叙事里的展示名。
     *
     * @param item 物品机制标识
     * @return 自然语言说法；未登记时退回标识本身
     */
    public static String itemDisplay(String item) {
        List<String> aliases = ITEM_ALIASES.get(item);
        return aliases == null ? item : aliases.get(0);
    }

    /**
     * 物品的全部别名（含中英文），用于输出校验。
     *
     * @param item 物品机制标识
     * @return 别名列表；未登记时只含标识本身
     */
    public static List<String> itemAliases(String item) {
        List<String> aliases = ITEM_ALIASES.get(item);
        return aliases == null ? List.of(item) : aliases;
    }

    /**
     * 结果的自然语言描述。
     *
     * @param result 结果机制标识
     * @return 自然语言描述；未登记时用中性说法
     */
    public static String resultDisplay(String result) {
        return RESULT_DESCRIPTIONS.getOrDefault(result, "事情有了结果");
    }

    /**
     * 意图的自然语言描述。
     *
     * @param intent 意图机制标识，可能是玩家自由输入的任意文本
     * @return 动作短语；自由文本会包进「」，保证提示词可读且不引入额外标识
     */
    public static String intentDisplay(String intent) {
        if (intent == null || intent.isBlank()) {
            return "行动了一次";
        }
        String known = INTENT_DESCRIPTIONS.get(intent);
        return known == null ? "尝试「" + intent + "」" : known;
    }
}
