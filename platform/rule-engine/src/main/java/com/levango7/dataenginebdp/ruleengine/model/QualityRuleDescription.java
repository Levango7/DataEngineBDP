package com.levango7.dataenginebdp.ruleengine.model;

/**
 * {@link Rule} 的 description ↔ (targetTable, targetField) 编解码。
 *
 * <p>存储约定：质量规则的 description 形如 {@code quality rule on TABLE[.FIELD]}。
 * 该格式此前由 {@code QualityRuleController.toView} 与
 * {@code BatchPipelineRuleAdapter.specFromStoredRule} 各手写解析一份，
 * 本类收敛为唯一实现，避免格式变更时两处失配。</p>
 */
public final class QualityRuleDescription {

    /** description 前缀；修改此值需同步迁移已存数据。 */
    public static final String PREFIX = "quality rule on ";

    private QualityRuleDescription() {
    }

    /**
     * 由目标表/字段构造 description。
     *
     * @param targetTable 目标表，可含库名（如 {@code ods.orders}）
     * @param targetField 目标字段，可为空
     * @return 形如 {@code quality rule on ods.orders.user_id} 的 description
     */
    public static String encode(String targetTable, String targetField) {
        String table = targetTable == null ? "" : targetTable;
        return PREFIX + table + (targetField != null && !targetField.isBlank() ? "." + targetField : "");
    }

    /**
     * 解析 description 得到目标表。
     *
     * <p>格式不符（含 null / 空 / 无前缀）时返回空串，调用方据此判定该规则不可映射。
     *
     * @param description 已存储的 description
     * @return 目标表（去掉字段后缀），格式不符时为空串
     */
    public static String targetTable(String description) {
        Target t = parse(description);
        return t.table();
    }

    /**
     * 解析 description 得到目标字段。
     *
     * @param description 已存储的 description
     * @return 目标字段；无字段后缀或格式不符时为空串
     */
    public static String targetField(String description) {
        Target t = parse(description);
        return t.field();
    }

    /**
     * 一次性解析出目标表与字段。
     *
     * @param description 已存储的 description
     * @return 解析结果；格式不符时两者均为空串
     */
    public static Target parse(String description) {
        if (description == null) {
            return Target.EMPTY;
        }
        String target = description.startsWith(PREFIX)
                ? description.substring(PREFIX.length()) : "";
        int lastDot = target.lastIndexOf('.');
        if (lastDot < 0) {
            return new Target(target, "");
        }
        return new Target(target.substring(0, lastDot), target.substring(lastDot + 1));
    }

    /** 解析结果：目标表与字段。 */
    public record Target(String table, String field) {
        static final Target EMPTY = new Target("", "");
    }
}