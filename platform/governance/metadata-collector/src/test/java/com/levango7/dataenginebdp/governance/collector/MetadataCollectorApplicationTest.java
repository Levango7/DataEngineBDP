package com.levango7.dataenginebdp.governance.collector;

import com.levango7.dataenginebdp.governance.collector.collector.MetadataCollector;
import com.levango7.dataenginebdp.governance.collector.service.CollectionSchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring 上下文加载集成测试。
 *
 * <p>验证所有 Collector Bean 正确注册、调度服务可注入、H2 数据库初始化成功。
 * 使用 {@link WebEnvironment#NONE} 避免嵌入 Tomcat 与 Hive JDBC 的 juli logging 冲突。</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:integrationdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MetadataCollectorApplicationTest {

    @Autowired
    private List<MetadataCollector> collectors;

    @Autowired
    private CollectionSchedulerService schedulerService;

    @Test
    @DisplayName("应注册 5 个 Collector Bean（含 Iceberg REST）")
    void shouldRegisterFourCollectors() {
        assertEquals(5, collectors.size());
        List<String> types = collectors.stream().map(MetadataCollector::getType).sorted().toList();
        assertTrue(types.contains("HIVE"));
        assertTrue(types.contains("DORIS"));
        assertTrue(types.contains("KAFKA"));
        assertTrue(types.contains("FILESYSTEM"));
        assertTrue(types.contains("ICEBERG"));
    }

    @Test
    @DisplayName("调度服务应注入成功并暴露已注册类型")
    void schedulerService_shouldExposeRegisteredTypes() {
        assertNotNull(schedulerService);
        List<String> types = schedulerService.getRegisteredTypes();
        assertEquals(5, types.size());
    }
}