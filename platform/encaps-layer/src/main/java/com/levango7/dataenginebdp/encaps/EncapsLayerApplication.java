package com.levango7.dataenginebdp.encaps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据引擎大数据平台 - 封装层（Encaps Layer）启动主类。
 *
 * <p>封装层负责向上屏蔽底层 K8s/大数据组件差异，向下统一调度资源供应原语。</p>
 *
 * <p>{@code @EnableScheduling} 启用 Spring 定时任务，供 JWT 密钥自动轮换
 * （{@code JwtKeyRotationService}）等调度任务使用。轮换默认关闭，
 * 由 {@code security.jwt.rotation.enabled} 控制。</p>
 */
@SpringBootApplication
@EnableScheduling
public class EncapsLayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncapsLayerApplication.class, args);
    }
}