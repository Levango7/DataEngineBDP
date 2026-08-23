package com.levango7.dataenginebdp.sqlgateway.calcite.join;

/**
 * Join 执行结果。
 */
public class JoinResult {
    private final java.util.List<Row> rows;
    private final JoinType joinType;
    private final JoinAlgorithm algorithm;
    private final JoinStatisticsSnapshot statistics;

    public JoinResult(java.util.List<Row> rows, JoinType joinType,
                      JoinAlgorithm algorithm, JoinStatisticsSnapshot statistics) {
        this.rows = java.util.Collections.unmodifiableList(rows);
        this.joinType = joinType;
        this.algorithm = algorithm;
        this.statistics = statistics;
    }

    public java.util.List<Row> getRows() { return rows; }
    public int getRowCount() { return rows.size(); }
    public JoinType getJoinType() { return joinType; }
    public JoinAlgorithm getAlgorithm() { return algorithm; }
    public JoinStatisticsSnapshot getStatistics() { return statistics; }

    @Override
    public String toString() {
        return "JoinResult{rows=" + rows.size()
                + ", type=" + joinType + ", algorithm=" + algorithm + '}';
    }
}
