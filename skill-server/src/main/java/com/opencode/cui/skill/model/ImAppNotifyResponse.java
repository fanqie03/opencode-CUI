package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImAppNotifyResponse(
        @JsonProperty("error") ErrorInfo error) {

    public record ErrorInfo(
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_msg") String errorMsg) {
    }
}
