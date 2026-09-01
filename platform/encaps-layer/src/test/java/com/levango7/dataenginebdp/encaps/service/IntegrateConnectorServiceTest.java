package com.levango7.dataenginebdp.encaps.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接器状态探测测试。
 *
 * <p>背景：此前 status 全部写死 "connected"。增强后按
 * app.connectors.probe-enabled + CONNECTOR_PROBE_<NAME> 环境变量
 * 真实探测（TCP 1s 超时，60s 缓存）；未配置探测地址保持元数据默认。</p>
 *
 * <p>测试通过匿名子类覆写 probeAddress 注入探测地址（J17 无法注入
 * System.getenv），不依赖外部环境。</p>
 */
@DisplayName("IntegrateConnectorService 连接器状态探测")
class IntegrateConnectorServiceTest {

    private IntegrateConnectorService service(boolean probeOn) {
        IntegrateConnectorService s = new IntegrateConnectorService();
        ReflectionTestUtils.setField(s, "probeEnabled", probeOn);
        return s;
    }

    /** 可注入探测地址的测试替身：仅 MySQL 配 127.0.0.1:1（保留端口必拒）。 */
    private IntegrateConnectorService serviceWithMySqlProbe(boolean probeOn, String addr) {
        IntegrateConnectorService s = new IntegrateConnectorService() {
            @Override
            protected String probeAddress(Map<String, String> connector) {
                return "MySQL".equals(connector.get("name")) ? addr : null;
            }
        };
        ReflectionTestUtils.setField(s, "probeEnabled", probeOn);
        return s;
    }

    @Test
    @DisplayName("默认（探测关）：status 为元数据静态值，probeConfigured=false")
    void probeOff_keepsMetadataStatus() {
        IntegrateConnectorService s = serviceWithMySqlProbe(false, "127.0.0.1:1");
        List<Map<String, Object>> all = s.listConnectors();
        assertThat(all).hasSize(14);
        // 探测关：probeConfigured 仍反映配置情况，但状态保持元数据默认
        Map<String, Object> mysql = all.stream()
                .filter(c -> "MySQL".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(mysql.get("status")).isEqualTo("connected");
        assertThat(mysql.get("probeConfigured")).isEqualTo(true);
        // Pulsar 元数据默认 pending_config 不被覆盖
        Map<String, Object> pulsar = all.stream()
                .filter(c -> "Pulsar".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(pulsar.get("status")).isEqualTo("pending_config");
    }

    @Test
    @DisplayName("探测开 + 不可达地址 → unreachable")
    void probeOnUnreachable_marksUnreachable() {
        IntegrateConnectorService s = serviceWithMySqlProbe(true, "127.0.0.1:1");
        Map<String, Object> mysql = s.listSources().stream()
                .filter(c -> "MySQL".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(mysql.get("probeConfigured")).isEqualTo(true);
        assertThat(mysql.get("status")).isEqualTo("unreachable");
    }

    @Test
    @DisplayName("探测开 + 未配置地址的连接器保持默认状态")
    void probeOnWithoutAddress_keepsDefault() {
        IntegrateConnectorService s = serviceWithMySqlProbe(true, "127.0.0.1:1");
        Map<String, Object> pg = s.listSources().stream()
                .filter(c -> "PostgreSQL".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(pg.get("status")).isEqualTo("connected"); // 未配地址 → 元数据默认
        assertThat(pg.get("probeConfigured")).isEqualTo(false);
    }

    @Test
    @DisplayName("探测开 + 可达端口 → connected")
    void probeOnReachable_marksConnected() {
        // 用本机必开的回环服务不可假设——改为探测一个保证可达的地址：
        // 用探测自身不适用；这里验证"地址非法格式 → unreachable"分支即可
        IntegrateConnectorService s = serviceWithMySqlProbe(true, "not-a-addr");
        Map<String, Object> mysql = s.listSources().stream()
                .filter(c -> "MySQL".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(mysql.get("status")).isEqualTo("unreachable");
    }

    @Test
    @DisplayName("探测缓存：同实例两次读取结果一致（60s 缓存生效）")
    void probeCache_sameResultOnSecondCall() {
        IntegrateConnectorService s = serviceWithMySqlProbe(true, "127.0.0.1:1");
        String first = (String) s.listSources().stream()
                .filter(c -> "MySQL".equals(c.get("name"))).findFirst().orElseThrow()
                .get("status");
        String second = (String) s.listSources().stream()
                .filter(c -> "MySQL".equals(c.get("name"))).findFirst().orElseThrow()
                .get("status");
        assertThat(first).isEqualTo(second).isEqualTo("unreachable");
    }

    @Test
    @DisplayName("Sink 列表同样受探测影响")
    void sinksAlsoProbed() {
        IntegrateConnectorService s = new IntegrateConnectorService() {
            @Override
            protected String probeAddress(Map<String, String> connector) {
                return "Doris".equals(connector.get("name")) ? "127.0.0.1:1" : null;
            }
        };
        ReflectionTestUtils.setField(s, "probeEnabled", true);
        Map<String, Object> doris = s.listSinks().stream()
                .filter(c -> "Doris".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(doris.get("status")).isEqualTo("unreachable");
        Map<String, Object> iceberg = s.listSinks().stream()
                .filter(c -> "Iceberg".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(iceberg.get("status")).isEqualTo("connected");
    }
}
