package com.yourapp.skill.service;

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

@Slf4j
@Service
public class ImMessageService {

    private final RestTemplate restTemplate;
    private final String imApiUrl;

    public ImMessageService(@Value("${skill.im.api-url}") String imApiUrl) {
        this.restTemplate = new RestTemplate();
        this.imApiUrl = imApiUrl;
    }

    /**
     * Send a message to an IM chat via the platform API.
     *
     * @param chatId  the IM chat identifier
     * @param content the text content to send
     * @return true if the message was sent successfully
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
            ResponseEntity<String> response = restTemplate.postForEntity(sendUrl, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("IM message sent successfully: chatId={}, contentLength={}", chatId, content.length());
                return true;
            } else {
                log.error("IM message send failed: chatId={}, status={}, body={}",
                        chatId, response.getStatusCode(), response.getBody());
                return false;
            }
        } catch (RestClientException e) {
            log.error("IM message send error: chatId={}, error={}", chatId, e.getMessage(), e);
            return false;
        }
    }
}
