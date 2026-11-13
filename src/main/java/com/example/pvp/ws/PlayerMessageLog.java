package com.example.pvp.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家实际收到消息的内存审计日志，按房间 + 玩家隔离，供 GET /debug/log 人工审查。
 *
 * <p>只记录“确实发送成功”的消息，按到达顺序分配从 1 开始的序号；进程重启即清空，玩家断线也不清除。</p>
 */
@Component
public class PlayerMessageLog {

    /** 单条记录：序号、投递时刻、消息体。 */
    public record Entry(long seq, String receivedAt, JsonNode message) {
    }

    private final Map<String, Map<String, List<Entry>>> logsByRoom = new ConcurrentHashMap<>();

    /**
     * 记录一条已成功投递的消息。
     *
     * @param roomId   房间标识
     * @param playerId 接收玩家
     * @param message  实际推送出去的 JSON 消息体
     * @return 写入的记录
     */
    public Entry record(String roomId, String playerId, JsonNode message) {
        List<Entry> entries = logsByRoom
                .computeIfAbsent(roomId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(playerId, id -> new ArrayList<>());
        synchronized (entries) {
            Entry entry = new Entry(entries.size() + 1L, OffsetDateTime.now().toString(), message);
            entries.add(entry);
            return entry;
        }
    }

    /**
     * 查询某房间某玩家收到的全部消息，按到达顺序返回。
     *
     * @param roomId   房间标识
     * @param playerId 玩家标识
     * @return 记录快照；该房间玩家从未收到过消息时返回空列表
     */
    public List<Entry> history(String roomId, String playerId) {
        Map<String, List<Entry>> roomLogs = logsByRoom.get(roomId);
        if (roomLogs == null) {
            return List.of();
        }
        List<Entry> entries = roomLogs.get(playerId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /**
     * 清空某房间全部玩家的送达记录（/admin/reload 重建房间时调用）。
     *
     * @param roomId 房间标识
     */
    public void clear(String roomId) {
        logsByRoom.remove(roomId);
    }
}
