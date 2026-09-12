package com.levango7.dataenginebdp.datastandard.model.dto;

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
 * UpdateStandardDTO Bean Validation 测试。
 *
 * <p>验证 update DTO 的 status 字段 @Pattern 枚举校验。
 * 纯 Bean Validation 测试，不依赖 Spring 上下文。</p>
 */
@DisplayName("UpdateStandardDTO @Pattern 校验")
class UpdateStandardDTOValidationTest {

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
    @DisplayName("合法 status DRAFT 通过校验")
    void shouldAcceptDraft() {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setStatus("DRAFT");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("合法 status PUBLISHED 通过校验")
    void shouldAcceptPublished() {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setStatus("PUBLISHED");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("合法 status DEPRECATED 通过校验")
    void shouldAcceptDeprecated() {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setStatus("DEPRECATED");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("null status 通过校验（不更新该字段）")
    void shouldAcceptNullStatus() {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setStatus(null);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("非法 status 被拒绝")
    void shouldRejectInvalidStatus() {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setStatus("ARCHIVED");
        Set<ConstraintViolation<UpdateStandardDTO>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("DRAFT / PUBLISHED / DEPRECATED");
    }
}