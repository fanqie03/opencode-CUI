package com.opencode.cui.skill.service;

/**
 * 协议层业务异常。
 * 用于 Controller / Service 层抛出带 HTTP 状态码的错误，
 * 由 {@link com.opencode.cui.skill.config.GlobalExceptionHandler} 统一捕获并返回标准响应。
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP 状态码 */
    private final int code;

    /**
     * @param code    HTTP 状态码（如 400、403、404）
     * @param message 错误描述
     */
    public ProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 获取 HTTP 状态码。 */
    public int getCode() {
        return code;
    }
}
