package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link DorisMetadataCollector} 单元测试。
 *
 * <p>使用 Mockito 静态 mock {@link DriverManager}，避免真实 Doris 连接。</p>
 */
class DorisMetadataCollectorTest {

    private static MockedStatic<DriverManager> driverManagerMock;

    private DorisMetadataCollector collector;

    @BeforeAll
    static void initStatic() {
        driverManagerMock = mockStatic(DriverManager.class);
    }

    @AfterAll
    static void closeStatic() {
        driverManagerMock.close();
    }

    @BeforeEach
    void setUp() {
        collector = new DorisMetadataCollector("information_schema");
    }

    @Test
    @DisplayName("getType 应返回 DORIS")
    void getType_shouldReturnDoris() {
        assertEquals(MetadataSource.TYPE_DORIS, collector.getType());
    }

    @Test
    @DisplayName("buildJdbcUrl 应保留完整 jdbc:mysql URL")
    void buildJdbcUrl_shouldKeepFullMysqlUrl() {
        MetadataSource source = new MetadataSource();
        source.setUrl("jdbc:mysql://doris-fe:9030/test");
        String url = invokeBuildJdbcUrl(source);
        assertEquals("jdbc:mysql://doris-fe:9030/test", url);
    }

    @Test
    @DisplayName("buildJdbcUrl 应对 host:port 形式补全 jdbc:mysql 前缀与参数")
    void buildJdbcUrl_shouldWrapHostPort() {
        MetadataSource source = new MetadataSource();
        source.setUrl("doris-fe:9030");
        String url = invokeBuildJdbcUrl(source);
        assertTrue(url.startsWith("jdbc:mysql://doris-fe:9030/information_schema?"));
        assertTrue(url.contains("useSSL=false"));
    }

    @Test
    @DisplayName("collect 成功路径：应解析 Doris 表模型与分桶信息")
    void collect_shouldParseDorisTableModelAndBuckets() throws SQLException {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("doris-test");
        source.setType(MetadataSource.TYPE_DORIS);
        source.setUrl("jdbc:mysql://localhost:9030/test");
        source.setUsername("root");
        source.setPassword("");

        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);

        ResultSet dbRs = mock(ResultSet.class);
        when(dbRs.next()).thenReturn(true, false);
        when(dbRs.getString(1)).thenReturn("test_db");

        ResultSet tableRs = mock(ResultSet.class);
        when(tableRs.next()).thenReturn(true, false);
        when(tableRs.getString(1)).thenReturn("t1");

        ResultSet descRs = mock(ResultSet.class);
        when(descRs.next()).thenReturn(true, true, false);
        when(descRs.getString(1)).thenReturn("id", "name");
        when(descRs.getString(2)).thenReturn("BIGINT", "VARCHAR(100)");
        doReturn((Object) null, (Object) null).when(descRs).getString(3);

        ResultSet paramsRs = mock(ResultSet.class);
        when(paramsRs.next()).thenReturn(false);

        ResultSet createRs = mock(ResultSet.class);
        when(createRs.next()).thenReturn(true, false);
        when(createRs.getString(2)).thenReturn(
                "CREATE TABLE t1 (id BIGINT, name VARCHAR(100)) "
                        + "ENGINE=OLAP DISTRIBUTED BY HASH(id) BUCKETS 10 PROPERTIES(\"replication_allocation\"=\"tag.location.default: 3\")");

        when(mockStmt.executeQuery(anyString()))
                .thenReturn(dbRs)      // SHOW DATABASES
                .thenReturn(tableRs)   // SHOW TABLES IN test_db
                .thenReturn(descRs)    // DESCRIBE test_db.t1
                .thenReturn(paramsRs)  // SHOW TBLPROPERTIES
                .thenReturn(createRs); // SHOW CREATE TABLE
        when(mockConn.createStatement()).thenReturn(mockStmt);

        driverManagerMock.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(mockConn);

        CollectionResult result = collector.collect(source);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTables());
        assertFalse(result.getTables().isEmpty());
        TableMetadata tm = result.getTables().get(0);
        assertEquals("OLAP", tm.getDorisTableModel());
        assertEquals(10, tm.getBucketCount());
        assertNotNull(tm.getBucketColumns());
        assertEquals(1, tm.getBucketColumns().size());
        assertEquals("id", tm.getBucketColumns().get(0));
    }

    @Test
    @DisplayName("collect 失败路径：JDBC 异常应返回 success=false")
    void collect_failurePath() throws SQLException {
        MetadataSource source = new MetadataSource();
        source.setId(2L);
        source.setName("doris-down");
        source.setType(MetadataSource.TYPE_DORIS);
        source.setUrl("jdbc:mysql://unreachable:9030/test");

        SQLException ex = new SQLException("connection refused");
        driverManagerMock.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(ex);

        CollectionResult result = collector.collect(source);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("testConnection 连接成功应返回 true")
    void testConnection_success() throws SQLException {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("doris-ok");
        source.setType(MetadataSource.TYPE_DORIS);
        source.setUrl("jdbc:mysql://localhost:9030/test");

        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(mockStmt.executeQuery(anyString())).thenReturn(rs);
        when(mockConn.createStatement()).thenReturn(mockStmt);

        driverManagerMock.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(mockConn);

        assertTrue(collector.testConnection(source));
    }

    @Test
    @DisplayName("testConnection 连接失败应返回 false")
    void testConnection_failure() throws SQLException {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("doris-bad");
        source.setType(MetadataSource.TYPE_DORIS);
        source.setUrl("jdbc:mysql://unreachable:9030/test");

        SQLException ex = new SQLException("refused");
        driverManagerMock.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(ex);

        assertFalse(collector.testConnection(source));
    }

    /**
     * 通过反射调用 protected buildJdbcUrl。
     */
    private String invokeBuildJdbcUrl(MetadataSource source) {
        try {
            java.lang.reflect.Method m = AbstractJdbcMetadataCollector.class
                    .getDeclaredMethod("buildJdbcUrl", MetadataSource.class);
            m.setAccessible(true);
            return (String) m.invoke(collector, source);
        } catch (Exception e) {
            fail("Failed to invoke buildJdbcUrl: " + e.getMessage());
            return null;
        }
    }
}