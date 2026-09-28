package com.levango7.dataenginebdp.finops.billing.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 出账请求非法异常。
 *
 * <p>用于请求内容无法被正确计价的场景：定价配置名不被支持、计量项的金额与单价
 * 均缺失且无法查表。语义是"拒绝出账并说明原因"，而不是忽略该字段继续出账。</p>
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BillingRequestInvalidException extends RuntimeException {

    public BillingRequestInvalidException(String message) {
        super(message);
    }
}
