package com.shuqing.bigdata.infra.privatecloud.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 集群扩缩容请求。
 *
 * <p>由 REST API {@code POST /api/v1/clusters/private/{provider}/{id}/scale} 接收。</p>
 *
 * <p>当前实现仅支持工作节点数量变更（{@code targetWorkerCount}），
 * 控制面节点扩缩容需重新创建集群。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScaleRequest {

    /** 目标工作节点数；扩容时新增 worker，缩容时按 LRU 顺序销毁多余 worker */
    @NotNull(message = "targetWorkerCount 不能为空")
    @Min(value = 0, message = "targetWorkerCount 不能为负")
    private Integer targetWorkerCount;

    /** 新增 worker 的规格（扩容时使用；缩容可留空） */
    private VMSpec workerSpec;
}