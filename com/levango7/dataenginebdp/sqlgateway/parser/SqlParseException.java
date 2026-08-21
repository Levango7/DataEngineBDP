package com.shuqing.bigdata.sqlgateway.parser;

/**
 * SQL 解析异常。
 *
 * <p>在 SQL 词法或语法解析失败时抛出，包含错误位置与原因信息。</p>
 *
 * @author shuqing-bigdata
 */
public class SqlParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误位置（字符偏移），{@code -1} 表示未知。
     */
    private final int position;

    /**
     * 构造异常。
     *
     * @param message 错误信息
     */
    public SqlParseException(String message) {
        super(message);
        this.position = -1;
    }

    /**
     * 构造异常。
     *
     * @param message  错误信息
     * @param position 错误位置
     */
    public SqlParseException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    /**
     * 构造异常。
     *
     * @param message 错误信息
     * @param cause   根因
     */
    public SqlParseException(String message, Throwable cause) {
        super(message, cause);
        this.position = -1;
    }

    /**
     * 构造异常。
     *
     * @param message  错误信息
     * @param position 错误位置
     * @param cause    根因
     */
    public SqlParseException(String message, int position, Throwable cause) {
        super(message + " at position " + position, cause);
        this.position = position;
    }

    /**
     * 获取错误位置。
     *
     * @return 字符偏移；{@code -1} 表示未知
     */
    public int getPosition() {
        return position;
    }
}