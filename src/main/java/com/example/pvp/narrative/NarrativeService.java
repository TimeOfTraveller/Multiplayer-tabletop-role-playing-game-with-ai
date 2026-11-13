package com.example.pvp.narrative;

import com.example.pvp.state.GameEvent;
import com.example.pvp.state.PlayerView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 叙事层：调用 OpenAI 兼容的 /chat/completions 网关，为单个玩家生成叙事文本。
 *
 * <p>默认对接火山方舟 Agent Plan；只要网关兼容 OpenAI 的 chat/completions，就可以通过
 * {@code ARK_BASE_URL} / {@code ARK_MODEL} / {@code ARK_API_KEY} 换成其他厂商。
 * {@code ARK_THINKING_ENABLED=false} 可关闭方舟专属的 thinking 字段。</p>
 *
 * <p>本类只读 PlayerView 与裁决结果，绝不修改房间状态，绝不决定成功失败。
 * 任何超时、非 200、解析失败都返回 {@link Optional#empty()} 并打 WARN，由上层降级。</p>
 *
 * <p>提示词里只出现自然语言说法（由 {@link Terminology} 渲染），不出现玩家标识与机制 token。</p>
 */
@Service
public class NarrativeService {

    private static final Logger log = LoggerFactory.getLogger(NarrativeService.class);

    /** 提示词文件（版本号体现在文件名 narrative_v3：多人房间 + 其他玩家清单）。 */
    private static final String PROMPT_RESOURCE = "prompts/narrative_v3.txt";

    /** 升级专属叙事提示词。 */
    private static final String LEVEL_UP_PROMPT_RESOURCE = "prompts/levelup_v1.txt";

    /** OpenAI 兼容网关的补全路径；ARK_BASE_URL 只需给到版本前缀。 */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final String PLACEHOLDER_SCENARIO_TITLE = "{{SCENARIO_TITLE}}";
    private static final String PLACEHOLDER_SCENARIO_WORLDVIEW = "{{SCENARIO_WORLDVIEW}}";
    private static final String PLACEHOLDER_SCENARIO_OPENING = "{{SCENARIO_OPENING}}";
    private static final String PLACEHOLDER_MY_INVENTORY = "{{MY_INVENTORY}}";
    private static final String PLACEHOLDER_OTHER_PLAYERS = "{{OTHER_PLAYERS}}";
    private static final String PLACEHOLDER_VISIBLE_EVENTS = "{{VISIBLE_EVENTS}}";
    private static final String PLACEHOLDER_NEW_EVENT = "{{NEW_EVENT}}";
    private static final String PLACEHOLDER_LEVEL_UP_FACTS = "{{LEVEL_UP_FACTS}}";

    /** 背包为空时的自然语言描述。 */
    private static final String EMPTY_INVENTORY_TEXT = "你身上什么也没有。";

    /** 没有任何可见历史事件时的自然语言描述。 */
    private static final String EMPTY_VISIBLE_EVENTS_TEXT = "你还没经历过什么值得记住的事。";

    /** 本轮没有该玩家可见的新事件时的自然语言描述。 */
    private static final String NO_VISIBLE_EVENT_TEXT = "这一次，你这边没有新的动静。";

    /** 房间里没有其他玩家可见时的自然语言描述。 */
    private static final String EMPTY_OTHER_PLAYERS_TEXT = "这一轮你看不到其他玩家的身影。";

    private static final String INVENTORY_PREFIX = "你身上有：";

    private static final String ITEM_SEPARATOR = "、";

    private static final String SENTENCE_END = "。";

    private static final String EVENT_LINE_PREFIX = "· ";

    private static final String RESULT_PREFIX = "，结果：";

    private static final String ROLE_USER = "user";

    /** 叙事不需要发散，固定 0 温度。 */
    private static final double TEMPERATURE = 0.0;

    private static final int HTTP_OK = 200;

    /** 日志里回显响应体时的截断长度。 */
    private static final int MAX_LOGGED_BODY_CHARS = 300;

    /** 日志时间戳格式，精确到毫秒，用于证明并发调用时间重叠。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int maxTokens;
    private final int maxCallsPerDay;
    private final boolean thinkingEnabled;
    private final String promptTemplate;
    private final String levelUpPromptTemplate;

    private final AtomicInteger callsToday = new AtomicInteger();
    private volatile LocalDate callsDate = LocalDate.now();

    public NarrativeService(ObjectMapper objectMapper,
                            @Value("${ark.base-url}") String baseUrl,
                            @Value("${ark.model}") String model,
                            @Value("${ark.timeout-millis}") long timeoutMillis,
                            @Value("${ark.max-tokens}") int maxTokens,
                            @Value("${ark.max-calls-per-day}") int maxCallsPerDay,
                            @Value("${ark.thinking-enabled:true}") boolean thinkingEnabled,
                            @Value("${ARK_API_KEY:}") String apiKey) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.maxTokens = maxTokens;
        this.maxCallsPerDay = maxCallsPerDay;
        this.thinkingEnabled = thinkingEnabled;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.promptTemplate = loadPrompt(PROMPT_RESOURCE);
        this.levelUpPromptTemplate = loadPrompt(LEVEL_UP_PROMPT_RESOURCE);
        log.info("叙事层初始化：baseUrl={} model={} timeout={}ms maxTokens={} 每日上限={} thinking={} apiKey已配置={}",
                this.baseUrl, model, timeoutMillis, maxTokens, maxCallsPerDay, thinkingEnabled, !apiKey.isBlank());
    }

    /**
     * 为单个玩家生成叙事文本。
     *
     * @param view          该玩家自己的视角投影（不含叙事文本）
     * @param visibleEvents 该玩家有权看到的本轮裁决结果（可能为空）
     * @return 模型返回的叙事文本；任何失败都返回空 Optional，由调用方降级
     */
    public Optional<String> narrate(PlayerView view, List<GameEvent> visibleEvents) {
        return generate(buildPrompt(view, visibleEvents), view.playerId());
    }

    /**
     * 为升级的玩家单独生成一段“变强了”的专属叙事。
     *
     * <p>与普通叙事使用不同提示词、单独一次模型调用，只发给成长归属玩家；
     * 数值全部来自裁决层的 {@link GameEvent.Growth}，模型不得改写。</p>
     *
     * @param view   升级后的玩家视角（maxHp / HP / 等级均为升级后数值）
     * @param growth 成长审计信息
     * @return 模型返回的升级叙事；任何失败都返回空 Optional，由调用方降级
     */
    public Optional<String> narrateLevelUp(PlayerView view, GameEvent.Growth growth) {
        return generate(buildLevelUpPrompt(growth), view.playerId());
    }

    /**
     * 公共模型调用链：鉴权、配额、请求、响应解析与日志。
     *
     * @param prompt   已渲染好的提示词
     * @param playerId 叙事接收者
     * @return 模型返回的叙事文本；任何失败都返回空 Optional
     */
    private Optional<String> generate(String prompt, String playerId) {
        String requestId = UUID.randomUUID().toString();
        String startTime = nowText();
        long startNanos = System.nanoTime();
        log.info("叙事请求开始 requestId={} player={} thread={} start={}",
                requestId, playerId, Thread.currentThread().getName(), startTime);

        if (apiKey.isBlank()) {
            return degrade(requestId, playerId, startTime, startNanos, "未配置 ARK_API_KEY");
        }
        if (!acquireDailyQuota()) {
            return degrade(requestId, playerId, startTime, startNanos,
                    "已达每日调用上限 " + maxCallsPerDay);
        }

        // 提示词不参与安全校验，只用于人工复核“没有玩家标识泄露”，因此可以直接落日志。
        log.info("叙事提示词 requestId={} player={} prompt={}", requestId, playerId, prompt);

        String requestBody;
        try {
            requestBody = buildRequestBody(prompt);
        } catch (JsonProcessingException e) {
            return degrade(requestId, playerId, startTime, startNanos,
                    "构建请求体失败 " + e.getMessage());
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + CHAT_COMPLETIONS_PATH))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != HTTP_OK) {
                return degrade(requestId, playerId, startTime, startNanos,
                        "HTTP " + response.statusCode() + " body=" + truncate(response.body()));
            }

            Optional<String> content = extractContent(response.body());
            if (content.isEmpty()) {
                return degrade(requestId, playerId, startTime, startNanos,
                        "响应解析失败 body=" + truncate(response.body()));
            }
            log.info("叙事请求完成 requestId={} player={} model={} start={} end={} 耗时={}ms 长度={}",
                    requestId, playerId, model, startTime, nowText(), elapsedMillis(startNanos),
                    content.get().length());
            log.info("叙事文本 requestId={} player={} text={}", requestId, playerId, content.get());
            return content;
        } catch (HttpTimeoutException e) {
            return degrade(requestId, playerId, startTime, startNanos, "超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return degrade(requestId, playerId, startTime, startNanos, "调用被中断");
        } catch (IOException e) {
            return degrade(requestId, playerId, startTime, startNanos, "网络异常 " + e);
        }
    }

    /**
     * 按玩家视角渲染提示词：所有列表都转成人类可读的自然语言。
     *
     * <p>其他玩家清单只包含该玩家本来就能从 publicPlayers 看到的公开状态；
     * 私有事件、秘密目标与经验明细不会进入提示词。</p>
     */
    private String buildPrompt(PlayerView view, List<GameEvent> visibleEvents) {
        return promptTemplate
                .replace(PLACEHOLDER_SCENARIO_TITLE, view.title())
                .replace(PLACEHOLDER_SCENARIO_WORLDVIEW, view.worldview())
                .replace(PLACEHOLDER_SCENARIO_OPENING, view.background())
                .replace(PLACEHOLDER_MY_INVENTORY, renderInventory(view.myInventory()))
                .replace(PLACEHOLDER_OTHER_PLAYERS, renderOtherPlayers(view))
                .replace(PLACEHOLDER_VISIBLE_EVENTS, renderVisibleEvents(view.visibleEvents(), view))
                .replace(PLACEHOLDER_NEW_EVENT, renderNewEvents(visibleEvents, view));
    }

    /**
     * 渲染升级提示词：只给出裁决层算好的前后数值，模型只能照抄。
     */
    private String buildLevelUpPrompt(GameEvent.Growth growth) {
        StringBuilder facts = new StringBuilder();
        facts.append("你从 ").append(growth.levelBefore()).append(" 级升到了 ").append(growth.level()).append(" 级");
        if (growth.levelsGained() > 1) {
            facts.append("，这一下连升 ").append(growth.levelsGained()).append(" 级");
        }
        facts.append("；最大生命值从 ").append(growth.maxHpBefore()).append(" 提升到 ").append(growth.maxHpAfter());
        facts.append("；攻击加值从 ").append(growth.attackBonusBefore()).append(" 提升到 ")
                .append(growth.attackBonusAfter());
        facts.append("；生命值已经回满到 ").append(growth.hpAfter()).append(" 点");
        facts.append("；当前经验值 ").append(growth.exp()).append(" 点。");
        return levelUpPromptTemplate.replace(PLACEHOLDER_LEVEL_UP_FACTS, facts.toString());
    }

    private String renderInventory(List<String> inventory) {
        if (inventory.isEmpty()) {
            return EMPTY_INVENTORY_TEXT;
        }
        StringBuilder sb = new StringBuilder(INVENTORY_PREFIX);
        for (int i = 0; i < inventory.size(); i++) {
            if (i > 0) {
                sb.append(ITEM_SEPARATOR);
            }
            sb.append(Terminology.itemDisplay(inventory.get(i)));
        }
        return sb.append(SENTENCE_END).toString();
    }

    /**
     * 渲染该玩家可见的其他玩家公开状态清单。
     */
    private String renderOtherPlayers(PlayerView view) {
        StringBuilder sb = new StringBuilder();
        for (PlayerView.PublicPlayer other : view.publicPlayers()) {
            if (other.playerId().equals(view.playerId())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(EVENT_LINE_PREFIX)
                    .append(other.name())
                    .append("（等级 ").append(other.level())
                    .append("，生命 ").append(other.hp()).append("/").append(other.maxHp());
            if (other.defeated()) {
                sb.append("，已倒下");
            }
            sb.append("）");
        }
        return sb.length() == 0 ? EMPTY_OTHER_PLAYERS_TEXT : sb.toString();
    }

    private String renderVisibleEvents(List<PlayerView.VisibleEvent> events, PlayerView view) {
        if (events.isEmpty()) {
            return EMPTY_VISIBLE_EVENTS_TEXT;
        }
        StringBuilder sb = new StringBuilder();
        for (PlayerView.VisibleEvent event : events) {
            // 倒下事件是攻击事件的派生结果，避免同一瞬间在提示词里重复描述两次。
            if (GameEvent.TYPE_DOWN.equals(event.type())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (event.combat() != null) {
                sb.append(EVENT_LINE_PREFIX).append(renderCombat(event.combat(), view));
            } else {
                sb.append(EVENT_LINE_PREFIX)
                        .append(displayName(event.actor(), view))
                        .append(Terminology.intentDisplay(event.intent()))
                        .append(RESULT_PREFIX)
                        .append(Terminology.resultDisplay(event.result()))
                        .append(SENTENCE_END);
            }
        }
        return sb.length() == 0 ? EMPTY_VISIBLE_EVENTS_TEXT : sb.toString();
    }

    /**
     * 渲染本轮该玩家可见的全部新事件；多人同时行动时可能不止一条。
     */
    private String renderNewEvents(List<GameEvent> visibleEvents, PlayerView view) {
        if (visibleEvents == null || visibleEvents.isEmpty()) {
            return NO_VISIBLE_EVENT_TEXT;
        }
        StringBuilder sb = new StringBuilder();
        for (GameEvent event : visibleEvents) {
            if (GameEvent.TYPE_DOWN.equals(event.type())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (event.combat() != null) {
                sb.append(EVENT_LINE_PREFIX).append(renderCombat(event.combat(), view));
            } else {
                sb.append(EVENT_LINE_PREFIX)
                        .append(displayName(event.actor(), view))
                        .append(Terminology.intentDisplay(event.intent()))
                        .append(RESULT_PREFIX)
                        .append(Terminology.resultDisplay(event.result()))
                        .append(SENTENCE_END);
            }
        }
        return sb.length() == 0 ? NO_VISIBLE_EVENT_TEXT : sb.toString();
    }

    /**
     * 玩家在叙事里的称呼：自己用“你”，其他人用公开名字（来自剧本玩家键）。
     */
    private String displayName(String playerId, PlayerView view) {
        if (playerId == null) {
            return "有人";
        }
        if (playerId.equals(view.playerId())) {
            return "你";
        }
        return view.publicPlayers().stream()
                .filter(player -> player.playerId().equals(playerId))
                .map(PlayerView.PublicPlayer::name)
                .findFirst()
                .orElse("其他玩家");
    }

    /**
     * 把裁决层算好的战斗结果翻译成给指定玩家看的自然语言，数字全部原样取自事件。
     *
     * <p>这里只做视角转换（你 / 其他玩家的名字），不做任何计算，保证提示词里的数字与裁决层完全一致。</p>
     *
     * @param combat 战斗审计信息
     * @param view   叙事接收者视角，用于把玩家标识换成“你”或公开名字
     * @return 一句包含命中与否、伤害与剩余生命的中文描述
     */
    private String renderCombat(GameEvent.Combat combat, PlayerView view) {
        String subject = displayName(combat.attacker(), view);
        String object = displayName(combat.target(), view);
        if (!combat.hit()) {
            return subject + "攻击了" + object + "，但没有命中，没有造成伤害。";
        }
        if (combat.targetDefeated()) {
            return subject + "攻击了" + object + "并命中，造成 " + combat.damage() + " 点伤害，"
                    + object + "的生命值降到 0，倒下了。";
        }
        return subject + "攻击了" + object + "并命中，造成 " + combat.damage() + " 点伤害，"
                + object + "还剩 " + combat.targetHpRemaining() + " 点生命。";
    }

    private String buildRequestBody(String prompt) throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", ROLE_USER);
        userMessage.put("content", prompt);
        body.put("max_tokens", maxTokens);
        body.put("temperature", TEMPERATURE);
        if (thinkingEnabled) {
            // 方舟关闭深度思考的写法，避免思考内容占用 max_tokens；第三方网关不支持时可关闭
            body.putObject("thinking").put("type", "disabled");
        }
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 从响应体里取叙事文本，兼容 OpenAI 标准的字符串 content 与部分网关返回的数组 content。
     */
    private Optional<String> extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").path(0);
            String content = extractText(choice.path("message").path("content"));
            if (content.isBlank()) {
                // 少数兼容网关沿用旧版 completions 结构，把文本放在 choices[0].text
                content = extractText(choice.path("text"));
            }
            if (content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(content);
        } catch (JsonProcessingException e) {
            log.warn("叙事响应不是合法 JSON：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 提取文本节点：支持字符串，以及 {@code [{"type":"text","text":"..."}]} 形式的数组分段。
     */
    private String extractText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node) {
                String text = part.isTextual() ? part.asText() : extractText(part.path("text"));
                if (!text.isBlank()) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String stripTrailingSlash(String url) {
        String result = url == null ? "" : url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private Optional<String> degrade(String requestId, String playerId, String startTime,
                                     long startNanos, String reason) {
        log.warn("叙事降级 requestId={} player={} start={} end={} 耗时={}ms 原因={}",
                requestId, playerId, startTime, nowText(), elapsedMillis(startNanos), reason);
        return Optional.empty();
    }

    private boolean acquireDailyQuota() {
        LocalDate today = LocalDate.now();
        if (!today.equals(callsDate)) {
            synchronized (this) {
                if (!today.equals(callsDate)) {
                    callsDate = today;
                    callsToday.set(0);
                }
            }
        }
        if (callsToday.incrementAndGet() > maxCallsPerDay) {
            callsToday.decrementAndGet();
            return false;
        }
        return true;
    }

    private String loadPrompt(String resource) {
        try {
            return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词失败：" + resource, e);
        }
    }

    private String nowText() {
        return LocalTime.now().format(TIME_FORMATTER);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_LOGGED_BODY_CHARS ? text : text.substring(0, MAX_LOGGED_BODY_CHARS) + "…";
    }
}
