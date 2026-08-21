package com.shuqing.bigdata.common.health.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一健康检查响应 DTO。
 *
 * <p>替代现有 9 个 HealthController 中字段命名分裂的实现
 * （{@code component} / {@code service} / {@code layer} 三种命名并存），
 * 收敛为单一结构：{@code status} / {@code service} / {@code version} / {@code timestamp} / {@code details}。</p>
 *
 * <p>典型 JSON 输出：</p>
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "service": "lineage-analyzer",
 *   "version": "1.2.0",
 *   "timestamp": "2026-08-21T12:34:56.789Z",
 *   "details": { "knownTables": 42 }
 * }
 * }</pre>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>不可变对象，通过 {@link #up(String, String, Map)} / {@link #down(String, String, Map)} /
 *       {@link #degraded(String, String, Map)} 静态工厂构造。</li>
 *   <li>{@code version} 由调用方从 {@link org.springframework.boot.info.BuildProperties} 动态读取，
 *       不在此处硬编码。</li>
 *   <li>{@code details} 为空时通过 {@code JsonInclude.Include.NON_EMPTY} 省略，保持响应精简。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@JsonPropertyOrder({"status", "service", "version", "timestamp", "details"})
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class HealthResponse {

    /**
     * 健康状态枚举。
     *
     * <p>对齐 Kubernetes Probe 语义与 Spring Boot Actuator HealthStatus：</p>
     * <ul>
     *   <li>{@link #UP} - 服务可用，所有关键依赖正常。</li>
     *   <li>{@link #DOWN} - 服务不可用，关键依赖失败，应触发重启。</li>
     *   <li>{@link #DEGRADED} - 服务可用但部分依赖异常，可服务流量但需告警。</li>
     * </ul>
     */
    public enum Status {
        /** 服务可用，所有关键依赖正常。 */
        UP,
        /** 服务不可用，关键依赖失败。 */
        DOWN,
        /** 服务可用但部分依赖异常（降级运行）。 */
        DEGRADED
    }

    private final Status status;
    private final String service;
    private final String version;
    private final Instant timestamp;
    private final Map<String, Object> details;

    private HealthResponse(Status status, String service, String version,
                           Instant timestamp, Map<String, Object> details) {
        this.status = Objects.requireNonNull(status, "status 不能为 null");
        this.service = Objects.requireNonNull(service, "service 不能为 null");
        this.version = version;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.details = details != null && !details.isEmpty()
                ? Collections.unmodifiableMap(new LinkedHashMap<>(details))
                : Collections.emptyMap();
    }

    /**
     * 构造 UP 状态响应。
     *
     * @param service 服务名
     * @param version 版本号（可为 {@code null}）
     * @param details 附加详情（可为 {@code null}）
     * @return UP 状态的 HealthResponse
     */
    public static HealthResponse up(String service, String version, Map<String, Object> details) {
        return new HealthResponse(Status.UP, service, version, Instant.now(), details);
    }

    /**
     * 构造 UP 状态响应（无附加详情）。
     *
     * @param service 服务名
     * @param version 版本号（可为 {@code null}）
     * @return UP 状态的 HealthResponse
     */
    public static HealthResponse up(String service, String version) {
        return up(service, version, null);
    }

    /**
     * 构造 DOWN 状态响应。
     *
     * @param service 服务名
     * @param version 版本号（可为 {@code null}）
     * @param details 附加详情（建议包含失败原因，可为 {@code null}）
     * @return DOWN 状态的 HealthResponse
     */
    public static HealthResponse down(String service, String version, Map<String, Object> details) {
        return new HealthResponse(Status.DOWN, service, version, Instant.now(), details);
    }

    /**
     * 构造 DEGRADED 状态响应。
     *
     * @param service 服务名
     * @param version 版本号（可为 {@code null}）
     * @param details 附加详情（建议包含降级原因，可为 {@code null}）
     * @return DEGRADED 状态的 HealthResponse
     */
    public static HealthResponse degraded(String service, String version, Map<String, Object> details) {
        return new HealthResponse(Status.DEGRADED, service, version, Instant.now(), details);
    }

    /**
     * 通用构造方法。
     *
     * @param status 状态
     * @param service 服务名
     * @param version 版本号（可为 {@code null}）
     * @param details 附加详情（可为 {@code null}）
     * @return HealthResponse
     */
    public static HealthResponse of(Status status, String service, String version,
                                    Map<String, Object> details) {
        return new HealthResponse(status, service, version, Instant.now(), details);
    }

    /** @return 健康状态 */
    public Status getStatus() {
        return status;
    }

    /** @return 服务名 */
    public String getService() {
        return service;
    }

    /** @return 版本号，可能为 {@code null} */
    public String getVersion() {
        return version;
    }

    /** @return 检查时间戳 */
    public Instant getTimestamp() {
        return timestamp;
    }

    /** @return 附加详情的不可变视图，永不为 {@code null} */
    public Map<String, Object> getDetails() {
        return details;
    }

    /**
     * 判断是否为可用状态（UP 或 DEGRADED）。
     *
     * @return 若状态为 UP 或 DEGRADED 返回 {@code true}
     */
    public boolean isAvailable() {
        return status == Status.UP || status == Status.DEGRADED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HealthResponse that)) {
            return false;
        }
        return status == that.status
                && service.equals(that.service)
                && Objects.equals(version, that.version)
                && Objects.equals(timestamp, that.timestamp)
                && details.equals(that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, service, version, timestamp, details);
    }

    @Override
    public String toString() {
        return "HealthResponse{status=" + status
                + ", service='" + service + '\''
                + ", version='" + version + '\''
                + ", timestamp=" + timestamp
                + ", details=" + details + '}';
    }
}