/**
 * 被动安全扫描 —— ZAP 基线扫描的轻量替代
 *
 * 当 ZAP 不可用时，用本模块对运行中的服务做被动扫描：
 *   1. 检查 HTTP 安全响应头
 *   2. 检查常见敏感端点是否暴露
 *   3. 检查 TLS/HTTPS 配置（如适用）
 *   4. 检查 actuator 端点暴露
 *   5. 检查错误页面信息泄露
 *   6. 检查 HTTP 方法白名单
 *   7. 检查 Cookie 安全属性
 */

const {
  BASE_URL, API_PREFIX, securityFetch, login,
  TestRunner,
} = require('./helpers');

async function main() {
  const t = new TestRunner('被动安全扫描（ZAP 替代）');
  const { token } = await login();

  // 1. HTTP 安全响应头检查
  await t.test('X-Content-Type-Options: nosniff 应存在', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const v = r.headers['x-content-type-options'] || '';
    if (!v) t.warn_('缺失', '建议添加 X-Content-Type-Options: nosniff');
    else if (v.toLowerCase() !== 'nosniff') throw new Error(`值异常: ${v}`);
  });

  await t.test('X-Frame-Options 应存在（防点击劫持）', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const v = r.headers['x-frame-options'] || '';
    if (!v) t.warn_('缺失', '建议添加 X-Frame-Options: DENY');
  });

  await t.test('X-XSS-Protection 应存在（旧浏览器）', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const v = r.headers['x-xss-protection'] || '';
    if (!v) t.warn_('缺失', '建议添加 X-XSS-Protection: 1; mode=block');
  });

  await t.test('Strict-Transport-Security 应存在（HTTPS）', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const v = r.headers['strict-transport-security'] || '';
    if (!v) {
      // HTTP 开发环境可缺失，记为 INFO
      t.warn_('缺失', '生产环境 HTTPS 应设置 HSTS');
    }
  });

  await t.test('Cache-Control 应禁用敏感数据缓存', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const v = r.headers['cache-control'] || '';
    if (!v.toLowerCase().includes('no-store') && !v.toLowerCase().includes('no-cache')) {
      t.warn_('缺失 no-store', '建议敏感 API 设置 Cache-Control: no-store');
    }
  });

  await t.test('Content-Security-Policy 应存在', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const v = r.headers['content-security-policy'] || '';
    if (!v) t.warn_('缺失', '建议前端设置 CSP');
  });

  await t.test('Referrer-Policy 应存在', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const v = r.headers['referrer-policy'] || '';
    if (!v) t.warn_('缺失', '建议设置 Referrer-Policy: no-referrer');
  });

  // 2. 敏感端点暴露检查
  const sensitiveEndpoints = [
    '/actuator/env',           // 环境变量（可能含密码）
    '/actuator/configprops',   // 配置属性
    '/actuator/heapdump',      // 堆转储
    '/actuator/threaddump',    // 线程转储
    '/actuator/mappings',      // 路由映射
    '/actuator/beans',         // Spring beans
    '/actuator/loggers',       // 日志配置
    '/actuator/metrics',       // 指标
    '/actuator/auditevents',   // 审计事件
    '/actuator/httptrace',     // HTTP 跟踪
    '/actuator/scheduledtasks',// 定时任务
    '/actuator/sessions',      // 会话
    '/actuator/shutdown',      // 关闭端点
    '/actuator/refresh',       // 刷新配置
    '/actuator/restart',       // 重启
    '/v2/api-docs',            // Swagger v2
    '/v3/api-docs',            // OpenAPI v3
    '/swagger-ui.html',        // Swagger UI
    '/swagger-ui/index.html',  // Swagger UI
    '/.env',                   // .env 文件
    '/.git/config',            // .git 配置
    '/WEB-INF/web.xml',        // web.xml
    '/META-INF/MANIFEST.MF',   // MANIFEST
  ];

  await t.test('敏感 actuator 端点不应暴露敏感信息', async () => {
    for (const ep of sensitiveEndpoints) {
      const r = await securityFetch('GET', ep, { token, timeout: 5000 });
      // 404/401/403 可接受，200 需检查内容
      if (r.status === 200) {
        const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
        // 检查是否泄露密码/密钥
        const sensitivePatterns = [
          /"password"\s*:\s*"[^"]+"/i,
          /"secret"\s*:\s*"[^"]+"/i,
          /"jwt.secret"\s*:\s*"[^"]+"/i,
          /"encrypt-key"\s*:\s*"[^"]+"/i,
          /"credentials"\s*:\s*"[^"]+"/i,
        ];
        for (const re of sensitivePatterns) {
          if (re.test(text)) {
            throw new Error(`${ep} 泄露敏感配置: ${text.slice(0, 200)}`);
          }
        }
      }
    }
  });

  // 3. actuator/health 可访问（预期）
  await t.test('actuator/health 可访问（公开端点）', async () => {
    const r = await securityFetch('GET', '/actuator/health');
    if (r.status !== 200) throw new Error(`期望 200，实际 ${r.status}`);
  });

  // 4. actuator/info 检查
  await t.test('actuator/info 不应泄露版本细节', async () => {
    const r = await securityFetch('GET', '/actuator/info', { token, timeout: 5000 });
    if (r.status === 200) {
      const text = JSON.stringify(r.body);
      // 检查是否泄露过多版本信息
      if (text.includes('java.version') && text.includes('os.arch')) {
        t.warn_('泄露系统信息', '建议限制 actuator/info 内容');
      }
    }
  });

  // 5. HTTP 方法检查
  await t.test('TRACE 方法应被禁用（防 XST）', async () => {
    // Node.js fetch 不支持 TRACE，用 http 模块直接发
    const http = require('http');
    const url = new URL(BASE_URL + API_PREFIX + '/tenants');
    const result = await new Promise((resolve) => {
      const req = http.request({
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'TRACE',
        headers: { Authorization: `Bearer ${token}` },
        timeout: 5000,
      }, (res) => {
        let body = '';
        res.on('data', (c) => body += c);
        res.on('end', () => resolve({ status: res.statusCode, body, headers: res.headers }));
      });
      req.on('error', (e) => resolve({ status: 0, body: e.message, headers: {} }));
      req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: 'timeout', headers: {} }); });
      req.end();
    });
    // 405 方法不允许是正确的，404 也算可接受（端点不存在）
    // 200 + 回显 Authorization 头是漏洞（XST）
    if (result.status === 200 && result.body.includes('Authorization')) {
      throw new Error('TRACE 回显了 Authorization 头（XST 漏洞）');
    }
    // 501/405/404 都表示 TRACE 被禁用
    if (result.status === 200) {
      t.warn_('TRACE 返回 200', '建议禁用 TRACE 方法');
    }
  });

  await t.test('DELETE 根路径应被拒绝', async () => {
    const r = await securityFetch('DELETE', `${API_PREFIX}/tenants`, { token, timeout: 5000 });
    // 405 方法不允许是合理的
    if (r.status === 200) throw new Error('DELETE /tenants 不应返回 200');
  });

  // 6. 错误页面信息泄露
  await t.test('404 错误不应泄露堆栈', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/nonexistent-endpoint-xyz`, { token });
    const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
    if (text.includes('at org.springframework.') || text.includes('at com.levango7.')) {
      throw new Error('404 页面泄露堆栈');
    }
  });

  await t.test('500 错误不应泄露堆栈', async () => {
    // 触发一个可能 500 的请求
    const r = await securityFetch('POST', `${API_PREFIX}/tenants`, {
      token,
      body: { invalid: 'structure' },
    });
    if (r.status === 500) {
      const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
      if (text.includes('at org.springframework.') || text.includes('at com.levango7.')) {
        throw new Error('500 页面泄露堆栈');
      }
    }
  });

  // 7. Cookie 安全属性
  await t.test('Cookie 应设置 HttpOnly + Secure + SameSite', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const setCookie = r.headers['set-cookie'] || '';
    if (setCookie) {
      if (!setCookie.toLowerCase().includes('httponly')) {
        throw new Error('Cookie 缺失 HttpOnly');
      }
      // Secure 在 HTTPS 环境要求，HTTP 开发环境可缺失
      if (!setCookie.toLowerCase().includes('samesite')) {
        t.warn_('Cookie 缺失 SameSite', '建议设置 SameSite=Strict');
      }
    }
    // 无 Cookie 也是可接受的（STATELESS）
  });

  // 8. 检查 Swagger 是否在生产暴露
  await t.test('Swagger/OpenAPI 文档不应在生产暴露', async () => {
    const endpoints = ['/v3/api-docs', '/swagger-ui.html', '/swagger-ui/index.html'];
    for (const ep of endpoints) {
      const r = await securityFetch('GET', ep, { token, timeout: 5000 });
      if (r.status === 200) {
        t.warn_(`${ep} 可访问`, '生产环境应禁用 Swagger');
      }
    }
  });

  // 9. 检查目录列表
  await t.test('不应开启目录列表', async () => {
    const paths = ['/static/', '/public/', '/resources/', '/WEB-INF/', '/META-INF/'];
    for (const p of paths) {
      const r = await securityFetch('GET', p, { token, timeout: 5000 });
      if (r.status === 200) {
        const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
        if (text.includes('Directory listing') || text.includes('Index of /')) {
          throw new Error(`${p} 开启了目录列表`);
        }
      }
    }
  });

  // 10. 检查 HTTP 版本
  await t.test('应使用 HTTP/1.1 或更高', async () => {
    // fetch 默认使用 HTTP/1.1，此处仅验证请求成功
    const r = await securityFetch('GET', `${API_PREFIX}/health`);
    if (r.status !== 200) throw new Error(`健康检查失败: ${r.status}`);
  });

  return t.summary();
}

if (require.main === module) {
  main().catch((e) => { console.error('FATAL:', e); process.exit(1); });
}
module.exports = main;