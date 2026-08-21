package com.shuqing.bigdata.encaps.security.facade.audit;

import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * 审计统一门面（AuditFacade）。
 *
 * <p>负责收集、暂存、查询安全相关操作事件，作为合规证据的实时来源。</p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>环形缓冲</b>：内存中仅保留最近 N 条事件（默认 10000），避免无限增长；
 *       超过容量时丢弃最旧事件并记录 WARN</li>
 *   <li><b>线程安全</b>：使用 {@link CopyOnWriteArrayList} 保证并发写安全，
 *       读多写少场景下性能可接受</li>
 *   <li><b>同步落盘</b>：每次记录事件同时通过 SLF4J 输出到审计 logger，
 *       由 logback 配置可独立归档到文件</li>
 *   <li><b>查询能力</b>：支持按级别、租户、时间范围过滤，供 EvidenceCollector 拉取</li>
 * </ul>
 *
 * <h3>等保对应</h3>
 * <p>对应 GB/T 22239-2019 等保 2.0 安全审计控制项（8.1.4.3）：</p>
 * <ul>
 *   <li>a) 启用安全审计功能 — {@link #record(AuditEvent)}</li>
 *   <li>b) 审计记录包含日期、时间、用户、事件类型、是否成功 — {@link AuditEvent} 字段</li>
 *   <li>c) 保护审计记录，避免未预期的删除、修改或覆盖 — 不可变事件 + 环形缓冲上限告警</li>
 *   <li>d) 审计记录的留存期至少 6 个月 — 由外部日志归档策略保证</li>
 * </ul>
 */
@Component
public class AuditFacade {

    private static final Logger log = LoggerFactory.getLogger(AuditFacade.class);
    /** 独立审计 logger，可在 logback 中配置单独 appender 归档 */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("security.audit");

    private final SecurityFacadeConfig config;
    private final CopyOnWriteArrayList<AuditEvent> events;
    private final int maxRetained;

    /**
     * 构造 AuditFacade。
     *
     * @param config SecurityFacade 配置
     */
    public AuditFacade(SecurityFacadeConfig config) {
        this.config = config;
        this.maxRetained = Math.max(1, config.getAudit().getMaxEventsRetained());
        this.events = new CopyOnWriteArrayList<>();
        log.info("AuditFacade initialized, maxRetained={}", maxRetained);
    }

    /**
     * 记录审计事件。
     *
     * @param event 审计事件
     * @throws IllegalStateException 审计能力被禁用
     * @throws NullPointerException  event 为 null
     */
    public void record(AuditEvent event) {
        ensureEnabled();
        java.util.Objects.requireNonNull(event, "event");

        // 容量控制：超过上限时丢弃最旧事件
        if (events.size() >= maxRetained) {
            int overflow = events.size() - maxRetained + 1;
            // subList 视图删除底层元素
            events.subList(0, overflow).clear();
            log.warn("Audit buffer overflow, dropped {} oldest events (maxRetained={})",
                    overflow, maxRetained);
        }

        events.add(event);
        AUDIT_LOG.info("{}", event);
    }

    /**
     * 便捷方法：创建一个会自动记录事件的 RecordingBuilder。
     *
     * <p>用法：{@code facade.record("LOGIN").tenantId("t1").userId("u1").build();}</p>
     *
     * @param action 操作名
     * @return RecordingBuilder，调用 {@link RecordingBuilder#build()} 后自动记录
     */
    public RecordingBuilder record(String action) {
        return new RecordingBuilder(action, this);
    }

    /**
     * 查询全部事件（不可变视图）。
     *
     * @return 事件列表
     */
    public List<AuditEvent> list() {
        return Collections.unmodifiableList(events);
    }

    /**
     * 按级别查询。
     *
     * @param level 级别
     * @return 匹配的事件列表
     */
    public List<AuditEvent> listByLevel(AuditLevel level) {
        return filterBy(e -> e.getLevel() == level);
    }

    /**
     * 按租户查询。
     *
     * @param tenantId 租户 ID
     * @return 匹配的事件列表
     */
    public List<AuditEvent> listByTenant(String tenantId) {
        return filterBy(e -> java.util.Objects.equals(e.getTenantId(), tenantId));
    }

    /**
     * 按时间范围查询。
     *
     * @param from 起始时间（包含）
     * @param to   结束时间（不包含）
     * @return 匹配的事件列表
     */
    public List<AuditEvent> listByTimeRange(Instant from, Instant to) {
        return filterBy(e -> !e.getTimestamp().isBefore(from) && e.getTimestamp().isBefore(to));
    }

    /**
     * 当前缓冲事件数。
     *
     * @return 事件数
     */
    public int size() {
        return events.size();
    }

    /**
     * 清空缓冲（仅用于测试）。
     */
    public void clear() {
        events.clear();
    }

    // ===== 内部 =====

    private List<AuditEvent> filterBy(Predicate<AuditEvent> predicate) {
        List<AuditEvent> result = new ArrayList<>();
        for (AuditEvent e : events) {
            if (predicate.test(e)) {
                result.add(e);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private void ensureEnabled() {
        if (!config.isEnabled() || !config.getAudit().isEnabled()) {
            throw new IllegalStateException("AuditFacade is disabled (app.security.facade.enabled="
                    + config.isEnabled() + ", audit.enabled=" + config.getAudit().isEnabled() + ")");
        }
    }

    /**
     * 自动记录的 Builder。
     *
     * <p>fluent API，调用 {@link #build()} 时自动通过 {@link AuditFacade#record(AuditEvent)} 记录事件。
     * 不实现 {@link AuditEvent.Builder} 接口以避免返回类型协变问题，
     * 而是提供相同的方法签名，返回自身类型。</p>
     */
    public static final class RecordingBuilder {
        private final AuditEvent.Builder delegate;
        private final AuditFacade facade;

        RecordingBuilder(String action, AuditFacade facade) {
            this.delegate = AuditEvent.builder().action(action);
            this.facade = facade;
        }

        /**
         * 设置时间戳。
         *
         * @param t 时间戳
         * @return this
         */
        public RecordingBuilder timestamp(Instant t) { delegate.timestamp(t); return this; }

        /**
         * 设置级别。
         *
         * @param l 级别
         * @return this
         */
        public RecordingBuilder level(AuditLevel l) { delegate.level(l); return this; }

        /**
         * 设置操作名。
         *
         * @param a 操作名
         * @return this
         */
        public RecordingBuilder action(String a) { delegate.action(a); return this; }

        /**
         * 设置租户 ID。
         *
         * @param t 租户 ID
         * @return this
         */
        public RecordingBuilder tenantId(String t) { delegate.tenantId(t); return this; }

        /**
         * 设置用户 ID。
         *
         * @param u 用户 ID
         * @return this
         */
        public RecordingBuilder userId(String u) { delegate.userId(u); return this; }

        /**
         * 设置资源标识。
         *
         * @param r 资源
         * @return this
         */
        public RecordingBuilder resource(String r) { delegate.resource(r); return this; }

        /**
         * 设置结果。
         *
         * @param r 结果
         * @return this
         */
        public RecordingBuilder result(String r) { delegate.result(r); return this; }

        /**
         * 添加附加详情。
         *
         * @param key   键
         * @param value 值
         * @return this
         */
        public RecordingBuilder detail(String key, String value) { delegate.detail(key, value); return this; }

        /**
         * 构建并自动记录事件。
         *
         * @return 已记录的 AuditEvent
         */
        public AuditEvent build() {
            AuditEvent event = delegate.build();
            facade.record(event);
            return event;
        }
    }
}
