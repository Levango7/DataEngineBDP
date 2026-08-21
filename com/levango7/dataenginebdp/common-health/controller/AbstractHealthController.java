package com.shuqing.bigdata.common.health.controller;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

/**
 * 健康检查控制器模板方法基类。
 *
 * <p>统一 9 个 HealthController 的实现模式，子类只需实现两个抽象方法：</p>
 * <ul>
 *   <li>{@link #serviceName()} - 返回模块服务名（如 {@code "lineage-analyzer"}）。</li>
 *   <li>{@link #probeReadiness()} - 探测模块关键依赖，返回 {@link HealthResponse}。</li>
 * </ul>
 *
 * <p>基类提供三个 REST 端点（均无需鉴权，由各模块 SecurityConfig permitAll 放行）：</p>
 * <table>
 *   <caption>表：健康检查端点说明</caption>
 *   <tr><th>端点</th><th>语义</th><th>是否查外部依赖</th></tr>
 *   <tr><td>GET /api/v1/health/liveness</td><td>存活探针（K8s livenessProbe）</td><td>否，始终快速返回 UP</td></tr>
 *   <tr><td>GET /api/v1/health/readiness</td><td>就绪探针（K8s readinessProbe）</td><td>是，调用 {@link #probeReadiness()}</td></tr>
 *   <tr><td>GET /api/v1/health</td><td>向后兼容端点</td><td>是，默认委托 {@link #readiness()}</td></tr>
 * </table>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>liveness 遵循 K8s 社区约定：仅检查进程存活，不查外部依赖，避免级联重启。</li>
 *   <li>readiness 调用子类 {@link #probeReadiness()} 探测真实依赖（DB / 缓存 / 下游服务）。</li>
 *   <li>readiness 在 DOWN 时返回 HTTP 503，UP/DEGRADED 返回 200，便于负载均衡器摘除流量。</li>
 *   <li>版本号从 {@link BuildProperties} 动态读取（spring-boot 插件自动注入），
 *       BuildProperties 不存在时降级为 {@code "unknown"}。</li>
 *   <li>{@link #health()} 非 final，子类可 override 以保留旧响应结构（迁移过渡期）。</li>
 * </ul>
 *
 * <p>子类示例：</p>
 * <pre>{@code
 * @RestController
 * public class LineageHealthController extends AbstractHealthController {
 *     private final LineageGraphWriter graphWriter;
 *
 *     public LineageHealthController(ObjectProvider<BuildProperties> bp,
 *                                    LineageGraphWriter graphWriter) {
 *         super(bp);
 *         this.graphWriter = graphWriter;
 *     }
 *
 *     @Override
 *     protected String serviceName() { return "lineage-analyzer"; }
 *
 *     @Override
 *     protected HealthResponse probeReadiness() {
 *         int tables = graphWriter.getKnownTables().size();
 *         Map<String, Object> details = Map.of("knownTables", tables);
 *         return HealthResponse.up(serviceName(), resolveVersion(), details);
 *     }
 * }
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@RequestMapping("/api/v1/health")
public abstract class AbstractHealthController {

    private static final String UNKNOWN_VERSION = "unknown";

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    /**
     * 构造基类。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者，
     *        当未配置 spring-boot 插件的 build-info 目标时，bean 不存在，降级为 {@code "unknown"}
     */
    protected AbstractHealthController(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.buildPropertiesProvider = buildPropertiesProvider;
    }

    /**
     * 返回模块服务名，用于响应体 {@code service} 字段。
     *
     * <p>建议返回与 K8s Service 名一致的稳定标识，如 {@code "lineage-analyzer"}。</p>
     *
     * @return 服务名
     */
    protected abstract String serviceName();

    /**
     * 探测模块关键依赖的就绪状态。
     *
     * <p>子类在此方法中检查 DB 连通性、缓存可用性、下游服务可达性等。
     * 方法应快速返回（建议超时 1-2s），避免阻塞 K8s 探针。</p>
     *
     * @return 就绪探测结果
     */
    protected abstract HealthResponse probeReadiness();

    /**
     * 存活探针端点。
     *
     * <p>对应 K8s livenessProbe，仅检查进程存活，<strong>不查外部依赖</strong>，
     * 始终快速返回 UP。避免因外部依赖抖动触发不必要的容器重启。</p>
     *
     * @return UP 状态的 HealthResponse，HTTP 200
     */
    @GetMapping("/liveness")
    public final ResponseEntity<HealthResponse> liveness() {
        HealthResponse response = HealthResponse.up(serviceName(), resolveVersion());
        return ResponseEntity.ok(response);
    }

    /**
     * 就绪探针端点。
     *
     * <p>对应 K8s readinessProbe，调用 {@link #probeReadiness()} 探测真实依赖。
     * DOWN 时返回 HTTP 503 供负载均衡器摘除流量；UP / DEGRADED 返回 200。</p>
     *
     * @return 探测结果，HTTP 200 或 503
     */
    @GetMapping("/readiness")
    public final ResponseEntity<HealthResponse> readiness() {
        HealthResponse response;
        try {
            response = probeReadiness();
        } catch (Exception ex) {
            response = HealthResponse.down(serviceName(), resolveVersion(),
                    Map.of("error", ex.getClass().getSimpleName(),
                           "message", String.valueOf(ex.getMessage())));
        }
        HttpStatus httpStatus = response.getStatus() == HealthResponse.Status.DOWN
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * 向后兼容端点。
     *
     * <p>保留 {@code GET /api/v1/health} 供现有平台探针与运维大盘平滑迁移，
     * 默认委托 {@link #readiness()} 返回统一 {@link HealthResponse} 结构。</p>
     *
     * <p>迁移过渡期，子类可 override 此方法保留旧的 {@code Map<String, Object>} 响应结构，
     * 待上游消费者全部切换到 {@code /liveness} 与 {@code /readiness} 后再移除 override。</p>
     *
     * @return 探测结果，HTTP 200 或 503
     */
    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        return readiness();
    }

    /**
     * 解析应用版本号。
     *
     * <p>从 {@link BuildProperties} 动态读取（spring-boot-maven-plugin 的 build-info 目标
     * 或 spring-boot-gradle-plugin 的 bootBuildInfo 任务自动生成 META-INF/build-info.properties）。
     * 当 BuildProperties bean 不存在时降级为 {@code "unknown"}。</p>
     *
     * @return 版本号，永不为 {@code null}
     */
    protected String resolveVersion() {
        BuildProperties bp = buildPropertiesProvider.getIfAvailable();
        return bp != null && bp.getVersion() != null ? bp.getVersion() : UNKNOWN_VERSION;
    }
}