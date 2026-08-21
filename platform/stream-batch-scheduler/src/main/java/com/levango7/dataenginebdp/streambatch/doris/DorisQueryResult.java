package com.levango7.dataenginebdp.streambatch.doris;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Doris OLAP 查询结果。
 *
 * <p>封装 Doris FE HTTP API 返回的结果集，含列名、行数据、耗时等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DorisQueryResult {

    /** 数据库名。 */
    private String database;

    /** SQL 语句。 */
    private String sql;

    /** 列名列表。 */
    private List<String> columnNames;

    /** 行数据（每行为 列名 → 值 的 Map，保持列顺序）。 */
    private List<Map<String, Object>> rows;

    /** 查询耗时（毫秒）。 */
    private long elapsedMs;

    /** 查询是否成功。 */
    private boolean success;

    /** 原始响应字符串（用于调试）。 */
    private String rawResponse;

    /**
     * 获取结果行数。
     *
     * @return 行数
     */
    public int getRowCount() {
        return rows != null ? rows.size() : 0;
    }
}