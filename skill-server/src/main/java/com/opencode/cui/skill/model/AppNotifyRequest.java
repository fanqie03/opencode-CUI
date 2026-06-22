package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AppNotifyRequest(
        @JsonProperty("client_notify_id") String clientNotifyId,
        @JsonProperty("notify_scope") int notifyScope,
        @JsonProperty("notify_tenant") String notifyTenant,
        @JsonProperty("notify_accounts") List<String> notifyAccounts,
        @JsonProperty("notify_module") String notifyModule,
        @JsonProperty("notify_data") String notifyData) {
}
