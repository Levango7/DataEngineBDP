package com.shuqing.bigdata.rule.engine.orchestrator.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 邮件告警通道。
 *
 * <p>MVP 阶段不引入 JavaMail 依赖，仅记录日志并模拟发送成功，
 * 保留接口与配置开关，后续接入 SMTP 时只需替换 send 内部实现。</p>
 *
 * <p>设计说明：通过 @Value 注入收件人列表与开关，便于在 application.yml
 * 的 app.orchestrator.alert.email 下统一配置。</p>
 */
@Component
public class EmailAlert implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailAlert.class);

    private final boolean enabled;
    private final String from;
    private final List<String> recipients;

    public EmailAlert(@Value("${app.orchestrator.alert.email.enabled:false}") boolean enabled,
                      @Value("${app.orchestrator.alert.email.from:rule-engine@shuqing.local}") String from,
                      @Value("${app.orchestrator.alert.email.recipients:}") String recipientsCsv) {
        this.enabled = enabled;
        this.from = from;
        this.recipients = parseRecipients(recipientsCsv);
    }

    @Override
    public boolean send(AlertEvent event) {
        if (!enabled) {
            log.debug("email alert disabled, skip event={}", event.getId());
            return false;
        }
        // MVP：仅日志，后续接入 JavaMail
        log.info("[EMAIL-ALERT] from={} to={} level={} type={} dag={} node={} title={} msg={}",
                from, recipients, event.getLevel(), event.getType(),
                event.getDagId(), event.getNodeId(), event.getTitle(), event.getMessage());
        return true;
    }

    @Override
    public String name() {
        return "EMAIL";
    }

    private static List<String> parseRecipients(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] parts = csv.split(",");
        List<String> result = new java.util.ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 暴露收件人列表用于测试 */
    public List<String> getRecipients() {
        return recipients;
    }
}