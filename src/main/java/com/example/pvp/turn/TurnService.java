package com.example.pvp.turn;

import com.example.pvp.ending.EndingService;
import com.example.pvp.ending.EndingState;
import com.example.pvp.judge.JudgeService;
import com.example.pvp.narrative.NarrativeService;
import com.example.pvp.narrative.OutputValidator;
import com.example.pvp.state.GameEvent;
import com.example.pvp.state.GlobalState;
import com.example.pvp.state.PlayerView;
import com.example.pvp.state.RoomRegistry;
import com.example.pvp.ws.PlayerMessageLog;
import com.example.pvp.ws.PlayerSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 轮次与行动队列：收集本房间所有在线玩家的行动，满足条件后统一裁决、并发叙事并推送。
 *
 * <p>触发条件：</p>
 * <ul>
 *   <li>所有在线玩家都提交（立即结算）；</li>
 *   <li>从本轮第一个提交开始计时，超过 {@code turn.timeout-millis}（默认 30000ms，
 *       可用 TURN_TIMEOUT_MS 覆盖）自动结算。</li>
 * </ul>
 *
 * <p>未提交的玩家在结算时按「观望」占位生成 idle 事件，不会从本轮消失；同一玩家重复提交会覆盖
 * 上一次行动；玩家断线时已入队行动保留并照常结算，若断线后剩余在线玩家都已提交则立即结算。</p>
 *
 * <p>结算顺序固定为玩家标识字典序；所有攻击的命中/伤害基于轮初快照计算（见
 * {@link JudgeService#resolveTurn(GlobalState, List, int)}），避免“已倒下的人还能出手”的歧义。</p>
 */
@Service
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    /** 结算原因：所有在线玩家都已提交。 */
    public static final String REASON_ALL_SUBMITTED = "all_submitted";

    /** 结算原因：达到本轮超时时间。 */
    public static final String REASON_TIMEOUT = "timeout";

    /** 结算原因：在线玩家断线后，剩余在线玩家都已提交。 */
    public static final String REASON_PLAYER_LEFT = "player_left";

    private final RoomRegistry roomRegistry;

    private final PlayerSessionRegistry sessionRegistry;

    private final JudgeService judgeService;

    private final EndingService endingService;

    private final NarrativeService narrativeService;

    private final OutputValidator outputValidator;

    private final PlayerMessageLog messageLog;

    private final ObjectMapper objectMapper;

    private final long timeoutMillis;

    /** 每个玩家一次叙事调用；固定线程池保证一轮内多个玩家真正并发请求模型。 */
    private final ExecutorService narrativeExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "narrative-worker");
        thread.setDaemon(true);
        return thread;
    });

    /** 超时计时器线程池；结算本身会占用一个线程，留两个槽位避免多房间互相饿死。 */
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "turn-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public TurnService(RoomRegistry roomRegistry, PlayerSessionRegistry sessionRegistry, JudgeService judgeService,
                       EndingService endingService, NarrativeService narrativeService,
                       OutputValidator outputValidator, PlayerMessageLog messageLog, ObjectMapper objectMapper,
                       @Value("${turn.timeout-millis:30000}") long timeoutMillis) {
        this.roomRegistry = roomRegistry;
        this.sessionRegistry = sessionRegistry;
        this.judgeService = judgeService;
        this.endingService = endingService;
        this.narrativeService = narrativeService;
        this.outputValidator = outputValidator;
        this.messageLog = messageLog;
        this.objectMapper = objectMapper;
        this.timeoutMillis = timeoutMillis;
        log.info("轮次系统初始化：超时 {} ms，叙事并发线程数=8", timeoutMillis);
    }

    /**
     * 把一名玩家的行动放入本房间队列，必要时立即结算。
     *
     * @param room          房间标识
     * @param playerId      玩家标识
     * @param intent        意图（skip 已在此前转换为 idle）
     * @param privateAction 是否私有
     * @param attackTarget  攻击目标；非攻击为 null
     * @return 本次提交的结果（是否已结算、当前等待状态）
     */
    public SubmitOutcome submit(String room, String playerId, String intent, boolean privateAction,
                                String attackTarget) {
        GlobalState state = roomRegistry.stateOf(room);
        List<String> online;
        List<String> submitted;
        boolean overwritten;
        boolean shouldSettle;
        boolean settlingNow;

        synchronized (state.turnLock()) {
            if (state.isFinished()) {
                return new SubmitOutcome(true, false, false, false, state.turnNumber(),
                        onlinePlayers(room, state), state.submittedPlayerIds(), List.of(), null);
            }
            if (state.isSettling()) {
                return new SubmitOutcome(false, false, false, true, state.turnNumber(), onlinePlayers(room, state),
                        state.submittedPlayerIds(), List.of(), null);
            }
            boolean wasEmpty = state.submittedPlayerIds().isEmpty();
            overwritten = state.enqueue(playerId, intent, privateAction, attackTarget) != null;
            if (wasEmpty) {
                state.setFirstSubmitAt(System.currentTimeMillis());
                scheduleTimeout(room, state);
            }
            online = onlinePlayers(room, state);
            submitted = state.submittedPlayerIds();
            shouldSettle = !online.isEmpty() && submitted.containsAll(online);
            settlingNow = state.isSettling();
        }

        Settlement settlement = null;
        boolean settled = false;
        if (shouldSettle) {
            settlement = settle(room, REASON_ALL_SUBMITTED);
            settled = settlement != null;
            if (settled) {
                // 结算后队列已清空、轮次已 +1；保留结算前的快照用于响应与日志，
                // 这样调用方看到的是「本轮 3/3 已提交」，而不是下一轮的 0/3。
                settlingNow = false;
            } else {
                // 另一个线程正在结算：重新读取真实状态再返回。
                synchronized (state.turnLock()) {
                    online = onlinePlayers(room, state);
                    submitted = state.submittedPlayerIds();
                    settlingNow = state.isSettling();
                }
            }
        }
        List<String> finalOnline = online;
        List<String> finalSubmitted = submitted;
        List<String> waiting = finalOnline.stream().filter(player -> !finalSubmitted.contains(player)).toList();
        log.info("行动入队 room={} player={} intent={} attackTarget={} overwritten={} 已提交={}/{} 等待={} 立即结算={}",
                room, playerId, intent, attackTarget, overwritten, submitted.size(), online.size(), waiting, settled);
        return new SubmitOutcome(false, settled, overwritten, settlingNow,
                settlement != null ? settlement.turn() : state.turnNumber(), online, submitted, waiting, settlement);
    }

    /**
     * 在线玩家断线后的处理：已入队行动保留；若剩余在线玩家都已提交，立即结算。
     *
     * @param room     房间标识
     * @param playerId 断线玩家
     */
    public void onPlayerLeft(String room, String playerId) {
        Optional<GlobalState> found = roomRegistry.find(room);
        if (found.isEmpty()) {
            return;
        }
        GlobalState state = found.get();
        boolean shouldSettle;
        synchronized (state.turnLock()) {
            if (state.isSettling() || state.isFinished()) {
                return;
            }
            List<String> submitted = state.submittedPlayerIds();
            if (submitted.isEmpty()) {
                return;
            }
            List<String> online = onlinePlayers(room, state);
            shouldSettle = online.isEmpty() || submitted.containsAll(online);
        }
        if (shouldSettle) {
            log.info("玩家断线触发结算 room={} player={}，已入队行动保留并照常结算", room, playerId);
            settle(room, REASON_PLAYER_LEFT);
        }
    }

    /**
     * 结算当前轮：裁决 → 每个在线玩家并发叙事 → 推送 → 清空队列、轮次 +1 并广播新一轮。
     *
     * <p>同一房间同一时间只允许一个结算线程；重复调用会直接返回 null。</p>
     *
     * @param room   房间标识
     * @param reason 结算原因
     * @return 结算结果；没有可结算内容或已有结算进行中时为 null
     */
    public Settlement settle(String room, String reason) {
        Optional<GlobalState> found = roomRegistry.find(room);
        if (found.isEmpty()) {
            return null;
        }
        GlobalState state = found.get();
        List<GlobalState.QueuedAction> actions;
        int turn;
        List<String> online;
        synchronized (state.turnLock()) {
            if (state.isSettling() || state.isFinished()) {
                return null;
            }
            actions = state.queuedActions();
            if (actions.isEmpty()) {
                return null;
            }
            state.setSettling(true);
            cancelTimeout(state);
            turn = state.turnNumber();
            online = onlinePlayers(room, state);
        }

        log.info("开始轮次结算 room={} turn={} reason={} 行动数={} 在线={}",
                room, turn, reason, actions.size(), online);
        List<GameEvent> events = List.of();
        List<String> delivered = new ArrayList<>();
        EndingState ending = null;
        try {
            events = judgeService.resolveTurn(state, actions, turn, online);
            // 事件与成长已经全部落账，此时再判定胜负；写入 ending 后本房间进入只读终局。
            ending = endingService.evaluate(state, turn);
            if (ending != null) {
                state.finish(ending);
                log.info("轮次结算触发终局 room={} turn={} reason={} 主条件={} 胜者={}",
                        room, turn, reason, ending.condition(), ending.winnerId());
            }
            Map<String, CompletableFuture<PlayerView>> futures = new java.util.LinkedHashMap<>();
            for (String playerId : online) {
                PlayerView view = state.projectFor(playerId, online);
                List<GameEvent> visible = events.stream()
                        .filter(event -> state.isEventVisibleTo(event, playerId))
                        .toList();
                futures.put(playerId, CompletableFuture.supplyAsync(
                        () -> narrateFor(state, view, visible, room, turn), narrativeExecutor));
            }
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
            for (Map.Entry<String, CompletableFuture<PlayerView>> entry : futures.entrySet()) {
                PlayerView view = entry.getValue().join();
                if (push(room, entry.getKey(), view)) {
                    delivered.add(entry.getKey());
                }
            }
        } catch (RuntimeException e) {
            log.error("轮次结算失败 room={} turn={} reason={}：{}", room, turn, reason, e.toString(), e);
            events = List.of();
        } finally {
            boolean finished = state.isFinished();
            int nextTurn;
            synchronized (state.turnLock()) {
                state.clearQueue();
                // 终局后不再推进轮次，避免出现“结束后还开了一轮”的错觉。
                nextTurn = finished ? turn : state.nextTurn();
                state.setSettling(false);
            }
            if (finished) {
                log.info("轮次收尾 room={} 对局已结束，停在第 {} 轮，不再广播新一轮；本轮事件数={} 已推送={}",
                        room, turn, events.size(), delivered);
            } else {
                log.info("轮次收尾 room={} 已清空行动队列，进入第 {} 轮；本轮事件数={} 已推送={}",
                        room, nextTurn, events.size(), delivered);
                broadcastTurnStarted(room, state, nextTurn);
            }
        }
        return new Settlement(turn, reason, events, List.copyOf(delivered), ending);
    }

    /**
     * 单个玩家的叙事：普通叙事 + 升级叙事各自独立调用、各自过校验，失败时降级安全模板。
     */
    private PlayerView narrateFor(GlobalState state, PlayerView view, List<GameEvent> visible, String room, int turn) {
        long started = System.currentTimeMillis();
        log.info("叙事任务开始 room={} turn={} player={} 可见事件数={}", room, turn, view.playerId(), visible.size());
        String normal = outputValidator.validateOrFallback(state, view,
                narrativeService.narrate(view, visible).orElse(null));

        String levelUp = "";
        GameEvent.Growth growth = latestLevelUp(visible, view.playerId());
        if (growth != null) {
            String raw = narrativeService.narrateLevelUp(view, growth).orElse(null);
            levelUp = outputValidator.validateOrFallback(state, view, raw);
        }
        log.info("叙事任务结束 room={} turn={} player={} 升级={} 耗时={}ms",
                room, turn, view.playerId(), growth != null, System.currentTimeMillis() - started);
        return view.withNarrative(normal, levelUp);
    }

    /**
     * 找出该玩家本轮最后一条“确实升级”的成长记录；没有则返回 null。
     */
    private GameEvent.Growth latestLevelUp(List<GameEvent> events, String playerId) {
        GameEvent.Growth result = null;
        for (GameEvent event : events) {
            if (event.growth() != null && event.growth().playerId().equals(playerId)
                    && event.growth().leveledUp()) {
                result = event.growth();
            }
        }
        return result;
    }

    /**
     * 向一名在线玩家推送视角；会话已关闭时返回 false。
     */
    private boolean push(String room, String playerId, PlayerView view) {
        WebSocketSession session = sessionRegistry.onlineSessions(room).get(playerId);
        if (session == null || !session.isOpen()) {
            log.info("room={} 玩家 {} 不在线，跳过本次推送", room, playerId);
            return false;
        }
        try {
            JsonNode payload = objectMapper.valueToTree(view);
            synchronized (session) {
                session.sendMessage(new TextMessage(payload.toString()));
            }
            messageLog.record(room, playerId, payload);
            log.info("room={} 玩家 {} 已推送轮次视角 turn={} payload={}", room, playerId, view.turn(), payload);
            return true;
        } catch (IOException e) {
            log.warn("room={} 玩家 {} 推送轮次视角失败：{}", room, playerId, e.toString());
            return false;
        }
    }

    /**
     * 广播新一轮开始：推送不含叙事的视角，带上新轮次号与空提交列表。
     */
    private void broadcastTurnStarted(String room, GlobalState state, int turn) {
        List<String> online = onlinePlayers(room, state);
        for (String playerId : online) {
            PlayerView view = state.projectFor(playerId, online).withNarrative("", "");
            push(room, playerId, view);
        }
        log.info("新一轮开始广播 room={} turn={} 在线={}", room, turn, online);
    }

    /**
     * 本房间“在线玩家”：有打开中的 WebSocket 会话且属于该房间剧本；按字典序排序。
     */
    private List<String> onlinePlayers(String room, GlobalState state) {
        return sessionRegistry.onlineSessions(room).keySet().stream()
                .filter(state::hasPlayer)
                .sorted()
                .toList();
    }

    private void scheduleTimeout(String room, GlobalState state) {
        cancelTimeout(state);
        ScheduledFuture<?> future = timeoutScheduler.schedule(() -> onTimeout(room), timeoutMillis,
                TimeUnit.MILLISECONDS);
        state.setTimeoutFuture(future);
        log.info("已安排本轮超时结算 room={} turn={} timeout={}ms", room, state.turnNumber(), timeoutMillis);
    }

    private void cancelTimeout(GlobalState state) {
        ScheduledFuture<?> future = state.timeoutFuture();
        if (future != null) {
            future.cancel(false);
            state.setTimeoutFuture(null);
        }
    }

    private void onTimeout(String room) {
        Optional<GlobalState> found = roomRegistry.find(room);
        if (found.isEmpty()) {
            return;
        }
        GlobalState state = found.get();
        boolean shouldSettle;
        synchronized (state.turnLock()) {
            shouldSettle = !state.isSettling() && !state.isFinished()
                    && !state.submittedPlayerIds().isEmpty();
        }
        if (shouldSettle) {
            log.info("本轮超时，按观望补齐未提交玩家并结算 room={} turn={}", room, state.turnNumber());
            settle(room, REASON_TIMEOUT);
        }
    }

    /**
     * 停止后台线程（应用关闭时）。
     */
    @PreDestroy
    public void shutdown() {
        timeoutScheduler.shutdownNow();
        narrativeExecutor.shutdownNow();
        log.info("轮次系统已停止：超时调度与叙事线程池已关闭");
    }

    /**
     * 一次提交的返回信息。
     *
     * @param finished        对局是否已结束（结束后拒绝新行动）
     * @param settled         是否在本次请求内完成了结算
     * @param overwritten     是否覆盖了该玩家本轮的旧行动
     * @param settling        房间是否正在结算（本次提交未生效）
     * @param turn            当前轮次号（已结算时为刚结算的轮次）
     * @param onlinePlayers   在线玩家
     * @param submittedPlayers 已提交玩家
     * @param waitingFor      仍在等待的在线玩家
     * @param settlement      本次结算结果；未结算时为 null
     */
    public record SubmitOutcome(boolean finished, boolean settled, boolean overwritten, boolean settling, int turn,
                                List<String> onlinePlayers, List<String> submittedPlayers,
                                List<String> waitingFor, Settlement settlement) {
    }

    /**
     * 一次结算的审计结果。
     *
     * @param turn        结算的轮次
     * @param reason      触发原因
     * @param events      本轮事件
     * @param deliveredTo 实际推送成功的在线玩家
     * @param ending      本局结算结果；未触发终局时为 null
     */
    public record Settlement(int turn, String reason, List<GameEvent> events, List<String> deliveredTo,
                             EndingState ending) {
    }
}
