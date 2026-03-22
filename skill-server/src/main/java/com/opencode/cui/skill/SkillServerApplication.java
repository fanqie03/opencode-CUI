package com.opencode.cui.skill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Skill Server 启动类。
 * 负责会话管理、消息持久化和与 AI Gateway 的流式通信。
 */
@SpringBootApplication
@MapperScan("com.opencode.cui.skill.repository")
public class SkillServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillServerApplication.class, args);
    }
}
