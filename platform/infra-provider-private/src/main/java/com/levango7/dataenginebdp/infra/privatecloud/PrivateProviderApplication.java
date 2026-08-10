package com.levango7.dataenginebdp.infra.privatecloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据引擎大数据平台 - L0.4 私有云 VM 供应 Provider 主入口。
 *
 * <p>统一对接 vSphere（VMware vCenter 7.0+）与 OpenStack（Nova v2.1）两类私有云底座，
 * 提供 VM 创建、销毁、查询与 K8s 集群引导能力，对外暴露统一 REST API。</p>
 *
 * <p>对应设计文档：{@code design/详细设计/私有云VM供应详细设计_v0.1.md}（L0.4）。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootApplication
public class PrivateProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrivateProviderApplication.class, args);
    }
}