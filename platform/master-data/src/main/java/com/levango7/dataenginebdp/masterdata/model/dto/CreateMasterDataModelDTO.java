package com.levango7.dataenginebdp.masterdata.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建主数据模型请求 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateMasterDataModelDTO {

    /** 模型编码（必填，唯一标识） */
    @NotBlank(message = "模型编码不能为空")
    private String code;

    /** 模型名称（必填） */
    @NotBlank(message = "模型名称不能为空")
    private String name;

    /** 字段 schema（JSON 数组） */
    private String fieldsSchema;

    /** 模型描述 */
    private String description;
}