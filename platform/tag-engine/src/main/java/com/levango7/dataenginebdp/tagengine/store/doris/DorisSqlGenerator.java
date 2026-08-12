package com.levango7.dataenginebdp.tagengine.store.doris;

import com.levango7.dataenginebdp.tagengine.model.TagQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Doris SQL 生成器。
 *
 * <p>将标签查询条件 {@link TagQuery} 翻译为参数化 SQL，避免 SQL 注入。
 * 对应详细设计 §5 人群圈选、§9 风险与对策（DSL 白名单 + SQL 模板化）。</p>
 *
 * <p>支持的白名单运算符：=、!=、&gt;、&gt;=、&lt;、&lt;=、IN、NOT IN、BETWEEN、IS NULL、IS NOT NULL、LIKE。</p>
 */
@Component
public class DorisSqlGenerator {

    /** 运算符白名单 */
    private static final List<String> ALLOWED_OPS = List.of(
            "=", "!=", ">", ">=", "<", "<=", "IN", "NOT IN", "BETWEEN", "IS NULL", "IS NOT NULL", "LIKE"
    );

    /**
     * 生成圈选 SQL（返回 user_id 列表）。
     *
     * @param wideTable  宽表名
     * @param query      查询条件
     * @param limit      最大返回行数
     * @param offset     偏移量
     * @return 参数化 SQL（? 占位）与参数列表
     */
    public PreparedSql buildSelectSql(String wideTable, TagQuery query, int limit, int offset) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        sql.append("SELECT user_id FROM ").append(quote(wideTable));
        appendWhere(sql, params, query);
        sql.append(" ORDER BY user_id");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        if (offset > 0) {
            sql.append(" OFFSET ").append(offset);
        }
        return new PreparedSql(sql.toString(), params);
    }

    /**
     * 生成圈选计数 SQL。
     *
     * @param wideTable 宽表名
     * @param query     查询条件
     * @return 参数化 SQL 与参数列表
     */
    public PreparedSql buildCountSql(String wideTable, TagQuery query) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        sql.append("SELECT COUNT(*) AS cnt FROM ").append(quote(wideTable));
        appendWhere(sql, params, query);
        return new PreparedSql(sql.toString(), params);
    }

    /**
     * 生成单用户画像查询 SQL。
     *
     * @param wideTable 宽表名
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @return 参数化 SQL 与参数列表
     */
    public PreparedSql buildProfileSql(String wideTable, String tenantId, String userId) {
        String sql = "SELECT * FROM " + quote(wideTable)
                + " WHERE tenant_id = ? AND user_id = ?";
        return new PreparedSql(sql, List.of(tenantId, userId));
    }

    /**
     * 拼接 WHERE 子句（递归处理 nested）。
     */
    private void appendWhere(StringBuilder sql, List<Object> params, TagQuery query) {
        if (query == null) {
            return;
        }
        List<String> fragments = new ArrayList<>();

        // tenant_id 强制注入（防越权）
        if (query.getTenantId() != null) {
            fragments.add("tenant_id = ?");
            params.add(query.getTenantId());
        }

        // 顶层条件
        if (query.getConditions() != null) {
            for (TagQuery.Condition c : query.getConditions()) {
                PreparedSql ps = buildConditionFragment(c);
                fragments.add(ps.sql());
                params.addAll(ps.params());
            }
        }

        // 嵌套子查询
        if (query.getNested() != null) {
            for (TagQuery q : query.getNested()) {
                StringBuilder sub = new StringBuilder("(");
                List<Object> subParams = new ArrayList<>();
                appendWhere(sub, subParams, q);
                sub.append(")");
                if (sub.length() > 2) {
                    fragments.add(sub.toString());
                    params.addAll(subParams);
                }
            }
        }

        if (fragments.isEmpty()) {
            return;
        }
        String logic = (query.getLogic() == null ? "AND" : query.getLogic().toUpperCase());
        sql.append(" WHERE ").append(String.join(" " + logic + " ", fragments));
    }

    /**
     * 构建单条件片段（参数化）。
     */
    private PreparedSql buildConditionFragment(TagQuery.Condition c) {
        String col = validateColumn(c.getColumnName());
        String op = validateOp(c.getOp());
        Object val = c.getValue();

        return switch (op) {
            case "IS NULL" -> new PreparedSql(col + " IS NULL", List.of());
            case "IS NOT NULL" -> new PreparedSql(col + " IS NOT NULL", List.of());
            case "IN", "NOT IN" -> {
                if (!(val instanceof List<?> list) || list.isEmpty()) {
                    throw new IllegalArgumentException("op " + op + " requires non-empty List value");
                }
                String placeholders = String.join(",", list.stream().map(x -> "?").toList());
                yield new PreparedSql(col + " " + op + " (" + placeholders + ")", new ArrayList<>(list));
            }
            case "BETWEEN" -> {
                if (!(val instanceof List<?> list) || list.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN requires List[lo, hi]");
                }
                yield new PreparedSql(col + " BETWEEN ? AND ?", new ArrayList<>(list));
            }
            default -> new PreparedSql(col + " " + op + " ?", List.of(val));
        };
    }

    /**
     * 校验列名：仅允许字母/数字/下划线，防注入。
     */
    private String validateColumn(String col) {
        if (col == null || !col.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("invalid column name: " + col);
        }
        return col;
    }

    /**
     * 校验运算符：白名单匹配。
     */
    private String validateOp(String op) {
        String normalized = op == null ? "=" : op.toUpperCase();
        if (!ALLOWED_OPS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported op: " + op);
        }
        return normalized;
    }

    /**
     * 引用标识符（Doris 反引号）。
     */
    private String quote(String ident) {
        Objects.requireNonNull(ident, "identifier must not be null");
        return "`" + ident.replace("`", "") + "`";
    }

    /**
     * 参数化 SQL 与参数列表。
     *
     * @param sql    带 ? 占位符的 SQL
     * @param params 按顺序绑定的参数
     */
    public record PreparedSql(String sql, List<Object> params) {
    }
}