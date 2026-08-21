package com.shuqing.bigdata.sqlgateway.virtual.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 谓词解析器：将字符串谓词转换为参数化 SQL 片段与参数列表。
 *
 * <p>本类用于修复 SQL 注入漏洞：原 {@link JdbcVirtualAdapter} 将 predicate 字符串
 * 直接拼接进 WHERE 子句，存在 SQL 注入风险。本解析器将谓词拆分为
 * "列名 + 操作符 + 占位符" 的结构化形式，值通过 {@link java.sql.PreparedStatement}
 * 参数绑定传递，从根本上消除注入面。</p>
 *
 * <p><b>支持的谓词语法：</b></p>
 * <ul>
 *   <li>等值：{@code col = value}</li>
 *   <li>不等：{@code col != value}、{@code col <> value}</li>
 *   <li>范围：{@code col > value}、{@code col < value}、{@code col >= value}、{@code col <= value}</li>
 *   <li>模糊：{@code col LIKE 'pattern'}</li>
 *   <li>枚举：{@code col IN (v1, v2, ...)}</li>
 *   <li>空值：{@code col IS NULL}、{@code col IS NOT NULL}</li>
 *   <li>组合：多个原子谓词以 {@code AND} / {@code OR} 连接</li>
 * </ul>
 *
 * <p><b>列名白名单：</b>仅允许 {@code [a-zA-Z_][a-zA-Z0-9_]*}（可选点号分隔的限定名，
 * 如 {@code schema.col}），禁止任何 SQL 元字符。</p>
 *
 * <p><b>值类型：</b>数字字面量、单/双引号字符串、布尔字面量、NULL 关键字。</p>
 *
 * <p>本类线程安全（仅使用静态正则与局部变量）。</p>
 *
 * @author shuqing-bigdata
 */
public final class PredicateParser {

    private PredicateParser() {
    }

    /** 列名白名单：字母/下划线开头，可含字母数字下划线与点号（schema.column）。 */
    private static final Pattern COLUMN_PATTERN =
            Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.]*");

    /** AND 连接符（大小写不敏感，前后需空白）。 */
    private static final Pattern AND_PATTERN =
            Pattern.compile("\\s+AND\\s+", Pattern.CASE_INSENSITIVE);

    /** OR 连接符（大小写不敏感，前后需空白）。 */
    private static final Pattern OR_PATTERN =
            Pattern.compile("\\s+OR\\s+", Pattern.CASE_INSENSITIVE);

    /** IS NULL 谓词。 */
    private static final Pattern IS_NULL_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_.]*)\\s+IS\\s+NULL$",
                    Pattern.CASE_INSENSITIVE);

    /** IS NOT NULL 谓词。 */
    private static final Pattern IS_NOT_NULL_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_.]*)\\s+IS\\s+NOT\\s+NULL$",
                    Pattern.CASE_INSENSITIVE);

    /** LIKE 谓词：col LIKE 'pattern' 或 col LIKE "pattern"。 */
    private static final Pattern LIKE_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_.]*)\\s+LIKE\\s+(['\"])([^'\"]*)\\2$",
                    Pattern.CASE_INSENSITIVE);

    /** IN 谓词：col IN (v1, v2, ...)。 */
    private static final Pattern IN_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_.]*)\\s+IN\\s*\\(([^)]+)\\)$",
                    Pattern.CASE_INSENSITIVE);

    /** 比较谓词：col OP value，OP ∈ {=, !=, <>, >=, <=, >, <}。 */
    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_.]*)\\s*(>=|<=|!=|<>|=|>|<)\\s*(.+)$");

    /**
     * 解析谓词字符串为参数化 SQL 片段与参数列表。
     *
     * @param predicate 谓词字符串（如 {@code "id > 100 AND name = 'alice'"}）；
     *                  {@code null} 或空白返回空结果
     * @return 解析结果，包含带 {@code ?} 占位符的 SQL 片段与按序排列的参数值
     * @throws VirtualAdapterException 若谓词语法非法或包含禁用字符
     */
    public static ParsedPredicate parse(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return new ParsedPredicate("", List.of());
        }
        String trimmed = predicate.trim();
        List<Object> params = new ArrayList<>();
        String sqlFragment = parseOr(trimmed, params);
        return new ParsedPredicate(sqlFragment, List.copyOf(params));
    }

    /**
     * 解析 OR 连接的谓词。
     */
    private static String parseOr(String predicate, List<Object> params) {
        String[] orParts = splitByTopLevel(predicate, OR_PATTERN);
        if (orParts.length == 1) {
            return parseAnd(predicate, params);
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < orParts.length; i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append(parseAnd(orParts[i].trim(), params));
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 解析 AND 连接的谓词。
     */
    private static String parseAnd(String predicate, List<Object> params) {
        String[] andParts = splitByTopLevel(predicate, AND_PATTERN);
        if (andParts.length == 1) {
            return parseAtomic(predicate.trim(), params);
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < andParts.length; i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append(parseAtomic(andParts[i].trim(), params));
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 解析原子谓词（不可再分）。
     */
    private static String parseAtomic(String atom, List<Object> params) {
        if (atom.isEmpty()) {
            throw new VirtualAdapterException("PREDICATE_INVALID",
                    "谓词包含空子表达式", null);
        }

        // 处理外层括号：(expr)
        if (atom.charAt(0) == '(' && atom.charAt(atom.length() - 1) == ')') {
            return parseOr(atom.substring(1, atom.length() - 1).trim(), params);
        }

        // IS NOT NULL
        Matcher isNotNull = IS_NOT_NULL_PATTERN.matcher(atom);
        if (isNotNull.matches()) {
            return isNotNull.group(1) + " IS NOT NULL";
        }

        // IS NULL
        Matcher isNull = IS_NULL_PATTERN.matcher(atom);
        if (isNull.matches()) {
            return isNull.group(1) + " IS NULL";
        }

        // LIKE
        Matcher like = LIKE_PATTERN.matcher(atom);
        if (like.matches()) {
            String column = like.group(1);
            String patternValue = like.group(3);
            params.add(patternValue);
            return column + " LIKE ?";
        }

        // IN
        Matcher in = IN_PATTERN.matcher(atom);
        if (in.matches()) {
            String column = in.group(1);
            String valueList = in.group(2);
            return parseIn(column, valueList, params);
        }

        // 比较谓词 (=, !=, <>, >=, <=, >, <)
        Matcher comparison = COMPARISON_PATTERN.matcher(atom);
        if (comparison.matches()) {
            String column = comparison.group(1);
            String operator = comparison.group(2);
            String rawValue = comparison.group(3).trim();
            Object value = parseValue(rawValue);
            params.add(value);
            return column + " " + operator + " ?";
        }

        throw new VirtualAdapterException("PREDICATE_INVALID",
                "无法解析的谓词表达式: " + atom, null);
    }

    /**
     * 解析 IN 谓词的值列表。
     */
    private static String parseIn(String column, String valueList, List<Object> params) {
        String[] values = valueList.split(",");
        StringBuilder sb = new StringBuilder(column).append(" IN (");
        for (int i = 0; i < values.length; i++) {
            String v = values[i].trim();
            if (v.isEmpty()) {
                throw new VirtualAdapterException("PREDICATE_INVALID",
                        "IN 谓词包含空值: " + valueList, null);
            }
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
            params.add(parseValue(v));
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 解析单个值字面量为 Java 对象。
     *
     * <p>支持：</p>
     * <ul>
     *   <li>单/双引号字符串：去引号后返回 String</li>
     *   <li>NULL 关键字：返回 {@code null}</li>
     *   <li>布尔：true/false 返回 Boolean</li>
     *   <li>整数：返回 Long</li>
     *   <li>浮点：返回 Double</li>
     * </ul>
     */
    private static Object parseValue(String raw) {
        if (raw.isEmpty()) {
            throw new VirtualAdapterException("PREDICATE_INVALID",
                    "谓词值为空", null);
        }

        // 字符串字面量
        char first = raw.charAt(0);
        char last = raw.charAt(raw.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return raw.substring(1, raw.length() - 1);
        }

        // NULL 关键字
        if (raw.equalsIgnoreCase("NULL")) {
            return null;
        }

        // 布尔
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }

        // 数值
        try {
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new VirtualAdapterException("PREDICATE_INVALID",
                    "无法识别的值字面量（未加引号的字符串需用引号包裹）: " + raw, null);
        }
    }

    /**
     * 按顶层连接符切分谓词（不切分括号内的子表达式）。
     *
     * <p>扫描所有连接符匹配，仅保留位于括号深度 0（顶层）的匹配作为切分点。</p>
     *
     * @param predicate 谓词字符串
     * @param connector 连接符正则（AND 或 OR）
     * @return 切分后的子谓词数组
     */
    private static String[] splitByTopLevel(String predicate, Pattern connector) {
        // 预计算每个位置的括号深度
        int[] depthAt = new int[predicate.length()];
        int depth = 0;
        for (int i = 0; i < predicate.length(); i++) {
            char c = predicate.charAt(i);
            depthAt[i] = depth;
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }

        // 找出所有位于顶层的连接符匹配
        List<int[]> connectorSpans = new ArrayList<>();
        Matcher m = connector.matcher(predicate);
        while (m.find()) {
            if (depthAt[m.start()] == 0) {
                connectorSpans.add(new int[]{m.start(), m.end()});
            }
        }

        if (connectorSpans.isEmpty()) {
            return new String[]{predicate};
        }

        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int[] span : connectorSpans) {
            parts.add(predicate.substring(start, span[0]));
            start = span[1];
        }
        parts.add(predicate.substring(start));
        return parts.toArray(new String[0]);
    }

    /**
     * 校验表名/列名是否合法（仅允许白名单字符）。
     *
     * @param identifier 待校验标识符
     * @throws VirtualAdapterException 若包含非法字符
     */
    public static void validateIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new VirtualAdapterException("IDENTIFIER_INVALID",
                    "标识符为空", null);
        }
        if (!COLUMN_PATTERN.matcher(identifier).matches()) {
            throw new VirtualAdapterException("IDENTIFIER_INVALID",
                    "标识符包含非法字符（仅允许字母、数字、下划线、点号）: " + identifier, null);
        }
    }

    /**
     * 解析结果。
     *
     * @param sqlFragment 带 {@code ?} 占位符的 SQL 片段（如 {@code "id > ? AND name = ?"}）；
     *                    空字符串表示无谓词
     * @param parameters  按占位符顺序排列的参数值列表
     */
    public record ParsedPredicate(String sqlFragment, List<Object> parameters) {

        /**
         * 判断是否存在有效谓词。
         *
         * @return {@code true} 表示有谓词片段
         */
        public boolean hasPredicate() {
            return sqlFragment != null && !sqlFragment.isEmpty();
        }
    }
}