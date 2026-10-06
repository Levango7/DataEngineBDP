package com.levango7.dataenginebdp.encaps.util;

import com.levango7.dataenginebdp.encaps.model.ApiKeyEntity;
import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.ApiKeyRepository;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 存量凭据密文的离线一次性迁移任务（永久迁移方案，稳态零热路径开销）。
 *
 * <p>背景：切换加密辖区（如 AES-GCM → SM4-GCM）后，历史行仍是旧算法密文。
 * 读取侧已通过 {@link CredentialEncryptor} 的**自描述算法标识双读**兼容，
 * 因此迁移**不必**放在读写热路径上——本任务在启动后一次性扫描存量并原地重加密，
 * 迁移完成后关闭开关即可，运行时零额外开销。</p>
 *
 * <h3>启用方式（默认关闭）</h3>
 * <pre>{@code
 * app:
 *   security:
 *     credential-migration:
 *       enabled: true    # 仅在迁移窗口临时置 true，跑完即关
 *       dry-run: false   # true 只统计不落库
 * }</pre>
 *
 * <h3>迁移语义（幂等）</h3>
 * <ul>
 *   <li>已是当前主算法密文 → 跳过</li>
 *   <li>旧算法密文（AES-GCM / SM4-CBC）→ 解密后按当前主算法重加密</li>
 *   <li>历史明文 → 直接加密为当前主算法</li>
 * </ul>
 * <p>重复运行安全（幂等）。逐行提交；单行失败仅记录 ERROR 并继续，不中断整体迁移。</p>
 *
 * <h3>可逆性</h3>
 * <p>重加密是**不可逆原地改写**。执行前应确保数据库已备份/Snapshot；如出现异常应立即停止，
 * 依赖备份恢复（双读兼容能保证迁移中途新旧密文均可读，但不会还原旧密文）。</p>
 */
@Component
@ConditionalOnProperty(name = "app.security.credential-migration.enabled", havingValue = "true")
public class CredentialMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialMigrationRunner.class);

    private final ObjectProvider<CredentialEncryptor> encryptorProvider;
    private final ObjectProvider<DataSourceRepository> dataSourceRepositoryProvider;
    private final ObjectProvider<ApiKeyRepository> apiKeyRepositoryProvider;
    private final boolean dryRun;

    public CredentialMigrationRunner(
            ObjectProvider<CredentialEncryptor> encryptorProvider,
            ObjectProvider<DataSourceRepository> dataSourceRepositoryProvider,
            ObjectProvider<ApiKeyRepository> apiKeyRepositoryProvider,
            @Value("${app.security.credential-migration.dry-run:false}") boolean dryRun) {
        this.encryptorProvider = encryptorProvider;
        this.dataSourceRepositoryProvider = dataSourceRepositoryProvider;
        this.apiKeyRepositoryProvider = apiKeyRepositoryProvider;
        this.dryRun = dryRun;
    }

    @Override
    public void run(ApplicationArguments args) {
        CredentialEncryptor encryptor = encryptorProvider.getIfAvailable();
        if (encryptor == null) {
            log.warn("凭据迁移已启用，但未找到 CredentialEncryptor（未配置密钥？），跳过。");
            return;
        }
        log.warn("凭据迁移任务启动：目标算法={}，dryRun={}。执行前请确认已备份数据库。",
                encryptor.getAlgorithm(), dryRun);
        migrateDataSources(encryptor);
        migrateApiKeys(encryptor);
    }

    private void migrateDataSources(CredentialEncryptor encryptor) {
        DataSourceRepository repo = dataSourceRepositoryProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        int scanned = 0;
        int changed = 0;
        int failed = 0;
        for (DataSourceEntity entity : repo.findAll()) {
            scanned++;
            try {
                String before = entity.getPassword();
                String after = encryptor.reencrypt(before);
                if (!Objects.equals(before, after)) {
                    changed++;
                    if (!dryRun) {
                        entity.setPassword(after);
                        repo.save(entity);
                    }
                }
            } catch (Exception e) {
                failed++;
                log.error("凭据迁移失败 datasource id={}: {}", entity.getId(), e.getMessage());
            }
        }
        log.info("凭据迁移[datasource] scanned={} changed={} failed={} dryRun={}",
                scanned, changed, failed, dryRun);
    }

    private void migrateApiKeys(CredentialEncryptor encryptor) {
        ApiKeyRepository repo = apiKeyRepositoryProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        int scanned = 0;
        int changed = 0;
        int failed = 0;
        for (ApiKeyEntity entity : repo.findAll()) {
            scanned++;
            try {
                String before = entity.getApiKey();
                String after = encryptor.reencrypt(before);
                if (!Objects.equals(before, after)) {
                    changed++;
                    if (!dryRun) {
                        entity.setApiKey(after);
                        repo.save(entity);
                    }
                }
            } catch (Exception e) {
                failed++;
                log.error("凭据迁移失败 apiKey id={}: {}", entity.getId(), e.getMessage());
            }
        }
        log.info("凭据迁移[apiKey] scanned={} changed={} failed={} dryRun={}",
                scanned, changed, failed, dryRun);
    }
}
