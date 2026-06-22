package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record AppNotifyData(
        @JsonProperty("notify_type") String notifyType,
        @JsonProperty("notify_content") Map<String, Object> notifyContent) {
}
