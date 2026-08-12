package com.levango7.dataenginebdp.sqlgateway.model;

import lombok.Builder;
import lombok.Data;

/**
 * SQL 校验响应 POJO。
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class SqlValidateResponse {

    /**
     * 是否合法。
     */
    private boolean valid;

    /**
     * 检测到的方言。
     */
    private String dialect;

    /**
     * 错误信息（合法时为 {@code null}）。
     */
    private String error;
}