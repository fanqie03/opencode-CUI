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

/**
 * IM 出站消息服务。
 * 通过 WeLink IM REST API 向群聊或单聊发送文本消息，
 * 支持分 Bot 账号发送（assistantAccount）。
 */
@Slf4j
@Service
public class ImOutboundService {

    /** IM 消息内容类型：文本 */
    private static final int CONTENT_TYPE_TEXT = 13;
    /** 群聊消息发送端点 */
    private static final String GROUP_ENDPOINT = "/v1/welinkim/im-service/chat/app-group-chat";
    /** 单聊消息发送端点 */
    private static final String DIRECT_ENDPOINT = "/v1/welinkim/im-service/chat/app-user-chat";

    private final RestTemplate restTemplate;
    /** IM 平台 API 根地址 */
    private final String imApiUrl;
    /** IM 平台认证令牌 */
    private final String imToken;

    public ImOutboundService(
            RestTemplate restTemplate,
            @org.springframework.beans.factory.annotation.Value("${skill.im.api-url}") String imApiUrl,
            @org.springframework.beans.factory.annotation.Value("${skill.im.token:}") String imToken) {
        this.restTemplate = restTemplate;
        this.imApiUrl = imApiUrl;
        this.imToken = imToken;
    }

    /**
     * 向 IM 发送文本消息。
     *
     * @param sessionType      会话类型（group / direct）
     * @param sessionId        IM 会话 ID
     * @param content          消息内容
     * @param assistantAccount 发送方 Bot 账号
     * @return 发送成功返回 true
     */
    public boolean sendTextToIm(String sessionType, String sessionId, String content, String assistantAccount) {
        log.info("Sending IM outbound: sessionType={}, sessionId={}, assistant={}, contentLength={}",
                sessionType, sessionId, assistantAccount, content != null ? content.length() : 0);
        if (sessionId == null || sessionId.isBlank()
                || content == null || content.isBlank()
                || assistantAccount == null || assistantAccount.isBlank()) {
            log.warn("IM outbound skipped due to blank params: sessionId={}, assistant={}",
                    sessionId, assistantAccount);
            return false;
        }

        String path = resolvePath(sessionType);
        if (path == null) {
            log.warn("IM outbound unknown sessionType: {}", sessionType);
            return false;
        }

        // 构建请求体
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

        long start = System.nanoTime();
        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    joinUrl(imApiUrl, path),
                    new HttpEntity<>(body, headers),
                    JsonNode.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            JsonNode respBody = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || respBody == null) {
                log.warn("[EXT_CALL] ImOutbound.send HTTP error: sessionId={}, status={}, durationMs={}",
                        sessionId, response.getStatusCode(), elapsedMs);
                return false;
            }
            // 检查业务错误码
            JsonNode errorNode = respBody.path("error");
            if (!errorNode.isMissingNode() && errorNode.has("errorCode")) {
                log.error(
                        "[EXT_CALL] ImOutbound.send business error: sessionId={}, errorCode={}, errorMsg={}, durationMs={}",
                        sessionId,
                        errorNode.path("errorCode").asText(null),
                        errorNode.path("errorMsg").asText(null),
                        elapsedMs);
                return false;
            }
            log.info("[EXT_CALL] ImOutbound.send success: sessionType={}, sessionId={}, msgId={}, durationMs={}",
                    sessionType, sessionId, respBody.path("msgId").asText(null), elapsedMs);
            return true;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] ImOutbound.send failed: sessionId={}, durationMs={}, error={}",
                    sessionId, elapsedMs, e.getMessage());
            return false;
        }
    }

    /**
     * 根据会话类型解析 API 路径。
     *
     * @return 对应的端点路径，未知类型返回 null
     */
    private String resolvePath(String sessionType) {
        if (SkillSession.SESSION_TYPE_GROUP.equalsIgnoreCase(sessionType)) {
            return GROUP_ENDPOINT;
        }
        if (SkillSession.SESSION_TYPE_DIRECT.equalsIgnoreCase(sessionType)) {
            return DIRECT_ENDPOINT;
        }
        return null;
    }

    /** 安全拼接 URL，避免路径分隔符重复或缺失。 */
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
