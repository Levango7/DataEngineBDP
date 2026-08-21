package com.shuqing.bigdata.sqlgateway.virtual;

import jakarta.persistence.Embeddable;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 虚拟表列定义（JPA Embeddable）。
 *
 * <p>描述虚拟表的一列，包括列名、数据类型、是否可空与注释。
 * 多个列定义以 {@code @ElementCollection} 形式嵌入到
 * {@link VirtualTableDefinition} 中持久化。</p>
 *
 * <p>数据类型使用 SQL 标准类型名（如 {@code VARCHAR}/{@code INTEGER}/{@code TIMESTAMP}），
 * 由适配器在查询时映射为外部源的原生类型。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@Embeddable
public class ColumnDefinition {

    /**
     * 列名。
     */
    private String name;

    /**
     * SQL 数据类型（如 VARCHAR、INTEGER、TIMESTAMP）。
     */
    private String type;

    /**
     * 是否允许 NULL。
     */
    private Boolean nullable = true;

    /**
     * 列注释（可选）。
     */
    private String comment;

    /**
     * 全参构造器。
     *
     * @param name     列名
     * @param type     SQL 数据类型
     * @param nullable 是否允许 NULL
     * @param comment  列注释
     */
    public ColumnDefinition(String name, String type, Boolean nullable, String comment) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.comment = comment;
    }

    /**
     * 简化构造器（默认可空、无注释）。
     *
     * @param name 列名
     * @param type SQL 数据类型
     */
    public ColumnDefinition(String name, String type) {
        this(name, type, true, null);
    }
}