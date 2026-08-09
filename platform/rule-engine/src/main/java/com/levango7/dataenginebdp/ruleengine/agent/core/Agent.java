package com.levango7.dataenginebdp.ruleengine.agent.core;

/**
 * 内置 Agent 角色统一接口。
 *
 * <p>数擎大数据平台 V2.0 在规则引擎模块下提供 8 种内置 Agent 角色：
 * <ul>
 *   <li>{@link Role#PLANNING}      规划 Agent：任务分解 + 执行计划生成</li>
 *   <li>{@link Role#SQL}           SQL Agent：自然语言转 SQL（对接 T010 NL2SQL）</li>
 *   <li>{@link Role#VISUALIZATION} 可视化 Agent：图表推荐 + 仪表盘生成</li>
 *   <li>{@link Role#QUALITY}       质量 Agent：数据质量检查规则生成</li>
 *   <li>{@link Role#LINEAGE}       血缘 Agent：数据血缘分析</li>
 *   <li>{@link Role#DOCUMENTATION} 文档 Agent：数据资产文档生成</li>
 *   <li>{@link Role#CODE}          代码 Agent：数据管道代码生成</li>
 *   <li>{@link Role#AUDIT}         审核 Agent：SQL/配置安全审核</li>
 * </ul>
 *
 * <p>每个 Agent 实现遵循统一执行契约：
 * 接收 {@link AgentContext}（含输入参数、租户/用户上下文、配额、工具白名单），
 * 返回 {@link AgentResult}（含输出 payload、状态、耗时、错误信息）。
 * 公共流程（配额检查、白名单校验、计时、tracing）由 {@link BaseAgent} 统一实现，
 * 子类只需实现 {@link #doExecute(AgentContext)} 业务逻辑。</p>
 *
 * @author shuqing-bigdata
 */
public interface Agent {

    /**
     * 获取 Agent 角色类型。
     *
     * @return 角色枚举，用于注册、路由与配额匹配
     */
    Role getRole();

    /**
     * 执行 Agent 业务逻辑。
     *
     * <p>实现方应聚焦业务逻辑，公共前置/后置处理由 {@link BaseAgent} 完成。</p>
     *
     * @param context 执行上下文，不应为 {@code null}
     * @return 执行结果，永不返回 {@code null}（失败时返回失败态 {@link AgentResult}）
     */
    AgentResult doExecute(AgentContext context);

    /**
     * 内置 Agent 角色枚举。
     *
     * <p>枚举名用作 REST 路径、配额键、指标 tag，禁止随意改名。</p>
     */
    enum Role {
        /** 规划 Agent：任务分解 + 执行计划生成 */
        PLANNING,
        /** SQL Agent：自然语言转 SQL（对接 T010 NL2SQL） */
        SQL,
        /** 可视化 Agent：图表推荐 + 仪表盘生成 */
        VISUALIZATION,
        /** 质量 Agent：数据质量检查规则生成 */
        QUALITY,
        /** 血缘 Agent：数据血缘分析 */
        LINEAGE,
        /** 文档 Agent：数据资产文档生成 */
        DOCUMENTATION,
        /** 代码 Agent：数据管道代码生成 */
        CODE,
        /** 审核 Agent：SQL/配置安全审核 */
        AUDIT
    }
}