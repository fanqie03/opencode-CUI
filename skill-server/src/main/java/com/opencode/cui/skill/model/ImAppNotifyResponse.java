package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ImAppNotifyResponse(
        @JsonProperty("error") ErrorInfo error,
        @JsonProperty("client_notify_id") String clientNotifyId,
        @JsonProperty("server_notify_id") Long serverNotifyId,
        @JsonProperty("invalid_account") List<String> invalidAccount) {

    public record ErrorInfo(
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_msg") String errorMsg) {
    }
}
