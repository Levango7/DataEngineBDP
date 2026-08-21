package com.shuqing.bigdata.sqlgateway.service;

import com.shuqing.bigdata.sqlgateway.model.RouteRule;
import com.shuqing.bigdata.sqlgateway.model.SqlExecuteRequest;
import com.shuqing.bigdata.sqlgateway.model.SqlExecuteResponse;
import com.shuqing.bigdata.sqlgateway.repository.RouteRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * SqlRoutingService 单元测试。
 *
 * <p>使用 Mockito 模拟依赖，测试路由解析和规则管理逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class SqlRoutingServiceTest {

    @Mock
    private BackendProxyService backendProxyService;

    @Mock
    private RouteRuleRepository routeRuleRepository;

    @InjectMocks
    private SqlRoutingService sqlRoutingService;

    @Test
    @DisplayName("execute — 指定trino引擎时路由到Trino后端")
    void execute_withTrinoEngine_shouldProxyToTrino() {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        request.setEngine("trino");
        request.setTenantId("t1");

        SqlExecuteResponse mockResponse = SqlExecuteResponse.builder()
                .queryId("q-001")
                .status("SUCCESS")
                .columns(List.of("1"))
                .rows(List.of(List.of(1)))
                .durationMs(50L)
                .engine("trino")
                .build();

        when(backendProxyService.proxyToTrino(anyString(), anyString())).thenReturn(Mono.just(mockResponse));

        SqlExecuteResponse result = sqlRoutingService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getEngine()).isEqualTo("trino");
    }

    @Test
    @DisplayName("execute — 指定doris引擎时路由到Doris后端")
    void execute_withDorisEngine_shouldProxyToDoris() {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        request.setEngine("doris");
        request.setTenantId("t1");

        SqlExecuteResponse mockResponse = SqlExecuteResponse.builder()
                .queryId("q-002")
                .status("SUCCESS")
                .columns(List.of("1"))
                .rows(List.of(List.of(1)))
                .durationMs(60L)
                .engine("doris")
                .build();

        when(backendProxyService.proxyToDoris(anyString(), anyString())).thenReturn(Mono.just(mockResponse));

        SqlExecuteResponse result = sqlRoutingService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getEngine()).isEqualTo("doris");
    }

    @Test
    @DisplayName("execute — 后端返回null时降级")
    void execute_backendReturnsNull_shouldFallback() {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        request.setEngine("trino");

        when(backendProxyService.proxyToTrino(anyString(), any())).thenReturn(Mono.empty());

        SqlExecuteResponse result = sqlRoutingService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("execute — 后端超时时降级")
    void execute_backendTimeout_shouldFallback() {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        request.setEngine("trino");

        when(backendProxyService.proxyToTrino(anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("Timeout on blocking read")));

        SqlExecuteResponse result = sqlRoutingService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("listRoutes — 按优先级升序排列")
    void listRoutes_shouldSortByPriority() {
        RouteRule r1 = new RouteRule("INSERT", "doris", 10, true);
        r1.setId(1L);
        RouteRule r2 = new RouteRule("SELECT", "trino", 1, true);
        r2.setId(2L);

        // 使用 ArrayList 确保列表可修改（排序需要）
        when(routeRuleRepository.findAll()).thenReturn(new ArrayList<>(List.of(r1, r2)));

        List<RouteRule> result = sqlRoutingService.listRoutes();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPriority()).isEqualTo(1);
        assertThat(result.get(1).getPriority()).isEqualTo(10);
    }

    @Test
    @DisplayName("addRoute — 新增规则设置默认enabled和priority")
    void addRoute_shouldSetDefaults() {
        RouteRule input = new RouteRule();
        input.setPattern("DELETE");
        input.setEngine("doris");
        // 不设置 id，让 id 为 null

        when(routeRuleRepository.save(any(RouteRule.class))).thenAnswer(invocation -> {
            RouteRule r = invocation.getArgument(0);
            r.setId(1L);
            return r;
        });

        RouteRule result = sqlRoutingService.addRoute(input);

        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getPriority()).isEqualTo(100);
    }
}
