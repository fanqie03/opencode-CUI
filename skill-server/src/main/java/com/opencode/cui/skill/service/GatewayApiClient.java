package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST client for querying AI-Gateway APIs.
 */
@Slf4j
@Service
public class GatewayApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String gatewayBaseUrl;

    public GatewayApiClient(
            ObjectMapper objectMapper,
            @Value("${skill.gateway.api-base-url:http://localhost:8081}") String gatewayBaseUrl) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    /**
     * Query online agents for a specific user from the Gateway.
     *
     * @param userId the user ID to filter by
     * @return list of online agent info maps
     */
    public List<Map<String, Object>> getOnlineAgentsByUserId(Long userId) {
        try {
            String url = gatewayBaseUrl + "/api/gateway/agents?userId=" + userId;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> agents = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                log.debug("Fetched {} online agents for userId={}", agents.size(), userId);
                return agents;
            }

            log.warn("Gateway returned non-success status: {}", response.getStatusCode());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to query Gateway for online agents: userId={}, error={}",
                    userId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
