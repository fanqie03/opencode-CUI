package com.opencode.cui.skill.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.MultiSyncProperties;
import com.opencode.cui.skill.model.AppNotifyRequest;
import com.opencode.cui.skill.model.ImAppNotifyResponse;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import com.opencode.cui.skill.model.SyncType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImMultiDeviceSyncServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ImMultiDeviceSyncService service;

    @BeforeEach
    void setUp() {
        MultiSyncProperties syncProperties = new MultiSyncProperties();
        MultiSyncProperties.Im.AppNotify appNotify = new MultiSyncProperties.Im.AppNotify();
        appNotify.setUrl("http://localhost:8080/v1/app-notify");
        appNotify.setTenant("test-tenant");
        appNotify.setModule("test-module");
        appNotify.setScope(2);
        syncProperties.getIm().setAppNotify(appNotify);

        service = new ImMultiDeviceSyncService(
                restTemplate, objectMapper, syncProperties, "token-123");
    }

    @Test
    @DisplayName("getSyncMode returns IM")
    void getSyncModeReturnsIm() {
        assertEquals(SyncMode.IM, service.getSyncMode());
    }

    @Test
    @DisplayName("sends app-notify request with correct body")
    void sendsAppNotifyRequest() throws Exception {
        when(restTemplate.postForEntity(
                eq("http://localhost:8080/v1/app-notify"),
                any(HttpEntity.class), eq(ImAppNotifyResponse.class)))
                .thenReturn(ResponseEntity.ok(new ImAppNotifyResponse(null)));

        SyncRequest request = new SyncRequest(
                SyncMode.IM,
                SyncType.SESSION_UNREAD,
                Map.of("welinkSessionId", "123", "unreadCount", 1),
                "user-1");

        service.push(request);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq("http://localhost:8080/v1/app-notify"),
                captor.capture(), eq(ImAppNotifyResponse.class));

        AppNotifyRequest body = (AppNotifyRequest) captor.getValue().getBody();
        assertEquals("test-tenant", body.notifyTenant());
        assertEquals("test-module", body.notifyModule());
        assertEquals(2, body.notifyScope());
        assertEquals(List.of("user-1"), body.notifyAccounts());
        assertTrue(body.clientNotifyId() != null && !body.clientNotifyId().isBlank());
        assertTrue(body.notifyData() != null && !body.notifyData().isBlank());

        assertTrue(body.notifyData().contains("session.unread"));
    }

    @Test
    @DisplayName("blank appNotifyUrl skips without HTTP call")
    void blankImApiUrlSkips() {
        MultiSyncProperties syncProperties = new MultiSyncProperties();
        MultiSyncProperties.Im.AppNotify appNotify = new MultiSyncProperties.Im.AppNotify();
        appNotify.setUrl("");
        syncProperties.getIm().setAppNotify(appNotify);

        ImMultiDeviceSyncService svc = new ImMultiDeviceSyncService(
                restTemplate, objectMapper, syncProperties, "");

        SyncRequest request = new SyncRequest(
                SyncMode.IM,
                SyncType.SESSION_UNREAD,
                Map.of("welinkSessionId", "123"),
                "user-1");

        svc.push(request);

        verify(restTemplate, never())
                .postForEntity(any(String.class), any(), any());
    }
}
