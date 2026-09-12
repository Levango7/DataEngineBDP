package com.levango7.dataenginebdp.masterdata.service;

import com.levango7.dataenginebdp.masterdata.model.entity.MasterData;
import com.levango7.dataenginebdp.masterdata.repository.MasterDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MasterDataService 分页查询测试。
 *
 * <p>验证 query 方法的分页逻辑（修复 P2: query 无分页）：
 * <ul>
 *   <li>默认页码 0、默认每页 20</li>
 *   <li>非法页码/大小回退默认值</li>
 *   <li>每页大小上限 200</li>
 *   <li>modelCode 为空时按租户查询</li>
 *   <li>modelCode 非空时按模型+租户查询</li>
 * </ul>
 *
 * <p>使用 Mockito mock Repository，纯 Service 逻辑测试，不依赖数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterDataService 分页查询")
class MasterDataServicePagingTest {

    @Mock
    private MasterDataRepository masterDataRepository;

    @InjectMocks
    private MasterDataService masterDataService;

    @Test
    @DisplayName("默认分页 page=0 size=20")
    void shouldUseDefaultPaging() {
        Page<MasterData> expectedPage = buildPage(0, 20, 0);
        when(masterDataRepository.findByTenantId(eq("t1"), any(Pageable.class)))
                .thenReturn(expectedPage);

        Page<MasterData> result = masterDataService.query(null, "t1", null, null);

        assertThat(result).isNotNull();
        verify(masterDataRepository).findByTenantId(eq("t1"), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("自定义分页 page=2 size=10")
    void shouldUseCustomPaging() {
        Page<MasterData> expectedPage = buildPage(2, 10, 0);
        when(masterDataRepository.findByTenantId(eq("t1"), any(Pageable.class)))
                .thenReturn(expectedPage);

        Page<MasterData> result = masterDataService.query(null, "t1", 2, 10);

        assertThat(result).isNotNull();
        verify(masterDataRepository).findByTenantId(eq("t1"), eq(PageRequest.of(2, 10)));
    }

    @Test
    @DisplayName("负页码回退 0")
    void shouldFallbackNegativePage() {
        when(masterDataRepository.findByTenantId(eq("t1"), any(Pageable.class)))
                .thenReturn(buildPage(0, 20, 0));

        masterDataService.query(null, "t1", -1, 20);

        verify(masterDataRepository).findByTenantId(eq("t1"), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("非正大小回退 20")
    void shouldFallbackNonPositiveSize() {
        when(masterDataRepository.findByTenantId(eq("t1"), any(Pageable.class)))
                .thenReturn(buildPage(0, 20, 0));

        masterDataService.query(null, "t1", 0, 0);

        verify(masterDataRepository).findByTenantId(eq("t1"), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("每页大小上限 200")
    void shouldCapSizeAt200() {
        when(masterDataRepository.findByTenantId(eq("t1"), any(Pageable.class)))
                .thenReturn(buildPage(0, 200, 0));

        masterDataService.query(null, "t1", 0, 1000);

        verify(masterDataRepository).findByTenantId(eq("t1"), eq(PageRequest.of(0, 200)));
    }

    @Test
    @DisplayName("modelCode 非空时按模型+租户分页查询")
    void shouldQueryByModelCodeWithPaging() {
        Page<MasterData> expectedPage = buildPage(0, 20, 5);
        when(masterDataRepository.findByModelCodeAndTenantId(
                eq("md-org"), eq("t1"), any(Pageable.class)))
                .thenReturn(expectedPage);

        Page<MasterData> result = masterDataService.query("md-org", "t1", 0, 20);

        assertThat(result.getTotalElements()).isEqualTo(5);
        verify(masterDataRepository).findByModelCodeAndTenantId(
                eq("md-org"), eq("t1"), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("空白 modelCode 按租户查询")
    void shouldQueryByTenantWhenModelCodeBlank() {
        when(masterDataRepository.findByTenantId(eq("t1"), any(Pageable.class)))
                .thenReturn(buildPage(0, 20, 0));

        masterDataService.query("  ", "t1", 0, 20);

        verify(masterDataRepository).findByTenantId(eq("t1"), eq(PageRequest.of(0, 20)));
    }

    /**
     * 构建测试用空页对象。
     */
    private Page<MasterData> buildPage(int page, int size, long total) {
        List<MasterData> content = Collections.emptyList();
        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }
}