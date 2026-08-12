package com.levango7.dataenginebdp.federated.catalog;

import com.levango7.dataenginebdp.federated.config.FederatedQueryProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TableLocationService} 单元测试 - SQL 表名提取部分。
 */
class TableLocationServiceTest {

    @Test
    void extractTableNames_simpleSelect() {
        FederatedQueryProperties props = new FederatedQueryProperties();
        props.setLocalCluster("local-cluster");
        // 不需要真实 Catalog，仅测试 SQL 解析
        TableLocationService svc = new TableLocationService(
                new GlobalCatalogClient(null, props));

        Set<String> tables = svc.extractTableNames("SELECT * FROM orders");

        // Calcite 默认将未加引号的标识符转为大写（标准 SQL 行为）
        assertThat(tables).anySatisfy(t -> assertThat(t.toLowerCase()).isEqualTo("orders"));
    }

    @Test
    void extractTableNames_qualifiedName() {
        FederatedQueryProperties props = new FederatedQueryProperties();
        TableLocationService svc = new TableLocationService(
                new GlobalCatalogClient(null, props));

        Set<String> tables = svc.extractTableNames("SELECT * FROM sales.orders");

        assertThat(tables).anySatisfy(t -> assertThat(t.toLowerCase()).isEqualTo("sales.orders"));
    }

    @Test
    void extractTableNames_join() {
        FederatedQueryProperties props = new FederatedQueryProperties();
        TableLocationService svc = new TableLocationService(
                new GlobalCatalogClient(null, props));

        Set<String> tables = svc.extractTableNames(
                "SELECT a.id, b.name FROM orders a JOIN customers b ON a.cid = b.id");

        assertThat(tables).hasSize(2);
        assertThat(tables.stream().map(String::toLowerCase)).contains("orders", "customers");
    }

    @Test
    void extractTableNames_union() {
        FederatedQueryProperties props = new FederatedQueryProperties();
        TableLocationService svc = new TableLocationService(
                new GlobalCatalogClient(null, props));

        Set<String> tables = svc.extractTableNames(
                "SELECT * FROM orders_east UNION SELECT * FROM orders_west");

        assertThat(tables.stream().map(String::toLowerCase)).contains("orders_east", "orders_west");
    }

    @Test
    void extractTableNames_regexFallback() {
        FederatedQueryProperties props = new FederatedQueryProperties();
        TableLocationService svc = new TableLocationService(
                new GlobalCatalogClient(null, props));

        // 非标准 SQL（Calcite 解析失败），走正则兜底
        Set<String> tables = svc.extractTableNames("SELECT * FROM my_table WHERE x = 1");

        assertThat(tables).isNotEmpty();
    }
}