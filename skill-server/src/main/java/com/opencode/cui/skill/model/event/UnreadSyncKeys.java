package com.opencode.cui.skill.model.event;

/**
 * Key constants for the {@code Map<String, Object> syncContent} carried by
 * {@code SyncRequest} in unread / read push payloads.
 */
public final class UnreadSyncKeys {

    private UnreadSyncKeys() {
    }

    public static final String WELINK_SESSION_ID = "welinkSessionId";
    public static final String MAX_SEQ = "maxSeq";
    public static final String READ_SEQ = "readSeq";
    public static final String ASSISTANT_ACCOUNT = "assistantAccount";
}
