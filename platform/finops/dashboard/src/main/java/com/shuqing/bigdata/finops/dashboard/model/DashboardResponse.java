package com.shuqing.bigdata.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 看板统一响应。
 *
 * <p>封装看板查询的元信息与数据列表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse<T> {

    /** 数据列表 */
    private List<T> items;

    /** 总条数 */
    private int total;

    /** 查询窗口起始时间 */
    private Instant start;

    /** 查询窗口结束时间 */
    private Instant end;

    /** 租户 ID */
    private String tenant;

    /** 汇总信息（如总成本） */
    private Object summary;
}