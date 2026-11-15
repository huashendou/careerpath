package com.careerpath.common;

/**
 * 可预期的业务异常。
 *
 * <p>只用 {@link ErrorCode} 构造，避免各处随手写错误码导致契约漂移；
 * 需要补充细节时传 message，前端展示的仍是这层中文提示。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public ApiException(ErrorCode code, String message) {
        super(message == null || message.isBlank() ? code.defaultMessage() : message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}
