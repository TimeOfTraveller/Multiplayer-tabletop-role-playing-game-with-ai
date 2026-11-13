package com.example.pvp.state;

import com.example.pvp.scenario.ScenarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 房间注册表：roomId -> 该房间独立的 {@link GlobalState}。
 *
 * <p>房间按需创建；不同房间之间没有任何共享状态（事件、背包、序号都各自独立）。</p>
 */
@Component
public class RoomRegistry {

    private static final Logger log = LoggerFactory.getLogger(RoomRegistry.class);

    /** 房间标识允许的字符与长度：字母、数字、下划线、短横线，1~32 位。 */
    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Map<String, GlobalState> rooms = new ConcurrentHashMap<>();

    private final ScenarioService scenarioService;

    /**
     * 创建房间注册表。
     *
     * @param scenarioService 剧本加载器，房间初始化统一取当前生效剧本
     */
    public RoomRegistry(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    /**
     * 校验房间标识是否合法。
     *
     * @param roomId 房间标识
     * @return true 表示合法
     */
    public static boolean isValidRoomId(String roomId) {
        return roomId != null && ROOM_ID_PATTERN.matcher(roomId).matches();
    }

    /**
     * 获取房间状态，不存在时创建。
     *
     * @param roomId 已校验合法的房间标识
     * @return 该房间的全局状态
     */
    public GlobalState stateOf(String roomId) {
        return rooms.computeIfAbsent(roomId, id -> {
            log.info("创建房间状态 room={}（剧本={}）", id, scenarioService.current().title());
            return new GlobalState(id, scenarioService.current());
        });
    }

    /**
     * 房间是否已经创建过。
     *
     * @param roomId 房间标识
     * @return true 表示该房间已有状态
     */
    public boolean exists(String roomId) {
        return rooms.containsKey(roomId);
    }

    /**
     * 用当前生效剧本重建房间，旧状态（HP、背包、事件、序号）整体丢弃。
     *
     * <p>调用前应先确保剧本已成功重载；本方法只做重建，不做文件 IO。
     * 在线会话不在此处处理，由调用方保留。</p>
     *
     * @param roomId 已校验合法的房间标识
     * @return 重建后的全新房间状态
     */
    public GlobalState rebuild(String roomId) {
        GlobalState fresh = new GlobalState(roomId, scenarioService.current());
        rooms.put(roomId, fresh);
        log.info("重建房间状态 room={}（剧本={}，HP/背包/事件/序号已重置）", roomId, scenarioService.current().title());
        return fresh;
    }

    /**
     * 查询已存在的房间状态。
     *
     * @param roomId 房间标识
     * @return 房间状态；从未创建过则为空
     */
    public Optional<GlobalState> find(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    /**
     * 已创建的房间标识快照。
     *
     * @return 房间标识集合
     */
    public Set<String> roomIds() {
        return Set.copyOf(rooms.keySet());
    }
}
