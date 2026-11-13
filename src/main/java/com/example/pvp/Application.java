package com.example.pvp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动类。
 *
 * <p>本骨架只验证 WebSocket 连接 + 按玩家转发，不含裁决、状态、LLM 调用。</p>
 */
@SpringBootApplication
public class Application {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
