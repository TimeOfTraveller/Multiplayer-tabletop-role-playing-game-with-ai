package com.example.pvp.scenario;

import java.util.regex.Pattern;

/**
 * 房间玩家名册约束：人数 2~4，人名单完全由剧本 {@code players} 的键决定。
 *
 * <p>不再有任何写死的玩家标识；WS、裁决、投影、叙事都从房间状态里的玩家集合遍历。</p>
 */
public final class Players {

    /** 一个房间至少需要两名玩家。 */
    public static final int MIN_PLAYERS = 2;

    /** 一个房间最多允许四名玩家。 */
    public static final int MAX_PLAYERS = 4;

    /** 玩家标识只允许字母/数字/下划线/短横线，长度 1~16，便于意图解析做词边界匹配。 */
    public static final Pattern PLAYER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    private Players() {
    }

    /**
     * 判断玩家标识是否合法。
     *
     * @param playerId 玩家标识
     * @return true 表示符合统一命名规则
     */
    public static boolean isValidPlayerId(String playerId) {
        return playerId != null && PLAYER_ID_PATTERN.matcher(playerId).matches();
    }
}
