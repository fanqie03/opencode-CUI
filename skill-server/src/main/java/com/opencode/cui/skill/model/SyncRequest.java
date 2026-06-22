package com.opencode.cui.skill.model;

import java.util.Map;

public record SyncRequest(
        SyncMode syncMode,
        SyncType syncType,
        Map<String, Object> syncContent,
        String targetAccount) {
}
