package com.opencode.cui.gateway.model;

import java.util.List;

public record SourceConnectionOverviewResponse(
        String localGwInstanceId,
        String sourceType,
        int localConnectionCount,
        int clusterConnectionCount,
        List<SourceConnectionLinkResponse> localLinks,
        List<SourceConnectionLinkResponse> clusterLinks) {
}
