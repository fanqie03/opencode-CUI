package com.opencode.cui.skill.telemetry.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricServiceEnumTest {

    @Test
    void enumHas13Values() {
        assertEquals(13, MetricServiceEnum.values().length);
    }

    @Test
    void imGroupChatHasCorrectIdAndComment() {
        assertEquals("im_group_chat", MetricServiceEnum.IM_GROUP_CHAT.getId());
        assertEquals("群聊消息发送", MetricServiceEnum.IM_GROUP_CHAT.getComment());
    }

    @Test
    void gatewayWsInvokeHasCorrectIdAndComment() {
        assertEquals("gateway_ws_invoke", MetricServiceEnum.GATEWAY_WS_INVOKE.getId());
        assertEquals("Gateway WS invoke 指令", MetricServiceEnum.GATEWAY_WS_INVOKE.getComment());
    }

    @Test
    void telemetryWelinkUploadHasCorrectIdAndComment() {
        assertEquals("telemetry_welink_upload", MetricServiceEnum.TELEMETRY_WELINK_UPLOAD.getId());
        assertEquals("WeLink 埋码上报", MetricServiceEnum.TELEMETRY_WELINK_UPLOAD.getComment());
    }
}