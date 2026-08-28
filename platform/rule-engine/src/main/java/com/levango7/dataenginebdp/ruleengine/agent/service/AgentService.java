package com.levango7.dataenginebdp.ruleengine.agent.service;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import com.levango7.dataenginebdp.ruleengine.agent.core.BaseAgent;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 编排调度服务。
 *
 * <p>统一管理 8 种内置 Agent 角色的注册、路由与执行：
 * <ul>
 *   <li>启动时收集所有 {@link Agent} bean，建立 {@link Agent.Role} → Agent 路由表</li>
 *   <li>{@link #execute} 按角色路由到具体 Agent，委托 {@link BaseAgent#execute} 完成模板流程</li>
 *   <li>{@link #listRoles}/{@link #describe} 暴露角色元数据，供 REST 与监控查询</li>
 *   <li>失败兜底：未知角色返回 {@link AgentResult.Status#FAILURE}</li>
 * </ul>
 *
 * <p>本服务不直接处理配额与白名单（由 {@link BaseAgent} 模板方法完成），
 * 仅负责"找到正确的 Agent 并调用"。</p>
 *
 * @author shuqing-bigdata
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 角色 → Agent 路由表 */
    private final Map<Agent.Role, Agent> agents = new EnumMap<>(Agent.Role.class);

    /** 配额执行器（用于 describe 暴露默认配额） */
    private final QuotaEnforcer quotaEnforcer;

    /** 工具白名单（用于 describe 暴露白名单） */
    private final ToolWhitelist toolWhitelist;

    /**
     * 构造时收集所有 Agent bean 并建立路由表。
     *
     * @param agentBeans      Spring 注入的所有 Agent 实现
     * @param quotaEnforcer   配额执行器
     * @param toolWhitelist   工具白名单
     */
    public AgentService(List<Agent> agentBeans, QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist) {
        this.quotaEnforcer = quotaEnforcer;
        this.toolWhitelist = toolWhitelist;
        for (Agent agent : agentBeans) {
            Agent.Role role = agent.getRole();
            Agent previous = agents.put(role, agent);
            if (previous != null) {
                log.warn("Duplicate Agent for role {}: {} overridden by {}",
                        role, previous.getClass().getSimpleName(), agent.getClass().getSimpleName());
            }
        }
        log.info("AgentService initialized with {} roles: {}", agents.size(), agents.keySet());
    }

    /**
     * 按角色执行 Agent。
     *
     * @param role    Agent 角色
     * @param context 执行上下文
     * @return 执行结果；未知角色返回 FAILURE
     */
    public AgentResult execute(Agent.Role role, AgentContext context) {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(context, "context must not be null");

        Agent agent = agents.get(role);
        if (agent == null) {
            log.warn("No Agent registered for role {}", role);
            return AgentResult.failure(role, AgentResult.Status.FAILURE,
                    "agent_not_found", "No Agent registered for role " + role,
                    0L, context.getTenantId(), context.getRequestId());
        }

        if (agent instanceof BaseAgent baseAgent) {
            return baseAgent.execute(context);
        }
        // 兜底：直接调用 doExecute（非 BaseAgent 实现的场景）
        return agent.doExecute(context);
    }

    /**
     * 列出所有已注册角色。
     *
     * @return 不可变角色集合
     */
    public Set<Agent.Role> listRoles() {
        return Collections.unmodifiableSet(agents.keySet());
    }

    /**
     * 查找角色对应的 Agent。
     *
     * @param role Agent 角色
     * @return Agent；不存在返回 {@link Optional#empty()}
     */
    public Optional<Agent> getAgent(Agent.Role role) {
        return Optional.ofNullable(agents.get(role));
    }

    /**
     * 描述所有角色的元数据（类名、默认配额、白名单）。
     *
     * @return 角色 → 元数据
     */
    public Map<Agent.Role, Map<String, Object>> describe() {
        Map<Agent.Role, Map<String, Object>> desc = new EnumMap<>(Agent.Role.class);
        for (Map.Entry<Agent.Role, Agent> entry : agents.entrySet()) {
            Agent.Role role = entry.getKey();
            Agent agent = entry.getValue();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("role", role.name());
            meta.put("implementation", agent.getClass().getSimpleName());
            meta.put("defaultQuota", quotaEnforcer.defaultQuotaOf(role));
            meta.put("allowedTools", toolWhitelist.allowlistOf(role));
            desc.put(role, meta);
        }
        return desc;
    }

    /**
     * 判断角色是否已注册。
     *
     * @param role Agent 角色
     * @return {@code true} 若已注册
     */
    public boolean hasRole(Agent.Role role) {
        return agents.containsKey(role);
    }

    /**
     * 动态注册 Agent（运行时扩展场景）。
     *
     * @param agent Agent 实现
     * @return 被覆盖的旧 Agent；无则返回 {@code null}
     */
    public Agent register(Agent agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        return agents.put(agent.getRole(), agent);
    }
}