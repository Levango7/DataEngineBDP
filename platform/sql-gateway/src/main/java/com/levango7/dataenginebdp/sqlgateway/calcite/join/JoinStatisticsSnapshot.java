package com.levango7.dataenginebdp.sqlgateway.calcite.join;

/**
 * Join 统计快照（不可变）。
 */
public class JoinStatisticsSnapshot {
    private final long durationMillis;
    private final boolean success;
    private final String failureReason;
    private final JoinAlgorithm algorithm;
    private final long buildRows;
    private final long outputRows;
    private final long spillTriggered;
    private final long spilledRows;
    private final long spillPartitions;

    public JoinStatisticsSnapshot(long durationMillis, boolean success, String failureReason,
                                  JoinAlgorithm algorithm, long buildRows, long outputRows,
                                  long spillTriggered, long spilledRows, long spillPartitions) {
        this.durationMillis = durationMillis;
        this.success = success;
        this.failureReason = failureReason;
        this.algorithm = algorithm;
        this.buildRows = buildRows;
        this.outputRows = outputRows;
        this.spillTriggered = spillTriggered;
        this.spilledRows = spilledRows;
        this.spillPartitions = spillPartitions;
    }

    public long getDurationMillis() { return durationMillis; }
    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
    public JoinAlgorithm getAlgorithm() { return algorithm; }
    public long getBuildRows() { return buildRows; }
    public long getOutputRows() { return outputRows; }
    public long getSpillTriggered() { return spillTriggered; }
    public long getSpilledRows() { return spilledRows; }
    public long getSpillPartitions() { return spillPartitions; }
}
