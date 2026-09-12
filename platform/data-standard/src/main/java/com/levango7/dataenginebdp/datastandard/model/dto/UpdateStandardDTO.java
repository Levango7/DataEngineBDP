package com.levango7.dataenginebdp.datastandard.model.dto;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新数据标准请求 DTO。
 *
 * <p>所有字段可选，仅更新传入的字段。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStandardDTO {

    /** 标准名称 */
    private String name;

    /** 标准分类 */
    private String category;

    /** 标准描述 */
    private String description;

    /** 标准规则表达式 */
    private String rule;

    /** JSON Schema 约束定义 */
    private String jsonSchema;

    /** 标准版本号 */
    private String version;

    /**
     * 标准状态：DRAFT / PUBLISHED / DEPRECATED。
     *
     * <p>仅接受合法枚举值（大小写敏感），null 视为合法（不更新该字段）。</p>
     */
    @Pattern(regexp = "DRAFT|PUBLISHED|DEPRECATED",
            message = "status 仅支持 DRAFT / PUBLISHED / DEPRECATED")
    private String status;
}