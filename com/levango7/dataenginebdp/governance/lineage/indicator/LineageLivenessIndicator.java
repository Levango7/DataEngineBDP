package com.shuqing.bigdata.governance.lineage.indicator;

import com.shuqing.bigdata.common.health.indicator.LivenessHealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 血缘分析服务 Actuator liveness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/liveness} 端点，
 * 对应 K8s livenessProbe 语义。仅检查进程存活，不查外部依赖（不查图存储），
 * 始终快速返回 UP，避免因 NebulaGraph/H2 抖动触发级联容器重启。</p>
 *
 * <p>bean 名 {@code lineageLivenessIndicator} 以 {@code livenessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 liveness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class LineageLivenessIndicator extends LivenessHealthIndicator {

    @Override
    protected String serviceName() {
        return "lineage-analyzer";
    }
}