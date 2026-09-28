package com.levango7.dataenginebdp.governance.lineage.service;

import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.SyncConnection;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 守护"Nebula 客户端可安全排除 okhttp"这一前提（CVE-2021-0341 治理）。
 *
 * <p>根 pom 从 com.vesoft:client 排除了 okhttp 3.14.0。该排除的唯一风险是
 * 图库的 HTTP/2 传输（THttp2Client 依赖 okhttp）被启用后在运行期抛
 * NoClassDefFoundError。本测试锁住两条事实：
 * useHttp2 默认关闭、且本项目实际使用的 thrift 连接路径不需要 okhttp 即可链接与工作。
 *
 * <p>若将来确有需求启用 HTTP/2，先改这里：届时应显式引入修复版 okhttp（>=4.9.2）
 * 而不是回退排除项。
 */
class NebulaWithoutOkhttpTest {

    @Test
    void nebulaPoolConfig_useHttp2DefaultsToFalse() {
        assertThat(new NebulaPoolConfig().isUseHttp2()).isFalse();
    }

    @Test
    void syncConnectionClassLinksWithoutOkhttpOnClasspath() {
        // 触发 SyncConnection 的链接与字节码校验：它引用了依赖 okhttp 的 THttp2Client。
        assertThatCode(() -> Class.forName(SyncConnection.class.getName(), true,
                SyncConnection.class.getClassLoader())).doesNotThrowAnyException();
    }

    @Test
    void poolInitOnRealThriftPathReachesNetworkErrorNotLinkageFailure() {
        // 不可达端口：期望"连接被拒"这类网络失败并被降级处理，而非 NoClassDefFoundError。
        NebulaPool pool = new NebulaPool();
        try {
            assertThatCode(() -> pool.init(
                    Collections.singletonList(new HostAddress("127.0.0.1", 1)),
                    new NebulaPoolConfig().setMaxConnSize(1)))
                    .doesNotThrowAnyException();
        } finally {
            pool.close();
        }
    }
}
