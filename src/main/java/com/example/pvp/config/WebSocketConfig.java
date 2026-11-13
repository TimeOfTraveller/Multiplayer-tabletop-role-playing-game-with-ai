package com.example.pvp.config;

import com.example.pvp.ws.GameWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 端点注册与容器参数配置。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** WebSocket 端点路径。 */
    private static final String WS_ENDPOINT = "/ws";

    /** 容器层空闲超时：超过该时间无任何帧则关闭连接，触发登记表清理。 */
    private static final long IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    /** 单条文本消息上限。 */
    private static final int MAX_TEXT_MESSAGE_BUFFER_BYTES = 64 * 1024;

    private final GameWebSocketHandler gameWebSocketHandler;

    public WebSocketConfig(GameWebSocketHandler gameWebSocketHandler) {
        this.gameWebSocketHandler = gameWebSocketHandler;
    }

    /**
     * 注册 /ws 端点。
     *
     * @param registry Spring 提供的 WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, WS_ENDPOINT)
                .setAllowedOriginPatterns("*");
    }

    /**
     * 配置 Servlet 容器上的 WebSocket 参数（空闲超时、消息大小上限）。
     *
     * @return 容器工厂，由 Spring 在启动时应用到内嵌 Tomcat
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(IDLE_TIMEOUT_MILLIS);
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_BYTES);
        return container;
    }
}
