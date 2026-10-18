package cn.edu.njupt.kms.common;

/**
 * 业务错误码字典（与前端约定，便于前端做差异化提示）。
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    /** 成功 */
    public static final int OK = 0;
    /** 参数错误 */
    public static final int BAD_REQUEST = 400;
    /** 未认证 */
    public static final int UNAUTHORIZED = 401;
    /** 无权限 */
    public static final int FORBIDDEN = 403;
    /** 资源不存在 */
    public static final int NOT_FOUND = 404;
    /** 状态冲突（如非法状态流转） */
    public static final int CONFLICT = 409;
    /** 通用业务错误 */
    public static final int BIZ_ERROR = 1000;
    /** 文件上传相关错误 */
    public static final int FILE_ERROR = 1001;
    /** 检索相关错误 */
    public static final int SEARCH_ERROR = 1002;
    /** RAG 相关错误 */
    public static final int RAG_ERROR = 1003;
    /** 服务器内部错误 */
    public static final int SERVER_ERROR = 500;
}
