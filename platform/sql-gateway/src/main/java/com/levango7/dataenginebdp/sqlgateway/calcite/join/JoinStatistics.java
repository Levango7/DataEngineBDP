package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Join 执行统计器。
 */
public class JoinStatistics {
    private volatile long startTime = 0;
    private volatile long endTime = 0;
    private volatile boolean success = false;
    private volatile String failureReason = null;
    private volatile JoinAlgorithm algorithm = null;
    private final AtomicLong buildRows = new AtomicLong(0);
    private final AtomicLong outputRows = new AtomicLong(0);
    private final AtomicLong spillTriggered = new AtomicLong(0);
    private final AtomicLong spilledRows = new AtomicLong(0);
    private final AtomicLong spillPartitions = new AtomicLong(0);

    public void recordStart() { startTime = System.nanoTime(); }
    public void recordEnd() { endTime = System.nanoTime(); }
    public void recordSuccess() { success = true; }
    public void recordFailure(String reason) { success = false; failureReason = reason; }
    public void recordAlgorithm(JoinAlgorithm algorithm) { this.algorithm = algorithm; }
    public void recordBuildRows(long count) { buildRows.addAndGet(count); }
    public void recordOutputRows(long count) { outputRows.addAndGet(count); }
    public void recordSpillTriggered() { spillTriggered.incrementAndGet(); }
    public void recordSpilledRows(long count) { spilledRows.addAndGet(count); }
    public void recordSpillPartitions(long count) { spillPartitions.addAndGet(count); }

    public long getDurationNanos() {
        return (startTime == 0 || endTime == 0) ? 0 : endTime - startTime;
    }

    public long getDurationMillis() { return getDurationNanos() / 1_000_000; }
    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
    public JoinAlgorithm getAlgorithm() { return algorithm; }
    public long getBuildRows() { return buildRows.get(); }
    public long getOutputRows() { return outputRows.get(); }
    public long getSpillTriggered() { return spillTriggered.get(); }
    public long getSpilledRows() { return spilledRows.get(); }
    public long getSpillPartitions() { return spillPartitions.get(); }

    public JoinStatisticsSnapshot snapshot() {
        return new JoinStatisticsSnapshot(
                getDurationMillis(), success, failureReason, algorithm,
                buildRows.get(), outputRows.get(),
                spillTriggered.get(), spilledRows.get(), spillPartitions.get());
    }

    @Override
    public String toString() {
        return "JoinStatistics{success=" + success
                + ", algorithm=" + algorithm
                + ", duration=" + getDurationMillis() + "ms"
                + ", buildRows=" + buildRows.get()
                + ", outputRows=" + outputRows.get()
                + ", spills=" + spillTriggered.get()
                + ", spilledRows=" + spilledRows.get() + '}';
    }
}
