package com.levango7.dataenginebdp.finops.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FinOps 看板与优化建议服务主类。
 *
 * <p>提供 FinOps 看板（Top10/趋势/明细/闲置清单）、优化建议引擎（5 类闲置模式识别）、
 * 账单导出（CSV/Excel）、分账到子工作空间四大能力。</p>
 *
 * <p>依赖 T028 FinOps 成本采集（cost-model）的 Prometheus 数据。</p>
 */
@SpringBootApplication
public class FinOpsDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinOpsDashboardApplication.class, args);
    }
}