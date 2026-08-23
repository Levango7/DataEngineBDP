package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.util.Objects;

/**
 * Join 键描述——定义 Join 条件的列映射与匹配逻辑。
 */
public class JoinKey {
    /** 左侧 Join 列索引 */
    private final int[] leftColumnIndices;
    /** 右侧 Join 列索引 */
    private final int[] rightColumnIndices;
    /** 左侧列数（用于 null 填充） */
    private final int leftColumnCount;
    /** 右侧列数 */
    private final int rightColumnCount;
    /** 是否等值 Join */
    private final boolean equiJoin;
    /** 非等值条件表达式（如 "a.x < b.y"），equiJoin=false 时使用 */
    private final String nonEquiCondition;

    public JoinKey(int[] leftColumnIndices, int[] rightColumnIndices,
                   int leftColumnCount, int rightColumnCount) {
        this(leftColumnIndices, rightColumnIndices, leftColumnCount, rightColumnCount,
                true, null);
    }

    public JoinKey(int[] leftColumnIndices, int[] rightColumnIndices,
                   int leftColumnCount, int rightColumnCount,
                   boolean equiJoin, String nonEquiCondition) {
        this.leftColumnIndices = Objects.requireNonNull(leftColumnIndices).clone();
        this.rightColumnIndices = Objects.requireNonNull(rightColumnIndices).clone();
        this.leftColumnCount = leftColumnCount;
        this.rightColumnCount = rightColumnCount;
        this.equiJoin = equiJoin;
        this.nonEquiCondition = nonEquiCondition;
    }

    public int[] getLeftColumnIndices() {
        return leftColumnIndices.clone();
    }

    public int[] getRightColumnIndices() {
        return rightColumnIndices.clone();
    }

    public int getLeftColumnCount() {
        return leftColumnCount;
    }

    public int getRightColumnCount() {
        return rightColumnCount;
    }

    public boolean isEquiJoin() {
        return equiJoin;
    }

    public String getNonEquiCondition() {
        return nonEquiCondition;
    }

    /**
     * 检查两行是否匹配（用于 Nested Loop Join）。
     */
    public boolean matches(Row leftRow, Row rightRow) {
        if (equiJoin) {
            return Objects.equals(
                    leftRow.get(leftColumnIndices[0]),
                    rightRow.get(rightColumnIndices[0]));
        }
        // 非等值：简化实现，仅支持单列
        if (nonEquiCondition == null) {
            return false;
        }
        Object lv = leftRow.get(leftColumnIndices[0]);
        Object rv = rightRow.get(rightColumnIndices[0]);
        return evaluateNonEqui(lv, rv, nonEquiCondition);
    }

    private boolean evaluateNonEqui(Object lv, Object rv, String cond) {
        if (lv instanceof Number && rv instanceof Number) {
            double l = ((Number) lv).doubleValue();
            double r = ((Number) rv).doubleValue();
            if (cond.contains("<") && !cond.contains("=")) {
                return l < r;
            } else if (cond.contains("<=")) {
                return l <= r;
            } else if (cond.contains(">") && !cond.contains("=")) {
                return l > r;
            } else if (cond.contains(">=")) {
                return l >= r;
            } else if (cond.contains("!=")) {
                return l != r;
            }
        }
        return false;
    }
}
