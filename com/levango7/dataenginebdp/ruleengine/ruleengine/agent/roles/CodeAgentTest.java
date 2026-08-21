package com.shuqing.bigdata.ruleengine.agent.roles;

import com.shuqing.bigdata.ruleengine.agent.core.Agent;
import com.shuqing.bigdata.ruleengine.agent.core.AgentContext;
import com.shuqing.bigdata.ruleengine.agent.core.AgentResult;
import com.shuqing.bigdata.ruleengine.agent.quota.QuotaEnforcer;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolRegistry;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolSandbox;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolWhitelist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeAgent 测试。
 */
class CodeAgentTest {

    private CodeAgent agent;
    private ToolSandbox sandbox;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        sandbox = AgentTestSupport.sandbox();
        registry = AgentTestSupport.emptyRegistry();
        agent = new CodeAgent(AgentTestSupport.looseQuotaEnforcer(),
                AgentTestSupport.permissiveWhitelist(), sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 CODE")
    void getRole() {
        assertEquals(Agent.Role.CODE, agent.getRole());
    }

    @Test
    @DisplayName("python 语言生成 PySpark 代码")
    void execute_python() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u").userInput("ETL 管道")
                .attributes(java.util.Map.of("language", "python", "framework", "pyspark"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        String code = (String) result.getOutput().get("code");
        assertNotNull(code);
        assertTrue(code.contains("SparkSession"));
        assertEquals("python", result.getOutput().get("language"));
    }

    @Test
    @DisplayName("scala 语言生成 Scala 代码")
    void execute_scala() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u").userInput("ETL")
                .attributes(java.util.Map.of("language", "scala"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        String code = (String) result.getOutput().get("code");
        assertTrue(code.contains("SparkSession"));
    }

    @Test
    @DisplayName("sql 语言生成 SQL")
    void execute_sql() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u").userInput("迁移数据")
                .attributes(java.util.Map.of("language", "sql"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        String code = (String) result.getOutput().get("code");
        assertTrue(code.contains("INSERT"));
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentResult result = agent.execute(AgentTestSupport.context("t1", ""));
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }
}