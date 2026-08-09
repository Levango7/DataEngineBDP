package com.levango7.dataenginebdp.finops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 - FinOps 成本模型服务主入口。
 *
 * <p>提供资源用量采集（CPU/内存/存储/GPU/网络五维度）与成本计算
 * （按量/包年/阶梯三种计费方式）能力，支持多租户隔离与 GPU 多卡型号差异化定价。</p>
 */
@SpringBootApplication
public class CostModelApplication {

    public static void main(String[] args) {
        SpringApplication.run(CostModelApplication.class, args);
    }
}