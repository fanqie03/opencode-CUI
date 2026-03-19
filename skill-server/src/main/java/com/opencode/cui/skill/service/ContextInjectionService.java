package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.SkillSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class ContextInjectionService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final ResourceLoader resourceLoader;
    private final boolean injectionEnabled;
    private final String groupChatTemplateLocation;
    private final int maxHistoryMessages;

    public ContextInjectionService(
            ResourceLoader resourceLoader,
            @org.springframework.beans.factory.annotation.Value("${skill.context.injection-enabled:true}") boolean injectionEnabled,
            @org.springframework.beans.factory.annotation.Value("${skill.context.templates.group-chat:classpath:templates/group-chat-prompt.txt}") String groupChatTemplateLocation,
            @org.springframework.beans.factory.annotation.Value("${skill.context.max-history-messages:20}") int maxHistoryMessages) {
        this.resourceLoader = resourceLoader;
        this.injectionEnabled = injectionEnabled;
        this.groupChatTemplateLocation = groupChatTemplateLocation;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public String resolvePrompt(String sessionType, String currentMessage, List<ImMessageRequest.ChatMessage> chatHistory) {
        if (!injectionEnabled
                || !SkillSession.SESSION_TYPE_GROUP.equalsIgnoreCase(sessionType)
                || currentMessage == null
                || currentMessage.isBlank()) {
            return currentMessage;
        }

        String historyText = formatHistory(chatHistory);
        if (historyText.isBlank()) {
            return currentMessage;
        }

        String template = loadTemplate();
        if (template == null || template.isBlank()) {
            return currentMessage;
        }

        return template
                .replace("{{chatHistory}}", historyText)
                .replace("{{currentMessage}}", currentMessage);
    }

    private String loadTemplate() {
        try {
            Resource resource = resourceLoader.getResource(groupChatTemplateLocation);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load group chat prompt template: location={}, error={}",
                    groupChatTemplateLocation, e.getMessage());
            return null;
        }
    }

    private String formatHistory(List<ImMessageRequest.ChatMessage> chatHistory) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            return "";
        }

        int start = Math.max(0, chatHistory.size() - maxHistoryMessages);
        StringBuilder history = new StringBuilder();
        for (ImMessageRequest.ChatMessage item : chatHistory.subList(start, chatHistory.size())) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            if (history.length() > 0) {
                history.append('\n');
            }
            history.append('[')
                    .append(formatTimestamp(item.timestamp()))
                    .append("] ")
                    .append(item.senderName() != null && !item.senderName().isBlank()
                            ? item.senderName()
                            : item.senderAccount())
                    .append(": ")
                    .append(item.content());
        }
        return history.toString();
    }

    private String formatTimestamp(long rawTimestamp) {
        long millis = rawTimestamp > 0 && rawTimestamp < 10_000_000_000L
                ? rawTimestamp * 1000
                : rawTimestamp;
        if (millis <= 0) {
            return "unknown-time";
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
    }
}
