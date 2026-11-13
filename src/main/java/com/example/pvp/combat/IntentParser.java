package com.example.pvp.combat;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 纯 Java 意图解析：识别“攻击 + 点名另一名玩家”的输入，不调用任何模型。
 *
 * <p>目标名册完全由调用方传入（来自房间剧本），因此 2~4 人房间共用同一套解析逻辑。
 * 匹配使用 ASCII 词边界（大小写敏感），中文句子里的普通字符不会命中单字母玩家标识；
 * “AB”“B2”“xB”这类连写也不会被当成目标。</p>
 */
@Component
public class IntentParser {

    /** 触发攻击的关键词；出现任意一个即视为攻击语义。 */
    private static final List<String> ATTACK_KEYWORDS = List.of("攻击", "砍", "打", "刺", "射击", "出手");

    /** 行为类型：攻击。 */
    public static final String ACTION_ATTACK = "attack";

    /** 玩家标识 -> 词边界匹配式；玩家标识全局有限，按需缓存。 */
    private final Map<String, Pattern> playerPatterns = new ConcurrentHashMap<>();

    /**
     * 尝试把一条输入解析为攻击，并找出被点名的另一名玩家。
     *
     * <p>按标识长度从长到短匹配，避免 P1 / P10 这类前缀互相截断；只考虑房间现有玩家。</p>
     *
     * @param intent     玩家输入的原始文本
     * @param attackerId 发起者标识
     * @param candidates 本房间玩家标识集合
     * @return 被攻击的玩家标识；不是攻击语义时为空
     */
    public Optional<String> parseAttackTarget(String intent, String attackerId, Collection<String> candidates) {
        if (intent == null || intent.isBlank() || !containsAttackKeyword(intent)) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> !candidate.equals(attackerId))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .filter(candidate -> pattern(candidate).matcher(intent).find())
                .findFirst();
    }

    private Pattern pattern(String playerId) {
        return playerPatterns.computeIfAbsent(playerId,
                id -> Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(id) + "(?![A-Za-z0-9_])"));
    }

    private boolean containsAttackKeyword(String intent) {
        for (String keyword : ATTACK_KEYWORDS) {
            if (intent.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
