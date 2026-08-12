package com.levango7.dataenginebdp.sqlgateway.crosssource;

/**
 * 跨源查询异常。
 *
 * <p>跨源归并引擎在解析、拆分、并行查询、内存归并过程中发生的可识别错误
 * 均包装为本异常向上抛出，便于 Controller 统一转换为 HTTP 错误响应。</p>
 *
 * <p>异常携带 {@code errorCode} 字段区分错误类别：</p>
 * <ul>
 *   <li>{@code PARSE_ERROR}     — SQL 解析失败或表名提取失败</li>
 *   <li>{@code SOURCE_NOT_FOUND} — 表对应的源未在 Catalog 中找到</li>
 *   <li>{@code QUERY_TIMEOUT}   — 单源查询超时</li>
 *   <li>{@code QUERY_FAILED}    — 单源查询执行失败</li>
 *   <li>{@code MERGE_ERROR}     — 内存归并（JOIN/UNION）失败</li>
 *   <li>{@code RESULT_TOO_LARGE} — 归并结果超过行数上限</li>
 *   <li>{@code UNSUPPORTED}     — 不支持的 SQL 形态或操作符</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public class CrossSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码：SQL 解析失败 */
    public static final String PARSE_ERROR = "PARSE_ERROR";
    /** 错误码：源未找到 */
    public static final String SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
    /** 错误码：查询超时 */
    public static final String QUERY_TIMEOUT = "QUERY_TIMEOUT";
    /** 错误码：查询失败 */
    public static final String QUERY_FAILED = "QUERY_FAILED";
    /** 错误码：归并失败 */
    public static final String MERGE_ERROR = "MERGE_ERROR";
    /** 错误码：结果集过大 */
    public static final String RESULT_TOO_LARGE = "RESULT_TOO_LARGE";
    /** 错误码：不支持的操作 */
    public static final String UNSUPPORTED = "UNSUPPORTED";

    private final String errorCode;

    /**
     * 构造异常。
     *
     * @param errorCode 错误码
     * @param message   错误信息
     */
    public CrossSourceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造异常（包含原因）。
     *
     * @param errorCode 错误码
     * @param message   错误信息
     * @param cause     原始异常
     */
    public CrossSourceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "CrossSourceException{" + "errorCode='" + errorCode + '\'' + ", message=" + getMessage() + '}';
    }
}