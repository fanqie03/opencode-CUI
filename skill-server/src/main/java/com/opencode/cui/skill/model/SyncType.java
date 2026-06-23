package com.opencode.cui.skill.model;

public enum SyncType {
    SESSION_UNREAD("session.unread"),
    SESSION_DELETED("session.deleted");

    private final String type;

    SyncType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
