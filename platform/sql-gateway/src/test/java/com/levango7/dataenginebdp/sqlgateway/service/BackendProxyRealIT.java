package com.levango7.dataenginebdp.sqlgateway.service;

import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendProxyService 真实后端集成测试（IT）。
 *
 * <p>连接 Docker 基础设施中的真实 Trino 460 与 Doris 2.1.7 后端，
 * 验证 {@link BackendProxyService#proxyToTrino} 与
 * {@link BackendProxyService#proxyToDoris} 的真实 HTTP / JDBC 调用链路。</p>
 *
 * <h2>前置条件</h2>
 * <ol>
 *   <li>Docker 基础设施已启动：{@code bash scripts/infra/start-infra.sh}</li>
 *   <li>Trino 8080 端口可达，已加载 memory + tpcds catalog</li>
 *   <li>Doris FE 9030 端口可达（MySQL 兼容协议），root 无密码</li>
 * </ol>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -pl platform/sql-gateway \
 *   -Dtest=BackendProxyRealIT \
 *   -Dinfra.it=true \
 *   -Dspring.profiles.active=it
 * </pre>
 *
 * <p>仅当系统属性 {@code infra.it=true} 时测试才会执行，避免在普通
 * {@code mvn test} 中因缺少 Docker 环境而失败。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
@EnabledIfSystemProperty(named = "infra.it", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendProxyRealIT {

    /**
     * 异步响应阻塞超时（秒），覆盖 Trino/Doris 首次查询冷启动。
     */
    private static final long BLOCK_TIMEOUT_SECONDS = 60L;

    @Autowired
    private BackendProxyService backendProxyService;

    /**
     * 前置检查：确认后端 URL 已注入，避免因配置缺失而静默跳过。
     */
    @BeforeAll
    static void assertInfraProperties() {
        // 仅做日志提示，实际连接由各测试用例验证
        System.out.println("[IT] BackendProxyRealIT 启动，infra.it="
                + System.getProperty("infra.it"));
    }

    // =========================================================================
    // Trino 真实后端测试
    // =========================================================================

    /**
     * 测试1：连接真实 Trino，执行 {@code SELECT 1}。
     *
     * <p>验证：</p>
     * <ul>
     *   <li>HTTP 链路打通（POST /v1/statement）</li>
     *   <li>响应状态 SUCCESS</li>
     *   <li>返回至少 1 行数据</li>
     *   <li>engine = trino</li>
     * </ul>
     */
    @Test
    @Order(1)
    @DisplayName("Trino SELECT 1 - 基本连接验证")
    void proxyToTrino_selectOne_returnsSuccess() {
        SqlExecuteResponse resp = backendProxyService
                .proxyToTrino("SELECT 1", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

        assertThat(resp).as("Trino SELECT 1 响应不应为 null").isNotNull();
        assertThat(resp.getStatus())
                .as("Trino SELECT 1 应返回 SUCCESS")
                .isEqualTo("SUCCESS");
        assertThat(resp.getEngine()).isEqualTo("trino");
        assertThat(resp.getRows())
                .as("SELECT 1 应返回 1 行数据")
                .hasSize(1);
        assertThat(resp.getRows().get(0))
                .as("SELECT 1 行数据应包含 1 个值")
                .hasSize(1);
        System.out.println("[IT] Trino SELECT 1 结果: " + resp.getRows()
                + " 耗时=" + resp.getDurationMs() + "ms");
    }

    /**
     * 测试2：连接真实 Trino，在 memory 连接器中创建测试表并查询。
     *
     * <p>步骤：</p>
     * <ol>
     *   <li>CREATE TABLE memory.default.it_test (id bigint, name varchar)</li>
     *   <li>INSERT INTO memory.default.it_test VALUES (1,'a'), (2,'b'), (3,'c')</li>
     *   <li>SELECT * FROM memory.default.it_test LIMIT 10</li>
     * </ol>
     *
     * <p>验证 DDL + DML + SELECT 全链路，且查询返回 3 行 2 列。</p>
     */
    @Test
    @Order(2)
    @DisplayName("Trino memory 连接器 - DDL/DML/SELECT 全链路")
    void proxyToTrino_memoryTable_createInsertSelect() {
        // 2.1 建表（memory 连接器支持 CREATE TABLE）
        SqlExecuteResponse createResp = backendProxyService
                .proxyToTrino("CREATE TABLE IF NOT EXISTS memory.default.it_test "
                        + "(id bigint, name varchar)", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
        assertThat(createResp).isNotNull();
        assertThat(createResp.getStatus())
                .as("CREATE TABLE 应返回 SUCCESS，实际: " + createResp.getStatus())
                .isEqualTo("SUCCESS");

        // 2.2 插入数据
        SqlExecuteResponse insertResp = backendProxyService
                .proxyToTrino("INSERT INTO memory.default.it_test VALUES "
                        + "(1, 'a'), (2, 'b'), (3, 'c')", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
        assertThat(insertResp).isNotNull();
        assertThat(insertResp.getStatus())
                .as("INSERT 应返回 SUCCESS，实际: " + insertResp.getStatus())
                .isEqualTo("SUCCESS");

        // 2.3 查询
        SqlExecuteResponse selectResp = backendProxyService
                .proxyToTrino("SELECT * FROM memory.default.it_test LIMIT 10", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
        assertThat(selectResp).isNotNull();
        assertThat(selectResp.getStatus())
                .as("SELECT memory 表应返回 SUCCESS")
                .isEqualTo("SUCCESS");
        assertThat(selectResp.getColumns())
                .as("应返回 2 列: id, name")
                .hasSize(2);
        assertThat(selectResp.getRows())
                .as("应返回 3 行数据")
                .hasSize(3);
        System.out.println("[IT] Trino memory 查询列: " + selectResp.getColumns()
                + " 行数: " + selectResp.getRows().size()
                + " 耗时=" + selectResp.getDurationMs() + "ms");
    }

    /**
     * 测试3：连接真实 Trino，执行 TPC-DS 基准查询。
     *
     * <p>使用 {@code tpcds.tiny} scale factor（极小数据集，适合 CI），
     * 查询 customer 表的记录数与样本数据。</p>
     */
    @Test
    @Order(3)
    @DisplayName("Trino TPC-DS 查询 - tpcds.tiny.customer")
    void proxyToTrino_tpcdsQuery_returnsRows() {
        // 3.1 count 查询
        SqlExecuteResponse countResp = backendProxyService
                .proxyToTrino("SELECT count(*) AS cnt FROM tpcds.tiny.customer", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
        assertThat(countResp).isNotNull();
        assertThat(countResp.getStatus())
                .as("TPC-DS count 查询应返回 SUCCESS，实际: " + countResp.getStatus())
                .isEqualTo("SUCCESS");
        assertThat(countResp.getRows())
                .as("count 查询应返回 1 行")
                .hasSize(1);
        System.out.println("[IT] Trino TPC-DS customer count: "
                + countResp.getRows() + " 耗时=" + countResp.getDurationMs() + "ms");

        // 3.2 样本查询
        SqlExecuteResponse sampleResp = backendProxyService
                .proxyToTrino("SELECT c_customer_sk, c_customer_id "
                        + "FROM tpcds.tiny.customer LIMIT 5", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
        assertThat(sampleResp).isNotNull();
        assertThat(sampleResp.getStatus()).isEqualTo("SUCCESS");
        assertThat(sampleResp.getColumns())
                .as("应返回 2 列: c_customer_sk, c_customer_id")
                .hasSize(2);
        assertThat(sampleResp.getRows())
                .as("LIMIT 5 应返回最多 5 行")
                .hasSizeBetween(1, 5);
        System.out.println("[IT] Trino TPC-DS customer 样本: "
                + sampleResp.getRows().size() + " 行"
                + " 耗时=" + sampleResp.getDurationMs() + "ms");
    }

    // =========================================================================
    // Doris 真实后端测试
    // =========================================================================

    /**
     * 测试4：连接真实 Doris FE，通过 JDBC（MySQL 兼容协议，9030 端口）执行 {@code SELECT 1}。
     *
     * <p>验证 {@link BackendProxyService#proxyToDoris} 的 JDBC 调用链路：
     * DriverManager → Doris FE → 返回结果集。</p>
     */
    @Test
    @Order(4)
    @DisplayName("Doris SELECT 1 - JDBC 连接验证")
    void proxyToDoris_selectOne_returnsSuccess() {
        SqlExecuteResponse resp = backendProxyService
                .proxyToDoris("SELECT 1", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

        assertThat(resp).as("Doris SELECT 1 响应不应为 null").isNotNull();
        assertThat(resp.getStatus())
                .as("Doris SELECT 1 应返回 SUCCESS，实际: " + resp.getStatus()
                        + "（请确认 Doris FE 9030 端口已就绪且 root 无密码）")
                .isEqualTo("SUCCESS");
        assertThat(resp.getEngine()).isEqualTo("doris");
        assertThat(resp.getRows())
                .as("SELECT 1 应返回 1 行")
                .hasSize(1);
        System.out.println("[IT] Doris SELECT 1 结果: " + resp.getRows()
                + " 耗时=" + resp.getDurationMs() + "ms");
    }

    /**
     * 测试4b：连接真实 Doris，执行 {@code SHOW DATABASES}。
     *
     * <p>验证 Doris 返回列信息与多行数据，确认 JDBC 元数据解析正常。</p>
     */
    @Test
    @Order(5)
    @DisplayName("Doris SHOW DATABASES - 元数据解析验证")
    void proxyToDoris_showDatabases_returnsRows() {
        SqlExecuteResponse resp = backendProxyService
                .proxyToDoris("SHOW DATABASES", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus())
                .as("SHOW DATABASES 应返回 SUCCESS，实际: " + resp.getStatus())
                .isEqualTo("SUCCESS");
        assertThat(resp.getColumns())
                .as("SHOW DATABASES 应返回至少 1 列")
                .isNotEmpty();
        assertThat(resp.getRows())
                .as("Doris 应至少有 information_schema 库")
                .isNotEmpty();
        System.out.println("[IT] Doris 数据库列表: " + resp.getRows()
                + " 耗时=" + resp.getDurationMs() + "ms");
    }

    // =========================================================================
    // 错误处理验证
    // =========================================================================

    /**
     * 测试5a：验证 Trino 错误 SQL 返回 FAILED 状态。
     *
     * <p>执行语法错误 SQL {@code SELCT 1}（拼写错误），
     * Trino 应返回 error 字段，{@link BackendProxyService#parseTrinoResponse}
     * 应将其映射为 {@code status=FAILED}。</p>
     */
    @Test
    @Order(6)
    @DisplayName("Trino 错误 SQL - 返回 FAILED 状态")
    void proxyToTrino_invalidSql_returnsFailed() {
        SqlExecuteResponse resp = backendProxyService
                .proxyToTrino("SELCT 1", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus())
                .as("错误 SQL 应返回 FAILED，实际: " + resp.getStatus())
                .isEqualTo("FAILED");
        assertThat(resp.getEngine()).isEqualTo("trino");
        assertThat(resp.getRows())
                .as("FAILED 响应不应包含数据行")
                .isEmpty();
        System.out.println("[IT] Trino 错误 SQL 正确返回 FAILED，耗时="
                + resp.getDurationMs() + "ms");
    }

    /**
     * 测试5b：验证 Doris 错误 SQL 返回 DEGRADED 状态。
     *
     * <p>执行语法错误 SQL，Doris JDBC 会抛出 SQLException，
     * {@link BackendProxyService#proxyToDoris} 的 catch 块应将其映射为
     * {@code status=DEGRADED}（{@code errorResponse} 方法约定）。</p>
     */
    @Test
    @Order(7)
    @DisplayName("Doris 错误 SQL - 返回 DEGRADED 状态")
    void proxyToDoris_invalidSql_returnsDegraded() {
        SqlExecuteResponse resp = backendProxyService
                .proxyToDoris("SELCT 1", "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus())
                .as("Doris 错误 SQL 应返回 DEGRADED，实际: " + resp.getStatus())
                .isEqualTo("DEGRADED");
        assertThat(resp.getEngine()).isEqualTo("doris");
        System.out.println("[IT] Doris 错误 SQL 正确返回 DEGRADED，耗时="
                + resp.getDurationMs() + "ms");
    }

    /**
     * 测试5c：验证 Trino 查询不存在的表返回 FAILED。
     *
     * <p>查询 {@code memory.default.nonexistent_table_xyz}，
     * Trino 应返回 "table not found" 错误。</p>
     */
    @Test
    @Order(8)
    @DisplayName("Trino 查询不存在的表 - 返回 FAILED")
    void proxyToTrino_nonexistentTable_returnsFailed() {
        SqlExecuteResponse resp = backendProxyService
                .proxyToTrino("SELECT * FROM memory.default.nonexistent_table_xyz_"
                        + System.currentTimeMillis(), "it-tenant")
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus())
                .as("查询不存在的表应返回 FAILED，实际: " + resp.getStatus())
                .isEqualTo("FAILED");
        System.out.println("[IT] Trino 查询不存在表正确返回 FAILED");
    }

    /**
     * 辅助：打印响应摘要（调试用，当前未启用）。
     */
    @SuppressWarnings("unused")
    private static String summarize(SqlExecuteResponse resp) {
        if (resp == null) {
            return "null";
        }
        List<List<Object>> rows = resp.getRows();
        int rowCount = rows == null ? 0 : rows.size();
        return String.format("status=%s engine=%s rows=%d cols=%s duration=%dms",
                resp.getStatus(), resp.getEngine(), rowCount,
                resp.getColumns(), resp.getDurationMs());
    }
}