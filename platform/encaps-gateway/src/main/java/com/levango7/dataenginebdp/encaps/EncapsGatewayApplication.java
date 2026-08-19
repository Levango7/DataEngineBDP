package com.levango7.dataenginebdp.encaps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据引擎大数据平台 - 封装层（路由网关域）启动主类。
 *
 * <p>本模块承载路由网关域职责：Gateway / ApiCatalog / Health / GatewayStats。</p>
 *
 * <p>通过依赖 {@code encaps-layer} 复用 common/model/repository/security 共享代码，
 * 通过依赖 {@code common-security} 复用通用 JwtAuthFilter/TenantContext/SecurityConfig。</p>
 */
@SpringBootApplication
@EnableScheduling
public class EncapsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncapsGatewayApplication.class, args);
    }
}