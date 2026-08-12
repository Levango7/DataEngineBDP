package com.levango7.dataenginebdp.ruleengine.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心。
 *
 * <p>统一管理所有 Agent 可调用的工具，提供注册、查找、列举能力。
 * 注册线程安全（{@link ConcurrentHashMap}），查找返回不可变视图。</p>
 *
 * <p>典型工具：
 * <ul>
 *   <li>{@code nl2sql}：自然语言转 SQL（对接 T010）</li>
 *   <li>{@code query_metadata}：查询表/字段元数据</li>
 *   <li>{@code recommend_chart}：图表类型推荐</li>
 *   <li>{@code analyze_lineage}：血缘分析</li>
 *   <li>{@code generate_doc}：文档生成</li>
 *   <li>{@code generate_code}：代码生成</li>
 *   <li>{@code audit_sql}：SQL 安全审核</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具名 → 工具定义 */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具。若同名工具已存在则覆盖并记录警告。
     *
     * @param tool 工具定义
     * @return 本注册中心，便于链式注册
     */
    public ToolRegistry register(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        Tool previous = tools.put(tool.name(), tool);
        if (previous != null) {
            log.warn("Tool '{}' overridden by new registration", tool.name());
        } else {
            log.debug("Tool '{}' registered (risk={})", tool.name(), tool.riskLevel());
        }
        return this;
    }

    /**
     * 批量注册工具。
     *
     * @param tools 工具集合
     * @return 本注册中心
     */
    public ToolRegistry registerAll(Collection<Tool> tools) {
        if (tools != null) {
            tools.forEach(this::register);
        }
        return this;
    }

    /**
     * 按名查找工具。
     *
     * @param name 工具名
     * @return 工具定义；不存在返回 {@link Optional#empty()}
     */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 判断工具是否已注册。
     *
     * @param name 工具名
     * @return {@code true} 若已注册
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 列出所有已注册工具名。
     *
     * @return 不可变工具名集合
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * 列出所有已注册工具定义。
     *
     * @return 不可变工具 map（名 → 定义）
     */
    public Map<String, Tool> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }

    /**
     * 注销工具。
     *
     * @param name 工具名
     * @return 被移除的工具；不存在返回 {@code null}
     */
    public Tool unregister(String name) {
        return tools.remove(name);
    }

    /**
     * 清空所有注册。
     */
    public void clear() {
        tools.clear();
    }
}