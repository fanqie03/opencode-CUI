package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应包装器。
 * 所有 REST 接口返回此格式，前端可统一解析。
 *
 * <p>
 * 约定：{@code code=0} 表示成功，非零表示错误。
 * </p>
 *
 * @param <T> 响应数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 响应码：0=成功，非零=错误 */
    private int code;

    /** 错误信息（成功时为空字符串） */
    private String errormsg;

    /** 响应数据（错误时为 null） */
    private T data;

    /**
     * 构建成功响应。
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return code=0 的成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "", data);
    }

    /**
     * 构建错误响应。
     *
     * @param code     错误码
     * @param errormsg 错误描述
     * @param <T>      数据类型
     * @return 不含 data 的错误响应
     */
    public static <T> ApiResponse<T> error(int code, String errormsg) {
        return new ApiResponse<>(code, errormsg, null);
    }
}
