package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantOfflineMessageProviderTest {

    @Mock
    private SysConfigService sysConfigService;

    private AssistantOfflineMessageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new AssistantOfflineMessageProvider(sysConfigService);
    }

    @Test
    @DisplayName("get: 配置有值时返回该值")
    void getReturnsConfiguredValue() {
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn("自定义文案");

        assertEquals("自定义文案", provider.get());
    }

    @Test
    @DisplayName("get: 配置为 null 时返回 DEFAULT_OFFLINE_MESSAGE")
    void getReturnsDefaultWhenNull() {
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn(null);

        assertEquals(AssistantOfflineMessageProvider.DEFAULT_OFFLINE_MESSAGE, provider.get());
    }

    @Test
    @DisplayName("get: 配置为空串时返回 DEFAULT_OFFLINE_MESSAGE")
    void getReturnsDefaultWhenBlank() {
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn("   ");

        assertEquals(AssistantOfflineMessageProvider.DEFAULT_OFFLINE_MESSAGE, provider.get());
    }

    @Test
    @DisplayName("get: SysConfigService 抛 RuntimeException 时不 catch，异常冒泡")
    void getDoesNotSwallowExceptions() {
        when(sysConfigService.getValue("assistant_offline", "message"))
                .thenThrow(new RuntimeException("db down"));

        RuntimeException ex = assertThrows(RuntimeException.class, provider::get);
        assertEquals("db down", ex.getMessage());
    }
}
