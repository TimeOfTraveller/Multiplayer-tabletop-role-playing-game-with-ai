package com.example.pvp.scenario;

import com.example.pvp.combat.CombatRules;
import com.example.pvp.combat.Dice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 剧本加载器：从运行目录的 scenarios/default.json 读取初始设定，供房间初始化与 /admin/reload 使用。
 *
 * <p>启动时加载一次；文件缺失、JSON 非法或必填字段缺失都会抛出带路径与字段名的异常，
 * 让应用直接启动失败，而不是带着空剧本继续跑。</p>
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    /** HP 的最小合法值，保证初始化时不会出现 0 或负数。 */
    private static final int MIN_VALID_HP = 1;

    private final ObjectMapper objectMapper;

    private final Path scenarioPath;

    /**
     * 仓库版本源路径（默认 outputs/scenarios/default.json，相对启动目录）。
     *
     * <p>构建时该文件会被 maven-resources-plugin 复制到 jar 同级的运行路径，
     * 所以保存时两处一起写，重新打包才不会把编辑结果冲掉。</p>
     */
    private final Path sourcePath;

    private volatile Scenario current;

    public ScenarioService(ObjectMapper objectMapper,
                           @Value("${scenario.dir}") String scenarioDir,
                           @Value("${scenario.file}") String scenarioFile,
                           @Value("${scenario.source-file:scenarios/default.json}") String sourceFile) {
        this.objectMapper = objectMapper;
        this.scenarioPath = Paths.get(scenarioDir, scenarioFile).toAbsolutePath().normalize();
        this.sourcePath = Paths.get(sourceFile).toAbsolutePath().normalize();
        this.current = load();
        log.info("剧本加载成功 file={} source={} title={} 世界观长度={} 开场长度={} 玩家={} endings={}",
                scenarioPath, sourcePath, current.title(), current.worldview().length(),
                current.background().length(), current.players().keySet(), current.endings());
    }

    /**
     * 当前生效的剧本。
     *
     * @return 已通过校验的剧本
     */
    public Scenario current() {
        return current;
    }

    /**
     * 剧本文件的绝对路径，便于日志与排障。
     *
     * @return 剧本文件路径
     */
    public Path path() {
        return scenarioPath;
    }

    /**
     * 仓库版本源文件的绝对路径。
     *
     * @return 版本源路径；与 {@link #path()} 相同表示只维护一份文件
     */
    public Path sourcePath() {
        return sourcePath;
    }

    /**
     * 只校验、不落盘：供保存接口在写文件之前拦下非法剧本。
     *
     * <p>JSON 语法错误或字段不合法都会抛出 {@link ScenarioValidationException}，
     * 消息里带有具体字段名，调用方可以直接作为 400 的原因返回。</p>
     *
     * @param json 待校验的完整剧本 JSON
     * @return 校验通过的剧本对象
     */
    public Scenario validateJson(String json) {
        Scenario scenario;
        try {
            scenario = objectMapper.readValue(json, Scenario.class);
        } catch (JsonProcessingException e) {
            throw new ScenarioValidationException("剧本 JSON 解析失败：" + e.getOriginalMessage());
        }
        validate(scenario);
        return scenario;
    }

    /**
     * 保存剧本：先校验，再原子写入运行路径与仓库版本源，最后重新加载并生效。
     *
     * <p>写入顺序：先同步版本源（存在时），再写运行路径。两处都用“临时文件 + 原子替换”，
     * 不会出现写一半的损坏文件；校验失败时磁盘上不会发生任何改动。</p>
     *
     * @param json 待保存的完整剧本 JSON（原样写入，保留未在本服务建模的字段）
     * @return 保存结果，包含实际写入了哪些路径
     */
    public synchronized SaveResult saveAndReload(String json) {
        validateJson(json);

        boolean sameFile = sourcePath.equals(scenarioPath);
        boolean sourceSynced = false;
        if (!sameFile) {
            if (Files.isDirectory(sourcePath.getParent())) {
                try {
                    atomicWrite(sourcePath, json);
                    sourceSynced = true;
                } catch (IOException e) {
                    // 版本源同步失败不影响当前生效文件，但要留下明确告警，避免误以为已同步。
                    log.warn("剧本版本源写入失败，本次仅运行路径生效 source={}：{}", sourcePath, e.getMessage());
                }
            } else {
                log.warn("剧本版本源目录不存在，跳过同步 source={}（当前仅运行路径生效）", sourcePath);
            }
        }

        try {
            atomicWrite(scenarioPath, json);
        } catch (IOException e) {
            throw new IllegalStateException("剧本写入运行路径失败：" + scenarioPath + " -> " + e.getMessage(), e);
        }

        Scenario fresh = load();
        current = fresh;
        log.info("剧本保存生效 file={} source={} sourceSynced={} title={} 玩家={}",
                scenarioPath, sourcePath, sourceSynced, fresh.title(), fresh.players().keySet());
        return new SaveResult(fresh, scenarioPath, sourcePath, sameFile, sourceSynced);
    }

    /**
     * 原子写入：先写同目录临时文件，再整体替换目标，避免进程中断留下半截文件。
     */
    private void atomicWrite(Path target, String content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 一次保存的结果。
     *
     * @param scenario     重新加载后的生效剧本
     * @param activePath   运行路径（jar 读取的那一份）
     * @param sourcePath   仓库版本源路径
     * @param sameFile     两处是否为同一个文件
     * @param sourceSynced 版本源是否同步成功
     */
    public record SaveResult(Scenario scenario, Path activePath, Path sourcePath, boolean sameFile,
                             boolean sourceSynced) {
    }

    /**
     * 剧本校验失败：JSON 语法错误或字段缺失/不合法，消息中包含具体字段名。
     *
     * <p>继承 {@link IllegalStateException}，保持 /admin/reload 原有异常处理不变。</p>
     */
    public static class ScenarioValidationException extends IllegalStateException {

        public ScenarioValidationException(String message) {
            super(message);
        }
    }

    /**
     * 重新从磁盘读取并校验剧本；校验通过后才替换当前剧本。
     *
     * <p>失败时抛出带原因的异常，当前剧本保持不变，调用方可以安全地放弃本次重载。</p>
     *
     * @return 新的剧本
     */
    public Scenario reload() {
        Scenario fresh = load();
        current = fresh;
        log.info("剧本重新加载成功 file={} title={} 玩家={}", scenarioPath, fresh.title(), fresh.players().keySet());
        return fresh;
    }

    private Scenario load() {

        if (!Files.isRegularFile(scenarioPath)) {
            throw new IllegalStateException("剧本文件不存在或不是普通文件：" + scenarioPath
                    + "（默认应位于 jar 同级的 scenarios/default.json，可用环境变量 SCENARIO_DIR 覆盖目录）");
        }

        Scenario scenario;
        try {
            String json = Files.readString(scenarioPath, StandardCharsets.UTF_8);
            scenario = objectMapper.readValue(json, Scenario.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("剧本文件 JSON 解析失败：" + scenarioPath
                    + " -> " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("读取剧本文件失败：" + scenarioPath + " -> " + e.getMessage(), e);
        }

        validate(scenario);
        return scenario;
    }

    private void validate(Scenario scenario) {
        if (scenario == null) {
            fail("根节点必须是 JSON 对象");
        }
        requireText(scenario.title(), "title");
        requireText(scenario.worldview(), "worldview");
        requireText(scenario.background(), "background");
        if (scenario.players() == null || scenario.players().isEmpty()) {
            fail("players 缺失");
        }
        if (scenario.players().size() < Players.MIN_PLAYERS || scenario.players().size() > Players.MAX_PLAYERS) {
            fail("players 人数必须在 " + Players.MIN_PLAYERS + "~" + Players.MAX_PLAYERS
                    + " 之间，实际为 " + scenario.players().size());
        }

        for (java.util.Map.Entry<String, Scenario.PlayerSpec> entry : scenario.players().entrySet()) {
            String playerId = entry.getKey();
            if (!Players.isValidPlayerId(playerId)) {
                fail("players 的键「" + playerId + "」非法，仅允许 1~16 位字母/数字/下划线/短横线");
            }
            String prefix = "players." + playerId;
            Scenario.PlayerSpec spec = entry.getValue();
            if (spec == null) {
                fail(prefix + " 缺失");
            }
            requireText(spec.secretGoal(), prefix + ".secretGoal");
            if (spec.maxHp() < MIN_VALID_HP) {
                fail(prefix + ".maxHp 必须 >= " + MIN_VALID_HP + "，实际为 " + spec.maxHp());
            }
            if (spec.hp() < MIN_VALID_HP || spec.hp() > spec.maxHp()) {
                fail(prefix + ".hp 必须在 " + MIN_VALID_HP + "~" + spec.maxHp() + " 之间，实际为 " + spec.hp());
            }
            requireTextList(spec.skills(), prefix + ".skills");
            requireTextList(spec.items(), prefix + ".items");
            if (spec.faction() != null && spec.faction().isBlank()) {
                fail(prefix + ".faction 不能为空字符串（不需要时直接省略该字段）");
            }
            validateCombatSpec(spec, prefix);
        }
        validateEndings(scenario.endings());
    }

    /**
     * 胜负结算配置：必须存在且至少启用一种；启用 maxTurns 时轮数必须 >= 1，
     * 启用 collectItems 时物品列表必须非空。
     */
    private void validateEndings(Scenario.Endings endings) {
        if (endings == null) {
            fail("endings 缺失，必须配置 lastStanding / maxTurns / collectItems 中的至少一种");
        }
        boolean anyEnabled = false;
        if (endings.maxTurns() != null && Boolean.TRUE.equals(endings.maxTurns().enabled())) {
            anyEnabled = true;
            Integer turns = endings.maxTurns().turns();
            if (turns == null || turns < 1) {
                fail("endings.maxTurns.turns 必须 >= 1，实际为 " + turns);
            }
        }
        if (endings.collectItems() != null && Boolean.TRUE.equals(endings.collectItems().enabled())) {
            anyEnabled = true;
            requireTextList(endings.collectItems().items(), "endings.collectItems.items");
        }
        if (endings.lastStanding() != null && Boolean.TRUE.equals(endings.lastStanding().enabled())) {
            anyEnabled = true;
        }
        if (!anyEnabled) {
            fail("endings 至少需要启用一种结算条件（lastStanding / maxTurns / collectItems）");
        }
    }

    /**
     * 战斗数值是可选字段：缺省走 {@link CombatRules} 的默认值；一旦填写就必须合法。
     */
    private void validateCombatSpec(Scenario.PlayerSpec spec, String prefix) {
        if (spec.armorClass() != null && spec.armorClass() < CombatRules.MIN_ARMOR_CLASS) {
            fail(prefix + ".armorClass 必须 >= " + CombatRules.MIN_ARMOR_CLASS + "，实际为 " + spec.armorClass());
        }
        if (spec.attackBonus() != null && (spec.attackBonus() < CombatRules.MIN_ATTACK_BONUS
                || spec.attackBonus() > CombatRules.MAX_ATTACK_BONUS)) {
            fail(prefix + ".attackBonus 必须在 " + CombatRules.MIN_ATTACK_BONUS + "~"
                    + CombatRules.MAX_ATTACK_BONUS + " 之间，实际为 " + spec.attackBonus());
        }
        if (spec.damageDice() != null) {
            try {
                Dice.parse(spec.damageDice());
            } catch (IllegalArgumentException e) {
                fail(prefix + ".damageDice 非法：" + e.getMessage());
            }
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            fail(field + " 缺失或为空");
        }
    }

    private void requireTextList(java.util.List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            fail(field + " 缺失或为空数组");
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                fail(field + " 含空字符串");
            }
        }
    }

    private void fail(String detail) {
        throw new ScenarioValidationException("剧本字段缺失或不合法：" + detail + "（文件：" + scenarioPath + "）");
    }
}
