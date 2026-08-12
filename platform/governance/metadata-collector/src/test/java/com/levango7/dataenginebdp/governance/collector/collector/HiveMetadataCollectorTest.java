package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link HiveMetadataCollector} 单元测试。
 *
 * <p>使用 Mockito 静态 mock {@link DriverManager}，避免真实 Hive 连接。</p>
 */
class HiveMetadataCollectorTest {

    private static MockedStatic<DriverManager> driverManagerMock;

    private HiveMetadataCollector collector;

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
        collector = new HiveMetadataCollector("default");
    }

    @Test
    @DisplayName("getType 应返回 HIVE")
    void getType_shouldReturnHive() {
        assertEquals(MetadataSource.TYPE_HIVE, collector.getType());
    }

    @Test
    @DisplayName("buildJdbcUrl 应保留完整 jdbc:hive2 URL")
    void buildJdbcUrl_shouldKeepFullHiveUrl() {
        MetadataSource source = new MetadataSource();
        source.setUrl("jdbc:hive2://hive-server:10000/default");
        // 通过反射调用 protected 方法验证
        String url = invokeBuildJdbcUrl(source);
        assertEquals("jdbc:hive2://hive-server:10000/default", url);
    }

    @Test
    @DisplayName("buildJdbcUrl 应对 host:port 形式补全 jdbc:hive2 前缀")
    void buildJdbcUrl_shouldWrapHostPort() {
        MetadataSource source = new MetadataSource();
        source.setUrl("hive-server:10000");
        String url = invokeBuildJdbcUrl(source);
        assertEquals("jdbc:hive2://hive-server:10000/default", url);
    }

    @Test
    @DisplayName("collect 成功路径：应返回 success=true 与表元数据")
    void collect_successPath() throws SQLException {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("hive-test");
        source.setType(MetadataSource.TYPE_HIVE);
        source.setUrl("jdbc:hive2://localhost:10000/default");
        source.setUsername("user");
        source.setPassword("pass");

        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet dbRs = mock(ResultSet.class);
        when(dbRs.next()).thenReturn(true, true, false);
        when(dbRs.getString(1)).thenReturn("db1", "db2");

        ResultSet tableRs = mock(ResultSet.class);
        when(tableRs.next()).thenReturn(true, false);
        when(tableRs.getString(1)).thenReturn("t1");

        ResultSet descRs = mock(ResultSet.class);
        when(descRs.next()).thenReturn(true, false);
        when(descRs.getString(1)).thenReturn("id");
        when(descRs.getString(2)).thenReturn("INT");
        when(descRs.getString(3)).thenReturn("primary key");

        ResultSet paramsRs = mock(ResultSet.class);
        when(paramsRs.next()).thenReturn(false);

        when(mockStmt.executeQuery(anyString()))
                .thenReturn(dbRs)      // SHOW DATABASES
                .thenReturn(tableRs)   // SHOW TABLES IN db1
                .thenReturn(tableRs)   // SHOW TABLES IN db2 (empty)
                .thenReturn(descRs)    // DESCRIBE db1.t1
                .thenReturn(paramsRs); // SHOW TBLPROPERTIES db1.t1
        when(mockConn.createStatement()).thenReturn(mockStmt);

        driverManagerMock.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(mockConn);

        CollectionResult result = collector.collect(source);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getDatabaseCount());
        assertNotNull(result.getTables());
        assertFalse(result.getTables().isEmpty());
        result.markFinished();
        assertTrue(result.getDurationMs() >= 0);
    }

    @Test
    @DisplayName("collect 失败路径：JDBC 异常应返回 success=false")
    void collect_failurePath() throws SQLException {
        MetadataSource source = new MetadataSource();
        source.setId(2L);
        source.setName("hive-down");
        source.setType(MetadataSource.TYPE_HIVE);
        source.setUrl("jdbc:hive2://unreachable:10000/default");

        // 预先构造异常，避免在 thenThrow 内部触发 DriverManager 静态方法
        SQLException ex = new SQLException("connection refused");
        driverManagerMock.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(ex);

        CollectionResult result = collector.collect(source);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("connection refused"));
    }

    @Test
    @DisplayName("testConnection 连接成功应返回 true")
    void testConnection_success() throws SQLException {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("hive-ok");
        source.setType(MetadataSource.TYPE_HIVE);
        source.setUrl("jdbc:hive2://localhost:10000/default");

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
        source.setName("hive-bad");
        source.setType(MetadataSource.TYPE_HIVE);
        source.setUrl("jdbc:hive2://unreachable:10000/default");

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