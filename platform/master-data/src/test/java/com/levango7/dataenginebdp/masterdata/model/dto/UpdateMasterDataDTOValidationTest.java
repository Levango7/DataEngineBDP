package com.levango7.dataenginebdp.masterdata.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UpdateMasterDataDTO Bean Validation 测试。
 *
 * <p>验证 update DTO 的 status 字段 @Pattern 枚举校验。
 * 纯 Bean Validation 测试，不依赖 Spring 上下文。</p>
 */
@DisplayName("UpdateMasterDataDTO @Pattern 校验")
class UpdateMasterDataDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("合法 status ACTIVE 通过校验")
    void shouldAcceptActive() {
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setStatus("ACTIVE");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("合法 status INACTIVE 通过校验")
    void shouldAcceptInactive() {
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setStatus("INACTIVE");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("合法 status DEPRECATED 通过校验")
    void shouldAcceptDeprecated() {
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setStatus("DEPRECATED");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("null status 通过校验（不更新该字段）")
    void shouldAcceptNullStatus() {
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setStatus(null);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("非法 status 被拒绝")
    void shouldRejectInvalidStatus() {
        UpdateMasterDataDTO dto = new UpdateMasterDataDTO();
        dto.setStatus("PENDING");
        Set<ConstraintViolation<UpdateMasterDataDTO>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("ACTIVE / INACTIVE / DEPRECATED");
    }
}