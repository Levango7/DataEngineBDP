package com.levango7.dataenginebdp.infra.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 数据引擎大数据平台 - L0.3 多云 VM 供应 Provider 主入口。
 *
 * <p>统一封装华为云 ECS / 阿里云 ECS / 腾讯云 CVM 的 VM 生命周期管理，
 * 通过 SPI 接口 {@code CloudProvider} 实现多云适配，对外暴露统一 REST API
 * {@code /api/v1/clusters/cloud/{provider}}。</p>
 *
 * <p>典型流程：创建 VM → 配置安全组 → 分配公网 IP → K8s 引导（kubeadm 二次封装的 SKE）→ 注册到 Catalog。</p>
 */
@SpringBootApplication
@EnableAsync
public class CloudProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudProviderApplication.class, args);
    }
}