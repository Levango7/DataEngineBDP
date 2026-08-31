package com.levango7.dataenginebdp.ruleengine.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内置数据质量规则模板库（六大质量维度 × 11 种模板）。
 *
 * <p>背景：此前 {@code Rule.expression} 为自由文本，非 SQL 规则只能走
 * {@code QualityCheckExecutionService} 的降级猜测（threshold/severity 形式判断），
 * 业务人员想检查"手机号非空"必须手写 SQL。本模板库把常用质量维度固化为
 * 参数化模板，前端传 {@code templateId + 目标表 + 目标列 + 参数} 即可生成
 * 可执行 SQL，走 DqRuleExecutor 真实校验路径。</p>
 *
 * <p>六大维度（GB/T 36344 对齐）：</p>
 * <ul>
 *   <li><b>完整性</b> completeness：非空检查 / 主键非空 / 行数波动</li>
 *   <li><b>唯一性</b> uniqueness：重复行检查（单列/组合列）</li>
 *   <li><b>准确性</b> accuracy：正则格式 / 值域范围 / 条件非空</li>
 *   <li><b>一致性</b> consistency：参照表外键检查</li>
 *   <li><b>时效性</b> timeliness：更新延迟检查</li>
 *   <li><b>有效性</b> validity：枚举白名单 / 黑名单</li>
 * </ul>
 *
 * <p>安全：表名/列名经 {@link #validateIdentifier} 白名单校验
 * （{@code [A-Za-z_][A-Za-z0-9_]*}，支持 schema.table 点分），杜绝 SQL 注入；
 * 模板 SQL 全部为违规计数形态，count=0 PASS / count&gt;0 FAIL，
 * 与 {@link DqRuleExecutor} 既有语义一致。</p>
 */
public final class BuiltinRuleTemplates {

    /** 合法标识符：字母/下划线开头，可含数字；两级 schema.table。 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    /** 模板实例化异常（参数非法/模板不存在）。 */
    public static class TemplateException extends RuntimeException {
        public TemplateException(String message) {
            super(message);
        }
    }

    /** 模板定义（不可变）。 */
    public record Template(
            String id,
            String name,
            String dimension,
            String description,
            List<String> requiredParams,
            List<String> optionalParams) {
    }

    private BuiltinRuleTemplates() {
        // 工具类禁止实例化
    }

    /** 全部模板（按维度分组，LinkedHashMap 保序）。 */
    private static final Map<String, Template> TEMPLATES = buildTemplates();

    private static Map<String, Template> buildTemplates() {
        Map<String, Template> m = new LinkedHashMap<>();

        // ---- 完整性 completeness ----
        m.put("not_null", new Template("not_null", "非空检查", "completeness",
                "目标列不允许 NULL（可选同时视为空字符串），统计违规行数",
                List.of(), List.of("includeEmpty")));
        m.put("pk_not_null", new Template("pk_not_null", "主键完整性", "completeness",
                "主键列不允许 NULL，主键为空将导致数据无法定位",
                List.of(), List.of()));
        m.put("row_count_range", new Template("row_count_range", "行数波动检查", "completeness",
                "表总行数须落在 [min,max] 区间，过低/过高提示数据缺失或重复导入",
                List.of("minCount", "maxCount"), List.of()));

        // ---- 唯一性 uniqueness ----
        m.put("unique", new Template("unique", "唯一性检查", "uniqueness",
                "目标列（或组合列）不得有重复值，统计重复组数",
                List.of(), List.of("columns")));

        // ---- 准确性 accuracy ----
        m.put("regex_match", new Template("regex_match", "正则格式检查", "accuracy",
                "目标列值必须匹配给定正则（如手机号/邮箱/身份证）",
                List.of("regex"), List.of()));
        m.put("value_range", new Template("value_range", "值域范围检查", "accuracy",
                "数值列值须落在 [min,max] 区间（闭区间）",
                List.of("minValue", "maxValue"), List.of()));
        m.put("not_null_if", new Template("not_null_if", "条件非空检查", "accuracy",
                "当条件列等于给定值时，目标列必须非空（依赖字段联动校验）",
                List.of("conditionColumn", "conditionValue"), List.of()));

        // ---- 一致性 consistency ----
        m.put("fk_reference", new Template("fk_reference", "参照完整性检查", "consistency",
                "目标列值必须存在于参照表的参照列中（孤儿记录检查）",
                List.of("refTable", "refColumn"), List.of()));

        // ---- 时效性 timeliness ----
        m.put("freshness", new Template("freshness", "数据新鲜度检查", "timeliness",
                "目标时间列的最近更新距今不得超过 maxHours 小时",
                List.of("maxHours"), List.of()));

        // ---- 有效性 validity ----
        m.put("enum_whitelist", new Template("enum_whitelist", "枚举白名单检查", "validity",
                "目标列值必须属于给定枚举集合（逗号分隔）",
                List.of("allowedValues"), List.of()));
        m.put("enum_blacklist", new Template("enum_blacklist", "枚举黑名单检查", "validity",
                "目标列值不得属于给定枚举集合（逗号分隔）",
                List.of("forbiddenValues"), List.of()));

        return Collections.unmodifiableMap(m);
    }

    /**
     * 列出全部模板定义（id/name/dimension/description/参数清单/示例SQL）。
     *
     * @return 模板视图列表（有序）
     */
    public static List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Template t : TEMPLATES.values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", t.id());
            v.put("name", t.name());
            v.put("dimension", t.dimension());
            v.put("description", t.description());
            v.put("requiredParams", t.requiredParams());
            v.put("optionalParams", t.optionalParams());
            v.put("sqlExample", render(t.id(), "my_schema.my_table", "my_column", exampleParams(t)));
            views.add(v);
        }
        return views;
    }

    /**
     * 实例化模板为可执行 SQL。
     *
     * @param templateId  模板 ID（not_null/unique/...）
     * @param targetTable 目标表（必须通过标识符白名单）
     * @param targetColumn 目标列（必须通过标识符白名单；row_count_range 场景可空）
     * @param params      模板参数（正则/值域/枚举等；数值与枚举值做转义校验）
     * @return 形如 "sql:SELECT COUNT(*) ..." 的规则表达式（可直接存入 Rule.expression
     *         走 DqRuleExecutor 真实执行路径）
     * @throws TemplateException 模板不存在或参数非法
     */
    public static String renderSql(String templateId, String targetTable,
                                   String targetColumn, Map<String, String> params) {
        Template t = TEMPLATES.get(templateId);
        if (t == null) {
            throw new TemplateException("未知模板: " + templateId + "（可用: " + TEMPLATES.keySet() + "）");
        }
        validateIdentifier(targetTable, "targetTable");
        if (requiresTargetColumn(templateId)) {
            validateIdentifier(targetColumn, "targetColumn");
        }
        Map<String, String> p = params == null ? Map.of() : params;
        for (String req : t.requiredParams()) {
            if (!p.containsKey(req) || p.get(req) == null || p.get(req).isBlank()) {
                throw new TemplateException("模板 " + templateId + " 缺少必填参数: " + req);
            }
        }
        return render(templateId, targetTable, targetColumn, p);
    }

    private static boolean requiresTargetColumn(String templateId) {
        // row_count_range 只看整表行数，不需要列
        return !"row_count_range".equals(templateId);
    }

    private static String render(String templateId, String table, String column, Map<String, String> p) {
        boolean includeEmpty = "true".equalsIgnoreCase(p.getOrDefault("includeEmpty", "false"));
        return switch (templateId) {
            // 完整性
            case "not_null" -> includeEmpty
                    ? sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column) + " IS NULL OR TRIM(CAST(" + q(column) + " AS VARCHAR(512))) = ''")
                    : sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column) + " IS NULL");
            case "pk_not_null" ->
                    sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column) + " IS NULL");
            case "row_count_range" -> {
                long min = parseLong(p.get("minCount"), "minCount");
                long max = parseLong(p.get("maxCount"), "maxCount");
                if (min < 0 || max < min) {
                    throw new TemplateException("行数区间非法: min=" + min + ", max=" + max);
                }
                yield sql("SELECT CASE WHEN cnt < " + min + " OR cnt > " + max + " THEN 1 ELSE 0 END FROM (SELECT COUNT(*) AS cnt FROM " + table + ") t");
            }

            // 唯一性
            case "unique" -> {
                String cols = p.get("columns");
                String target = (cols == null || cols.isBlank())
                        ? q(column)
                        : joinQuotedColumns(cols);
                yield sql("SELECT COUNT(*) FROM (SELECT " + target + " FROM " + table
                        + " WHERE " + target + " IS NOT NULL GROUP BY " + target
                        + " HAVING COUNT(*) > 1) dup");
            }

            // 准确性
            case "regex_match" ->
                    sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column)
                            + " IS NOT NULL AND NOT REGEXP_LIKE(CAST(" + q(column) + " AS VARCHAR(2048)), " + strLiteral(p.get("regex")) + ")");
            case "value_range" -> {
                double min = parseDouble(p.get("minValue"), "minValue");
                double max = parseDouble(p.get("maxValue"), "maxValue");
                if (max < min) {
                    throw new TemplateException("值域区间非法: min=" + min + " > max=" + max);
                }
                yield sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column)
                        + " IS NOT NULL AND (CAST(" + q(column) + " AS DOUBLE) < " + min
                        + " OR CAST(" + q(column) + " AS DOUBLE) > " + max + ")");
            }
            case "not_null_if" -> {
                validateIdentifier(p.get("conditionColumn"), "conditionColumn");
                yield sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(p.get("conditionColumn"))
                        + " = " + strLiteral(p.get("conditionValue"))
                        + " AND " + q(column) + " IS NULL");
            }

            // 一致性
            case "fk_reference" -> {
                validateIdentifier(p.get("refTable"), "refTable");
                validateIdentifier(p.get("refColumn"), "refColumn");
                yield sql("SELECT COUNT(*) FROM " + table + " t LEFT JOIN " + p.get("refTable")
                        + " r ON t." + q(column) + " = r." + q(p.get("refColumn"))
                        + " WHERE t." + q(column) + " IS NOT NULL AND r." + q(p.get("refColumn")) + " IS NULL");
            }

            // 时效性
            case "freshness" -> {
                long hours = parseLong(p.get("maxHours"), "maxHours");
                if (hours <= 0) {
                    throw new TemplateException("maxHours 必须为正整数: " + hours);
                }
                yield sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column)
                        + " IS NOT NULL AND " + q(column) + " < CURRENT_TIMESTAMP - INTERVAL '" + hours + "' HOUR");
            }

            // 有效性
            case "enum_whitelist" ->
                    sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column)
                            + " IS NOT NULL AND " + q(column) + " NOT IN (" + enumLiterals(p.get("allowedValues")) + ")");
            case "enum_blacklist" ->
                    sql("SELECT COUNT(*) FROM " + table + " WHERE " + q(column)
                            + " IS NOT NULL AND " + q(column) + " IN (" + enumLiterals(p.get("forbiddenValues")) + ")");

            default -> throw new TemplateException("模板未实现渲染: " + templateId);
        };
    }

    /** 统一加 sql: 前缀（QualityCheckExecutionService 的 SQL 规则识别约定）。 */
    private static String sql(String s) {
        return "sql:" + s;
    }

    /** 列名按平台约定加双引号（保留大小写），已校验过白名单。 */
    private static String q(String identifier) {
        return "\"" + identifier + "\"";
    }

    /** 组合唯一键列（逗号分隔多个标识符）逐个校验后拼装。 */
    private static String joinQuotedColumns(String columns) {
        String[] parts = columns.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String c = parts[i].trim();
            validateIdentifier(c, "columns");
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(q(c));
        }
        if (sb.isEmpty()) {
            throw new TemplateException("columns 参数为空");
        }
        return sb.toString();
    }

    /** 字符串字面量：单引号转义防注入。 */
    private static String strLiteral(String v) {
        if (v == null) {
            throw new TemplateException("字符串参数为 null");
        }
        return "'" + v.replace("'", "''") + "'";
    }

    /** 枚举集合：逐项做字符串字面量转义。 */
    private static String enumLiterals(String csv) {
        String[] items = csv.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            String item = items[i].trim();
            if (item.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(strLiteral(item));
        }
        if (sb.isEmpty()) {
            throw new TemplateException("枚举参数为空: " + csv);
        }
        return sb.toString();
    }

    /** 标识符白名单：防 SQL 注入。 */
    private static void validateIdentifier(String id, String field) {
        if (id == null || id.isBlank() || !IDENTIFIER.matcher(id).matches()) {
            throw new TemplateException(field + " 非法标识符: " + id
                    + "（仅允许 [A-Za-z_][A-Za-z0-9_]*，表可含一级 schema 前缀）");
        }
    }

    private static long parseLong(String v, String field) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            throw new TemplateException(field + " 必须为整数: " + v);
        }
    }

    private static double parseDouble(String v, String field) {
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            throw new TemplateException(field + " 必须为数值: " + v);
        }
    }

    /** listTemplates 示例 SQL 用的示例参数。 */
    private static Map<String, String> exampleParams(Template t) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String req : t.requiredParams()) {
            m.put(req, switch (req) {
                case "regex" -> "^1[3-9][0-9]{9}$";
                case "minCount" -> "1";
                case "maxCount" -> "1000000";
                case "maxHours" -> "24";
                case "refTable" -> "my_schema.dim_product";
                case "refColumn" -> "product_id";
                case "conditionColumn" -> "status";
                case "conditionValue" -> "PAID";
                case "allowedValues" -> "ACTIVE,DISABLED";
                case "forbiddenValues" -> "DELETED,FRAUD";
                default -> "example";
            });
        }
        if (t.requiredParams().containsAll(List.of("minValue", "maxValue"))) {
            m.put("minValue", "0");
            m.put("maxValue", "1000");
        }
        return m;
    }
}
