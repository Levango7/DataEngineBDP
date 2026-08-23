package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存管理器——跟踪 Join 执行过程中的内存占用，触发 spill。
 */
public class MemoryManager {
    private final long totalBudget;
    private final double buildSideRatio;
    private final AtomicLong usedBytes = new AtomicLong(0);
    private final AtomicLong peakBytes = new AtomicLong(0);
    private final AtomicLong spillTriggerCount = new AtomicLong(0);

    public MemoryManager(long totalBudget, double buildSideRatio) {
        if (totalBudget <= 0) {
            throw new IllegalArgumentException("totalBudget 必须为正");
        }
        if (buildSideRatio < 0.0 || buildSideRatio > 1.0) {
            throw new IllegalArgumentException("buildSideRatio must be in [0, 1], got: " + buildSideRatio);
        }
        this.totalBudget = totalBudget;
        this.buildSideRatio = buildSideRatio;
    }

    /**
     * 申请内存。
     *
     * @param bytes 字节数
     * @return true 表示申请成功
     */
    public boolean acquire(long bytes) {
        if (bytes < 0) return false;
        long newUsed = usedBytes.addAndGet(bytes);
        long currentPeak = peakBytes.get();
        while (newUsed > currentPeak) {
            if (peakBytes.compareAndSet(currentPeak, newUsed)) {
                break;
            }
            currentPeak = peakBytes.get();
        }
        return newUsed <= totalBudget;
    }

    /**
     * 释放内存。
     */
    public void release(long bytes) {
        if (bytes > 0) {
            usedBytes.updateAndGet(v -> Math.max(0, v - bytes));
        }
    }

    /**
     * 释放所有内存。
     */
    public void releaseAll() {
        usedBytes.set(0);
    }

    /**
     * 是否超过预算。
     */
    public boolean isOverBudget() {
        return usedBytes.get() > totalBudget;
    }

    /**
     * 是否超过 build 侧预算。
     */
    public boolean isOverBuildBudget() {
        return usedBytes.get() > getBuildBudget();
    }

    public long getTotalBudget() { return totalBudget; }
    public long getBuildBudget() { return (long) (totalBudget * buildSideRatio); }
    public long getProbeBudget() { return totalBudget - getBuildBudget(); }
    public long getUsedBytes() { return usedBytes.get(); }
    public long getPeakBytes() { return peakBytes.get(); }
    public long getSpillTriggerCount() { return spillTriggerCount.get(); }

    public void incrementSpillTrigger() {
        spillTriggerCount.incrementAndGet();
    }

    public double getUsageRate() {
        return (double) usedBytes.get() / totalBudget;
    }

    @Override
    public String toString() {
        return "MemoryManager{used=" + usedBytes.get() + "/" + totalBudget
                + " (" + String.format("%.1f%%", getUsageRate() * 100)
                + "), peak=" + peakBytes.get() + ", spills=" + spillTriggerCount.get() + '}';
    }
}
