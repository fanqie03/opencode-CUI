package com.opencode.cui.skill.service;

public class ProtocolException extends RuntimeException {

    private final int code;

    public ProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
