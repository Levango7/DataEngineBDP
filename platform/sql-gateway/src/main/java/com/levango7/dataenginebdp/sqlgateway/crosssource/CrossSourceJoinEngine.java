package com.levango7.dataenginebdp.sqlgateway.crosssource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 跨源 JOIN 引擎。
 *
 * <p>在内存中对两个 {@link MergeResult}（分别来自不同数据源）执行 JOIN 操作，
 * 支持三种算法：</p>
 * <ul>
 *   <li>{@link #hashJoin}     — Hash Join，构建小表 Hash 表后扫描大表匹配，适用于等值 JOIN；</li>
 *   <li>{@link #nestedLoopJoin} — Nested Loop Join，双重循环逐行比较，适用于不等值 JOIN；</li>
 *   <li>{@link #mergeJoin}    — Sort-Merge Join，适用于两侧已按 JOIN 键排序的数据。</li>
 * </ul>
 *
 * <p>JOIN 条件通过 {@link JoinCondition} 描述：左列名、右列名、操作符（=、!=、&lt;、&gt;、&lt;=、&gt;=）。
 * 输出列 = 左表列 + 右表列（不去重，保留两侧所有列以便上层投影）。</p>
 *
 * <p>结果集行数受 {@code maxRows} 限制（默认 {@link MergeResult#DEFAULT_MAX_ROWS}），
 * 超限时抛 {@link CrossSourceException}（错误码 {@code RESULT_TOO_LARGE}）。</p>
 *
 * @author shuqing-bigdata
 */
public class CrossSourceJoinEngine {

    /** 默认结果集行数上限 */
    private static final int DEFAULT_MAX_ROWS = MergeResult.DEFAULT_MAX_ROWS;

    private final int maxRows;

    /**
     * 默认构造（使用默认行数上限）。
     */
    public CrossSourceJoinEngine() {
        this(DEFAULT_MAX_ROWS);
    }

    /**
     * 指定结果集行数上限。
     *
     * @param maxRows 最大行数
     */
    public CrossSourceJoinEngine(int maxRows) {
        this.maxRows = maxRows > 0 ? maxRows : DEFAULT_MAX_ROWS;
    }

    /**
     * Hash Join：构建小表 Hash 表后扫描大表匹配。
     *
     * <p>仅适用于等值 JOIN（操作符为 {@code =}）。非等值 JOIN 会回退到
     * {@link #nestedLoopJoin}。</p>
     *
     * <p>策略：自动选择较小的一侧作为构建侧（build side），较大的一侧作为探测侧（probe side）。</p>
     *
     * @param left          左表结果
     * @param right         右表结果
     * @param joinCondition JOIN 条件
     * @return 归并结果
     * @throws CrossSourceException 归并失败
     */
    public MergeResult hashJoin(MergeResult left, MergeResult right, JoinCondition joinCondition) {
        validateInputs(left, right, joinCondition);

        // 非等值 JOIN 回退到 Nested Loop
        if (!"=".equals(joinCondition.operator)) {
            return nestedLoopJoin(left, right, joinCondition);
        }

        long start = System.currentTimeMillis();

        int leftKeyIdx = left.indexOfColumn(joinCondition.leftColumn);
        int rightKeyIdx = right.indexOfColumn(joinCondition.rightColumn);
        if (leftKeyIdx < 0 || rightKeyIdx < 0) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "Hash Join 找不到 JOIN 键列: left=" + joinCondition.leftColumn
                            + " (idx=" + leftKeyIdx + "), right=" + joinCondition.rightColumn
                            + " (idx=" + rightKeyIdx + ")");
        }

        // 选择小表作为 build side
        boolean leftAsBuild = left.getRowCount() <= right.getRowCount();
        MergeResult build = leftAsBuild ? left : right;
        MergeResult probe = leftAsBuild ? right : left;
        int buildKeyIdx = leftAsBuild ? leftKeyIdx : rightKeyIdx;
        int probeKeyIdx = leftAsBuild ? rightKeyIdx : leftKeyIdx;

        // 构建 Hash 表：key → 行列表（允许重复键）
        // SQL 语义：NULL = NULL 为 UNKNOWN，不匹配 → 跳过键为 null 的 build 行
        Map<Object, List<List<Object>>> hashTable = new LinkedHashMap<>();
        for (List<Object> row : build.getRows()) {
            Object key = normalizeKey(row.get(buildKeyIdx));
            if (key == null) {
                continue;
            }
            hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // 扫描 probe side 匹配
        List<List<Object>> mergedRows = new ArrayList<>();
        for (List<Object> probeRow : probe.getRows()) {
            Object key = normalizeKey(probeRow.get(probeKeyIdx));
            // SQL 语义：NULL 键不参与匹配
            if (key == null) {
                continue;
            }
            List<List<Object>> matches = hashTable.get(key);
            if (matches == null) {
                continue;
            }
            for (List<Object> buildRow : matches) {
                // 输出顺序：左表列 + 右表列
                List<Object> outRow = leftAsBuild
                        ? combineRow(buildRow, probeRow)
                        : combineRow(probeRow, buildRow);
                mergedRows.add(outRow);
                if (mergedRows.size() > maxRows) {
                    throw new CrossSourceException(CrossSourceException.RESULT_TOO_LARGE,
                            "Hash Join 结果超过上限 " + maxRows + " 行");
                }
            }
        }

        List<String> mergedCols = combineColumns(left.getColumns(), right.getColumns());
        long duration = System.currentTimeMillis() - start;
        return new MergeResult(mergedCols, mergedRows, "merged", duration);
    }

    /**
     * Nested Loop Join：双重循环逐行比较。
     *
     * <p>适用于不等值 JOIN（&lt;、&gt;、&lt;=、&gt;=、!=）。复杂度 O(N*M)，
     * 仅适用于小表场景。</p>
     *
     * @param left          左表结果
     * @param right         右表结果
     * @param joinCondition JOIN 条件
     * @return 归并结果
     * @throws CrossSourceException 归并失败
     */
    public MergeResult nestedLoopJoin(MergeResult left, MergeResult right, JoinCondition joinCondition) {
        validateInputs(left, right, joinCondition);
        long start = System.currentTimeMillis();

        int leftKeyIdx = left.indexOfColumn(joinCondition.leftColumn);
        int rightKeyIdx = right.indexOfColumn(joinCondition.rightColumn);
        if (leftKeyIdx < 0 || rightKeyIdx < 0) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "Nested Loop Join 找不到 JOIN 键列: left=" + joinCondition.leftColumn
                            + " (idx=" + leftKeyIdx + "), right=" + joinCondition.rightColumn
                            + " (idx=" + rightKeyIdx + ")");
        }

        List<List<Object>> mergedRows = new ArrayList<>();
        for (List<Object> leftRow : left.getRows()) {
            Object leftVal = leftRow.get(leftKeyIdx);
            for (List<Object> rightRow : right.getRows()) {
                Object rightVal = rightRow.get(rightKeyIdx);
                if (compareValues(leftVal, rightVal, joinCondition.operator)) {
                    mergedRows.add(combineRow(leftRow, rightRow));
                    if (mergedRows.size() > maxRows) {
                        throw new CrossSourceException(CrossSourceException.RESULT_TOO_LARGE,
                                "Nested Loop Join 结果超过上限 " + maxRows + " 行");
                    }
                }
            }
        }

        List<String> mergedCols = combineColumns(left.getColumns(), right.getColumns());
        long duration = System.currentTimeMillis() - start;
        return new MergeResult(mergedCols, mergedRows, "merged", duration);
    }

    /**
     * Sort-Merge Join：两侧已按 JOIN 键排序时的归并连接。
     *
     * <p>要求两侧数据已按 JOIN 键升序排列，且仅支持等值 JOIN。
     * 若数据未排序，调用方应先排序再调用本方法。</p>
     *
     * @param left          左表结果（已按 JOIN 键升序）
     * @param right         右表结果（已按 JOIN 键升序）
     * @param joinCondition JOIN 条件（仅支持等值）
     * @return 归并结果
     * @throws CrossSourceException 归并失败
     */
    public MergeResult mergeJoin(MergeResult left, MergeResult right, JoinCondition joinCondition) {
        validateInputs(left, right, joinCondition);
        if (!"=".equals(joinCondition.operator)) {
            throw new CrossSourceException(CrossSourceException.UNSUPPORTED,
                    "Sort-Merge Join 仅支持等值 JOIN，实际操作符: " + joinCondition.operator);
        }
        long start = System.currentTimeMillis();

        int leftKeyIdx = left.indexOfColumn(joinCondition.leftColumn);
        int rightKeyIdx = right.indexOfColumn(joinCondition.rightColumn);
        if (leftKeyIdx < 0 || rightKeyIdx < 0) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "Sort-Merge Join 找不到 JOIN 键列");
        }

        List<List<Object>> mergedRows = new ArrayList<>();
        int i = 0;
        int j = 0;
        List<List<Object>> leftRows = left.getRows();
        List<List<Object>> rightRows = right.getRows();

        while (i < leftRows.size() && j < rightRows.size()) {
            Object leftKey = normalizeKey(leftRows.get(i).get(leftKeyIdx));
            Object rightKey = normalizeKey(rightRows.get(j).get(rightKeyIdx));

            // SQL 语义：NULL 键不参与等值匹配 → 跳过 null 键的行
            if (leftKey == null) {
                i++;
                continue;
            }
            if (rightKey == null) {
                j++;
                continue;
            }

            int cmp = compareForSort(leftKey, rightKey);

            if (cmp < 0) {
                i++;
            } else if (cmp > 0) {
                j++;
            } else {
                // 收集所有相同键的右侧行
                int k = j;
                while (k < rightRows.size()
                        && Objects.equals(normalizeKey(rightRows.get(k).get(rightKeyIdx)), rightKey)) {
                    mergedRows.add(combineRow(leftRows.get(i), rightRows.get(k)));
                    if (mergedRows.size() > maxRows) {
                        throw new CrossSourceException(CrossSourceException.RESULT_TOO_LARGE,
                                "Sort-Merge Join 结果超过上限 " + maxRows + " 行");
                    }
                    k++;
                }
                i++;
            }
        }

        List<String> mergedCols = combineColumns(left.getColumns(), right.getColumns());
        long duration = System.currentTimeMillis() - start;
        return new MergeResult(mergedCols, mergedRows, "merged", duration);
    }

    // ===================== 内部工具 =====================

    /**
     * 校验输入。
     */
    private void validateInputs(MergeResult left, MergeResult right, JoinCondition cond) {
        if (left == null || right == null) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "JOIN 输入结果不能为 null");
        }
        if (cond == null) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "JOIN 条件不能为 null");
        }
        if (cond.leftColumn == null || cond.rightColumn == null) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "JOIN 条件的左/右列名不能为 null");
        }
        if (!isValidOperator(cond.operator)) {
            throw new CrossSourceException(CrossSourceException.UNSUPPORTED,
                    "不支持的 JOIN 操作符: " + cond.operator);
        }
    }

    /**
     * 校验操作符。
     */
    private boolean isValidOperator(String op) {
        return op != null && Set.of("=", "!=", "<", ">", "<=", ">=").contains(op);
    }

    /**
     * 合并列定义（左 + 右）。
     * <p>若左右列名冲突（大小写无关），右侧列名追加 {@code _right} 后缀。</p>
     */
    private List<String> combineColumns(List<String> leftCols, List<String> rightCols) {
        List<String> result = new ArrayList<>(leftCols);
        // 大小写无关的列名集合，避免 ID 与 id 被视为不同列导致冲突检测遗漏
        Set<String> leftLowerSet = new LinkedHashSet<>();
        for (String col : leftCols) {
            if (col != null) {
                leftLowerSet.add(col.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String col : rightCols) {
            if (col == null) {
                result.add(null);
                continue;
            }
            String lower = col.trim().toLowerCase(Locale.ROOT);
            if (leftLowerSet.contains(lower)) {
                result.add(col + "_right");
            } else {
                result.add(col);
            }
        }
        return result;
    }

    /**
     * 合并行（左 + 右）。
     * <p>对左右行数据应用类型归一化，避免不同源返回类型不一致导致下游消费异常。</p>
     */
    private List<Object> combineRow(List<Object> leftRow, List<Object> rightRow) {
        List<Object> leftConverted = ColumnTypeConverter.convertRow(leftRow);
        List<Object> rightConverted = ColumnTypeConverter.convertRow(rightRow);
        List<Object> out = new ArrayList<>(leftConverted.size() + rightConverted.size());
        out.addAll(leftConverted);
        out.addAll(rightConverted);
        return out;
    }

    /**
     * 归一化 JOIN 键（用于 Hash 表查找）。
     * <p>先通过 {@link ColumnTypeConverter#convertValue} 统一类型（数值→BigDecimal，
     * 字符串 trim），再对字符串额外转大写以实现大小写无关匹配。null 保持 null。</p>
     */
    private Object normalizeKey(Object value) {
        if (value == null) {
            return null;
        }
        Object converted = ColumnTypeConverter.convertValue(value);
        if (converted instanceof String s) {
            return s.toUpperCase(Locale.ROOT);
        }
        return converted;
    }

    /**
     * 比较两个值是否满足操作符关系。
     *
     * <p>比较前先通过 {@link ColumnTypeConverter#convertValue} 统一类型，
     * 避免不同源返回类型不一致（如 Integer vs Long）导致比较失败。</p>
     */
    @SuppressWarnings("unchecked")
    private boolean compareValues(Object left, Object right, String op) {
        if (left == null || right == null) {
            // null 比较语义：null = null 视为 false（SQL 中 NULL 比较结果为 UNKNOWN）
            return false;
        }
        // 统一类型后再比较
        Object leftNorm = ColumnTypeConverter.convertValue(left);
        Object rightNorm = ColumnTypeConverter.convertValue(right);
        try {
            int cmp;
            if (leftNorm instanceof java.math.BigDecimal ld && rightNorm instanceof java.math.BigDecimal rd) {
                cmp = ld.compareTo(rd);
            } else if (leftNorm instanceof Comparable && rightNorm.getClass().equals(leftNorm.getClass())) {
                cmp = ((Comparable<Object>) leftNorm).compareTo(rightNorm);
            } else {
                // 类型不一致时按字符串比较
                cmp = String.valueOf(leftNorm).compareTo(String.valueOf(rightNorm));
            }
            return switch (op) {
                case "=" -> cmp == 0;
                case "!=" -> cmp != 0;
                case "<" -> cmp < 0;
                case ">" -> cmp > 0;
                case "<=" -> cmp <= 0;
                case ">=" -> cmp >= 0;
                default -> false;
            };
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     * 用于 Sort-Merge 的比较（返回 -1/0/1）。
     *
     * <p>比较前先通过 {@link ColumnTypeConverter#convertValue} 统一类型。</p>
     */
    private int compareForSort(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        Object leftNorm = ColumnTypeConverter.convertValue(left);
        Object rightNorm = ColumnTypeConverter.convertValue(right);
        if (leftNorm instanceof java.math.BigDecimal ld && rightNorm instanceof java.math.BigDecimal rd) {
            return ld.compareTo(rd);
        }
        return String.valueOf(leftNorm).compareTo(String.valueOf(rightNorm));
    }

    /**
     * JOIN 条件描述。
     */
    public static final class JoinCondition {
        /** 左表列名 */
        private final String leftColumn;
        /** 右表列名 */
        private final String rightColumn;
        /** 操作符：=、!=、&lt;、&gt;、&lt;=、&gt;= */
        private final String operator;

        /**
         * 构造 JOIN 条件（默认等值）。
         *
         * @param leftColumn  左列名
         * @param rightColumn 右列名
         */
        public JoinCondition(String leftColumn, String rightColumn) {
            this(leftColumn, rightColumn, "=");
        }

        /**
         * 构造 JOIN 条件。
         *
         * @param leftColumn  左列名
         * @param rightColumn 右列名
         * @param operator    操作符
         */
        public JoinCondition(String leftColumn, String rightColumn, String operator) {
            this.leftColumn = leftColumn;
            this.rightColumn = rightColumn;
            this.operator = operator == null ? "=" : operator;
        }

        /**
         * 获取左列名。
         *
         * @return 左列名
         */
        public String getLeftColumn() {
            return leftColumn;
        }

        /**
         * 获取右列名。
         *
         * @return 右列名
         */
        public String getRightColumn() {
            return rightColumn;
        }

        /**
         * 获取操作符。
         *
         * @return 操作符
         */
        public String getOperator() {
            return operator;
        }

        @Override
        public String toString() {
            return leftColumn + " " + operator + " " + rightColumn;
        }
    }

    /**
     * 获取结果集行数上限。
     *
     * @return 行数上限
     */
    public int getMaxRows() {
        return maxRows;
    }

    /**
     * 列出所有支持的操作符。
     *
     * @return 操作符列表
     */
    public static List<String> supportedOperators() {
        return Collections.unmodifiableList(Arrays.asList("=", "!=", "<", ">", "<=", ">="));
    }
}