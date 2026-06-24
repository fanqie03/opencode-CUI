package com.opencode.cui.skill.telemetry.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.telemetry.client.dto.TelemetryPayload;
import com.opencode.cui.skill.telemetry.config.WelinkTelemetryProperties;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WelinkTelemetryClientTest {

    private static String publicKeyBase64;

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private ObjectMapper mapper;
    private WelinkTelemetryProperties properties;
    private WelinkTelemetryClient client;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        publicKeyBase64 = Base64.getEncoder().encodeToString(
                kpg.generateKeyPair().getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        mapper = new ObjectMapper();
        properties = new WelinkTelemetryProperties();
        properties.setUrl("http://welink.local/producer");
        properties.setToken("test-token");
         properties.setPublicKey(publicKeyBase64);
         properties.setTenantId("tenant-1");
         client = new WelinkTelemetryClient(restTemplate, mapper, properties,
                 new ApiCallMetricsService(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("send: POST {url}, 带 Authorization Bearer + x-wlk-hwa:1, body 为 {key, content}")
    void sendVerifiesHeadersAndBody() {
        mockServer.expect(requestTo("http://welink.local/producer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("x-wlk-hwa", "1"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(req -> {
                    String body = req.getBody().toString();
                    JsonNode node = mapper.readTree(body);
                    assertTrue(node.has("key"), "body must contain key");
                    assertTrue(node.has("content"), "body must contain content");
                    assertTrue(node.get("content").asText().startsWith("security:"),
                            "content must start with security: prefix");
                    assertEquals(2, node.size(), "body should only have key + content");
                })
                .andRespond(withSuccess());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", "skill_chat_request");
        TelemetryPayload payload = new TelemetryPayload(data, "POLICY_TEST");
        client.send("skill_chat_request", "biz-1", payload);

        mockServer.verify();
    }

    @Test
    @DisplayName("send: 4xx 不抛回业务线程")
    void send4xxSwallowed() {
        mockServer.expect(requestTo("http://welink.local/producer"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        Map<String, Object> data = new LinkedHashMap<>();
        TelemetryPayload payload = new TelemetryPayload(data, "POLICY_TEST");
        client.send("skill_chat_request", "biz-1", payload); // 不抛

        mockServer.verify();
    }
}
