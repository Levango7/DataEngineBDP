package com.levango7.dataenginebdp.masterdata.service;

import com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataDTO;
import com.levango7.dataenginebdp.masterdata.model.dto.UpdateMasterDataDTO;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterData;
import com.levango7.dataenginebdp.masterdata.repository.MasterDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MasterDataService 的创建 / 详情 / 更新 / 删除测试。
 *
 * <p>覆盖 service 层主路径与全部参数校验、存在性校验分支：
 * <ul>
 *   <li>create：version / status 缺省回退、显式值透传、createdAt 与 updatedAt 一致</li>
 *   <li>getById：id / tenantId 为 null、未命中、命中</li>
 *   <li>update：id / tenantId 为 null、未命中、仅更新非 null 字段、全字段更新、刷新 updatedAt</li>
 *   <li>delete：id / tenantId 为 null、未命中、命中删除</li>
 * </ul>
 *
 * <p>使用 Mockito mock Repository，纯 Service 逻辑测试，不依赖数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterDataService CRUD 与租户隔离")
class MasterDataServiceCrudTest {

    @Mock
    private MasterDataRepository masterDataRepository;

    @InjectMocks
    private MasterDataService masterDataService;

    @Test
    @DisplayName("create: version/status 为空时回退 1.0.0 / ACTIVE")
    void createShouldFallbackVersionAndStatusWhenNull() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-org");
        dto.setDataKey("ORG-001");
        dto.setDataValue("总部");
        dto.setAttributes("{\"level\":1}");
        when(masterDataRepository.save(any(MasterData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MasterData saved = masterDataService.create(dto, "tenant-A");

        assertThat(saved.getModelCode()).isEqualTo("md-org");
        assertThat(saved.getDataKey()).isEqualTo("ORG-001");
        assertThat(saved.getDataValue()).isEqualTo("总部");
        assertThat(saved.getAttributes()).isEqualTo("{\"level\":1}");
        assertThat(saved.getVersion()).isEqualTo("1.0.0");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getTenantId()).isEqualTo("tenant-A");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    @DisplayName("create: 显式 version/status 原样透传")
    void createShouldKeepExplicitVersionAndStatus() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-material");
        dto.setDataKey("MAT-001");
        dto.setDataValue("钢板");
        dto.setVersion("2.3.1");
        dto.setStatus("DEPRECATED");
        when(masterDataRepository.save(any(MasterData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MasterData saved = masterDataService.create(dto, "tenant-B");

        assertThat(saved.getVersion()).isEqualTo("2.3.1");
        assertThat(saved.getStatus()).isEqualTo("DEPRECATED");
        assertThat(saved.getTenantId()).isEqualTo("tenant-B");
    }

    @Test
    @DisplayName("create: 返回仓储持久化后的实体（含数据库生成的 id）")
    void createShouldReturnPersistedEntity() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-org");
        dto.setDataKey("ORG-002");
        dto.setDataValue("分公司");
        MasterData persisted = new MasterData();
        persisted.setId(99L);
        when(masterDataRepository.save(any(MasterData.class))).thenReturn(persisted);

        MasterData saved = masterDataService.create(dto, "tenant-A");

        assertThat(saved).isSameAs(persisted);
        assertThat(saved.getId()).isEqualTo(99L);
        ArgumentCaptor<MasterData> captor = ArgumentCaptor.forClass(MasterData.class);
        verify(masterDataRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("getById: id 为 null 直接返回 null 且不查询仓储")
    void getByIdShouldReturnNullWhenIdNull() {
        assertThat(masterDataService.getById(null, "tenant-A")).isNull();
        verifyNoInteractions(masterDataRepository);
    }

    @Test
    @DisplayName("getById: tenantId 为 null 直接返回 null 且不查询仓储")
    void getByIdShouldReturnNullWhenTenantNull() {
        assertThat(masterDataService.getById(1L, null)).isNull();
        verifyNoInteractions(masterDataRepository);
    }

    @Test
    @DisplayName("getById: 未命中返回 null")
    void getByIdShouldReturnNullWhenNotFound() {
        when(masterDataRepository.findByIdAndTenantId(7L, "tenant-A")).thenReturn(Optional.empty());

        assertThat(masterDataService.getById(7L, "tenant-A")).isNull();
    }

    @Test
    @DisplayName("getById: 命中返回实体")
    void getByIdShouldReturnEntityWhenFound() {
        MasterData existing = sampleEntity(7L, "tenant-A");
        when(masterDataRepository.findByIdAndTenantId(7L, "tenant-A")).thenReturn(Optional.of(existing));

        MasterData result = masterDataService.getById(7L, "tenant-A");

        assertThat(result).isSameAs(existing);
        assertThat(result.getDataKey()).isEqualTo("ORG-001");
    }

    @Test
    @DisplayName("update: id 为 null 返回 null 且不触达仓储")
    void updateShouldReturnNullWhenIdNull() {
        assertThat(masterDataService.update(null, new UpdateMasterDataDTO(), "tenant-A")).isNull();
        verifyNoInteractions(masterDataRepository);
    }

    @Test
    @DisplayName("update: tenantId 为 null 返回 null 且不触达仓储")
    void updateShouldReturnNullWhenTenantNull() {
        assertThat(masterDataService.update(7L, new UpdateMasterDataDTO(), null)).isNull();
        verifyNoInteractions(masterDataRepository);
    }

    @Test
    @DisplayName("update: 记录不存在返回 null 且不落库")
    void updateShouldReturnNullWhenNotFound() {
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setDataValue("新值");
        when(masterDataRepository.findByIdAndTenantId(7L, "tenant-A")).thenReturn(Optional.empty());

        assertThat(masterDataService.update(7L, dto, "tenant-A")).isNull();
        verify(masterDataRepository, never()).save(any(MasterData.class));
    }

    @Test
    @DisplayName("update: 仅更新非 null 字段，未传入字段保持不变")
    void updateShouldApplyOnlyNonNullFields() {
        MasterData existing = sampleEntity(7L, "tenant-A");
        LocalDateTime originalUpdatedAt = LocalDateTime.now().minusDays(1);
        existing.setUpdatedAt(originalUpdatedAt);
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setDataValue("新值");
        when(masterDataRepository.findByIdAndTenantId(7L, "tenant-A")).thenReturn(Optional.of(existing));
        when(masterDataRepository.save(any(MasterData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MasterData updated = masterDataService.update(7L, dto, "tenant-A");

        assertThat(updated.getDataValue()).isEqualTo("新值");
        assertThat(updated.getDataKey()).isEqualTo("ORG-001");
        assertThat(updated.getAttributes()).isEqualTo("{\"level\":1}");
        assertThat(updated.getVersion()).isEqualTo("1.0.0");
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
    }

    @Test
    @DisplayName("update: 全字段更新时逐个字段生效")
    void updateShouldApplyAllFields() {
        MasterData existing = sampleEntity(7L, "tenant-A");
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setDataKey("ORG-999");
        dto.setDataValue("研发中心");
        dto.setAttributes("{\"level\":9}");
        dto.setVersion("3.0.0");
        dto.setStatus("INACTIVE");
        when(masterDataRepository.findByIdAndTenantId(7L, "tenant-A")).thenReturn(Optional.of(existing));
        when(masterDataRepository.save(any(MasterData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MasterData updated = masterDataService.update(7L, dto, "tenant-A");

        assertThat(updated.getDataKey()).isEqualTo("ORG-999");
        assertThat(updated.getDataValue()).isEqualTo("研发中心");
        assertThat(updated.getAttributes()).isEqualTo("{\"level\":9}");
        assertThat(updated.getVersion()).isEqualTo("3.0.0");
        assertThat(updated.getStatus()).isEqualTo("INACTIVE");
        assertThat(updated.getModelCode()).isEqualTo("md-org");
        assertThat(updated.getTenantId()).isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("delete: id 为 null 返回 false 且不触达仓储")
    void deleteShouldReturnFalseWhenIdNull() {
        assertThat(masterDataService.delete(null, "tenant-A")).isFalse();
        verifyNoInteractions(masterDataRepository);
    }

    @Test
    @DisplayName("delete: tenantId 为 null 返回 false 且不触达仓储")
    void deleteShouldReturnFalseWhenTenantNull() {
        assertThat(masterDataService.delete(7L, null)).isFalse();
        verifyNoInteractions(masterDataRepository);
    }

    @Test
    @DisplayName("delete: 记录不存在返回 false 且不执行删除")
    void deleteShouldReturnFalseWhenNotFound() {
        when(masterDataRepository.findByIdAndTenantId(7L, "tenant-A")).thenReturn(Optional.empty());

        assertThat(masterDataService.delete(7L, "tenant-A")).isFalse();
        verify(masterDataRepository, never()).delete(any(MasterData.class));
    }

    @Test
    @DisplayName("delete: 命中时删除该实体并返回 true")
    void deleteShouldRemoveEntityAndReturnTrue() {
        MasterData existing = sampleEntity(7L, "tenant-A");
        when(masterDataRepository.findByIdAndTenantId(7L, "tenant-A")).thenReturn(Optional.of(existing));

        assertThat(masterDataService.delete(7L, "tenant-A")).isTrue();
        verify(masterDataRepository).delete(existing);
    }

    /**
     * 构造一条已持久化的主数据记录（id=7，租户 tenant-A）。
     */
    private MasterData sampleEntity(Long id, String tenantId) {
        MasterData entity = new MasterData();
        entity.setId(id);
        entity.setModelCode("md-org");
        entity.setDataKey("ORG-001");
        entity.setDataValue("总部");
        entity.setAttributes("{\"level\":1}");
        entity.setVersion("1.0.0");
        entity.setStatus("ACTIVE");
        entity.setTenantId(tenantId);
        entity.setCreatedAt(LocalDateTime.now().minusDays(3));
        entity.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return entity;
    }
}
