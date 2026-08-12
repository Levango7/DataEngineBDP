package com.levango7.dataenginebdp.sqlgateway.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * SQL 解析响应 POJO。
 *
 * <p>包含 AST 类型、属性、子节点结构，以及提取的表名与列名列表。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class SqlParseResponse {

    /**
     * 检测/指定的方言。
     */
    private String dialect;

    /**
     * 顶层语句类型。
     */
    private String statementType;

    /**
     * AST 根节点的属性映射。
     */
    private Map<String, Object> properties;

    /**
     * AST 子节点结构（递归 JSON）。
     */
    private List<Map<String, Object>> children;

    /**
     * SQL 涉及的所有表名。
     */
    private List<String> tables;

    /**
     * SQL 涉及的所有列名。
     */
    private List<String> columns;
}