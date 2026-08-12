package com.levango7.dataenginebdp.governance.collector.service;

import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link MetadataWriterService} 单元测试。
 *
 * <p>使用 mock WebClient.Builder，验证批量写入的容错与计数逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class MetadataWriterServiceTest {

    @Mock
    private WebClient.Builder webClientBuilder;
    @Mock
    private WebClient webClient;
    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private WebClient.RequestBodySpec requestBodySpec;
    @Mock
    private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;

    private MetadataWriterService writerService;

    @BeforeEach
    void setUp() {
        // WebClient 调用链 mock
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);

        writerService = new MetadataWriterService(webClientBuilder,
                "http://localhost:8082", 5, 1);
    }

    @Test
    @DisplayName("getCatalogBaseUrl 应返回构造时传入的地址")
    void getCatalogBaseUrl_shouldReturnConfiguredUrl() {
        assertEquals("http://localhost:8082", writerService.getCatalogBaseUrl());
    }

    @Test
    @DisplayName("writeBatch 空列表应返回 0")
    void writeBatch_emptyList() {
        assertEquals(0, writerService.writeBatch(null));
        assertEquals(0, writerService.writeBatch(new ArrayList<>()));
    }

    @Test
    @DisplayName("writeBatch 单表失败应返回 0 成功计数")
    void writeBatch_singleTableFailure() {
        // WebClient 调用链未配置，将抛异常，写入失败
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class)))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("catalog unavailable")));

        TableMetadata tm = new TableMetadata();
        tm.setDatabaseName("db");
        tm.setTableName("t1");

        int success = writerService.writeBatch(List.of(tm));
        assertEquals(0, success);
    }

    @Test
    @DisplayName("writeTableMetadata null 输入应返回 false")
    void writeTableMetadata_nullInput() {
        assertFalse(writerService.writeTableMetadata(null));
    }
}