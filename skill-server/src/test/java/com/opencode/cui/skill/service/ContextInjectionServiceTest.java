package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.ImMessageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ContextInjectionServiceTest 单元测试：验证上下文注入服务的逻辑。 */
class ContextInjectionServiceTest {

        @Test
        @DisplayName("group chat prompt includes history and current message when injection enabled")
        void groupChatPromptIncludesHistory() {
                ContextInjectionService service = new ContextInjectionService(
                                new DefaultResourceLoader(),
                                true,
                                "classpath:templates/group-chat-prompt.txt",
                                20);

                String prompt = service.resolvePrompt(
                                "group",
                                "现在该怎么做？",
                                List.of(
                                                new ImMessageRequest.ChatMessage("user-1", "张三", "前置消息", 1710000000L),
                                                new ImMessageRequest.ChatMessage("user-2", "李四", "补充上下文",
                                                                1710000001L)));

                assertTrue(prompt.contains("张三"));
                assertTrue(prompt.contains("李四"));
                assertTrue(prompt.contains("现在该怎么做？"));
        }

        @Test
        @DisplayName("direct chat bypasses context injection")
        void directChatBypassesContextInjection() {
                ContextInjectionService service = new ContextInjectionService(
                                new DefaultResourceLoader(),
                                true,
                                "classpath:templates/group-chat-prompt.txt",
                                20);

                String prompt = service.resolvePrompt(
                                "direct",
                                "帮我写个测试",
                                List.of(new ImMessageRequest.ChatMessage("user-1", "张三", "历史消息", 1710000000L)));

                assertEquals("帮我写个测试", prompt);
        }
}
