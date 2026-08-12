package com.levango7.dataenginebdp.tagengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单标签计算结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagComputeResult {

    /** 标签 ID */
    private String tagId;

    /** 计算状态：SUCCESS / FAILED / RUNNING */
    private String status;

    /** 本次计算覆盖的用户数 */
    private long affectedRows;

    /** 标签版本号 */
    private String tagVersion;

    /** 计算耗时（毫秒） */
    private long costMs;

    /** 失败时的错误信息 */
    private String errorMessage;
}