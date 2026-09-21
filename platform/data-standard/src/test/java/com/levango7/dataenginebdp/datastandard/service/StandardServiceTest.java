package com.levango7.dataenginebdp.datastandard.service;

import com.levango7.dataenginebdp.datastandard.model.dto.CreateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.dto.StandardQueryDTO;
import com.levango7.dataenginebdp.datastandard.model.dto.UpdateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.entity.Standard;
import com.levango7.dataenginebdp.datastandard.repository.StandardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * StandardService 单元测试（租户隔离 + 字段默认值 + 查询路由分支）。
 *
 * <p>Repository 用 Mockito 隔离，只验证服务层的真实行为：
 * 默认值回退、按条件路由到不同查询方法、null 守卫、部分字段更新语义。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandardService 单元测试")
class StandardServiceTest {

    private static final String TENANT = "tenant-a";

    @Mock
    private StandardRepository standardRepository;

    @InjectMocks
    private StandardService standardService;

    // ==================== create ====================

    @Test
    @DisplayName("create — version/status 为 null 时回退 1.0.0 / DRAFT，并写入租户与时间戳")
    void createShouldFallbackVersionAndStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("客户命名规范");
        dto.setCategory("user-domain");
        dto.setDescription("客户名称必须非空");
        dto.setRule("not_null=true");
        dto.setJsonSchema("{\"type\":\"string\"}");

        when(standardRepository.save(any(Standard.class))).thenAnswer(inv -> inv.getArgument(0));

        Standard created = standardService.create(dto, TENANT);

        ArgumentCaptor<Standard> captor = ArgumentCaptor.forClass(Standard.class);
        verify(standardRepository).save(captor.capture());
        Standard toSave = captor.getValue();

        assertThat(toSave.getVersion()).isEqualTo("1.0.0");
        assertThat(toSave.getStatus()).isEqualTo("DRAFT");
        assertThat(toSave.getTenantId()).isEqualTo(TENANT);
        assertThat(toSave.getName()).isEqualTo("客户命名规范");
        assertThat(toSave.getCategory()).isEqualTo("user-domain");
        assertThat(toSave.getDescription()).isEqualTo("客户名称必须非空");
        assertThat(toSave.getRule()).isEqualTo("not_null=true");
        assertThat(toSave.getJsonSchema()).isEqualTo("{\"type\":\"string\"}");
        assertThat(toSave.getCreatedAt()).isNotNull();
        assertThat(toSave.getUpdatedAt()).isEqualTo(toSave.getCreatedAt());
        assertThat(created).isSameAs(toSave);
    }

    @Test
    @DisplayName("create — version/status 显式传入时原样保留，不回退默认值")
    void createShouldKeepExplicitVersionAndStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("订单金额标准");
        dto.setVersion("2.3.1");
        dto.setStatus("PUBLISHED");

        when(standardRepository.save(any(Standard.class))).thenAnswer(inv -> inv.getArgument(0));

        Standard created = standardService.create(dto, TENANT);

        assertThat(created.getVersion()).isEqualTo("2.3.1");
        assertThat(created.getStatus()).isEqualTo("PUBLISHED");
        assertThat(created.getTenantId()).isEqualTo(TENANT);
    }

    // ==================== query 路由分支 ====================

    @Test
    @DisplayName("query — 无任何过滤条件时按租户分页查询，page/size 取默认 0 / 20")
    void queryShouldUseTenantOnlyWithDefaultPaging() {
        StandardQueryDTO query = new StandardQueryDTO();
        Page<Standard> page = new PageImpl<>(List.of(standard(1L, "a")));

        when(standardRepository.findByTenantId(eq(TENANT), any(Pageable.class))).thenReturn(page);

        Page<Standard> result = standardService.query(query, TENANT);

        assertThat(result.getContent()).hasSize(1);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(standardRepository).findByTenantId(eq(TENANT), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        verify(standardRepository, never()).findByNameContainingAndTenantId(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("query — 显式传入 page/size 时按传入值分页")
    void queryShouldHonourExplicitPaging() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setPage(2);
        query.setSize(50);

        when(standardRepository.findByTenantId(eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        standardService.query(query, TENANT);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(standardRepository).findByTenantId(eq(TENANT), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("query — page/size 为负数时视为非法，回退到默认 0 / 20")
    void queryShouldClampNegativePaging() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setPage(-3);
        query.setSize(-1);

        when(standardRepository.findByTenantId(eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        standardService.query(query, TENANT);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(standardRepository).findByTenantId(eq(TENANT), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("query — name 非空非空白时按名称模糊匹配（优先级最高）")
    void queryShouldRouteByName() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setName("客户");
        query.setCategory("order-domain");

        when(standardRepository.findByNameContainingAndTenantId(eq("客户"), eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(standard(7L, "客户命名规范"))));

        Page<Standard> result = standardService.query(query, TENANT);

        assertThat(result.getContent().get(0).getId()).isEqualTo(7L);
        verify(standardRepository, never()).findByTenantIdAndCategory(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("query — name 为空白串时视为未指定，降级按 category 过滤")
    void queryShouldSkipBlankNameAndRouteByCategory() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setName("   ");
        query.setCategory("order-domain");

        when(standardRepository.findByTenantIdAndCategory(eq(TENANT), eq("order-domain"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(standard(9L, "订单金额标准"))));

        Page<Standard> result = standardService.query(query, TENANT);

        assertThat(result.getContent().get(0).getId()).isEqualTo(9L);
        verify(standardRepository).findByTenantIdAndCategory(eq(TENANT), eq("order-domain"), any(Pageable.class));
        verify(standardRepository, never()).findByNameContainingAndTenantId(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("query — 仅 category 非空时按分类过滤")
    void queryShouldRouteByCategory() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setCategory("order-domain");

        when(standardRepository.findByTenantIdAndCategory(eq(TENANT), eq("order-domain"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(standard(3L, "订单金额标准"))));

        Page<Standard> result = standardService.query(query, TENANT);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(standardRepository, never()).findByTenantIdAndStatus(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("query — category 为空白串时视为未指定，降级按 status 过滤")
    void queryShouldSkipBlankCategoryAndRouteByStatus() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setCategory(" ");
        query.setStatus("PUBLISHED");

        when(standardRepository.findByTenantIdAndStatus(eq(TENANT), eq("PUBLISHED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(standard(4L, "已发布标准"))));

        Page<Standard> result = standardService.query(query, TENANT);

        assertThat(result.getContent()).hasSize(1);
        verify(standardRepository).findByTenantIdAndStatus(eq(TENANT), eq("PUBLISHED"), any(Pageable.class));
    }

    @Test
    @DisplayName("query — 仅 status 非空时按状态过滤")
    void queryShouldRouteByStatus() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setStatus("DEPRECATED");

        when(standardRepository.findByTenantIdAndStatus(eq(TENANT), eq("DEPRECATED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(standard(5L, "废弃标准"))));

        Page<Standard> result = standardService.query(query, TENANT);

        assertThat(result.getContent().get(0).getId()).isEqualTo(5L);
        verify(standardRepository).findByTenantIdAndStatus(eq(TENANT), eq("DEPRECATED"), any(Pageable.class));
    }

    @Test
    @DisplayName("query — status 为空白串时视为未指定，退化到全量租户分页查询")
    void queryShouldSkipBlankStatusAndFallBackToTenantOnly() {
        StandardQueryDTO query = new StandardQueryDTO();
        query.setStatus(" ");

        when(standardRepository.findByTenantId(eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(standard(6L, "任意标准"))));

        Page<Standard> result = standardService.query(query, TENANT);

        assertThat(result.getContent()).hasSize(1);
        verify(standardRepository).findByTenantId(eq(TENANT), any(Pageable.class));
    }

    // ==================== getById ====================

    @Test
    @DisplayName("getById — id 为 null 时返回 null 且不访问数据库")
    void getByIdShouldReturnNullWhenIdNull() {
        assertThat(standardService.getById(null, TENANT)).isNull();
        verifyNoInteractions(standardRepository);
    }

    @Test
    @DisplayName("getById — tenantId 为 null 时返回 null 且不访问数据库")
    void getByIdShouldReturnNullWhenTenantNull() {
        assertThat(standardService.getById(1L, null)).isNull();
        verifyNoInteractions(standardRepository);
    }

    @Test
    @DisplayName("getById — 命中且租户匹配时返回标准")
    void getByIdShouldReturnEntityWhenFound() {
        Standard found = standard(11L, "客户命名规范");
        when(standardRepository.findByIdAndTenantId(11L, TENANT)).thenReturn(Optional.of(found));

        assertThat(standardService.getById(11L, TENANT)).isSameAs(found);
    }

    @Test
    @DisplayName("getById — 不属于该租户（或不存在）时返回 null，避免跨租户泄漏")
    void getByIdShouldReturnNullWhenNotFound() {
        when(standardRepository.findByIdAndTenantId(11L, TENANT)).thenReturn(Optional.empty());

        assertThat(standardService.getById(11L, TENANT)).isNull();
    }

    // ==================== update ====================

    @Test
    @DisplayName("update — id 或 tenantId 为 null 时返回 null 且不访问数据库")
    void updateShouldReturnNullOnNullGuards() {
        assertThat(standardService.update(null, new UpdateStandardDTO(), TENANT)).isNull();
        assertThat(standardService.update(1L, new UpdateStandardDTO(), null)).isNull();
        verifyNoInteractions(standardRepository);
    }

    @Test
    @DisplayName("update — 记录不存在时返回 null，不执行保存")
    void updateShouldReturnNullWhenMissing() {
        when(standardRepository.findByIdAndTenantId(42L, TENANT)).thenReturn(Optional.empty());

        assertThat(standardService.update(42L, new UpdateStandardDTO(), TENANT)).isNull();
        verify(standardRepository, never()).save(any(Standard.class));
    }

    @Test
    @DisplayName("update — 部分字段更新：仅覆盖传入字段，保留 createdAt 与 tenantId")
    void updateShouldPatchOnlyProvidedFields() {
        Standard existing = standard(12L, "旧名称");
        existing.setCategory("old-domain");
        existing.setDescription("旧描述");
        existing.setRule("old-rule");
        existing.setJsonSchema("{\"old\":true}");
        existing.setVersion("1.0.0");
        existing.setStatus("DRAFT");
        existing.setTenantId(TENANT);
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        existing.setCreatedAt(createdAt);
        existing.setUpdatedAt(createdAt);

        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setName("新名称");

        when(standardRepository.findByIdAndTenantId(12L, TENANT)).thenReturn(Optional.of(existing));
        when(standardRepository.save(any(Standard.class))).thenAnswer(inv -> inv.getArgument(0));

        Standard updated = standardService.update(12L, dto, TENANT);

        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getCategory()).isEqualTo("old-domain");
        assertThat(updated.getDescription()).isEqualTo("旧描述");
        assertThat(updated.getRule()).isEqualTo("old-rule");
        assertThat(updated.getJsonSchema()).isEqualTo("{\"old\":true}");
        assertThat(updated.getVersion()).isEqualTo("1.0.0");
        assertThat(updated.getStatus()).isEqualTo("DRAFT");
        assertThat(updated.getTenantId()).isEqualTo(TENANT);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(createdAt);
    }

    @Test
    @DisplayName("update — 全字段更新：所有传入字段均生效")
    void updateShouldApplyAllProvidedFields() {
        Standard existing = standard(13L, "旧名称");

        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setName("新名称");
        dto.setCategory("new-domain");
        dto.setDescription("新描述");
        dto.setRule("new-rule");
        dto.setJsonSchema("{\"new\":true}");
        dto.setVersion("3.0.0");
        dto.setStatus("PUBLISHED");

        when(standardRepository.findByIdAndTenantId(13L, TENANT)).thenReturn(Optional.of(existing));
        when(standardRepository.save(any(Standard.class))).thenAnswer(inv -> inv.getArgument(0));

        Standard updated = standardService.update(13L, dto, TENANT);

        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getCategory()).isEqualTo("new-domain");
        assertThat(updated.getDescription()).isEqualTo("新描述");
        assertThat(updated.getRule()).isEqualTo("new-rule");
        assertThat(updated.getJsonSchema()).isEqualTo("{\"new\":true}");
        assertThat(updated.getVersion()).isEqualTo("3.0.0");
        assertThat(updated.getStatus()).isEqualTo("PUBLISHED");
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete — id 或 tenantId 为 null 时返回 false 且不访问数据库")
    void deleteShouldReturnFalseOnNullGuards() {
        assertThat(standardService.delete(null, TENANT)).isFalse();
        assertThat(standardService.delete(1L, null)).isFalse();
        verifyNoInteractions(standardRepository);
    }

    @Test
    @DisplayName("delete — 记录不存在时返回 false，不执行删除")
    void deleteShouldReturnFalseWhenMissing() {
        when(standardRepository.findByIdAndTenantId(77L, TENANT)).thenReturn(Optional.empty());

        assertThat(standardService.delete(77L, TENANT)).isFalse();
        verify(standardRepository, never()).delete(any(Standard.class));
    }

    @Test
    @DisplayName("delete — 命中且租户匹配时删除并返回 true")
    void deleteShouldRemoveWhenFound() {
        Standard existing = standard(78L, "待删除标准");
        when(standardRepository.findByIdAndTenantId(78L, TENANT)).thenReturn(Optional.of(existing));

        assertThat(standardService.delete(78L, TENANT)).isTrue();
        verify(standardRepository).delete(existing);
    }

    private static Standard standard(Long id, String name) {
        Standard s = new Standard();
        s.setId(id);
        s.setName(name);
        return s;
    }
}
