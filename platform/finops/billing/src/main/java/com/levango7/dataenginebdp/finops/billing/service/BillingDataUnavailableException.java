package com.levango7.dataenginebdp.finops.billing.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 出账数据不可用异常。
 *
 * <p>当用量数据源无法提供可信数据（Prometheus 不可用、未采集到任何租户用量时序）
 * 且没有计量汇入数据兜底时抛出。语义是"拒绝出账"而不是"出 0 元账单"：
 * 0 元账单会被下游当成真实结算依据，属于资损风险。</p>
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class BillingDataUnavailableException extends RuntimeException {

    public BillingDataUnavailableException(String message) {
        super(message);
    }
}
