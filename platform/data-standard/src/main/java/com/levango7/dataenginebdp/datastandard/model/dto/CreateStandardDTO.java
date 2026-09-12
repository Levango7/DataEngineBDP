package com.levango7.dataenginebdp.datastandard.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建数据标准请求 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateStandardDTO {

    /** 标准名称（必填） */
    @NotBlank(message = "标准名称不能为空")
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
     * <p>仅接受合法枚举值（大小写敏感），null 视为合法（服务层回退 DRAFT）。</p>
     */
    @Pattern(regexp = "DRAFT|PUBLISHED|DEPRECATED",
            message = "status 仅支持 DRAFT / PUBLISHED / DEPRECATED")
    private String status;
}