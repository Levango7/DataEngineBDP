package com.levango7.dataenginebdp.streambatch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * DAG 边（节点间依赖关系）。
 *
 * <p>表示 {@code source} 节点完成后才能执行 {@code target} 节点。
 * 流批统一 DAG 中，边可以连接批→批、流→流、批→流（批先完成再启动流）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagEdge {

    /** 上游节点 ID。 */
    @NotBlank
    private String source;

    /** 下游节点 ID。 */
    @NotBlank
    private String target;

    /** 边名称（可选，用于展示）。 */
    private String name;
}