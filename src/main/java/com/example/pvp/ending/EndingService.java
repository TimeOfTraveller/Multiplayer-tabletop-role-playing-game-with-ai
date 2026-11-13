package com.example.pvp.ending;

import com.example.pvp.narrative.Terminology;
import com.example.pvp.scenario.Scenario;
import com.example.pvp.state.GlobalState;
import com.example.pvp.state.PlayerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 胜负判定：一轮的事件与成长全部结算完成之后调用，只读取状态，不修改玩家数值。
 *
 * <p>三种条件可单独或组合启用，组合时判定优先级固定为：</p>
 * <ol>
 *   <li>collectItems：集齐指定物品（若多人同时满足，玩家标识字典序最小者胜）；</li>
 *   <li>lastStanding：只剩一名未倒下玩家，该玩家胜；</li>
 *   <li>maxTurns：达到轮数上限，存活者中当前 HP 最高者胜；同 HP 比等级，再同比最大 HP，
 *       仍相同则玩家标识字典序最小者胜；全员倒下为平局。</li>
 * </ol>
 *
 * <p>返回值同时包含“本轮满足的全部条件”，即使主条件不是它，结算面板也会完整展示。</p>
 */
@Service
public class EndingService {

    private static final Logger log = LoggerFactory.getLogger(EndingService.class);

    /**
     * 判断本局是否结束。
     *
     * @param state 房间状态（裁决与成长已落账）
     * @param turn  刚结算完成的轮次
     * @return 结束快照；未结束返回 null
     */
    public EndingState evaluate(GlobalState state, int turn) {
        Scenario scenario = state.scenario();
        List<String> conditions = new ArrayList<>();

        String collectorWinner = findCollector(state, scenario, conditions);
        String lastStandingWinner = findLastStanding(state, scenario, conditions);
        String maxTurnsWinner = findMaxTurnsWinner(state, scenario, turn, conditions);

        if (conditions.isEmpty()) {
            return null;
        }

        String primary;
        String winnerId;
        if (collectorWinner != null) {
            primary = EndingState.CONDITION_COLLECT_ITEMS;
            winnerId = collectorWinner;
        } else if (lastStandingWinner != null) {
            primary = EndingState.CONDITION_LAST_STANDING;
            winnerId = lastStandingWinner;
        } else {
            primary = EndingState.CONDITION_MAX_TURNS;
            winnerId = maxTurnsWinner;
        }

        List<EndingState.FinalPlayer> players = state.playerIds().stream()
                .map(state::playerOf)
                .map(player -> new EndingState.FinalPlayer(player.playerId(), player.level(), player.hp(),
                        player.maxHp(), player.armorClass(), player.isDefeated(), player.faction()))
                .toList();

        EndingState ending = new EndingState(primary, List.copyOf(conditions), winnerId, turn, players);
        log.info("胜负判定 room={} turn={} 主条件={} 满足条件={} 胜者={} 玩家状态={}",
                state.roomId(), turn, primary, conditions, winnerId, players);
        return ending;
    }

    /**
     * collectItems：检查每名玩家是否集齐所需物品。
     *
     * <p>物品既可以用机制标识（found_key），也可以用它登记的自然语言别名（钥匙）配置。</p>
     *
     * @return 满足条件且标识字典序最小的玩家；无人满足返回 null
     */
    private String findCollector(GlobalState state, Scenario scenario, List<String> conditions) {
        if (!scenario.collectItemsEnabled()) {
            return null;
        }
        List<String> required = scenario.endings().collectItems().items();
        String winner = null;
        for (String playerId : state.playerIds()) {
            if (hasAll(state.playerOf(playerId), required)) {
                winner = playerId;
                break;
            }
        }
        if (winner != null) {
            conditions.add(EndingState.CONDITION_COLLECT_ITEMS);
        }
        return winner;
    }

    private boolean hasAll(PlayerState player, List<String> required) {
        for (String need : required) {
            boolean owned = player.inventory().stream().anyMatch(item -> itemMatches(item, need));
            if (!owned) {
                return false;
            }
        }
        return true;
    }

    /**
     * 物品匹配：机制标识、登记别名（如“钥匙”）都算同一件物品。
     */
    private boolean itemMatches(String item, String required) {
        if (item.equalsIgnoreCase(required)) {
            return true;
        }
        return Terminology.itemAliases(item).stream().anyMatch(alias -> alias.equalsIgnoreCase(required));
    }

    /**
     * lastStanding：只剩一名未倒下玩家。
     *
     * @return 唯一存活玩家；不满足返回 null
     */
    private String findLastStanding(GlobalState state, Scenario scenario, List<String> conditions) {
        if (!scenario.lastStandingEnabled()) {
            return null;
        }
        List<String> alive = state.playerIds().stream()
                .filter(playerId -> !state.playerOf(playerId).isDefeated())
                .toList();
        if (alive.size() != 1) {
            return null;
        }
        conditions.add(EndingState.CONDITION_LAST_STANDING);
        return alive.get(0);
    }

    /**
     * maxTurns：达到轮数上限后按 HP 规则判定；没有存活者时为平局（winner=null）。
     */
    private String findMaxTurnsWinner(GlobalState state, Scenario scenario, int turn, List<String> conditions) {
        if (!scenario.maxTurnsEnabled()) {
            return null;
        }
        Integer limit = scenario.endings().maxTurns().turns();
        if (limit == null || turn < limit) {
            return null;
        }
        conditions.add(EndingState.CONDITION_MAX_TURNS);

        String best = null;
        int bestHp = -1;
        int bestLevel = -1;
        int bestMaxHp = -1;
        for (String playerId : state.playerIds()) {
            PlayerState player = state.playerOf(playerId);
            if (player.isDefeated()) {
                continue;
            }
            boolean better = player.hp() > bestHp
                    || (player.hp() == bestHp && player.level() > bestLevel)
                    || (player.hp() == bestHp && player.level() == bestLevel && player.maxHp() > bestMaxHp);
            if (best == null || better) {
                best = playerId;
                bestHp = player.hp();
                bestLevel = player.level();
                bestMaxHp = player.maxHp();
            }
        }
        return best;
    }
}
