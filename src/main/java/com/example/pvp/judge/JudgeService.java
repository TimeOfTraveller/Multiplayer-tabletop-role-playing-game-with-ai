package com.example.pvp.judge;

import com.example.pvp.combat.CombatRules;
import com.example.pvp.combat.DiceRoller;
import com.example.pvp.growth.GrowthRules;
import com.example.pvp.state.GameEvent;
import com.example.pvp.state.GlobalState;
import com.example.pvp.state.PlayerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 裁决层：一轮内所有数值与命中判定都在这里用 Java 完成，叙事模型不得参与计算。
 *
 * <p>结算语义（多人房间同时行动）：</p>
 * <ul>
 *   <li>本轮所有攻击的命中与伤害都基于“轮初快照”计算：攻击加值、目标 AC、目标轮初 HP 都不会被
 *       同一轮里更早的行动改变。因此本轮所有存活玩家都能按提交的行动出手，不会出现“被先手打死后
 *       就凭空少一次行动”。</li>
 *   <li>伤害按玩家标识字典序依次记账，事件里的 targetHpRemaining 是这个固定顺序下的累计结果；
 *       目标是否倒下以轮初 HP 为基准，由累计伤害跨过 0 的那一次行动标记 target_down。</li>
 *   <li>同一目标被多人打倒也只结算一次胜利经验，归给按顺序跨过阈值的那名攻击者。</li>
 *   <li>轮初已经倒下的玩家不产生伤害（事件结果 already_down）；未提交行动的玩家生成 idle 事件占位。</li>
 * </ul>
 */
@Service
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);

    /** 假裁决的固定结果，同时也是搜索成功时获得的物品标识。 */
    private static final String FOUND_KEY = "found_key";

    /** 当前唯一有实际状态副作用的探索意图。 */
    private static final String INTENT_SEARCH = "search";

    /** 已有的第二个标准意图：喊话，不产生物品。 */
    private static final String INTENT_SHOUT = "shout";

    private final DiceRoller diceRoller;

    public JudgeService(DiceRoller diceRoller) {
        this.diceRoller = diceRoller;
    }

    /**
     * 结算一整轮：按玩家标识字典序处理本轮参与者的行动，未提交的在线玩家按观望占位。
     *
     * <p>参与者 = 本轮在线玩家 ∪ 已入队玩家。既不在线也没入队的剧本玩家不会出现在本轮事件里，
     * 避免向只上线了一人的房间虚构其他人的行动。</p>
     *
     * <p>方法内部会修改玩家 HP、背包、经验，并写入事件日志；调用方负责并发叙事与推送。
     * 同一个房间同一时间只允许一个结算线程（由 TurnService 保证）。</p>
     *
     * @param state          房间状态
     * @param actions        本轮已提交的行动（顺序不敏感，内部会排序）
     * @param turnNumber     轮次号，仅用于日志
     * @param onlinePlayers  本轮在线玩家（结算开始时快照）
     * @return 本轮写入的全部事件（按写入顺序）
     */
    public List<GameEvent> resolveTurn(GlobalState state, List<GlobalState.QueuedAction> actions, int turnNumber,
                                       Collection<String> onlinePlayers) {
        Map<String, GlobalState.QueuedAction> byPlayer = new LinkedHashMap<>();
        for (GlobalState.QueuedAction action : actions) {
            byPlayer.put(action.playerId(), action);
        }

        List<String> playerIds = state.playerIds();
        Set<String> participants = new HashSet<>(onlinePlayers);
        participants.addAll(byPlayer.keySet());
        Map<String, Integer> startHp = new HashMap<>();
        Map<String, Boolean> aliveAtStart = new HashMap<>();
        for (String playerId : playerIds) {
            int hp = state.playerOf(playerId).hp();
            startHp.put(playerId, hp);
            aliveAtStart.put(playerId, hp > CombatRules.MIN_HP);
        }

        // 第一遍：基于轮初快照规划，不做任何状态修改。
        List<Plan> plans = new ArrayList<>();
        for (String playerId : playerIds) {
            if (!participants.contains(playerId)) {
                continue;
            }
            GlobalState.QueuedAction action = byPlayer.get(playerId);
            plans.add(plan(state, playerId, action, aliveAtStart, startHp));
        }

        // 第二遍：按固定顺序落账，生成事件。
        List<GameEvent> events = new ArrayList<>();
        Set<String> downedThisTurn = new HashSet<>();
        Map<String, GameEvent.Combat> downCombat = new HashMap<>();
        int idleCount = 0;
        int attackCount = 0;
        int hitCount = 0;
        int totalDamage = 0;

        for (Plan plan : plans) {
            if (plan.idle()) {
                idleCount++;
                GameEvent event = new GameEvent(state.nextEventId(), GameEvent.TYPE_ACTION, plan.playerId(),
                        GameEvent.INTENT_IDLE, GameEvent.RESULT_IDLE, true, List.of(), null, null);
                state.appendEvent(event);
                events.add(event);
                continue;
            }
            if (plan.attack() != null) {
                attackCount++;
                if (plan.attack().hit()) {
                    hitCount++;
                    totalDamage += plan.attack().damage();
                }
                GameEvent event = resolveAttack(state, plan, startHp, downedThisTurn, downCombat);
                state.appendEvent(event);
                events.add(event);
                continue;
            }
            GameEvent event = resolveAction(state, plan);
            state.appendEvent(event);
            events.add(event);
        }

        // 倒下事件统一放在本轮事件之后，保证同一轮多目标倒下时顺序稳定。
        downedThisTurn.stream().sorted().forEach(targetId -> {
            GameEvent down = new GameEvent(state.nextEventId(), GameEvent.TYPE_DOWN, targetId,
                    "down", GameEvent.RESULT_TARGET_DOWN, true, List.of(), downCombat.get(targetId), null);
            state.appendEvent(down);
            events.add(down);
            log.warn("轮次结算 room={} turn={} 玩家 {} HP 归零，已生成倒下事件 eventId={}",
                    state.roomId(), turnNumber, targetId, down.eventId());
        });

        log.info("轮次结算完成 room={} turn={} 玩家数={} 攻击={} 命中={} 观望={} 总伤害={} 倒下={} 事件数={}",
                state.roomId(), turnNumber, plans.size(), attackCount, hitCount, idleCount, totalDamage,
                downedThisTurn.size(), events.size());
        return events;
    }

    /**
     * 基于轮初快照规划一条行动：不读取、不修改任何会被本轮其他行动影响的状态。
     */
    private Plan plan(GlobalState state, String playerId, GlobalState.QueuedAction action,
                      Map<String, Boolean> aliveAtStart, Map<String, Integer> startHp) {
        if (action == null || action.intent() == null || action.intent().isBlank()
                || GameEvent.INTENT_IDLE.equals(action.intent())) {
            return Plan.idle(playerId);
        }
        if (!aliveAtStart.getOrDefault(playerId, false)) {
            // 轮初已经倒下：不能出手，按观望占位，避免“已死的人还能攻击”。
            log.info("轮次规划 room={} 玩家={} 轮初已倒下，本轮行动按观望处理", state.roomId(), playerId);
            return Plan.idle(playerId);
        }
        if (action.attackTarget() != null) {
            return planAttack(state, playerId, action.attackTarget(), aliveAtStart, startHp);
        }
        return new Plan(playerId, action.intent(), action.privateAction(), null, null, false);
    }

    /**
     * 规划一次攻击：命中与伤害全部用轮初的攻击加值 / 目标 AC / 目标 HP 计算。
     */
    private Plan planAttack(GlobalState state, String attackerId, String targetId,
                            Map<String, Boolean> aliveAtStart, Map<String, Integer> startHp) {
        PlayerState attacker = state.playerOf(attackerId);
        PlayerState target = state.playerOf(targetId);
        String damageDice = attacker.damageDice().notation();
        int targetAc = target.armorClass();
        int attackBonus = attacker.attackBonus();

        if (!aliveAtStart.getOrDefault(targetId, false)) {
            // 目标在轮初已经倒下：记录一次无伤害攻击，结果标记 already_down。
            return new Plan(attackerId, "attack", true, targetId,
                    new AttackRoll(0, attackBonus, attackBonus, targetAc, false, damageDice, 0, 0, false),
                    false);
        }

        int attackRoll = diceRoller.roll(CombatRules.ATTACK_DIE_SIDES);
        int attackTotal = attackRoll + attackBonus;
        boolean hit = attackTotal >= targetAc;
        int damageRoll = 0;
        int damage = 0;
        if (hit) {
            DiceRoller.Roll roll = diceRoller.roll(attacker.damageDice());
            damageRoll = roll.total();
            damage = damageRoll;
        }
        log.info("轮次规划 room={} attacker={} target={} 命中检定: 1d20={} + attackBonus={} = {} vs AC={} => {}"
                        + " | 伤害骰={} 点数={} damage={}（均基于轮初快照）",
                state.roomId(), attackerId, targetId, attackRoll, attackBonus, attackTotal, targetAc,
                hit ? "命中" : "未命中", damageDice, damageRoll, damage);
        return new Plan(attackerId, "attack", true, targetId,
                new AttackRoll(attackRoll, attackBonus, attackTotal, targetAc, hit, damageDice, damageRoll,
                        damage, startHp.getOrDefault(targetId, 0) > CombatRules.MIN_HP),
                false);
    }

    /**
     * 按固定顺序把一次攻击落账：扣血、判定是否跨过倒下阈值、结算胜利经验。
     */
    private GameEvent resolveAttack(GlobalState state, Plan plan, Map<String, Integer> startHp,
                                    Set<String> downedThisTurn, Map<String, GameEvent.Combat> downCombat) {
        AttackRoll roll = plan.attack();
        PlayerState target = state.playerOf(plan.attackTarget());
        int hpBefore = target.hp();
        int remaining = target.applyDamage(roll.damage());
        boolean defeatedNow = roll.targetAliveAtStart() && roll.hit() && hpBefore > CombatRules.MIN_HP
                && remaining <= CombatRules.MIN_HP;

        String result;
        if (!roll.targetAliveAtStart()) {
            result = GameEvent.RESULT_ALREADY_DOWN;
        } else if (!roll.hit()) {
            result = GameEvent.RESULT_MISS;
        } else if (defeatedNow) {
            result = GameEvent.RESULT_TARGET_DOWN;
        } else {
            result = GameEvent.RESULT_HIT;
        }

        GameEvent.Combat combat = new GameEvent.Combat(plan.playerId(), plan.attackTarget(), roll.attackRoll(),
                roll.attackBonus(), roll.attackTotal(), roll.targetAc(), roll.hit(), roll.damageDice(),
                roll.damageRoll(), roll.damage(), remaining, defeatedNow);
        String eventId = state.nextEventId();
        // 只有“轮初存活 + 本轮累计伤害跨过 0”的那一次行动结算胜利经验，避免多人围殴重复发经验。
        GameEvent.Growth growth = defeatedNow
                ? applyGrowth(state, plan.playerId(), GrowthRules.SOURCE_COMBAT_VICTORY,
                GrowthRules.EXP_COMBAT_VICTORY, eventId)
                : null;
        if (defeatedNow) {
            downedThisTurn.add(plan.attackTarget());
            downCombat.put(plan.attackTarget(), combat);
        }

        log.info("攻击落账 room={} attacker={} target={} 1d20={} + {} = {} vs AC={} => {} | 伤害={} 剩余HP {}->{}"
                        + " eventId={} 结果={}",
                state.roomId(), plan.playerId(), plan.attackTarget(), roll.attackRoll(), roll.attackBonus(),
                roll.attackTotal(), roll.targetAc(), roll.hit() ? "命中" : "未命中", roll.damage(), hpBefore,
                remaining, eventId, result);
        return new GameEvent(eventId, GameEvent.TYPE_ATTACK, plan.playerId(), "attack", result,
                true, List.of(), combat, growth);
    }

    /**
     * 落账一条非攻击行动（搜索 / 喊话 / 自由文本）。
     */
    private GameEvent resolveAction(GlobalState state, Plan plan) {
        String result;
        GameEvent.Growth growth = null;
        String eventId = state.nextEventId();
        if (INTENT_SEARCH.equals(plan.intent())) {
            // 钥匙是唯一物品：重复搜索不再入包，也不重复结算经验，避免背包出现多把钥匙。
            boolean firstKey = !state.playerOf(plan.playerId()).hasItem(FOUND_KEY);
            if (firstKey) {
                state.addItem(plan.playerId(), FOUND_KEY);
                growth = applyGrowth(state, plan.playerId(), GrowthRules.SOURCE_PUZZLE_KEY,
                        GrowthRules.EXP_PUZZLE_KEY, eventId);
            }
            result = GameEvent.RESULT_FOUND_KEY;
        } else if (INTENT_SHOUT.equals(plan.intent())) {
            result = GameEvent.RESULT_FOUND_KEY;
        } else {
            result = GameEvent.RESULT_NOTHING;
        }

        boolean isPublic = !plan.privateAction();
        List<String> visibleTo = isPublic ? List.of() : state.factionAudience(plan.playerId());
        log.info("行动裁决 room={} player={} intent={} result={} private={} visibleTo={} eventId={}",
                state.roomId(), plan.playerId(), plan.intent(), result, plan.privateAction(), visibleTo, eventId);
        return new GameEvent(eventId, GameEvent.TYPE_ACTION, plan.playerId(), plan.intent(), result,
                isPublic, visibleTo, null, growth);
    }

    /**
     * 在裁决层结算经验：调用 {@link PlayerState#gainExp(int, int)} 并写一行可复核日志。
     */
    private GameEvent.Growth applyGrowth(GlobalState state, String playerId, String source, int amount,
                                        String eventId) {
        PlayerState.LevelUp up = state.playerOf(playerId)
                .gainExp(amount, GrowthRules.DEFAULT_EXP_PER_LEVEL);
        log.info("成长结算 room={} player={} source={} eventId={} expGained={} expBefore={} exp={}"
                        + " | level {}->{}（本次 +{} 级） | maxHp {}->{} attackBonus {}->{} hp {}->{}（升级回满={}）",
                state.roomId(), playerId, source, eventId, up.expGained(), up.expBefore(), up.exp(),
                up.levelBefore(), up.level(), up.levelsGained(),
                up.maxHpBefore(), up.maxHpAfter(), up.attackBonusBefore(), up.attackBonusAfter(),
                up.hpBefore(), up.hpAfter(), up.leveledUp());
        return new GameEvent.Growth(playerId, source, up.expGained(), up.expBefore(), up.exp(),
                up.levelBefore(), up.level(), up.levelsGained(), up.leveledUp(),
                up.maxHpBefore(), up.maxHpAfter(), up.attackBonusBefore(), up.attackBonusAfter(),
                up.hpBefore(), up.hpAfter());
    }

    /**
     * 一条行动的规划结果；idle=true 表示观望/未提交/轮初已倒下。
     */
    private record Plan(String playerId, String intent, boolean privateAction, String attackTarget,
                        AttackRoll attack, boolean idle) {

        static Plan idle(String playerId) {
            return new Plan(playerId, GameEvent.INTENT_IDLE, true, null, null, true);
        }
    }

    /**
     * 一次攻击的轮初快照计算结果。
     */
    private record AttackRoll(int attackRoll, int attackBonus, int attackTotal, int targetAc, boolean hit,
                              String damageDice, int damageRoll, int damage, boolean targetAliveAtStart) {
    }
}
