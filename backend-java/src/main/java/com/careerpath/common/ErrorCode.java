package com.careerpath.common;

/**
 * 全项目错误码。
 *
 * <p>与前端约定：HTTP 状态码照常设置，业务分支一律看 {@code detail.code} 字符串——
 * 字符串自带语义，比数字码表更难写错。新增错误码前先改 {@code docs/协作看板.md} 契约。
 */
public enum ErrorCode {

    /** 参数校验失败 */
    VALIDATION_ERROR(422, "参数校验失败"),
    /** 未登录或令牌失效 */
    UNAUTHORIZED(401, "未登录或登录已失效"),
    /** 已登录但无权访问该资源 */
    FORBIDDEN(403, "无权访问该资源"),
    /** 资源不存在；越权访问他人资源时同样返回它，不泄露存在性 */
    NOT_FOUND(404, "资源不存在"),
    /** 用户名已被占用 */
    USERNAME_TAKEN(409, "用户名已存在"),
    /** 登录凭据错误 */
    INVALID_CREDENTIALS(401, "用户名或密码不正确"),
    /** 唯一约束冲突 */
    DUPLICATE_RESOURCE(409, "记录已存在"),
    /** 引用了不存在的技能 id */
    UNKNOWN_SKILL(422, "技能不存在"),
    /** 分析服务不可用 */
    ANALYSIS_UNAVAILABLE(503, "分析服务暂不可用，请稍后重试"),
    /** 分析服务返回了非预期结果 */
    ANALYSIS_FAILED(502, "分析服务返回异常"),
    /** 兜底 */
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int status;
    private final String defaultMessage;

    ErrorCode(int status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public int status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
