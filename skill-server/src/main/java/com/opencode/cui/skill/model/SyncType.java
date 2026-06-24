package com.opencode.cui.skill.model;

public enum SyncType {
    SESSION_DELETED("session.deleted"),
    SESSION_UNREAD("session.unread"),
    SESSION_READ("session.read");

    private final String type;

    SyncType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
