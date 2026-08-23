package com.levango7.dataenginebdp.sqlgateway.calcite.join;

/**
 * Join 类型枚举。
 */
public enum JoinType {
    /** 内连接 */
    INNER("INNER JOIN"),
    /** 左外连接 */
    LEFT("LEFT OUTER JOIN"),
    /** 右外连接 */
    RIGHT("RIGHT OUTER JOIN"),
    /** 全外连接 */
    FULL("FULL OUTER JOIN"),
    /** 半连接（仅输出左行，若右有匹配） */
    SEMI("LEFT SEMI JOIN"),
    /** 反连接（仅输出左行，若右无匹配） */
    ANTI("LEFT ANTI JOIN");

    private final String sql;

    JoinType(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    /** 是否为外连接 */
    public boolean isOuter() {
        return this == LEFT || this == RIGHT || this == FULL;
    }
}
