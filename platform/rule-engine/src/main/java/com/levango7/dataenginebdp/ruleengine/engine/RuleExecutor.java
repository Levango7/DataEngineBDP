package com.levango7.dataenginebdp.ruleengine.engine;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;

import java.util.Map;

/**
 * 规则执行器接口。
 *
 * <p>每种规则类型（DQ / MASK / ALERT）对应一个实现。</p>
 */
public interface RuleExecutor {

    /** 返回执行器支持的规则类型：DQ / MASK / ALERT */
    String getType();

    /** 执行规则 */
    RuleExecutionResult execute(Rule rule, Map<String, Object> context);
}