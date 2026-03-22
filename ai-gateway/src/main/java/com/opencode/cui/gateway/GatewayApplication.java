package com.opencode.cui.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Gateway 启动类。
 * 负责 Agent WebSocket 连接管理、AK/SK 认证和消息路由。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.opencode.cui.gateway.repository")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
