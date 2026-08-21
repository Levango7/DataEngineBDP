package com.shuqing.bigdata.ruleengine.agent.tool;

import com.shuqing.bigdata.ruleengine.agent.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用白名单。
 *
 * <p>按 Agent 角色配置允许调用的工具集合，实现"最小权限"原则：
 * 每个角色只能调用其白名单内的工具，跨角色调用被拒绝。</p>
 *
 * <p>白名单来源：
 * <ol>
 *   <li>构造时传入的默认白名单（来自 {@code AgentConfig}）</li>
 *   <li>运行时通过 {@link #grant} 动态授权（如管理员临时放行）</li>
 *   <li>运行时通过 {@link #revoke} 动态回收</li>
 * </ol>
 *
 * <p>当 {@link #checkAllowed(Agent.Role, java.util.Set)} 收到 {@code null} requestedTools 时，
 * 表示本次调用未显式指定工具集，直接放行（由角色默认白名单在执行时约束）。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class ToolWhitelist {

    private static final Logger log = LoggerFactory.getLogger(ToolWhitelist.class);

    /** 角色 → 允许的工具集合（运行时可变，使用 ConcurrentHashMap 保证线程安全） */
    private final Map<Agent.Role, Set<String>> roleAllowlist = new EnumMap<>(Agent.Role.class);

    /**
     * 使用默认白名单构造。
     *
     * @param defaults 角色 → 默认白名单；{@code null} 视为空
     */
    public ToolWhitelist(Map<Agent.Role, Set<String>> defaults) {
        if (defaults != null) {
            defaults.forEach((role, tools) ->
                    roleAllowlist.put(role, ConcurrentHashMap.newKeySet()));
            defaults.forEach((role, tools) -> {
                if (tools != null) {
                    roleAllowlist.get(role).addAll(tools);
                }
            });
        }
    }

    /**
     * 默认空白名单构造（Spring 自动注入用，后续由 AgentConfig 填充）。
     */
    public ToolWhitelist() {
        this(null);
    }

    /**
     * 校验请求的工具集合是否全部在角色白名单内。
     *
     * @param role           Agent 角色
     * @param requestedTools 请求调用的工具集合；{@code null} 或空表示未显式指定，直接放行
     * @return 第一个不在白名单内的工具名；全部允许返回 {@code null}
     */
    public String checkAllowed(Agent.Role role, Set<String> requestedTools) {
        Objects.requireNonNull(role, "role must not be null");
        if (requestedTools == null || requestedTools.isEmpty()) {
            return null;
        }
        Set<String> allowed = roleAllowlist.getOrDefault(role, Set.of());
        for (String tool : requestedTools) {
            if (!allowed.contains(tool)) {
                log.warn("Tool '{}' denied for role {} (allowlist: {})", tool, role, allowed);
                return tool;
            }
        }
        return null;
    }

    /**
     * 判断工具是否在角色白名单内。
     *
     * @param role     Agent 角色
     * @param toolName 工具名
     * @return {@code true} 若允许
     */
    public boolean isAllowed(Agent.Role role, String toolName) {
        Set<String> allowed = roleAllowlist.get(role);
        return allowed != null && allowed.contains(toolName);
    }

    /**
     * 获取角色白名单的只读视图。
     *
     * @param role Agent 角色
     * @return 不可变工具集合；未配置返回空集合
     */
    public Set<String> allowlistOf(Agent.Role role) {
        Set<String> allowed = roleAllowlist.get(role);
        return allowed == null ? Set.of() : Collections.unmodifiableSet(allowed);
    }

    /**
     * 动态授权：向角色白名单添加工具。
     *
     * @param role     Agent 角色
     * @param toolName 工具名
     */
    public void grant(Agent.Role role, String toolName) {
        roleAllowlist.computeIfAbsent(role, r -> ConcurrentHashMap.newKeySet()).add(toolName);
        log.info("Granted tool '{}' to role {}", toolName, role);
    }

    /**
     * 动态回收：从角色白名单移除工具。
     *
     * @param role     Agent 角色
     * @param toolName 工具名
     * @return {@code true} 若实际移除
     */
    public boolean revoke(Agent.Role role, String toolName) {
        Set<String> allowed = roleAllowlist.get(role);
        if (allowed == null) {
            return false;
        }
        boolean removed = allowed.remove(toolName);
        if (removed) {
            log.info("Revoked tool '{}' from role {}", toolName, role);
        }
        return removed;
    }

    /**
     * 重置角色白名单。
     *
     * @param role  Agent 角色
     * @param tools 新白名单
     */
    public void reset(Agent.Role role, Set<String> tools) {
        Set<String> newSet = ConcurrentHashMap.newKeySet();
        if (tools != null) {
            newSet.addAll(tools);
        }
        roleAllowlist.put(role, newSet);
    }

    /**
     * 获取全部角色白名单快照。
     *
     * @return 角色 → 不可变工具集合
     */
    public Map<Agent.Role, Set<String>> snapshot() {
        Map<Agent.Role, Set<String>> snap = new EnumMap<>(Agent.Role.class);
        roleAllowlist.forEach((role, tools) -> snap.put(role, Set.copyOf(tools)));
        return Collections.unmodifiableMap(snap);
    }
}