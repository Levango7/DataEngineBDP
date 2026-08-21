package com.levango7.dataenginebdp.common.security.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 审计合规配置（v2.1 审计合规增强）。
 *
 * <p>满足等保三级与金融行业审计要求的操作审计全链路配置。</p>
 *
 * <p>等保三级审计要求（GB/T 22239-2019）：</p>
 * <ul>
 *   <li>8.1.4.3 a) 安全审计功能：覆盖到每个用户，对重要的用户行为和重要安全事件进行审计</li>
 *   <li>8.1.4.3 b) 审计记录内容：日期、时间、用户、事件类型、事件是否成功等</li>
 *   <li>8.1.4.3 c) 审计记录保护：防止未预期的删除、修改或覆盖</li>
 *   <li>8.1.4.3 d) 审计进程保护：免受未预期的中断</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.audit")
public class AuditConfig {

    /**
     * 是否启用审计日志。
     */
    private boolean enabled = true;

    /**
     * 审计日志存储方式：file / database / kafka / elasticsearch。
     */
    private String storage = "file";

    /**
     * 审计日志文件路径。
     */
    private String logFilePath = "/var/log/dataenginebdp/audit";

    /**
     * 审计日志文件最大大小（MB）。
     */
    private int logFileMaxSizeMb = 100;

    /**
     * 审计日志文件最大保留数量。
     */
    private int logFileMaxHistory = 90;

    /**
     * 审计日志异步写入（提高性能）。
     */
    private boolean asyncWrite = true;

    /**
     * 异步写入队列大小。
     */
    private int asyncQueueSize = 10000;

    /**
     * 是否记录请求参数。
     */
    private boolean logRequestParams = true;

    /**
     * 请求参数最大长度（超出截断）。
     */
    private int requestParamsMaxLength = 2000;

    /**
     * 敏感字段脱敏列表（等保三级要求敏感信息脱敏）。
     */
    private List<String> sensitiveFields = List.of(
            "password", "passwd", "pwd", "secret", "token", "apiKey", "api_key",
            "creditCard", "credit_card", "idCard", "id_card", "phone", "mobile",
            "email", "bankAccount", "bank_account", "cvv", "pin"
    );

    /**
     * 需要审计的路径白名单（空表示审计所有路径）。
     */
    private List<String> includePaths = List.of();

    /**
     * 不需要审计的路径黑名单。
     */
    private List<String> excludePaths = List.of(
            "/actuator/health",
            "/actuator/info",
            "/actuator/metrics",
            "/api/v1/health"
    );

    /**
     * 是否记录响应体。
     */
    private boolean logResponseBody = false;

    /**
     * 响应体最大长度。
     */
    private int responseBodyMaxLength = 1000;

    /**
     * 是否启用等保三级合规检查。
     */
    private boolean dengbaoLevel3Enabled = true;

    /**
     * 是否启用金融行业审计。
     */
    private boolean financeAuditEnabled = true;

    /**
     * 审计日志保留天数（等保三级要求至少 180 天，金融行业要求至少 7 年）。
     */
    private int retentionDays = 2555;

    /**
     * 审计日志防篡改（HMAC 签名）。
     */
    private boolean tamperProof = true;

    /**
     * 审计日志 HMAC 签名密钥。
     */
    private String hmacSecret = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public String getLogFilePath() {
        return logFilePath;
    }

    public void setLogFilePath(String logFilePath) {
        this.logFilePath = logFilePath;
    }

    public int getLogFileMaxSizeMb() {
        return logFileMaxSizeMb;
    }

    public void setLogFileMaxSizeMb(int logFileMaxSizeMb) {
        this.logFileMaxSizeMb = logFileMaxSizeMb;
    }

    public int getLogFileMaxHistory() {
        return logFileMaxHistory;
    }

    public void setLogFileMaxHistory(int logFileMaxHistory) {
        this.logFileMaxHistory = logFileMaxHistory;
    }

    public boolean isAsyncWrite() {
        return asyncWrite;
    }

    public void setAsyncWrite(boolean asyncWrite) {
        this.asyncWrite = asyncWrite;
    }

    public int getAsyncQueueSize() {
        return asyncQueueSize;
    }

    public void setAsyncQueueSize(int asyncQueueSize) {
        this.asyncQueueSize = asyncQueueSize;
    }

    public boolean isLogRequestParams() {
        return logRequestParams;
    }

    public void setLogRequestParams(boolean logRequestParams) {
        this.logRequestParams = logRequestParams;
    }

    public int getRequestParamsMaxLength() {
        return requestParamsMaxLength;
    }

    public void setRequestParamsMaxLength(int requestParamsMaxLength) {
        this.requestParamsMaxLength = requestParamsMaxLength;
    }

    public List<String> getSensitiveFields() {
        return sensitiveFields;
    }

    public void setSensitiveFields(List<String> sensitiveFields) {
        this.sensitiveFields = sensitiveFields;
    }

    public List<String> getIncludePaths() {
        return includePaths;
    }

    public void setIncludePaths(List<String> includePaths) {
        this.includePaths = includePaths;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public boolean isLogResponseBody() {
        return logResponseBody;
    }

    public void setLogResponseBody(boolean logResponseBody) {
        this.logResponseBody = logResponseBody;
    }

    public int getResponseBodyMaxLength() {
        return responseBodyMaxLength;
    }

    public void setResponseBodyMaxLength(int responseBodyMaxLength) {
        this.responseBodyMaxLength = responseBodyMaxLength;
    }

    public boolean isDengbaoLevel3Enabled() {
        return dengbaoLevel3Enabled;
    }

    public void setDengbaoLevel3Enabled(boolean dengbaoLevel3Enabled) {
        this.dengbaoLevel3Enabled = dengbaoLevel3Enabled;
    }

    public boolean isFinanceAuditEnabled() {
        return financeAuditEnabled;
    }

    public void setFinanceAuditEnabled(boolean financeAuditEnabled) {
        this.financeAuditEnabled = financeAuditEnabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isTamperProof() {
        return tamperProof;
    }

    public void setTamperProof(boolean tamperProof) {
        this.tamperProof = tamperProof;
    }

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }
}