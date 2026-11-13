package com.example.pvp.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间内的在线玩家登记表：roomId -> (playerId -> WebSocketSession)。
 *
 * <p>不同房间的在线玩家表互相隔离，不会互相投递。</p>
 */
@Component
public class PlayerSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(PlayerSessionRegistry.class);

    private final Map<String, Map<String, WebSocketSession>> sessionsByRoom = new ConcurrentHashMap<>();

    /**
     * 登记（或替换）某房间内某玩家的在线会话。
     *
     * @param roomId   房间标识
     * @param playerId 玩家标识
     * @param session  该玩家的 WebSocket 会话
     * @return 被替换掉的旧会话；没有旧会话时为空
     */
    public Optional<WebSocketSession> register(String roomId, String playerId, WebSocketSession session) {
        Map<String, WebSocketSession> sessions = sessionsByRoom.computeIfAbsent(roomId, id -> new ConcurrentHashMap<>());
        WebSocketSession previous = sessions.put(playerId, session);
        log.info("room={} 玩家 {} 已登记 sessionId={}，当前在线={}",
                roomId, playerId, session.getId(), sessions.keySet());
        return Optional.ofNullable(previous);
    }

    /**
     * 注销某房间内某玩家的会话，仅当登记表里的会话仍是同一个对象时才删除。
     *
     * @param roomId   房间标识
     * @param playerId 玩家标识
     * @param session  触发注销的会话
     * @return true 表示本次调用确实删掉了一条登记
     */
    public boolean remove(String roomId, String playerId, WebSocketSession session) {
        Map<String, WebSocketSession> sessions = sessionsByRoom.get(roomId);
        if (sessions == null) {
            return false;
        }
        boolean removed = sessions.remove(playerId, session);
        if (removed) {
            log.info("room={} 玩家 {} 的会话 {} 已清理，当前在线={}",
                    roomId, playerId, session.getId(), sessions.keySet());
        }
        return removed;
    }

    /**
     * 返回某房间内所有仍处于打开状态的会话快照。
     *
     * @param roomId 房间标识
     * @return 玩家标识 -> 会话的只读快照，已按玩家标识排序
     */
    public Map<String, WebSocketSession> onlineSessions(String roomId) {
        Map<String, WebSocketSession> sessions = sessionsByRoom.get(roomId);
        if (sessions == null) {
            return Map.of();
        }
        Map<String, WebSocketSession> snapshot = new TreeMap<>();
        sessions.forEach((playerId, session) -> {
            if (session.isOpen()) {
                snapshot.put(playerId, session);
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }
}
