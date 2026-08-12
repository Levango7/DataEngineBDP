package com.levango7.dataenginebdp.tagengine.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标签定义。
 *
 * <p>标签画像引擎的核心元数据对象，描述一个标签的名称、类型、值域与归属租户。
 * 持久化到关系型数据库（开发环境 H2，生产环境 PostgreSQL），
 * 计算结果写入 Doris 标签宽表（{@code dws_user_tag_wide}）。</p>
 *
 * <p>对应详细设计 §3 标签模型。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagDefinition {

    /** 标签唯一标识（UUID 风格字符串，由引擎生成） */
    private String tagId;

    /** 租户 ID，多租户隔离边界 */
    @NotBlank(message = "tenantId must not be blank")
    private String tenantId;

    /** 标签名称（同租户内逻辑唯一） */
    @NotBlank(message = "name must not be blank")
    private String name;

    /** 标签展示名 */
    private String displayName;

    /** 标签类型：FACT / RULE / MINING */
    private TagType type;

    /**
     * 标签值域，枚举型标签的合法取值列表。
     * <p>例如 user_level 的值域为 ["新客","活跃","沉睡","流失"]。</p>
     * <p>数值型标签可为空。</p>
     */
    private List<String> valueDomain;

    /** 标签描述 */
    private String description;

    /** 标签在 Doris 宽表中对应的列名 */
    private String columnName;

    /** 标签状态：DRAFT / ACTIVE / ARCHIVED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}