package com.example.pvp.ws;

import com.example.pvp.state.PlayerView;
import com.example.pvp.state.RoomRegistry;
import com.example.pvp.turn.TurnService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WebSocket 端点处理器：校验 room / player 后登记到该房间的在线表，并立即推送一份初始视角。
 *
 * <p>业务消息一律由 HTTP POST /action 触发，这里不做任何语义处理。
 * room 与 player 任一缺失或非法都会直接关闭连接，不做默认房间兜底。</p>
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    /** query 参数名：/ws?room=room1&player=A（玩家名册完全来自剧本，2~4 人） */
    private static final String ROOM_QUERY_PARAM = "room";

    private static final String PLAYER_QUERY_PARAM = "player";

    /** 初始视角没有叙事内容，页面只渲染背景 / 目标 / HP / 背包。 */
    private static final String EMPTY_NARRATIVE = "";

    private final PlayerSessionRegistry registry;

    private final RoomRegistry roomRegistry;

    private final PlayerMessageLog messageLog;

    private final ObjectMapper objectMapper;

    private final TurnService turnService;

    public GameWebSocketHandler(PlayerSessionRegistry registry, RoomRegistry roomRegistry,
                                PlayerMessageLog messageLog, ObjectMapper objectMapper,
                                TurnService turnService) {
        this.registry = registry;
        this.roomRegistry = roomRegistry;
        this.messageLog = messageLog;
        this.objectMapper = objectMapper;
        this.turnService = turnService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Optional<String> roomId = queryParam(session, ROOM_QUERY_PARAM);
        if (roomId.isEmpty() || !RoomRegistry.isValidRoomId(roomId.get())) {
            log.warn("拒绝连接 sessionId={}：query 参数 room 缺失或非法", session.getId());
            session.close(CloseStatus.BAD_DATA.withReason("missing or invalid room parameter"));
            return;
        }

        Optional<String> playerId = queryParam(session, PLAYER_QUERY_PARAM);
        if (playerId.isEmpty() || !roomRegistry.stateOf(roomId.get()).hasPlayer(playerId.get())) {
            log.warn("拒绝连接 sessionId={}：query 参数 player 缺失或不在房间 {} 的剧本玩家中",
                    session.getId(), roomId.get());
            session.close(CloseStatus.BAD_DATA.withReason("missing or invalid player parameter"));
            return;
        }

        String room = roomId.get();
        String player = playerId.get();
        session.getAttributes().put(ROOM_QUERY_PARAM, room);
        session.getAttributes().put(PLAYER_QUERY_PARAM, player);

        registry.register(room, player, session)
                .ifPresent(previous -> closeQuietly(previous, room, player));
        log.info("room={} 玩家 {} 连接建立 sessionId={}", room, player, session.getId());
        pushInitialView(room, player, session);
    }

    /**
     * 连接建立后立刻推送一份初始视角，让页面不依赖任何点击就有背景 / 目标 / HP / 背包。
     *
     * @param room    房间标识
     * @param player  玩家标识
     * @param session 刚建立的会话
     */
    private void pushInitialView(String room, String player, WebSocketSession session) {
        try {
            List<String> online = registry.onlineSessions(room).keySet().stream()
                    .filter(roomRegistry.stateOf(room)::hasPlayer)
                    .sorted()
                    .toList();
            PlayerView view = roomRegistry.stateOf(room).projectFor(player, online)
                    .withNarrative(EMPTY_NARRATIVE, EMPTY_NARRATIVE);
            JsonNode payload = objectMapper.valueToTree(view);
            synchronized (session) {
                session.sendMessage(new TextMessage(payload.toString()));
            }
            messageLog.record(room, player, payload);
            log.info("room={} 玩家 {} 已推送初始视角 sessionId={} payload={}",
                    room, player, session.getId(), payload);
        } catch (IOException | IllegalStateException e) {
            log.warn("room={} 玩家 {} 初始视角推送失败 sessionId={}：{}",
                    room, player, session.getId(), e.toString());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 骨架阶段不处理客户端上行消息，只记录，避免误以为这里有业务逻辑。
        log.info("room={} 玩家 {} 的上行消息（未处理）sessionId={} payload={}",
                session.getAttributes().get(ROOM_QUERY_PARAM),
                session.getAttributes().get(PLAYER_QUERY_PARAM), session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
        log.warn("room={} 玩家 {} 传输错误 sessionId={}，开始清理：{}",
                session.getAttributes().get(ROOM_QUERY_PARAM),
                session.getAttributes().get(PLAYER_QUERY_PARAM), session.getId(), exception.toString());
        removeFromRegistry(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR.withReason("transport error"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("room={} 玩家 {} 连接关闭 sessionId={} status={}",
                session.getAttributes().get(ROOM_QUERY_PARAM),
                session.getAttributes().get(PLAYER_QUERY_PARAM), session.getId(), status);
        removeFromRegistry(session);
    }

    /**
     * 注销会话；确实注销成功后通知轮次系统：已入队行动保留，必要时立即结算。
     */
    private void removeFromRegistry(WebSocketSession session) {
        Object room = session.getAttributes().get(ROOM_QUERY_PARAM);
        Object player = session.getAttributes().get(PLAYER_QUERY_PARAM);
        if (room instanceof String roomId && player instanceof String playerId) {
            if (registry.remove(roomId, playerId, session)) {
                turnService.onPlayerLeft(roomId, playerId);
            }
        }
    }

    private Optional<String> queryParam(WebSocketSession session, String name) {
        if (session.getUri() == null) {
            return Optional.empty();
        }
        Map<String, List<String>> params = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(values.get(0));
    }

    private void closeQuietly(WebSocketSession staleSession, String roomId, String playerId) {
        try {
            if (staleSession.isOpen()) {
                log.info("room={} 玩家 {} 重复连接，关闭旧会话 {}", roomId, playerId, staleSession.getId());
                staleSession.close(CloseStatus.NORMAL.withReason("replaced by newer connection"));
            }
        } catch (IOException e) {
            log.warn("room={} 关闭玩家 {} 的旧会话失败 sessionId={}", roomId, playerId, staleSession.getId(), e);
        }
    }
}
