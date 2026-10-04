package com.levango7.dataenginebdp.storage.replication;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次跨域复制任务的汇总报告（真实运行产物，非模拟）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplicationReport {

    /** 运行模式标识，固定 real，供验证脚本显式区分模拟产物。 */
    @Builder.Default
    private String mode = "real";

    /** 源存储端点。 */
    private String sourceEndpoint;

    /** 目标存储端点。 */
    private String targetEndpoint;

    /** 复制的源前缀。 */
    private String sourcePrefix;

    /** 开始时间。 */
    private Instant startedAt;

    /** 结束时间。 */
    private Instant finishedAt;

    /** 总耗时（毫秒）。 */
    private long elapsedMs;

    /** 扫描到的对象总数。 */
    private int totalObjects;

    /** 首次复制的对象数。 */
    private int copied;

    /** LWW 覆盖的对象数。 */
    private int overwritten;

    /** LWW 跳过的对象数。 */
    private int skipped;

    /** 失败对象数。 */
    private int failed;

    /** sha256 校验通过的对象数。 */
    private int verified;

    /** 传输字节总数（COPY + OVERWRITE）。 */
    private long totalBytes;

    /** 逐对象结果。 */
    @Builder.Default
    private List<ReplicationItemResult> items = new ArrayList<>();

    /** 是否全部成功且校验通过。 */
    public boolean isAllVerified() {
        return failed == 0 && (copied + overwritten) == verified;
    }
}
