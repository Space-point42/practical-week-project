package cn.edu.njupt.kms.common;

import lombok.Data;

/**
 * 统一响应包装。
 *
 * <p>约定：{@code code == 0} 表示成功，非 0 为业务/系统错误码，
 * 前端仅需判断 {@code code} 与 {@code message}。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        ApiResponse<T> r = ok(data);
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
