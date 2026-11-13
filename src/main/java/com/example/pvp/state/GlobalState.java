package com.example.pvp.state;

import com.example.pvp.ending.EndingState;
import com.example.pvp.growth.GrowthRules;
import com.example.pvp.scenario.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个房间的全局状态：该房间的单一真相源，仅服务器可见。
 *
 * <p>房间之间不共享任何对象：每个房间各自持有一份 players / events / 事件序号 / 行动队列。
 * 玩家集合完全来自剧本，不再有任何写死的 A/B。对外只通过 {@link #projectFor(String)}
 * 暴露玩家视角，绝不直接序列化本对象。</p>
 *
 * <p>轮次状态（行动队列、是否正在结算、超时计时器）也放在这里，统一由
 * {@link #turnLock()} 保护；LLM 调用等耗时操作必须释放该锁。</p>
 */
public class GlobalState {

    private static final Logger log = LoggerFactory.getLogger(GlobalState.class);

    /** 事件 ID 前缀，最终形如 e1 / e2 / e3。 */
    private static final String EVENT_ID_PREFIX = "e";

    private final String roomId;

    private final Scenario scenario;

    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();

    private final List<GameEvent> events = new CopyOnWriteArrayList<>();

    private final AtomicLong eventSequence = new AtomicLong();

    /** 本轮行动队列：playerId -> 待结算行动。受 {@link #turnLock()} 保护。 */
    private final Map<String, QueuedAction> actionQueue = new LinkedHashMap<>();

    /** 提交顺序序号，用于审计与日志。 */
    private final AtomicLong actionSequence = new AtomicLong();

    private final Object turnLock = new Object();

    /** 当前轮次号，从 1 开始；结算完成并广播新一轮时 +1。 */
    private int turnNumber = 1;

    /** 本轮是否正在结算（裁决 + LLM + 推送）；结算期间拒绝新提交。 */
    private boolean settling;

    /** 本轮第一个提交的毫秒时间戳；0 表示本轮还没有提交。 */
    private long firstSubmitAt;

    /** 本轮超时计时器；结算开始时取消。 */
    private ScheduledFuture<?> timeoutFuture;

    /** 对局结算结果；null 表示对局仍在进行。写入后不再开新轮、不再接受新行动。 */
    private volatile EndingState ending;

    /**
     * 按剧本初始设定创建房间状态。
     *
     * @param roomId   房间标识
     * @param scenario 该房间初始化时使用的剧本
     */
    public GlobalState(String roomId, Scenario scenario) {
        this.roomId = roomId;
        this.scenario = scenario;
        for (String playerId : scenario.players().keySet()) {
            players.put(playerId, new PlayerState(playerId, scenario.player(playerId)));
        }
    }

    /**
     * 房间标识。
     *
     * @return 房间标识
     */
    public String roomId() {
        return roomId;
    }

    /**
     * 该房间初始化时使用的剧本。
     *
     * @return 剧本
     */
    public Scenario scenario() {
        return scenario;
    }

    /**
     * 对局是否已经结束。
     *
     * @return true 表示已产生结算结果，拒绝新行动且不再开新轮
     */
    public boolean isFinished() {
        return ending != null;
    }

    /**
     * 写入对局结算结果（只允许写一次，重复调用忽略）。
     *
     * @param result 结算快照
     */
    public void finish(EndingState result) {
        synchronized (turnLock) {
            if (ending == null) {
                ending = result;
                log.info("room={} 对局结束：主条件={} 满足条件={} 胜者={} 轮次={}",
                        roomId, result.condition(), result.conditions(), result.winnerId(), result.turn());
            }
        }
    }

    /**
     * 对局结算结果。
     *
     * @return 结算快照；未结束返回 null
     */
    public EndingState ending() {
        return ending;
    }

    /**
     * 房间所有玩家标识，按字典序排序（与剧本 JSON 的顺序无关，保证各端一致）。
     *
     * @return 不可变玩家标识列表
     */
    public List<String> playerIds() {
        return players.keySet().stream().sorted().toList();
    }

    /**
     * 判断玩家是否属于该房间。
     *
     * @param playerId 玩家标识
     * @return true 表示是剧本中的玩家
     */
    public boolean hasPlayer(String playerId) {
        return playerId != null && players.containsKey(playerId);
    }

    /**
     * 生成该房间下一个全局自增事件 ID（线程安全）。
     *
     * @return 形如 e1 / e2 / e3 的事件 ID
     */
    public String nextEventId() {
        return EVENT_ID_PREFIX + eventSequence.incrementAndGet();
    }

    /**
     * 给某个玩家增加一件物品。
     *
     * @param playerId 玩家标识
     * @param item     物品标识
     */
    public void addItem(String playerId, String item) {
        playerOf(playerId).addItem(item);
        log.info("room={} GlobalState: 玩家 {} 获得物品 {}，当前背包={}", roomId, playerId, item, inventoryOf(playerId));
    }

    /**
     * 追加一条已完成裁决的事件。
     *
     * @param event 事件
     */
    public void appendEvent(GameEvent event) {
        events.add(event);
        log.info("room={} GlobalState: 追加事件 eventId={} actor={} intent={} result={} public={} visibleTo={}",
                roomId, event.eventId(), event.actor(), event.intent(), event.result(), event.isPublic(),
                event.visibleTo());
    }

    /**
     * 取某个玩家的服务器侧状态。
     *
     * @param playerId 玩家标识
     * @return 玩家状态
     * @throws IllegalArgumentException 玩家不属于该房间
     */
    public PlayerState playerOf(String playerId) {
        PlayerState state = players.get(playerId);
        if (state == null) {
            throw new IllegalArgumentException("房间 " + roomId + " 没有玩家 " + playerId + " 的状态");
        }
        return state;
    }

    /**
     * 查询某玩家的物品快照。
     *
     * @param playerId 玩家标识
     * @return 物品列表；该玩家没有状态时返回空列表
     */
    public List<String> inventoryOf(String playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? List.of() : state.inventory();
    }

    /**
     * 同阵营私有事件的可见范围：本人 + 同阵营其他玩家。
     *
     * <p>阵营是可选字段：未声明阵营的玩家没有任何队友，私有事件仍然只有本人可见。
     * 不同阵营之间不会互相扩权。</p>
     *
     * @param playerId 事件归属玩家
     * @return 可见玩家列表（含本人）
     */
    public List<String> factionAudience(String playerId) {
        PlayerState self = playerOf(playerId);
        String faction = self.faction();
        List<String> audience = new ArrayList<>();
        audience.add(playerId);
        if (faction != null && !faction.isBlank()) {
            players.values().stream()
                    .filter(other -> !other.playerId().equals(playerId))
                    .filter(other -> faction.equals(other.faction()))
                    .map(PlayerState::playerId)
                    .sorted()
                    .forEach(audience::add);
        }
        return List.copyOf(audience);
    }

    /**
     * 收集“该玩家看不见的他人私有物品”。
     *
     * <p>判定：该物品在别的玩家背包里、不在我的背包里，且没有作为任何对我可见事件的
     * intent / result 出现过（例如公共事件结果已经把该标识公开给我，就不算私有）。
     * 校验层会再用别名表扩展成自然语言说法后比对。</p>
     *
     * @param playerId 视角所属玩家
     * @return 该玩家不可见的私有物品标识集合
     */
    public Set<String> hiddenItemsFrom(String playerId) {
        List<String> myInventory = inventoryOf(playerId);
        Set<String> visibleTokens = new LinkedHashSet<>();
        events.stream()
                .filter(event -> isEventVisibleTo(event, playerId))
                .forEach(event -> {
                    visibleTokens.add(event.intent());
                    visibleTokens.add(event.result());
                });

        Set<String> hidden = new LinkedHashSet<>();
        players.forEach((other, state) -> {
            if (!other.equals(playerId)) {
                state.inventory().stream()
                        .filter(item -> !myInventory.contains(item) && !visibleTokens.contains(item))
                        .forEach(hidden::add);
            }
        });
        return hidden;
    }

    /**
     * 为指定玩家显式构造视角投影（尚未填充叙事文本）。
     *
     * <p>visibleEvents 只保留公共事件、visibleTo 含该玩家的事件，以及同阵营队友的私有事件；
     * myInventory 只取该玩家自己的背包；HP / 背景 / 秘密目标只取该玩家自己的状态。</p>
     *
     * @param playerId 视角所属玩家
     * @return 该玩家的 PlayerView，narrativeText 为 null
     */
    public PlayerView projectFor(String playerId) {
        return projectFor(playerId, playerIds());
    }

    /**
     * 为指定玩家构造视角投影，并带上当前在线名单与本轮已提交名单。
     *
     * @param playerId      视角所属玩家
     * @param onlinePlayers 当前在线玩家（会排序后写入视图）
     * @return 该玩家的 PlayerView，narrativeText 为 null
     */
    public PlayerView projectFor(String playerId, List<String> onlinePlayers) {
        PlayerState state = playerOf(playerId);
        List<PlayerView.VisibleEvent> visibleEvents = events.stream()
                .filter(event -> isEventVisibleTo(event, playerId))
                .map(event -> new PlayerView.VisibleEvent(event.eventId(), event.type(), event.actor(),
                        event.intent(), event.result(), event.combat(),
                        // 经验/升级审计只给成长归属玩家：其他人能看到公开等级与 HP，但看不到经验明细。
                        event.growth() != null && event.growth().playerId().equals(playerId)
                                ? event.growth() : null))
                .toList();
        List<PlayerView.PublicPlayer> publicPlayers = playerIds().stream()
                .map(players::get)
                .filter(java.util.Objects::nonNull)
                .map(other -> new PlayerView.PublicPlayer(other.playerId(), other.playerId(), other.level(),
                        other.hp(), other.maxHp(), other.armorClass(), other.isDefeated()))
                .toList();
        List<String> online = onlinePlayers == null ? List.of()
                : onlinePlayers.stream().filter(this::hasPlayer).distinct().sorted().toList();
        List<String> submitted = submittedPlayerIds();
        int turn;
        synchronized (turnLock) {
            turn = turnNumber;
        }
        return new PlayerView(roomId, playerId, state.hp(), state.maxHp(), state.exp(), state.level(),
                GrowthRules.DEFAULT_EXP_PER_LEVEL, scenario.title(), scenario.worldview(), scenario.background(),
                state.secretGoal(), state.inventory(), visibleEvents, publicPlayers, turn, online, submitted,
                null, null, endingViewFor(playerId));
    }

    /**
     * 把服务器侧结算快照投影成“这个玩家看到的结算结果”。
     *
     * <p>结算叙述使用确定性模板，不依赖模型：即使 LLM 完全不可用，结算也一定完整送达。
     * 阵营只在结束时公开，用于复盘。</p>
     *
     * @param playerId 视角所属玩家
     * @return 结算视图；对局未结束时返回 null
     */
    private PlayerView.EndingView endingViewFor(String playerId) {
        EndingState result = ending;
        if (result == null) {
            return null;
        }
        boolean win = playerId.equals(result.winnerId());
        String outcome = result.draw() ? "draw" : (win ? "win" : "lose");
        List<PlayerView.EndingPlayer> players = result.players().stream()
                .map(player -> new PlayerView.EndingPlayer(player.playerId(), player.playerId(), player.level(),
                        player.hp(), player.maxHp(), player.armorClass(), player.defeated(), player.faction()))
                .toList();
        return new PlayerView.EndingView(result.condition(), result.conditions(), outcome, result.winnerId(),
                win, endingNarrative(result, playerId), players);
    }

    /**
     * 结算叙述模板：按视角区分胜者、败者与平局，只引用剧本标题与条件说明。
     */
    private String endingNarrative(EndingState result, String playerId) {
        String conditions = result.conditions().stream()
                .map(EndingState::label)
                .collect(java.util.stream.Collectors.joining("；"));
        if (result.draw()) {
            return "《" + scenario.title() + "》落幕。触发条件：" + conditions + "，但没有人真正胜出。";
        }
        if (playerId.equals(result.winnerId())) {
            return "《" + scenario.title() + "》落幕。你胜出了，触发条件：" + conditions + "。";
        }
        return "《" + scenario.title() + "》落幕。" + result.winnerId() + " 胜出，触发条件："
                + conditions + "。你留在了故事的另一侧。";
    }

    /**
     * 事件是否对指定玩家可见：公共事件、显式 visibleTo，或同阵营队友的私有事件。
     *
     * <p>visibleTo 在裁决层已经展开过同阵营范围；这里再兜底一次，保证历史事件在阵营判断上稳定。</p>
     *
     * @param event    事件
     * @param playerId 视角所属玩家
     * @return true 表示该玩家可见
     */
    public boolean isEventVisibleTo(GameEvent event, String playerId) {
        if (event.isPublic() || event.visibleTo().contains(playerId)) {
            return true;
        }
        if (event.actor() == null) {
            return false;
        }
        PlayerState actor = players.get(event.actor());
        PlayerState viewer = players.get(playerId);
        return actor != null && viewer != null && actor.faction() != null
                && actor.faction().equals(viewer.faction());
    }

    // ------------------------------------------------------------------
    // 轮次与行动队列
    // ------------------------------------------------------------------

    /**
     * 轮次锁：行动队列、轮次号、结算标记与超时计时器都由它保护。
     *
     * @return 锁对象
     */
    public Object turnLock() {
        return turnLock;
    }

    /**
     * 当前轮次号。
     *
     * @return 轮次号，从 1 开始
     */
    public int turnNumber() {
        synchronized (turnLock) {
            return turnNumber;
        }
    }

    /**
     * 进入下一轮（结算推送完成后调用）。
     *
     * @return 新的轮次号
     */
    public int nextTurn() {
        synchronized (turnLock) {
            turnNumber++;
            return turnNumber;
        }
    }

    /**
     * 本轮是否正在结算。
     *
     * @return true 表示裁决/叙事/推送进行中
     */
    public boolean isSettling() {
        synchronized (turnLock) {
            return settling;
        }
    }

    /**
     * 设置本轮结算标记。
     *
     * @param value true 表示开始结算
     */
    public void setSettling(boolean value) {
        synchronized (turnLock) {
            this.settling = value;
        }
    }

    /**
     * 入队一条行动；同一玩家重复提交时覆盖旧行动。
     *
     * @param playerId      玩家标识
     * @param intent        意图
     * @param privateAction 是否私有
     * @param attackTarget  攻击目标；非攻击为 null
     * @return 被覆盖的旧行动；首次提交返回 null
     */
    public QueuedAction enqueue(String playerId, String intent, boolean privateAction, String attackTarget) {
        synchronized (turnLock) {
            QueuedAction action = new QueuedAction(playerId, intent, privateAction, attackTarget,
                    actionSequence.incrementAndGet());
            return actionQueue.put(playerId, action);
        }
    }

    /**
     * 本轮已提交的玩家标识，按字典序排序。
     *
     * @return 不可变列表
     */
    public List<String> submittedPlayerIds() {
        synchronized (turnLock) {
            return actionQueue.keySet().stream().sorted().toList();
        }
    }

    /**
     * 本轮行动队列快照，按玩家标识排序（结算顺序固定）。
     *
     * @return 不可变行动列表
     */
    public List<QueuedAction> queuedActions() {
        synchronized (turnLock) {
            return actionQueue.values().stream()
                    .sorted(java.util.Comparator.comparing(QueuedAction::playerId))
                    .toList();
        }
    }

    /**
     * 清空本轮行动队列（结算推送完成后调用）。
     */
    public void clearQueue() {
        synchronized (turnLock) {
            actionQueue.clear();
            firstSubmitAt = 0L;
        }
    }

    /**
     * 本轮第一个提交的毫秒时间戳。
     *
     * @return 时间戳；本轮尚未提交时为 0
     */
    public long firstSubmitAt() {
        synchronized (turnLock) {
            return firstSubmitAt;
        }
    }

    /**
     * 记录本轮第一个提交的时间。
     *
     * @param millis 毫秒时间戳
     */
    public void setFirstSubmitAt(long millis) {
        synchronized (turnLock) {
            this.firstSubmitAt = millis;
        }
    }

    /**
     * 当前超时计时器。
     *
     * @return 计时器；未安排时为 null
     */
    public ScheduledFuture<?> timeoutFuture() {
        synchronized (turnLock) {
            return timeoutFuture;
        }
    }

    /**
     * 保存超时计时器，便于结算时取消。
     *
     * @param future 计时器；null 表示清除
     */
    public void setTimeoutFuture(ScheduledFuture<?> future) {
        synchronized (turnLock) {
            this.timeoutFuture = future;
        }
    }

    /**
     * 一条待结算行动。
     *
     * @param playerId      提交者
     * @param intent        意图（攻击固定为 attack，观望为 idle）
     * @param privateAction 请求里的 private 标记（仅对非战斗事件生效）
     * @param attackTarget  攻击目标；非攻击为 null
     * @param sequence      提交顺序（从 1 开始，用于审计日志）
     */
    public record QueuedAction(String playerId, String intent, boolean privateAction, String attackTarget,
                               long sequence) {
    }
}
