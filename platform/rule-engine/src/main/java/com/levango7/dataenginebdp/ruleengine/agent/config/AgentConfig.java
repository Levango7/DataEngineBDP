package com.levango7.dataenginebdp.ruleengine.agent.config;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.quota.AgentQuota;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.Tool;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolRegistry;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 模块配置。
 *
 * <p>绑定 {@code app.agent.*} 配置项，在启动后初始化：
 * <ol>
 *   <li>工具白名单：按角色填充 {@link ToolWhitelist}</li>
 *   <li>资源配额：按角色覆盖 {@link QuotaEnforcer} 默认配额</li>
 *   <li>内置工具：注册只读元数据查询工具到 {@link ToolRegistry}</li>
 * </ol>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * app:
 *   agent:
 *     enabled: true
 *     sandbox:
 *       pool-size: 4
 *       default-timeout-ms: 30000
 *     whitelist:
 *       SQL: [nl2sql, query_metadata]
 *       AUDIT: [audit_sql]
 *     quota:
 *       SQL:
 *         max-tool-calls: 5
 *         max-duration-ms: 30000
 * </pre>
 *
 * @author shuqing-bigdata
 */
@Configuration
@ConfigurationProperties(prefix = "app.agent")
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /** 是否启用 Agent 模块 */
    private boolean enabled = true;

    /** 沙箱配置 */
    private Sandbox sandbox = new Sandbox();

    /** 角色 → 工具白名单 */
    private Map<String, List<String>> whitelist = new LinkedHashMap<>();

    /** 角色 → 配额覆盖 */
    private Map<String, QuotaOverride> quota = new LinkedHashMap<>();

    /** 依赖：工具白名单 */
    private final ToolWhitelist toolWhitelist;
    /** 依赖：配额执行器 */
    private final QuotaEnforcer quotaEnforcer;
    /** 依赖：工具注册中心 */
    private final ToolRegistry toolRegistry;

    public AgentConfig(ToolWhitelist toolWhitelist, QuotaEnforcer quotaEnforcer, ToolRegistry toolRegistry) {
        this.toolWhitelist = toolWhitelist;
        this.quotaEnforcer = quotaEnforcer;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 启动后初始化白名单、配额与内置工具。
     */
    @PostConstruct
    public void initialize() {
        if (!enabled) {
            log.info("Agent module disabled, skip initialization");
            return;
        }
        initWhitelist();
        initQuota();
        initBuiltinTools();
        log.info("AgentConfig initialized: whitelist roles={}, quota overrides={}, tools={}",
                whitelist.size(), quota.size(), toolRegistry.names().size());
    }

    /**
     * 初始化工具白名单。
     */
    private void initWhitelist() {
        Map<Agent.Role, Set<String>> defaults = defaultWhitelists();
        for (Map.Entry<Agent.Role, Set<String>> entry : defaults.entrySet()) {
            toolWhitelist.reset(entry.getKey(), entry.getValue());
        }
        // 配置覆盖
        whitelist.forEach((roleStr, tools) -> {
            Agent.Role role = parseRole(roleStr);
            if (role != null) {
                toolWhitelist.reset(role, tools == null ? Set.of() : Set.copyOf(tools));
            }
        });
    }

    /**
     * 初始化配额覆盖。
     */
    private void initQuota() {
        quota.forEach((roleStr, override) -> {
            Agent.Role role = parseRole(roleStr);
            if (role == null || override == null) {
                return;
            }
            AgentQuota base = quotaEnforcer.defaultQuotaOf(role);
            AgentQuota overridden = base.merge(AgentQuota.builder()
                    .maxToolCalls(override.maxToolCalls)
                    .maxDurationMs(override.maxDurationMs)
                    .maxOutputChars(override.maxOutputChars)
                    .maxConcurrentExecutions(override.maxConcurrentExecutions)
                    .dailyCallLimit(override.dailyCallLimit)
                    .build());
            quotaEnforcer.updateDefaultQuota(role, overridden);
        });
    }

    /**
     * 注册内置只读工具（元数据查询等），供各角色 fallback 使用。
     */
    private void initBuiltinTools() {
        toolRegistry.register(new Tool(
                "query_metadata", "查询表/字段元数据（只读）",
                Tool.RiskLevel.SAFE,
                args -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "mock");
                    result.put("message", "metadata query tool stub");
                    return result;
                }
        ));
    }

    /**
     * 默认白名单：每个角色允许调用的工具集合。
     */
    private Map<Agent.Role, Set<String>> defaultWhitelists() {
        Map<Agent.Role, Set<String>> map = new EnumMap<>(Agent.Role.class);
        map.put(Agent.Role.PLANNING, Set.of("task_decompose", "query_metadata"));
        map.put(Agent.Role.SQL, Set.of("nl2sql", "query_metadata"));
        map.put(Agent.Role.VISUALIZATION, Set.of("recommend_chart", "query_metadata"));
        map.put(Agent.Role.QUALITY, Set.of("generate_dq_rule", "query_metadata"));
        map.put(Agent.Role.LINEAGE, Set.of("analyze_lineage", "query_metadata"));
        map.put(Agent.Role.DOCUMENTATION, Set.of("generate_doc", "query_metadata"));
        map.put(Agent.Role.CODE, Set.of("generate_code"));
        map.put(Agent.Role.AUDIT, Set.of("audit_sql"));
        return map;
    }

    private Agent.Role parseRole(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Agent.Role.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown role in config: {}", s);
            return null;
        }
    }

    // --- getters/setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Sandbox getSandbox() { return sandbox; }
    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }

    public Map<String, List<String>> getWhitelist() { return whitelist; }
    public void setWhitelist(Map<String, List<String>> whitelist) { this.whitelist = whitelist; }

    public Map<String, QuotaOverride> getQuota() { return quota; }
    public void setQuota(Map<String, QuotaOverride> quota) { this.quota = quota; }

    /**
     * 沙箱配置。
     */
    public static class Sandbox {
        private int poolSize = 4;
        private long defaultTimeoutMs = 30_000L;

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
        public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
        public void setDefaultTimeoutMs(long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
    }

    /**
     * 配额覆盖项。
     */
    public static class QuotaOverride {
        private Integer maxToolCalls;
        private Long maxDurationMs;
        private Integer maxOutputChars;
        private Integer maxConcurrentExecutions;
        private Integer dailyCallLimit;

        public Integer getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(Integer maxToolCalls) { this.maxToolCalls = maxToolCalls; }
        public Long getMaxDurationMs() { return maxDurationMs; }
        public void setMaxDurationMs(Long maxDurationMs) { this.maxDurationMs = maxDurationMs; }
        public Integer getMaxOutputChars() { return maxOutputChars; }
        public void setMaxOutputChars(Integer maxOutputChars) { this.maxOutputChars = maxOutputChars; }
        public Integer getMaxConcurrentExecutions() { return maxConcurrentExecutions; }
        public void setMaxConcurrentExecutions(Integer maxConcurrentExecutions) { this.maxConcurrentExecutions = maxConcurrentExecutions; }
        public Integer getDailyCallLimit() { return dailyCallLimit; }
        public void setDailyCallLimit(Integer dailyCallLimit) { this.dailyCallLimit = dailyCallLimit; }
    }
}