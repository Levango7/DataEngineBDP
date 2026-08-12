package com.levango7.dataenginebdp.sqlgateway.model;

import lombok.Builder;
import lombok.Data;

/**
 * SQL 方言转换响应 POJO。
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class SqlConvertResponse {

    /**
     * 检测/指定的源方言。
     */
    private String fromDialect;

    /**
     * 目标方言。
     */
    private String toDialect;

    /**
     * 转换后的 SQL。
     */
    private String convertedSql;
}