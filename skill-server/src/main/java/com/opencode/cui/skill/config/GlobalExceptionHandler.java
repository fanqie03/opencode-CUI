package com.opencode.cui.skill.config;

import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.service.ProtocolException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * 统一捕获 Controller 层抛出的异常，返回标准化 ApiResponse 格式。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理协议层自定义异常。
     * 保持 HTTP 200 + body error 的约定（前端使用 ApiResponse.code 判断成功/失败）。
     */
    @ExceptionHandler(ProtocolException.class)
    public ResponseEntity<ApiResponse<Void>> handleProtocolException(ProtocolException ex) {
        log.warn("Protocol error: code={}, message={}", ex.getCode(), ex.getMessage());
        HttpStatus status = mapToHttpStatus(ex.getCode());
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    private HttpStatus mapToHttpStatus(int code) {
        return switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * 处理参数校验异常。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
    }

    /**
     * 处理 NumberFormatException（如非法 session ID）。
     */
    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<ApiResponse<?>> handleNumberFormat(NumberFormatException e) {
        log.warn("Invalid number format: {}", e.getMessage());
        return ResponseEntity.ok(ApiResponse.error(400, "Invalid ID format"));
    }

    /**
     * 处理请求体解析失败。
     * <p>空请求体或 JSON {@code null} / 格式错误时，{@code @RequestBody} 在进入 Controller
     * 之前即抛出 {@link HttpMessageNotReadableException}。属于客户端参数错误，返回 400 而非 500。
     *
     * @param e 请求体解析异常
     * @return 400 + 标准化 ApiResponse 错误结构
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Request body not readable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, "请求体不能为空或格式错误"));
    }

    /**
     * 兜底：处理所有未捕获的异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(500).body(ApiResponse.error(500, "Internal server error"));
    }
}
