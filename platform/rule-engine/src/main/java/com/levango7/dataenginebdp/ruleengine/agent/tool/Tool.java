package com.levango7.dataenginebdp.ruleengine.agent.tool;

import java.util.Map;
import java.util.Objects;

/**
 * Agent 可调用工具的统一抽象。
 *
 * <p>每个工具声明名称、描述、危险等级与执行函数。工具由
 * {@link ToolRegistry} 统一注册，由 {@link ToolSandbox} 在沙箱内执行。</p>
 *
 * @param name        工具唯一名（如 {@code nl2sql}、{@code query_metadata}）
 * @param description 工具描述
 * @param riskLevel   危险等级：{@code SAFE}（只读）、{@code MUTATING}（写操作）、{@code DANGEROUS}（高风险，需审核）
 * @param executor    执行函数，接收参数 map，返回结果对象
 * @author shuqing-bigdata
 */
public record Tool(
        String name,
        String description,
        RiskLevel riskLevel,
        ToolExecutor executor) {

    /**
     * 工具危险等级。
     */
    public enum RiskLevel {
        /** 只读安全（如元数据查询） */
        SAFE,
        /** 写操作（如生成代码、写入文档） */
        MUTATING,
        /** 高风险，需审核（如执行 SQL、删除资源） */
        DANGEROUS
    }

    /**
     * 工具执行函数。
     */
    @FunctionalInterface
    public interface ToolExecutor {
        /**
         * 执行工具。
         *
         * @param args 输入参数
         * @return 执行结果
         * @throws Exception 执行异常
         */
        Object execute(Map<String, Object> args) throws Exception;
    }

    /**
     * 校验工具字段非空。
     */
    public Tool {
        Objects.requireNonNull(name, "tool name must not be null");
        Objects.requireNonNull(riskLevel, "tool riskLevel must not be null");
        Objects.requireNonNull(executor, "tool executor must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
    }
}