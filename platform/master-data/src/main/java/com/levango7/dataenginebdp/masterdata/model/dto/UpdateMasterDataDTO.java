package com.levango7.dataenginebdp.masterdata.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新主数据记录请求 DTO。
 *
 * <p>所有字段可选，仅更新传入的字段。modelCode 不可变更。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMasterDataDTO {

    /** 数据键 */
    private String dataKey;

    /** 数据值 */
    private String dataValue;

    /** 动态属性（JSON 格式） */
    private String attributes;

    /** 版本号 */
    private String version;

    /** 状态：ACTIVE / INACTIVE / DEPRECATED */
    private String status;
}