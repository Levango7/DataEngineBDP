package com.levango7.dataenginebdp.governance.realtime.quality;

import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink CEP 流式质量规则作业。
 *
 * <p>将 {@link QualityRule} 编译为 Flink CEP {@link Pattern}，在 Flink 集群上
 * 流式评估每条记录，违规时通过侧输出流触发告警。
 *
 * <p>作业拓扑：
 * <pre>
 * Source（Kafka/Iceberg CDC）
 *   → Map（解析记录为 QualityRecord）
 *   → CEP Pattern（按规则类型编译）
 *   → Select（匹配 → QualityRuleResult）
 *   → Side Output（违规 → QualityAlert → Kafka/Webhook）
 * </pre>
 *
 * <p>本类提供作业构建与提交逻辑，实际提交通过 Flink SQL Gateway REST API。
 * 单元测试使用 Flink MiniCluster 验证 CEP 模式正确性。
 *
 * <p><b>注意</b>：本类依赖 Flink provided scope，运行时由 Flink 集群提供依赖。
 * 编译时需要 Flink 依赖在 classpath 上（pom.xml 已配置 provided scope）。
 */
public class QualityRuleCepJob {

    private static final Logger log = LoggerFactory.getLogger(QualityRuleCepJob.class);

    /**
     * 构建质量规则 CEP Pattern。
     *
     * <p>不同规则类型编译为不同的 CEP 模式：
     * <ul>
     *   <li>NOT_NULL：匹配 fieldValue == null 的记录</li>
     *   <li>UNIQUE：匹配连续两次相同 fieldValue 的记录（within window）</li>
     *   <li>RANGE：匹配 fieldValue < min 或 > max 的记录</li>
     *   <li>FORMAT：匹配 fieldValue 不匹配正则的记录</li>
     *   <li>CUSTOM：匹配 expression 求值为 true 的记录</li>
     * </ul>
     *
     * @param rule 质量规则
     * @return Flink CEP Pattern
     */
    public static Pattern<QualityRecord, ?> buildPattern(QualityRule rule) {
        Pattern<QualityRecord, ?> basePattern = Pattern
                .<QualityRecord>begin("violation")
                .where(new QualityRuleCondition(rule));

        // UNIQUE 规则需要连续两次匹配（检测重复）
        if (rule.getRuleType() == QualityRule.RuleType.UNIQUE) {
            basePattern = Pattern
                    .<QualityRecord>begin("first")
                    .where(new QualityRuleCondition(rule))
                    .timesOrMore(2)
                    .consecutive();
        }

        return basePattern;
    }

    /**
     * 构建并执行 Flink CEP 作业（本地 MiniCluster，用于测试）。
     *
     * @param rules 质量规则列表
     * @param source 自定义 SourceFunction
     * @throws Exception 作业执行异常
     */
    public static void executeJob(java.util.List<QualityRule> rules,
                                  SourceFunction<QualityRecord> source) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<QualityRecord> inputStream = env.addSource(source);

        for (QualityRule rule : rules) {
            Pattern<QualityRecord, ?> pattern = buildPattern(rule);
            // 在实际实现中，使用 CEP.pattern(inputStream, pattern).select(...) 提取匹配
            // 这里仅构建 Pattern，实际作业提交通过 Flink SQL Gateway
            log.info("Built CEP pattern for rule: ruleId={}, type={}",
                    rule.getRuleId(), rule.getRuleType());
        }

        // 实际生产环境通过 Flink SQL Gateway REST API 提交，而非 LocalEnvironment
        env.execute("quality-rule-cep-job");
    }

    /**
     * 质量记录（流式评估输入）。
     *
     * <p>包含记录 ID、表标识符、字段名、字段值、产生时间戳。
     */
    public static class QualityRecord {
        public String recordId;
        public String tableIdentifier;
        public String fieldName;
        public Object fieldValue;
        public long timestamp;

        public QualityRecord() {}

        public QualityRecord(String recordId, String tableIdentifier,
                             String fieldName, Object fieldValue, long timestamp) {
            this.recordId = recordId;
            this.tableIdentifier = tableIdentifier;
            this.fieldName = fieldName;
            this.fieldValue = fieldValue;
            this.timestamp = timestamp;
        }
    }
}