package com.levango7.dataenginebdp.masterdata.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建主数据记录请求 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateMasterDataDTO {

    /** 所属主数据模型编码（必填） */
    @NotBlank(message = "模型编码不能为空")
    private String modelCode;

    /** 数据键（业务唯一标识，必填） */
    @NotBlank(message = "数据键不能为空")
    private String dataKey;

    /** 数据值（显示名称，必填） */
    @NotBlank(message = "数据值不能为空")
    private String dataValue;

    /** 动态属性（JSON 格式） */
    private String attributes;

    /** 版本号 */
    private String version;

    /** 状态：ACTIVE / INACTIVE / DEPRECATED */
    private String status;
}