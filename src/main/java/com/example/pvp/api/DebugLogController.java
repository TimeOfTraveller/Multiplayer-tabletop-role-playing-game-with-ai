package com.example.pvp.api;

import com.example.pvp.state.RoomRegistry;
import com.example.pvp.ws.PlayerMessageLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工审查用的调试接口：按房间返回某个玩家实际收到的全部消息。
 */
@RestController
public class DebugLogController {

    private final PlayerMessageLog messageLog;

    public DebugLogController(PlayerMessageLog messageLog) {
        this.messageLog = messageLog;
    }

    /**
     * 查询某房间内某玩家的送达记录，按到达顺序返回，带序号与时间戳。
     *
     * <p>room 缺失与 /ws、/action 保持一致：直接拒绝（400），不做默认房间兜底。
     * 未知房间或玩家尚未收到消息时返回空数组。</p>
     *
     * @param room   房间标识，如 "room1"，必填
     * @param player 玩家标识，如 "A" / "B"，必填
     * @return 记录数组；房间/玩家不存在时返回空数组
     */
    @GetMapping(path = "/debug/log", produces = "application/json")
    public ResponseEntity<?> getLog(@RequestParam(name = "room", required = false) String room,
                                    @RequestParam(name = "player", required = false) String player) {
        if (room == null || room.isBlank()) {
            return ResponseEntity.badRequest().body(error(
                    "缺少 room 查询参数，例如 /debug/log?room=room1&player=A"));
        }
        if (!RoomRegistry.isValidRoomId(room)) {
            return ResponseEntity.badRequest().body(error(
                    "room 查询参数非法，仅允许 1~32 位字母/数字/下划线/短横线"));
        }
        if (player == null || player.isBlank()) {
            return ResponseEntity.badRequest().body(error(
                    "缺少 player 查询参数，例如 /debug/log?room=room1&player=A"));
        }
        List<PlayerMessageLog.Entry> entries = messageLog.history(room, player);
        return ResponseEntity.ok(entries);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad_request");
        body.put("message", message);
        return body;
    }
}
