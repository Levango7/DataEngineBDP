package com.levango7.dataenginebdp.masterdata.controller;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 异常映射测试。
 *
 * <p>逐个 handler 直接调用，断言 HTTP 状态码、error 码与 message：</p>
 * <ul>
 *   <li>{@code IllegalStateException} → 400 precondition_failed</li>
 *   <li>{@code ConstraintViolationException} → 400 constraint_violation（含空集合回退分支）</li>
 *   <li>{@code NoSuchElementException} → 404 not_found（含 message 为 null 的回退分支）</li>
 *   <li>{@code OptimisticLockingFailureException} → 409 conflict</li>
 *   <li>{@code DataAccessException} → 503 data_access_error</li>
 *   <li>{@code Exception} 兜底 → 500 internal_error</li>
 * </ul>
 */
@DisplayName("GlobalExceptionHandler 异常映射")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务前置条件失败 → 400 precondition_failed")
    void shouldMapIllegalStateTo400() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalState(new IllegalStateException("缺少租户上下文"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "precondition_failed");
        assertThat(response.getBody()).containsEntry("message", "缺少租户上下文");
    }

    @Test
    @DisplayName("参数校验失败 → 400 constraint_violation，message 含字段与原因")
    void shouldMapConstraintViolationTo400() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<CreateMasterDataDTO>> violations =
                validator.validate(new CreateMasterDataDTO());
        assertThat(violations).isNotEmpty();

        ResponseEntity<Map<String, String>> response =
                handler.handleConstraintViolation(new ConstraintViolationException(violations));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "constraint_violation");
        assertThat(response.getBody().get("message")).contains("不能为空");
    }

    @Test
    @DisplayName("参数校验失败但无明细 → 400 constraint_violation 且回退默认文案")
    void shouldMapEmptyConstraintViolationTo400() {
        ResponseEntity<Map<String, String>> response = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.<ConstraintViolation<?>>of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "constraint_violation");
        assertThat(response.getBody()).containsEntry("message", "constraint violation");
    }

    @Test
    @DisplayName("资源不存在 → 404 not_found 且回显异常 message")
    void shouldMapNoSuchElementTo404() {
        ResponseEntity<Map<String, String>> response =
                handler.handleNotFound(new NoSuchElementException("MasterData 7 not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "not_found");
        assertThat(response.getBody()).containsEntry("message", "MasterData 7 not found");
    }

    @Test
    @DisplayName("资源不存在且 message 为 null → 404 且回退默认文案")
    void shouldMapNoSuchElementWithNullMessageTo404() {
        ResponseEntity<Map<String, String>> response =
                handler.handleNotFound(new NoSuchElementException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "not_found");
        assertThat(response.getBody()).containsEntry("message", "resource not found");
    }

    @Test
    @DisplayName("乐观锁冲突 → 409 conflict")
    void shouldMapOptimisticLockTo409() {
        ResponseEntity<Map<String, String>> response =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("row was updated"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "conflict");
        assertThat(response.getBody()).containsEntry("message", "资源已被并发修改");
    }

    @Test
    @DisplayName("数据访问异常 → 503 data_access_error")
    void shouldMapDataAccessExceptionTo503() {
        ResponseEntity<Map<String, String>> response =
                handler.handleDataAccess(new DataAccessResourceFailureException("connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "data_access_error");
        assertThat(response.getBody()).containsEntry("message", "数据访问失败");
    }

    @Test
    @DisplayName("未预期异常走兜底 handler → 500 internal_error")
    void shouldMapUnexpectedExceptionTo500() {
        ResponseEntity<Map<String, String>> response =
                handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "internal_error");
        assertThat(response.getBody()).containsEntry("message", "服务内部错误");
    }

    @Test
    @DisplayName("兜底 handler 也能处理 IllegalArgumentException 等运行时异常")
    void shouldMapAnyRuntimeExceptionTo500() {
        ResponseEntity<Map<String, String>> response =
                handler.handleUnexpected(new IllegalArgumentException("bad argument"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "internal_error");
    }
}
