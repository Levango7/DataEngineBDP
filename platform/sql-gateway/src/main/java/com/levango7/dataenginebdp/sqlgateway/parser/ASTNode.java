package com.levango7.dataenginebdp.sqlgateway.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SQL 抽象语法树（AST）节点。
 *
 * <p>每个节点包含类型、子节点列表与属性映射（表名、列名、条件等）。
 * 节点不可变性由调用方在构建完成后保证；本类提供 {@link #extractTables()} 与
 * {@link #extractColumns()} 两个血缘分析辅助方法。</p>
 *
 * @author shuqing-bigdata
 */
public class ASTNode {

    /**
     * AST 节点类型。
     */
    public enum NodeType {
        /** 顶层语句 */
        STATEMENT,
        /** SELECT 查询 */
        SELECT,
        /** FROM 子句 */
        FROM,
        /** WHERE 子句 */
        WHERE,
        /** JOIN 子句 */
        JOIN,
        /** GROUP BY 子句 */
        GROUP_BY,
        /** HAVING 子句 */
        HAVING,
        /** ORDER BY 子句 */
        ORDER_BY,
        /** LIMIT 子句 */
        LIMIT,
        /** INSERT 语句 */
        INSERT,
        /** CREATE TABLE 语句 */
        CREATE_TABLE,
        /** DROP 语句 */
        DROP,
        /** ALTER 语句 */
        ALTER,
        /** DDL 通用 */
        DDL,
        /** DML 通用 */
        DML,
        /** UNION 查询 */
        UNION,
        /** 子查询 */
        SUBQUERY,
        /** 列引用 */
        COLUMN,
        /** 表引用 */
        TABLE,
        /** 字面量 */
        LITERAL,
        /** 表达式 */
        EXPRESSION,
        /** CTE（WITH 子句） */
        CTE
    }

    private final NodeType type;
    private final List<ASTNode> children;
    private final Map<String, Object> properties;

    /**
     * 构造节点。
     *
     * @param type 节点类型
     */
    public ASTNode(NodeType type) {
        this.type = Objects.requireNonNull(type, "type");
        this.children = new ArrayList<>();
        this.properties = new LinkedHashMap<>();
    }

    /**
     * 获取节点类型。
     *
     * @return 节点类型
     */
    public NodeType getType() {
        return type;
    }

    /**
     * 获取子节点列表（可变，便于构建期追加）。
     *
     * @return 子节点列表
     */
    public List<ASTNode> getChildren() {
        return children;
    }

    /**
     * 获取属性映射（可变，便于构建期写入）。
     *
     * @return 属性映射
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * 添加子节点并返回当前节点（链式调用）。
     *
     * @param child 子节点
     * @return 当前节点
     */
    public ASTNode addChild(ASTNode child) {
        if (child != null) {
            children.add(child);
        }
        return this;
    }

    /**
     * 写入属性并返回当前节点（链式调用）。
     *
     * @param key   属性键
     * @param value 属性值
     * @return 当前节点
     */
    public ASTNode setProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    /**
     * 读取字符串属性。
     *
     * @param key 属性键
     * @return 属性值；不存在返回 {@code null}
     */
    public String getString(String key) {
        Object v = properties.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * 读取字符串列表属性。
     *
     * @param key 属性键
     * @return 字符串列表；不存在返回空列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object v = properties.get(key);
        if (v instanceof List) {
            return (List<String>) v;
        }
        return Collections.emptyList();
    }

    /**
     * 提取 SQL 涉及的所有表名（含 INSERT 目标表、CREATE 表名等）。
     *
     * <p>遍历整棵 AST，收集 {@link NodeType#TABLE} 节点的 {@code name} 属性，
     * 以及 {@link NodeType#INSERT}/{@link NodeType#CREATE_TABLE} 节点的 {@code table} 属性。</p>
     *
     * @return 去重后的表名列表（保持插入顺序）
     */
    public List<String> extractTables() {
        Set<String> tables = new LinkedHashSet<>();
        collectTables(this, tables);
        return new ArrayList<>(tables);
    }

    private static void collectTables(ASTNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        // 表引用节点
        if (node.type == NodeType.TABLE) {
            String name = node.getString("name");
            if (name != null && !name.isEmpty()) {
                tables.add(name);
            }
        }
        // INSERT/CREATE_TABLE/DROP/ALTER 中显式 table 属性
        String tableAttr = node.getString("table");
        if (tableAttr != null && !tableAttr.isEmpty()) {
            tables.add(tableAttr);
        }
        for (ASTNode child : node.children) {
            collectTables(child, tables);
        }
    }

    /**
     * 提取 SQL 涉及的所有列名。
     *
     * <p>遍历整棵 AST，收集 {@link NodeType#COLUMN} 节点的 {@code name} 属性。</p>
     *
     * @return 去重后的列名列表（保持插入顺序）
     */
    public List<String> extractColumns() {
        Set<String> columns = new LinkedHashSet<>();
        collectColumns(this, columns);
        return new ArrayList<>(columns);
    }

    private static void collectColumns(ASTNode node, Set<String> columns) {
        if (node == null) {
            return;
        }
        if (node.type == NodeType.COLUMN) {
            String name = node.getString("name");
            if (name != null && !name.isEmpty()) {
                columns.add(name);
            }
        }
        for (ASTNode child : node.children) {
            collectColumns(child, columns);
        }
    }

    /**
     * 查找第一个指定类型的子节点。
     *
     * @param nodeType 节点类型
     * @return 子节点；不存在返回 {@code null}
     */
    public ASTNode findChild(NodeType nodeType) {
        for (ASTNode child : children) {
            if (child.type == nodeType) {
                return child;
            }
        }
        return null;
    }

    /**
     * 递归查找所有指定类型的后代节点。
     *
     * @param nodeType 节点类型
     * @return 后代节点列表
     */
    public List<ASTNode> findAll(NodeType nodeType) {
        List<ASTNode> result = new ArrayList<>();
        findAll(this, nodeType, result);
        return result;
    }

    private static void findAll(ASTNode node, NodeType nodeType, List<ASTNode> result) {
        if (node == null) {
            return;
        }
        if (node.type == nodeType) {
            result.add(node);
        }
        for (ASTNode child : node.children) {
            findAll(child, nodeType, result);
        }
    }

    @Override
    public String toString() {
        return toString(0);
    }

    private String toString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ".repeat(indent));
        sb.append(type);
        if (!properties.isEmpty()) {
            sb.append(properties);
        }
        for (ASTNode child : children) {
            sb.append('\n').append(child.toString(indent + 1));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ASTNode other)) {
            return false;
        }
        return type == other.type
                && Objects.equals(children, other.children)
                && Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, children, properties);
    }
}