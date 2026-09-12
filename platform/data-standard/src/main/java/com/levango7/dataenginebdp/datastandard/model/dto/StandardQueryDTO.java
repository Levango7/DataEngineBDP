package com.levango7.dataenginebdp.datastandard.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据标准分页查询请求 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StandardQueryDTO {

    /** 标准名称（模糊匹配） */
    private String name;

    /** 标准分类 */
    private String category;

    /** 标准状态 */
    private String status;

    /** 页码（从 0 开始） */
    private Integer page;

    /** 每页大小 */
    private Integer size;

    /**
     * 获取页码，默认 0。
     *
     * @return 页码
     */
    public int getPageOrDefault() {
        return page == null || page < 0 ? 0 : page;
    }

    /**
     * 获取每页大小，默认 20。
     *
     * @return 每页大小
     */
    public int getSizeOrDefault() {
        return size == null || size <= 0 ? 20 : size;
    }
}