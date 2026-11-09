package com.levango7.dataenginebdp.streambatch.router;

import com.levango7.dataenginebdp.streambatch.doris.DorisOlapClient;
import com.levango7.dataenginebdp.streambatch.doris.DorisOlapException;
import com.levango7.dataenginebdp.streambatch.iceberg.IcebergSnapshotManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DorisMaterializedViewIntegration 单元测试（2026-11-09 批 4 真实化）。
 *
 * <p>钉子语义：刷新真实委托 DorisOlapClient（库名解析自 {@code database.table}
 * 或配置 {@code base-table}）；Doris 不可达 / 请求失败 ⇒ <b>如实返回 false</b>，
 * 绝不伪造成功（旧实现恒 true 的 facade 语义已废弃）。</p>
 */
class DorisMaterializedViewIntegrationTest {

    private static DorisMaterializedViewIntegration integration(
            DorisOlapClient client, ViewRouterConfig config) {
        return new DorisMaterializedViewIntegration(
                config, mock(IcebergSnapshotManager.class), client);
    }

    private static ViewRouterConfig emptyConfig() {
        return new ViewRouterConfig();
    }

    @Test
    void refresh_从表名解析库名并真实委托() throws Exception {
        DorisOlapClient client = mock(DorisOlapClient.class);
        when(client.refreshMaterializedView("orders_db", "orders_mv")).thenReturn(true);

        boolean ok = integration(client, emptyConfig())
                .refreshMaterializedView("orders_db.orders_table", "orders_mv");

        assertThat(ok).isTrue();
        verify(client).refreshMaterializedView("orders_db", "orders_mv");
    }

    @Test
    void refresh_配置baseTable优先于入参表名() throws Exception {
        DorisOlapClient client = mock(DorisOlapClient.class);
        ViewRouterConfig config = emptyConfig();
        ViewRouterConfig.MaterializedViewEntry entry = new ViewRouterConfig.MaterializedViewEntry();
        entry.setViewName("orders_mv");
        entry.setBaseTable("analytics_db.orders_table");
        entry.setEnabled(true);
        config.getMaterializedViews().put("orders_db.orders_table", entry);
        when(client.refreshMaterializedView("analytics_db", "orders_mv")).thenReturn(true);

        boolean ok = integration(client, config)
                .refreshMaterializedView("orders_db.orders_table", "orders_mv");

        assertThat(ok).isTrue();
        verify(client).refreshMaterializedView("analytics_db", "orders_mv");
    }

    @Test
    void refresh_Doris不可达时如实返回false而非伪造成功() throws Exception {
        DorisOlapClient client = mock(DorisOlapClient.class);
        when(client.refreshMaterializedView(anyString(), anyString()))
                .thenThrow(new DorisOlapException("connection refused"));

        boolean ok = integration(client, emptyConfig())
                .refreshMaterializedView("orders_db.orders_table", "orders_mv");

        assertThat(ok).isFalse();
        verify(client).refreshMaterializedView("orders_db", "orders_mv");
    }

    @Test
    void refresh_库名不可解析时不调用客户端且返回false() throws Exception {
        DorisOlapClient client = mock(DorisOlapClient.class);

        boolean ok = integration(client, emptyConfig())
                .refreshMaterializedView("no_database_prefix", "orders_mv");

        assertThat(ok).isFalse();
        verify(client, never()).refreshMaterializedView(anyString(), anyString());
    }
}
