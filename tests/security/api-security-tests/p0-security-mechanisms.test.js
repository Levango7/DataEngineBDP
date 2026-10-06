/**
 * P0 安全机制验证 —— 验证任务 401 落地的安全机制是否生效
 *
 * 验证项：
 *   1. SmCryptoUtil 国密工具类可加载（SM2/SM3/SM4）
 *   2. @Encrypt 字段加密注解 + FieldEncryptAspect 切面工作
 *   3. @AuditLog 审计日志注解 + AuditLogAspect 切面工作
 *   4. JWT 认证过滤器（JwtAuthFilter）工作
 *   5. SecurityConfig 安全配置生效（permitAll/authenticated）
 *   6. TenantContext 租户上下文隔离
 *   7. CORS 配置
 *   8. 密钥配置（app.security.encrypt-key）
 *
 * 验证方式：
 *   - 静态：检查类文件存在、注解使用点
 *   - 动态：通过 API 调用验证行为
 *   - 日志：检查审计日志输出
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const {
  API_PREFIX, securityFetch, login,
  TestRunner,
} = require('./helpers');

const PROJECT_ROOT = process.env.PROJECT_ROOT || 'F:\\nexus\\DataEngineBDP';
const SECURITY_PKG = path.join(PROJECT_ROOT, 'platform/encaps-layer/src/main/java/com/levango7/dataenginebdp/encaps/security');

function fileExists(p) {
  try { return fs.statSync(p).isFile(); } catch { return false; }
}

function grepInFile(p, pattern) {
  try {
    const content = fs.readFileSync(p, 'utf8');
    return content.match(pattern) !== null;
  } catch { return false; }
}

function grepInDir(dir, pattern, include = '*.java') {
  try {
    const cmd = `rg -l "${pattern}" "${dir}" --glob "${include}" 2>nul`;
    const out = execSync(cmd, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] });
    return out.trim().split(/\r?\n/).filter(Boolean);
  } catch { return []; }
}

async function main() {
  const t = new TestRunner('P0 安全机制验证');

  // 1. SmCryptoUtil 国密工具类
  await t.test('SmCryptoUtil 国密工具类文件存在', async () => {
    const f = path.join(SECURITY_PKG, 'SmCryptoUtil.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('SmCryptoUtil 包含 SM2/SM3/SM4 方法', async () => {
    const f = path.join(SECURITY_PKG, 'SmCryptoUtil.java');
    const content = fs.readFileSync(f, 'utf8');
    const required = ['sm2Sign', 'sm2Verify', 'sm2Encrypt', 'sm2Decrypt',
                      'sm3Hash', 'sm3HashHex',
                      'sm4Encrypt', 'sm4Decrypt', 'sm4GenerateKey'];
    for (const m of required) {
      if (!content.includes(m)) throw new Error(`SmCryptoUtil 缺失方法: ${m}`);
    }
  });

  await t.test('SmCryptoUtil 引用国标 Provider', async () => {
    const f = path.join(SECURITY_PKG, 'SmCryptoUtil.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('SM2Provider')) throw new Error('未引用 SM2Provider');
    if (!content.includes('SM3Provider')) throw new Error('未引用 SM3Provider');
    if (!content.includes('SM4Provider')) throw new Error('未引用 SM4Provider');
  });

  // 2. @Encrypt 注解 + FieldEncryptAspect 切面
  await t.test('@Encrypt 注解类存在', async () => {
    const f = path.join(SECURITY_PKG, 'Encrypt.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('@Decrypt 注解类存在', async () => {
    const f = path.join(SECURITY_PKG, 'Decrypt.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('FieldEncryptAspect 切面类存在', async () => {
    const f = path.join(SECURITY_PKG, 'FieldEncryptAspect.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('FieldEncryptAspect 标注 @Aspect @Component', async () => {
    const f = path.join(SECURITY_PKG, 'FieldEncryptAspect.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('@Aspect')) throw new Error('未标注 @Aspect');
    if (!content.includes('@Component')) throw new Error('未标注 @Component');
  });

  await t.test('FieldEncryptAspect 拦截 @Encrypt @Decrypt', async () => {
    const f = path.join(SECURITY_PKG, 'FieldEncryptAspect.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('@annotation(encrypt)')) throw new Error('未拦截 @Encrypt');
    if (!content.includes('@annotation(decrypt)')) throw new Error('未拦截 @Decrypt');
  });

  await t.test('FieldEncryptAspect 使用 SmCryptoUtil', async () => {
    const f = path.join(SECURITY_PKG, 'FieldEncryptAspect.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('SmCryptoUtil.sm4Encrypt')) throw new Error('未调用 SM4 加密');
    if (!content.includes('SmCryptoUtil.sm4Decrypt')) throw new Error('未调用 SM4 解密');
    if (!content.includes('SmCryptoUtil.sm3Hash')) throw new Error('未调用 SM3 哈希');
  });

  // 3. @AuditLog 注解 + AuditLogAspect 切面
  await t.test('@AuditLog 注解类存在', async () => {
    const f = path.join(SECURITY_PKG, 'AuditLog.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('AuditLogAspect 切面类存在', async () => {
    const f = path.join(SECURITY_PKG, 'AuditLogAspect.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('AuditLogAspect 标注 @Aspect @Component', async () => {
    const f = path.join(SECURITY_PKG, 'AuditLogAspect.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('@Aspect')) throw new Error('未标注 @Aspect');
    if (!content.includes('@Component')) throw new Error('未标注 @Component');
  });

  await t.test('AuditLogAspect 记录完整审计字段（等保 8.1.4.3）', async () => {
    const f = path.join(SECURITY_PKG, 'AuditLogAspect.java');
    const content = fs.readFileSync(f, 'utf8');
    const required = ['timestamp', 'user', 'tenant', 'action', 'resource', 'result', 'durationMs', 'ip'];
    for (const field of required) {
      if (!content.includes(field)) throw new Error(`审计字段缺失: ${field}`);
    }
  });

  await t.test('AuditLogAspect 使用独立审计 logger', async () => {
    const f = path.join(SECURITY_PKG, 'AuditLogAspect.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('security.audit.log')) throw new Error('未使用独立审计 logger');
  });

  // 4. JwtAuthFilter
  await t.test('JwtAuthFilter 类存在', async () => {
    const f = path.join(SECURITY_PKG, 'JwtAuthFilter.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('JwtAuthFilter 验证签名 + issuer + 过期', async () => {
    const f = path.join(SECURITY_PKG, 'JwtAuthFilter.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('verifyWith')) throw new Error('未验证签名');
    if (!content.includes('requireIssuer')) throw new Error('未验证 issuer');
    if (!content.includes('parseSignedClaims')) throw new Error('未解析 JWT');
  });

  await t.test('JwtAuthFilter 清理 ThreadLocal（防串号）', async () => {
    const f = path.join(SECURITY_PKG, 'JwtAuthFilter.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('TenantContext.clear')) throw new Error('未清理 TenantContext');
    if (!content.includes('SecurityContextHolder.clearContext')) throw new Error('未清理 SecurityContext');
  });

  // 5. SecurityConfig
  await t.test('SecurityConfig 类存在', async () => {
    const f = path.join(SECURITY_PKG, 'SecurityConfig.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  await t.test('SecurityConfig 配置 STATELESS 会话', async () => {
    const f = path.join(SECURITY_PKG, 'SecurityConfig.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('SessionCreationPolicy.STATELESS')) throw new Error('未配置 STATELESS');
  });

  await t.test('SecurityConfig 放行 health + login + actuator', async () => {
    const f = path.join(SECURITY_PKG, 'SecurityConfig.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('/api/v1/health')) throw new Error('未放行 /api/v1/health');
    if (!content.includes('/api/v1/auth/login')) throw new Error('未放行 /api/v1/auth/login');
    if (!content.includes('/actuator/**')) throw new Error('未放行 /actuator/**');
  });

  await t.test('SecurityConfig 其他请求要求认证', async () => {
    const f = path.join(SECURITY_PKG, 'SecurityConfig.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('anyRequest().authenticated')) throw new Error('未配置 anyRequest().authenticated');
  });

  await t.test('SecurityConfig 配置 CORS', async () => {
    const f = path.join(SECURITY_PKG, 'SecurityConfig.java');
    const content = fs.readFileSync(f, 'utf8');
    if (!content.includes('CorsConfiguration')) throw new Error('未配置 CORS');
  });

  // 6. TenantContext
  await t.test('TenantContext 类存在', async () => {
    const f = path.join(SECURITY_PKG, 'TenantContext.java');
    if (!fileExists(f)) throw new Error(`缺失: ${f}`);
  });

  // 7. 动态验证：API 行为
  await t.test('动态：未认证访问受保护 API 返回 401', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`);
    if (r.status !== 401) throw new Error(`期望 401，实际 ${r.status}`);
  });

  await t.test('动态：合法 token 访问受保护 API 返回 2xx', async () => {
    const { token } = await login();
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    if (!(r.status >= 200 && r.status < 300)) throw new Error(`期望 2xx，实际 ${r.status}`);
  });

  await t.test('动态：公开端点可匿名访问', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/health`);
    if (!(r.status >= 200 && r.status < 300)) throw new Error(`期望 2xx，实际 ${r.status}`);
  });

  // 8. 检查 @AuditLog 使用点
  await t.test('代码中存在 @AuditLog 使用点', async () => {
    const controllersDir = path.join(PROJECT_ROOT, 'platform/encaps-layer/src/main/java');
    const files = grepInDir(controllersDir, '@AuditLog\\s*\\(');
    if (files.length === 0) {
      t.warn_('未找到 @AuditLog 使用点', '建议在关键 Controller 方法上标注 @AuditLog');
    }
  });

  // 9. 检查 @Encrypt 使用点
  await t.test('代码中存在 @Encrypt 使用点', async () => {
    const srcDir = path.join(PROJECT_ROOT, 'platform/encaps-layer/src/main/java');
    const files = grepInDir(srcDir, '@Encrypt\\s*[\\(\\s]');
    if (files.length === 0) {
      t.warn_('未找到 @Encrypt 使用点', '建议在敏感字段上标注 @Encrypt');
    } else {
      console.log(`     找到 ${files.length} 处 @Encrypt 使用`);
    }
  });

  // 10. 检查 application.yml 安全配置
  await t.test('application.yml 包含安全配置', async () => {
    const ymlPaths = [
      path.join(PROJECT_ROOT, 'platform/encaps-layer/src/main/resources/application.yml'),
      path.join(PROJECT_ROOT, 'platform/encaps-layer/src/main/resources/application-dev.yml'),
    ];
    let found = false;
    for (const yml of ymlPaths) {
      if (fileExists(yml)) {
        const content = fs.readFileSync(yml, 'utf8');
        if (content.includes('app.security') || content.includes('jwt.secret') || content.includes('encrypt-key')) {
          found = true;
          break;
        }
      }
    }
    if (!found) {
      t.warn_('未找到 app.security 配置', '建议在 application.yml 中配置 app.security.*');
    }
  });

  // 11. 检查审计日志文件
  await t.test('审计日志文件应被创建（运行时）', async () => {
    const logPaths = [
      path.join(PROJECT_ROOT, 'logs/audit.log'),
      path.join(PROJECT_ROOT, 'platform/encaps-layer/logs/audit.log'),
      path.join(PROJECT_ROOT, 'logs/encaps-layer-audit.log'),
    ];
    let found = false;
    for (const lp of logPaths) {
      if (fileExists(lp)) { found = true; break; }
    }
    if (!found) {
      t.warn_('未找到 audit.log 文件', '审计日志可能在其他位置或未触发写入');
    }
  });

  // 12. 检查 SecurityFacade（任务 401 的统一安全门面）
  await t.test('SecurityFacade 统一安全门面存在', async () => {
    const f = path.join(SECURITY_PKG, 'facade/SecurityFacade.java');
    if (!fileExists(f)) {
      t.warn_('SecurityFacade 不存在', '建议提供统一安全门面');
    }
  });

  return t.summary();
}

if (require.main === module) {
  main().catch((e) => { console.error('FATAL:', e); process.exit(1); });
}
module.exports = main;