package com.example.pvp.api;

import com.example.pvp.scenario.Scenario;
import com.example.pvp.scenario.ScenarioService;
import com.example.pvp.state.RoomRegistry;
import com.example.pvp.ws.PlayerMessageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理员接口：剧本读取/保存，以及把某个房间按磁盘上的剧本重建为初始状态。
 *
 * <p>语义是“重载 = 重建房间”：HP、背包、事件、事件序号、送达日志全部回到初始，
 * 在线 WebSocket 会话保留（不主动断开，也不自动补推初始视角）。
 * 如果后续需要“只热更配置不重置状态”，应另开接口而不是改这里的语义。</p>
 */
@RestController
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ScenarioService scenarioService;

    private final RoomRegistry roomRegistry;

    private final PlayerMessageLog messageLog;

    public AdminController(ScenarioService scenarioService, RoomRegistry roomRegistry, PlayerMessageLog messageLog) {
        this.scenarioService = scenarioService;
        this.roomRegistry = roomRegistry;
        this.messageLog = messageLog;
    }

    /**
     * 返回当前生效的剧本 JSON，结构与 scenarios/default.json 一致，供 admin 页面首次填充。
     *
     * @return 当前生效剧本
     */
    @GetMapping(path = "/admin/scenario", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Scenario> currentScenario() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(scenarioService.current());
    }

    /**
     * 保存剧本并让指定房间立即生效。
     *
     * <p>流程固定为：整体校验（失败 400，磁盘不动）→ 原子写入运行路径与仓库版本源 →
     * 重新加载 → 重建房间。请求体原样落盘，因此技能、初始物品、AC 等未上表单的字段都会保留。</p>
     *
     * @param room 需要重建的房间标识，必填
     * @param body 完整剧本 JSON
     * @return 200 保存生效；400 剧本非法/参数缺失；500 写盘失败
     */
    @PostMapping(path = "/admin/scenario", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> saveScenario(
            @RequestParam(name = "room", required = false) String room,
            @RequestBody(required = false) String body) {
        if (room == null || room.isBlank()) {
            return ResponseEntity.badRequest().body(error("bad_request",
                    "缺少 room 查询参数，例如 /admin/scenario?room=room1"));
        }
        if (!RoomRegistry.isValidRoomId(room)) {
            return ResponseEntity.badRequest().body(error("bad_request",
                    "room 查询参数非法，仅允许 1~32 位字母/数字/下划线/短横线"));
        }
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest().body(error("scenario_invalid",
                    "请求体不能为空，应提交完整剧本 JSON"));
        }

        boolean existed = roomRegistry.exists(room);
        ScenarioService.SaveResult result;
        try {
            result = scenarioService.saveAndReload(body);
        } catch (ScenarioService.ScenarioValidationException e) {
            log.warn("管理员保存剧本校验失败 room={}：{}", room, e.getMessage());
            return ResponseEntity.badRequest().body(error("scenario_invalid", e.getMessage()));
        } catch (IllegalStateException e) {
            log.error("管理员保存剧本失败 room={} file={}：{}", room, scenarioService.path(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error("scenario_save_failed", e.getMessage()));
        }

        roomRegistry.rebuild(room);
        messageLog.clear(room);
        log.info("管理员保存并重建完成 room={} existed={} file={} source={} sourceSynced={} 剧本={}",
                room, existed, result.activePath(), result.sourcePath(), result.sourceSynced(),
                result.scenario().title());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("room", room);
        response.put("status", "saved");
        response.put("created", !existed);
        response.put("scenario", result.scenario().title());
        response.put("activePath", result.activePath().toString());
        response.put("sourcePath", result.sourcePath().toString());
        response.put("sameFile", result.sameFile());
        response.put("sourceSynced", result.sourceSynced());
        response.put("players", playerSummary(result.scenario()));
        response.put("message", "剧本已保存并生效，房间已重建为初始状态；在线连接保留，刷新玩家页面即可看到新视角");
        return ResponseEntity.ok(response);
    }

    /**
     * 重新读取剧本并把指定房间重建为初始状态。
     *
     * <p>房间从未创建过时直接创建（created=true），等价于初始化；已存在时整体替换（created=false）。
     * 剧本读取/校验失败返回 500，已有房间状态保持不变。</p>
     *
     * @param room 房间标识，必填，规则与 /ws、/action 一致
     * @return 200 重建成功；400 room 缺失或非法；500 剧本加载失败
     */
    @GetMapping(path = "/admin/reload", produces = "application/json")
    public ResponseEntity<Map<String, Object>> reload(@RequestParam(name = "room", required = false) String room) {
        if (room == null || room.isBlank()) {
            return ResponseEntity.badRequest().body(error("bad_request",
                    "缺少 room 查询参数，例如 /admin/reload?room=room1"));
        }
        if (!RoomRegistry.isValidRoomId(room)) {
            return ResponseEntity.badRequest().body(error("bad_request",
                    "room 查询参数非法，仅允许 1~32 位字母/数字/下划线/短横线"));
        }

        boolean existed = roomRegistry.exists(room);
        Scenario scenario;
        try {
            scenario = scenarioService.reload();
        } catch (IllegalStateException e) {
            log.error("管理员重载失败 room={} file={}：{}", room, scenarioService.path(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error("scenario_load_failed", e.getMessage()));
        }

        roomRegistry.rebuild(room);
        messageLog.clear(room);
        log.info("管理员重载完成 room={} existed={} file={} 剧本={}", room, existed, scenarioService.path(), scenario.title());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        body.put("status", "reloaded");
        body.put("created", !existed);
        body.put("scenario", scenario.title());
        body.put("players", playerSummary(scenario));
        body.put("message", "该房间已重建为剧本初始状态：HP/背包/事件/送达日志全部重置；在线连接保留");
        return ResponseEntity.ok(body);
    }

    /**
     * 房间重建响应里的玩家摘要（保持与既有 /admin/reload 返回结构一致）。
     *
     * <p>玩家名单完全来自本次生效剧本，不再假定只有 A / B。</p>
     */
    private Map<String, Object> playerSummary(Scenario scenario) {
        Map<String, Object> players = new LinkedHashMap<>();
        scenario.players().forEach((playerId, spec) -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("hp", spec.hp());
            detail.put("maxHp", spec.maxHp());
            detail.put("items", spec.items());
            players.put(playerId, detail);
        });
        return players;
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}
