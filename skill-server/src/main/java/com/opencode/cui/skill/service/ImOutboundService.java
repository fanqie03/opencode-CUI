package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.SkillSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ImOutboundService {

    private static final int CONTENT_TYPE_TEXT = 13;
    private static final String GROUP_ENDPOINT = "/v1/welinkim/im-service/chat/app-group-chat";
    private static final String DIRECT_ENDPOINT = "/v1/welinkim/im-service/chat/app-user-chat";

    private final RestTemplate restTemplate;
    private final String imApiUrl;
    private final String imToken;

    public ImOutboundService(
            RestTemplate restTemplate,
            @org.springframework.beans.factory.annotation.Value("${skill.im.api-url}") String imApiUrl,
            @org.springframework.beans.factory.annotation.Value("${skill.im.token:}") String imToken) {
        this.restTemplate = restTemplate;
        this.imApiUrl = imApiUrl;
        this.imToken = imToken;
    }

    public boolean sendTextToIm(String sessionType, String sessionId, String content, String assistantAccount) {
        if (sessionId == null || sessionId.isBlank()
                || content == null || content.isBlank()
                || assistantAccount == null || assistantAccount.isBlank()) {
            return false;
        }

        String path = resolvePath(sessionType);
        if (path == null) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appMsgId", UUID.randomUUID().toString());
        body.put("senderAccount", assistantAccount);
        body.put("sessionId", sessionId);
        body.put("contentType", CONTENT_TYPE_TEXT);
        body.put("content", content);
        body.put("clientSendTime", System.currentTimeMillis());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (imToken != null && !imToken.isBlank()) {
            headers.setBearerAuth(imToken);
        }

        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    joinUrl(imApiUrl, path),
                    new HttpEntity<>(body, headers),
                    JsonNode.class);
            JsonNode respBody = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || respBody == null) {
                return false;
            }
            JsonNode errorNode = respBody.path("error");
            if (!errorNode.isMissingNode() && errorNode.has("errorCode")) {
                log.error("IM outbound business error: sessionType={}, sessionId={}, errorCode={}, errorMsg={}",
                        sessionType,
                        sessionId,
                        errorNode.path("errorCode").asText(null),
                        errorNode.path("errorMsg").asText(null));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("IM outbound request failed: sessionType={}, sessionId={}, error={}",
                    sessionType, sessionId, e.getMessage());
            return false;
        }
    }

    private String resolvePath(String sessionType) {
        if (SkillSession.SESSION_TYPE_GROUP.equalsIgnoreCase(sessionType)) {
            return GROUP_ENDPOINT;
        }
        if (SkillSession.SESSION_TYPE_DIRECT.equalsIgnoreCase(sessionType)) {
            return DIRECT_ENDPOINT;
        }
        return null;
    }

    private String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }
}
