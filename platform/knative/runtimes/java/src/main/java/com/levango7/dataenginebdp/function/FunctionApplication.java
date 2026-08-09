package com.levango7.dataenginebdp.function;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Java 函数运行时入口 · 数据引擎大数据平台 T025.
 *
 * <p>Spring Boot 3.3 + GraalVM Native Image，封装为 Knative Service。
 * 冷启动优化：Native Image 编译为单二进制，启动耗时 &lt; 1s（满足 ≤ 3s 目标）。</p>
 *
 * <p>特性：
 * <ul>
 *   <li>invocation 计量：Micrometer Prometheus 指标，按 tenant 标签隔离</li>
 *   <li>函数加载：从 classpath 或外部目录动态加载用户函数</li>
 *   <li>健康检查：/actuator/health 供 Knative readinessProbe 使用</li>
 * </ul></p>
 */
@SpringBootApplication
@EnableScheduling
public class FunctionApplication {

    /**
     * 应用入口.
     *
     * @param args 启动参数
     */
    public static void main(final String[] args) {
        SpringApplication.run(FunctionApplication.class, args);
    }
}