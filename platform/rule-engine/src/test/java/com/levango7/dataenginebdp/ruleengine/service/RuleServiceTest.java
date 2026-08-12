package com.levango7.dataenginebdp.ruleengine.service;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.repository.RuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RuleService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

    @Mock
    private RuleRepository ruleRepository;

    @InjectMocks
    private RuleService ruleService;

    @Test
    @DisplayName("create — 设置时间戳和默认enabled后保存")
    void create_shouldSetTimestampsAndDefaultEnabled() {
        Rule input = new Rule();
        input.setName("dq-rule");
        input.setType("DQ");

        when(ruleRepository.save(any(Rule.class))).thenAnswer(invocation -> {
            Rule r = invocation.getArgument(0);
            r.setId(1L);
            return r;
        });

        Rule result = ruleService.create(input);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(ruleRepository).save(any(Rule.class));
    }

    @Test
    @DisplayName("create — 已有enabled时不覆盖")
    void create_shouldPreserveExistingEnabled() {
        Rule input = new Rule();
        input.setName("dq-rule");
        input.setType("DQ");
        input.setEnabled(false);

        when(ruleRepository.save(any(Rule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Rule result = ruleService.create(input);

        assertThat(result.getEnabled()).isFalse();
    }

    @Test
    @DisplayName("listAll — 返回全部规则列表")
    void listAll_shouldReturnAllRules() {
        Rule r1 = new Rule();
        r1.setId(1L);
        r1.setName("r1");
        Rule r2 = new Rule();
        r2.setId(2L);
        r2.setName("r2");

        when(ruleRepository.findAll()).thenReturn(List.of(r1, r2));

        List<Rule> result = ruleService.listAll();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("getById — 存在时返回规则")
    void getById_existingId_shouldReturnRule() {
        Rule rule = new Rule();
        rule.setId(1L);
        rule.setName("found");

        when(ruleRepository.findById(1L)).thenReturn(Optional.of(rule));

        Rule result = ruleService.getById(1L);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("found");
    }

    @Test
    @DisplayName("getById — 不存在时返回null")
    void getById_nonExistingId_shouldReturnNull() {
        when(ruleRepository.findById(999L)).thenReturn(Optional.empty());

        Rule result = ruleService.getById(999L);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getById — null id返回null")
    void getById_nullId_shouldReturnNull() {
        Rule result = ruleService.getById(null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("update — 存在时更新并保留createdAt")
    void update_existingId_shouldUpdateAndPreserveCreatedAt() {
        Rule existing = new Rule();
        existing.setId(1L);
        existing.setName("old");
        existing.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));

        Rule input = new Rule();
        input.setName("new-name");

        when(ruleRepository.existsById(1L)).thenReturn(true);
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(Rule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Rule result = ruleService.update(1L, input);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("new-name");
        assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("update — 不存在时返回null")
    void update_nonExistingId_shouldReturnNull() {
        when(ruleRepository.existsById(999L)).thenReturn(false);

        Rule result = ruleService.update(999L, new Rule());

        assertThat(result).isNull();
        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("update — null id返回null")
    void update_nullId_shouldReturnNull() {
        Rule result = ruleService.update(null, new Rule());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("delete — 存在时删除并返回true")
    void delete_existingId_shouldDeleteAndReturnTrue() {
        when(ruleRepository.existsById(1L)).thenReturn(true);

        boolean result = ruleService.delete(1L);

        assertThat(result).isTrue();
        verify(ruleRepository).deleteById(1L);
    }

    @Test
    @DisplayName("delete — 不存在时返回false")
    void delete_nonExistingId_shouldReturnFalse() {
        when(ruleRepository.existsById(999L)).thenReturn(false);

        boolean result = ruleService.delete(999L);

        assertThat(result).isFalse();
        verify(ruleRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete — null id返回false")
    void delete_nullId_shouldReturnFalse() {
        boolean result = ruleService.delete(null);

        assertThat(result).isFalse();
    }
}