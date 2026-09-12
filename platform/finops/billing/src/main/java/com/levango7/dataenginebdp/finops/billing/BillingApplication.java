package com.levango7.dataenginebdp.finops.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据引擎大数据平台 - FinOps 出账闭环账单生成服务主入口。
 *
 * <p>从 Prometheus 指标采集租户级成本，产出租户级成本账单 JSON，
 * 支持账单持久化（JPA，H2 开发 / PostgreSQL 生产）与 REST API 触发生成。</p>
 *
 * <p>本服务是出账闭环的起点（出账），下游对接 asset-exchange 结算与
 * open-api-catalog 分账，形成 出账 → 结算 → 分账 完整闭环。</p>
 */
@SpringBootApplication
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}