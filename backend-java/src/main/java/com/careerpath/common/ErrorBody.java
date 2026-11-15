package com.careerpath.common;

/**
 * 错误响应体。
 *
 * <p>序列化结果为 {@code {"detail":{"code":"...","message":"..."}}}，与 Python 分析服务完全一致，
 * 前端与 Java 的 internal client 都只需一套解析逻辑。
 */
public record ErrorBody(Detail detail) {

    public record Detail(String code, String message) {
    }

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(new Detail(code, message));
    }
}
