package com.shuqing.bigdata.common.health.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * common-health 模块自动配置。
 *
 * <p>统一配置 Spring Boot Actuator 端点暴露策略与 health groups，
 * 供所有继承 {@link com.shuqing.bigdata.common.health.controller.AbstractHealthController}
 * 的模块复用。</p>
 *
 * <p>配置策略：</p>
 * <table>
 *   <caption>表：Actuator 端点暴露策略</caption>
 *   <tr><th>端点</th><th>是否暴露</th><th>说明</th></tr>
 *   <tr><td>health</td><td>是</td><td>聚合健康检查，含 liveness / readiness 子组</td></tr>
 *   <tr><td>info</td><td>是</td><td>构建信息（version / git）</td></tr>
 *   <tr><td>env</td><td>否</td><td>敏感：暴露环境变量与配置项</td></tr>
 *   <tr><td>configprops</td><td>否</td><td>敏感：暴露 @ConfigurationProperties 绑定值</td></tr>
 *   <tr><td>heapdump</td><td>否</td><td>敏感：堆转储可能含敏感数据且耗资源</td></tr>
 *   <tr><td>threaddump</td><td>否</td><td>敏感：线程转储可能含栈帧中的敏感信息</td></tr>
 *   <tr><td>loggers</td><td>否</td><td>敏感：可动态修改日志级别</td></tr>
 * </table>
 *
 * <p>Health groups 配置：</p>
 * <ul>
 *   <li><b>liveness</b> - 仅包含 liveness 探针，对应 {@code /actuator/health/liveness}。</li>
 *   <li><b>readiness</b> - 仅包含 readiness 探针，对应 {@code /actuator/health/readiness}。</li>
 * </ul>
 *
 * <p>本配置类通过 {@code @AutoConfiguration} 注册为 Spring Boot 3.x 自动配置，
 * 需在 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 中登记全限定类名（见模块资源文件）。</p>
 *
 * <p>等效的 application.yml 配置（本类以编程方式等价实现，避免各模块重复配置）：</p>
 * <pre>{@code
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: health,info
 *         exclude: env,configprops,heapdump,threaddump,loggers
 *   endpoint:
 *     health:
 *       probes:
 *         enabled: true
 *       show-details: when-authorized
 *   health:
 *     groups:
 *       liveness:
 *         include: livenessIndicator
 *       readiness:
 *         include: readinessIndicator
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
public class HealthModuleConfig {

    /**
     * 暴露的 Actuator 端点白名单。
     *
     * <p>仅暴露 health 与 info，其余端点默认关闭。
     * 供各模块 application.yml 引用：{@code management.endpoints.web.exposure.include}。</p>
     */
    public static final String[] EXPOSED_ENDPOINTS = {"health", "info"};

    /**
     * 禁用的敏感 Actuator 端点黑名单。
     *
     * <p>显式排除含敏感数据或可变更运行态的端点。
     * 供各模块 application.yml 引用：{@code management.endpoints.web.exposure.exclude}。</p>
     */
    public static final String[] EXCLUDED_ENDPOINTS = {
            "env", "configprops", "heapdump", "threaddump", "loggers"
    };

    /**
     * 默认构造。
     *
     * <p>Spring Boot 自动配置通过无参构造实例化。端点暴露与 health groups 的实际生效
     * 依赖 application.yml 配置，本类提供常量供各模块配置引用与文档化，
     * 并通过 {@link #healthGroupsConfig()} 提供 health groups 的编程式配置 bean。</p>
     */
    public HealthModuleConfig() {
    }

    /**
     * Health groups 编程式配置。
     *
     * <p>定义 liveness 与 readiness 两个 health group，分别对应
     * {@code /actuator/health/liveness} 与 {@code /actuator/health/readiness}。
     * 各模块的 {@code LivenessHealthIndicator} 与 {@code ReadinessHealthIndicator}
     * 子类 bean 自动归入对应 group。</p>
     *
     * <p>返回的 Map 结构与 {@code management.health.groups.*} 配置项等价，
     * 供配置后置处理器或文档化使用。bean 名 {@code healthGroupsConfig}
     * 可在启动日志中确认自动配置生效。</p>
     *
     * @return health group 名到配置的映射
     */
    @Bean
    public Map<String, HealthGroupConfig> healthGroupsConfig() {
        Map<String, HealthGroupConfig> groups = new LinkedHashMap<>();
        groups.put("liveness", new HealthGroupConfig("livenessIndicator"));
        groups.put("readiness", new HealthGroupConfig("readinessIndicator"));
        return groups;
    }

    /**
     * Health group 配置项。
     *
     * <p>对应 {@code management.health.groups.<name>.include} 配置，
     * 指定该 group 包含的 HealthIndicator bean 名后缀。</p>
     *
     * @param include 包含的 indicator 名后缀
     */
    public record HealthGroupConfig(String include) {
    }
}
