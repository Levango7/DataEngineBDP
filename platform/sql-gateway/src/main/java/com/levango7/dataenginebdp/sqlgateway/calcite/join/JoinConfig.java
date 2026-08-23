package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.util.Objects;

/**
 * Join 执行配置。
 */
public class JoinConfig {
    private long memoryBudgetBytes = CrossSourceJoinExecutor.DEFAULT_MEMORY_BUDGET;
    private double buildSideRatio = CrossSourceJoinExecutor.DEFAULT_BUILD_SIDE_RATIO;
    private int spillPartitions = CrossSourceJoinExecutor.DEFAULT_SPILL_PARTITIONS;
    private int batchSize = CrossSourceJoinExecutor.DEFAULT_BATCH_SIZE;
    private String spillDir = CrossSourceJoinExecutor.DEFAULT_SPILL_DIR;
    private boolean buildOnLeft = false;
    private boolean spillEnabled = true;

    public long getMemoryBudgetBytes() {
        return memoryBudgetBytes;
    }

    public JoinConfig setMemoryBudgetBytes(long memoryBudgetBytes) {
        if (memoryBudgetBytes <= 0) {
            throw new IllegalArgumentException("memoryBudgetBytes 必须为正");
        }
        this.memoryBudgetBytes = memoryBudgetBytes;
        return this;
    }

    public double getBuildSideRatio() {
        return buildSideRatio;
    }

    public JoinConfig setBuildSideRatio(double buildSideRatio) {
        if (buildSideRatio <= 0 || buildSideRatio >= 1) {
            throw new IllegalArgumentException("buildSideRatio 必须在 (0,1) 之间");
        }
        this.buildSideRatio = buildSideRatio;
        return this;
    }

    public int getSpillPartitions() {
        return spillPartitions;
    }

    public JoinConfig setSpillPartitions(int spillPartitions) {
        if (spillPartitions <= 0) {
            throw new IllegalArgumentException("spillPartitions 必须为正");
        }
        this.spillPartitions = spillPartitions;
        return this;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public JoinConfig setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正");
        }
        this.batchSize = batchSize;
        return this;
    }

    public String getSpillDir() {
        return spillDir;
    }

    public JoinConfig setSpillDir(String spillDir) {
        this.spillDir = Objects.requireNonNull(spillDir);
        return this;
    }

    public boolean isBuildOnLeft() {
        return buildOnLeft;
    }

    public JoinConfig setBuildOnLeft(boolean buildOnLeft) {
        this.buildOnLeft = buildOnLeft;
        return this;
    }

    public boolean isSpillEnabled() {
        return spillEnabled;
    }

    public JoinConfig setSpillEnabled(boolean spillEnabled) {
        this.spillEnabled = spillEnabled;
        return this;
    }
}
