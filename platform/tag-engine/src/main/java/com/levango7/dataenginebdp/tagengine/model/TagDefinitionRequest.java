package com.levango7.dataenginebdp.tagengine.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建标签定义请求 DTO。
 *
 * <p>对应接口 {@code POST /api/v1/tags}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagDefinitionRequest {

    /** 租户 ID */
    @NotBlank(message = "tenantId must not be blank")
    private String tenantId;

    /** 标签名称 */
    @NotBlank(message = "name must not be blank")
    private String name;

    /** 标签展示名 */
    private String displayName;

    /** 标签类型：FACT / RULE / MINING */
    private TagType type;

    /** 标签值域 */
    private List<String> valueDomain;

    /** 标签描述 */
    private String description;

    /** Doris 宽表列名；为空时由引擎按 name 推导 */
    private String columnName;
}