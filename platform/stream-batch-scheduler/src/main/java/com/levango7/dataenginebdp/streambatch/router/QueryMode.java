package com.levango7.dataenginebdp.streambatch.router;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 查询模式枚举（BI 自动选择视图的依据）。
 *
 * <p>BI 查询路由器根据查询模式决定使用批快照视图还是流最新视图：
 * <ul>
 *   <li>{@link #OFFLINE} — 离线/批查询，使用批快照视图（Spark 固定 snapshot）</li>
 *   <li>{@link #REALTIME} — 实时/流查询，使用流最新视图（Flink 最新 snapshot）</li>
 *   <li>{@link #AUTO} — 自动判断（根据查询延迟要求、数据新鲜度要求自动选择）</li>
 * </ul>
 */
public enum QueryMode {
    /** 离线/批查询（使用批快照视图）。 */
    OFFLINE("OFFLINE"),
    /** 实时/流查询（使用流最新视图）。 */
    REALTIME("REALTIME"),
    /** 自动判断。 */
    AUTO("AUTO");

    private final String code;

    QueryMode(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static QueryMode fromCode(String code) {
        for (QueryMode m : values()) {
            if (m.code.equalsIgnoreCase(code)) {
                return m;
            }
        }
        throw new IllegalArgumentException("未知的查询模式: " + code);
    }
}