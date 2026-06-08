package com.opencode.cui.gateway.model;

public record SourceConnectionLinkResponse(
        String sourceType,
        String ssInstanceId,
        String gwInstanceId,
        String linkId,
        boolean local,
        boolean open,
        Boolean senderRunning,
        Integer pending,
        Long lastSeenEpochSeconds,
        Long ageSeconds) {
}
