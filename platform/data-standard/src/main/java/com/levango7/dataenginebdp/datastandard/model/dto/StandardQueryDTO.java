package com.levango7.dataenginebdp.datastandard.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
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

    /**
     * 标准状态（DRAFT / PUBLISHED / DEPRECATED）。
     *
     * <p>仅接受合法枚举值，null/空表示不按状态过滤。</p>
     */
    @Pattern(regexp = "DRAFT|PUBLISHED|DEPRECATED",
            message = "status 仅支持 DRAFT / PUBLISHED / DEPRECATED")
    private String status;

    /** 页码（从 0 开始） */
    private Integer page;

    /** 每页大小（最大 100，防止 OOM） */
    @Max(value = 100, message = "每页大小不能超过 100")
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