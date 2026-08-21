package com.shuqing.bigdata.finops.indicator;

import com.shuqing.bigdata.common.health.indicator.LivenessHealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 成本模型服务 Actuator liveness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/liveness} 端点，
 * 对应 K8s livenessProbe 语义。仅检查进程存活，不查外部依赖，
 * 始终快速返回 UP，避免因 Prometheus 抖动触发级联容器重启。</p>
 *
 * <p>bean 名 {@code costModelLivenessIndicator} 以 {@code livenessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 liveness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class CostModelLivenessIndicator extends LivenessHealthIndicator {

    @Override
    protected String serviceName() {
        return "cost-model";
    }
}