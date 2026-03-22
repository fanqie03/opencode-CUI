package com.opencode.cui.skill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * IM 消息发送服务。
 * 通过平台 REST API 向 IM 聊天发送文本消息。
 */
@Slf4j
@Service
public class ImMessageService {

    private final RestTemplate restTemplate;
    /** IM 平台 API 根地址 */
    private final String imApiUrl;

    public ImMessageService(RestTemplate restTemplate,
            @Value("${skill.im.api-url}") String imApiUrl) {
        this.restTemplate = restTemplate;
        this.imApiUrl = imApiUrl;
    }

    /**
     * 向 IM 聊天发送文本消息。
     *
     * @param chatId  IM 会话标识
     * @param content 文本内容
     * @return 发送成功返回 true
     */
    public boolean sendMessage(String chatId, String content) {
        if (chatId == null || chatId.isBlank()) {
            log.warn("Cannot send IM message: chatId is empty");
            return false;
        }
        if (content == null || content.isBlank()) {
            log.warn("Cannot send IM message: content is empty");
            return false;
        }

        String sendUrl = imApiUrl + "/messages/send";

        Map<String, Object> body = new HashMap<>();
        body.put("chatId", chatId);
        body.put("content", content);
        body.put("msgType", "text");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = com.opencode.cui.skill.logging.LogTimer.timed(
                    log, "ImMessage.send(chatId=" + chatId + ")",
                    () -> restTemplate.postForEntity(sendUrl, request, String.class));
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("IM message sent successfully: chatId={}, contentLength={}", chatId, content.length());
                return true;
            } else {
                log.error("IM message send failed: chatId={}, status={}", chatId, response.getStatusCode());
                return false;
            }
        } catch (RestClientException e) {
            log.error("IM message send error: chatId={}, error={}", chatId, e.getMessage());
            return false;
        }
    }
}
