package com.example.pvp.api;

import com.example.pvp.combat.IntentParser;
import com.example.pvp.state.GameEvent;
import com.example.pvp.state.GlobalState;
import com.example.pvp.state.RoomRegistry;
import com.example.pvp.turn.TurnService;
import com.example.pvp.ws.PlayerSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 行动提交入口：把行动放入本房间的行动队列，等本轮所有在线玩家提交或超时后统一结算。
 *
 * <p>本接口不再直接裁决、不再直接调用模型，只负责校验与入队，因此提交是即时返回的。
 * 真正的数值结算与多人并发叙事由 {@link TurnService} 在满足条件时执行。</p>
 */
@RestController
public class ActionController {

    private static final Logger log = LoggerFactory.getLogger(ActionController.class);

    /** 观望/跳过行动入库时统一的意图标记。 */
    private static final String INTENT_IDLE = GameEvent.INTENT_IDLE;

    private final PlayerSessionRegistry registry;

    private final IntentParser intentParser;

    private final RoomRegistry roomRegistry;

    private final TurnService turnService;

    public ActionController(PlayerSessionRegistry registry, IntentParser intentParser, RoomRegistry roomRegistry,
                            TurnService turnService) {
        this.registry = registry;
        this.intentParser = intentParser;
        this.roomRegistry = roomRegistry;
        this.turnService = turnService;
    }

    /**
     * 记录一条行动；满足结算条件时由 {@link TurnService} 在同一次调用内完成结算。
     *
     * <p>校验规则：房间与玩家必须存在且本人在线；攻击目标必须是本房间在线玩家。
     * 同一玩家重复提交会覆盖本轮旧行动，返回体里 overwritten=true。</p>
     *
     * @param request 请求体，形如 {"room":"room1","from":"A","intent":"search","private":true}
     * @return 200 已记录/已结算；400 请求体不合法；404 发起者或攻击目标不在线；
     *         409 正在结算或对局已结束（room_finished）
     */
    @PostMapping(path = "/action", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> postAction(@RequestBody(required = false) ActionRequest request) {
        boolean skip = request != null && Boolean.TRUE.equals(request.skip());
        if (request == null || isBlank(request.room()) || !RoomRegistry.isValidRoomId(request.room())
                || isBlank(request.from()) || request.privateAction() == null
                || (!skip && isBlank(request.intent()))) {
            return ResponseEntity.badRequest()
                    .body(error("bad_request",
                            "请求体必须是 JSON，且包含合法的 room（1~32 位字母/数字/下划线/短横线）、"
                                    + "非空 from、布尔 private，以及非空 intent（或 skip=true）"));
        }

        String room = request.room();
        String playerId = request.from();
        GlobalState state = roomRegistry.stateOf(room);
        if (state.isFinished()) {
            String winner = state.ending().winnerId() == null ? "平局" : state.ending().winnerId();
            log.info("room={} 玩家 {} 提交被拒：对局已结束，胜者={}", room, playerId, winner);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("room_finished",
                            "本房间对局已结束（胜者：" + winner + "），不再接受新行动；"
                                    + "请让管理员保存或重载剧本后重新开始"));
        }
        Map<String, WebSocketSession> online = registry.onlineSessions(room);
        if (!online.containsKey(playerId)) {
            log.warn("room={} 玩家 {} 提交行动被拒：本人不在线，该房间在线玩家={}", room, playerId, online.keySet());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("player_not_connected",
                            "玩家 " + playerId + " 未连接到房间 " + room + "，本次行动未入队"));
        }

        String intent = skip ? INTENT_IDLE : normalizeIntent(request.intent());
        String attackTarget = null;
        if (!skip) {
            Optional<String> parsed = intentParser.parseAttackTarget(intent, playerId, state.playerIds());
            if (parsed.isPresent()) {
                attackTarget = parsed.get();
                if (!online.containsKey(attackTarget)) {
                    log.warn("room={} 玩家 {} 攻击目标 {} 不在线，在线玩家={}", room, playerId, attackTarget,
                            online.keySet());
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(error("target_not_connected",
                                    "攻击目标 " + attackTarget + " 未连接到房间 " + room
                                            + "，本次行动未入队；请等待目标上线后重试"));
                }
            }
        }

        TurnService.SubmitOutcome outcome = turnService.submit(room, playerId, intent,
                Boolean.TRUE.equals(request.privateAction()), attackTarget);
        if (outcome.finished()) {
            // 与上面的预检查形成双保险：预检查之后、入队之前刚好完成终局结算。
            log.info("room={} 玩家 {} 提交被拒：对局在本请求期间结束", room, playerId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("room_finished",
                            "本房间对局已结束，不再接受新行动；请让管理员保存或重载剧本后重新开始"));
        }
        if (outcome.settling()) {
            log.info("room={} 玩家 {} 提交被拒：本轮正在结算", room, playerId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("settling", "本轮正在结算，本次行动未入队；请等待本轮结算完成后重新提交"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        body.put("player", playerId);
        body.put("turn", outcome.turn());
        body.put("intent", intent);
        if (attackTarget != null) {
            body.put("attackTarget", attackTarget);
        }
        body.put("overwritten", outcome.overwritten());
        body.put("submittedCount", outcome.submittedPlayers().size());
        body.put("onlineCount", outcome.onlinePlayers().size());
        body.put("submittedPlayers", outcome.submittedPlayers());
        body.put("onlinePlayers", outcome.onlinePlayers());
        body.put("waitingFor", outcome.waitingFor());

        if (outcome.settlement() != null) {
            TurnService.Settlement settlement = outcome.settlement();
            body.put("status", "settled");
            body.put("reason", settlement.reason());
            body.put("events", settlement.events());
            body.put("deliveredTo", settlement.deliveredTo());
            body.put("finished", settlement.ending() != null);
            if (settlement.ending() != null) {
                body.put("ending", settlement.ending());
                body.put("message", "本轮已结算，并触发对局结束；结算结果已按各自视角推送给在线玩家");
            } else {
                body.put("message", "本轮行动已齐，已统一结算并推送给在线玩家");
            }
            return ResponseEntity.ok(body);
        }

        body.put("status", "queued");
        String prefix = outcome.overwritten() ? "已覆盖你本轮此前的行动，" : "";
        body.put("message", prefix + "已记录，等待本轮结算（已提交 " + outcome.submittedPlayers().size()
                + "/" + outcome.onlinePlayers().size() + "）");
        return ResponseEntity.ok(body);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 把玩家常用的中文说法归一到裁决层认识的标准意图。
     *
     * <p>裁决层只识别 search / shout；如果不做这层归一，玩家输入“搜索”只会得到 nothing，
     * 也就无法通过 collectItems 的钥匙条件。攻击意图不受影响，由 {@code attackTarget} 优先。</p>
     *
     * @param raw 玩家原始输入
     * @return 标准意图或原文本
     */
    private String normalizeIntent(String raw) {
        String intent = raw.trim();
        if (intent.equalsIgnoreCase("search") || intent.contains("搜索")
                || intent.contains("搜寻") || intent.contains("寻找")) {
            return "search";
        }
        if (intent.equalsIgnoreCase("shout") || intent.contains("喊话") || intent.contains("大喊")) {
            return "shout";
        }
        return intent;
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}
