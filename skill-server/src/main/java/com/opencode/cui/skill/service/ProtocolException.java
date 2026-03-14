package com.opencode.cui.skill.service;

public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public ProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
