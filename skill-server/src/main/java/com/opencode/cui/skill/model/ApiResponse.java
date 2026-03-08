package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified API response wrapper.
 * All REST API responses are wrapped in this format:
 * {@code {"code": 0, "errormsg": null, "data": {...}}}
 *
 * @param <T> the type of the response data
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 0 = success, non-zero = error code */
    private int code;

    /** Error message (null on success) */
    private String errormsg;

    /** Response data (null on error) */
    private T data;

    /**
     * Create a success response with data.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .code(0)
                .data(data)
                .build();
    }

    /**
     * Create an error response.
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .errormsg(message)
                .build();
    }
}
