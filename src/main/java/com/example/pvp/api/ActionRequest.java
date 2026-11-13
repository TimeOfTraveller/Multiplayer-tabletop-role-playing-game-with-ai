package com.example.pvp.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /action 的请求体。
 *
 * <p>Java 里 private 是关键字，因此字段名用 privateAction，并通过 {@link JsonProperty} 绑定 JSON 的 "private"。</p>
 *
 * @param room          房间标识，如 "room1"，决定读写哪一份房间状态
 * @param from          行动提交者，必须属于该房间剧本的玩家集合
 * @param intent        行动意图，当前骨架不解释语义，仅透传
 * @param privateAction 是否为私有行动，决定裁决结果的 public 取值
 * @param skip          是否为「观望/跳过」；为 true 时忽略 intent，直接按 idle 入队
 */
public record ActionRequest(
        @JsonProperty("room") String room,
        @JsonProperty("from") String from,
        @JsonProperty("intent") String intent,
        @JsonProperty("private") Boolean privateAction,
        @JsonProperty("skip") Boolean skip) {
}
