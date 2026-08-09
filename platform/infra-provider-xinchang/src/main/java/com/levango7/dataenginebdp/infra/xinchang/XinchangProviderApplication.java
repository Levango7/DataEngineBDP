package com.levango7.dataenginebdp.infra.xinchang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 - L0.1 信创资源供应 Provider 启动主类。
 *
 * <p>信创资源供应 Provider 负责向下对接国产 CPU（鲲鹏/海光/飞腾/兆芯）+ 国产 OS（麒麟 V10/统信 UOS）
 * 物理机，通过 IPMI Redfish + PXE 实现自动化装机，并使用 kubeadm 初始化 K8s 集群，
 * 向上为封装层（L0.11）提供统一的 {@code ClusterProvider} SPI。</p>
 */
@SpringBootApplication
public class XinchangProviderApplication {

    /**
     * 启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(XinchangProviderApplication.class, args);
    }
}