/**
 * CSRF 安全测试 —— 跨站请求伪造防护验证
 *
 * 覆盖 OWASP Top 10:
 *   A01:2021 — Broken Access Control（CSRF 子项）
 *
 * 设计说明：
 *   - 本系统为纯 REST API + JWT Bearer 认证，理论上 CSRF 风险较低
 *     （因为浏览器不会自动附带 Authorization 头）
 *   - 但仍需验证：
 *     1. 不依赖 Cookie 进行会话管理（STATELESS）
 *     2. CORS 配置不应过于宽松（不允许 * + credentials）
 *     3. 跨域请求应被 CORS 拦截或无 Cookie 携带
 *     4. 关键操作（POST/PUT/DELETE）应要求 Authorization 头
 *
 * 测试项：
 *   1. 响应不应设置会话 Cookie（JSESSIONID 等）
 *   2. CORS 不应允许任意源 + credentials
 *   3. 跨域预检 OPTIONS 应正确配置
 *   4. 无 Authorization 头的 POST 应返回 401（非 CSRF 攻击可绕过）
 *   5. Content-Type: application/x-www-form-urlencoded 跨域请求应被拦截
 *   6. 响应头应包含安全头（X-Content-Type-Options 等）
 */

const {
  API_PREFIX, BASE_URL, securityFetch, login,
  TestRunner,
} = require('./helpers');

async function main() {
  const t = new TestRunner('CSRF 安全测试');
  const { token } = await login();

  // 1. 不应设置会话 Cookie
  await t.test('响应不应设置会话 Cookie（STATELESS）', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const setCookie = r.headers['set-cookie'] || '';
    if (setCookie.includes('JSESSIONID') || setCookie.includes('SESSION')) {
      throw new Error(`设置了会话 Cookie: ${setCookie}`);
    }
  });

  // 2. CORS 配置检查
  await t.test('CORS 不应允许任意源（*）+ credentials', async () => {
    const r = await securityFetch('OPTIONS', `${API_PREFIX}/tenants`, {
      headers: {
        Origin: 'https://evil.example.com',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'Content-Type',
      },
    });
    const allowOrigin = r.headers['access-control-allow-origin'] || '';
    const allowCreds = r.headers['access-control-allow-credentials'] || '';
    // 不应同时允许 * 和 credentials=true
    if (allowOrigin === '*' && allowCreds === 'true') {
      throw new Error('CORS 配置过于宽松：Allow-Origin=* + Allow-Credentials=true');
    }
    // 不应允许任意 evil 域
    if (allowOrigin === 'https://evil.example.com') {
      throw new Error('CORS 允许了未授权的 evil 域');
    }
  });

  // 3. 跨域预检 OPTIONS
  await t.test('OPTIONS 预检应返回 2xx 或 403', async () => {
    const r = await securityFetch('OPTIONS', `${API_PREFIX}/tenants`, {
      headers: {
        Origin: 'http://localhost:5173',
        'Access-Control-Request-Method': 'GET',
      },
    });
    // 2xx 或 403 都可接受
    if (!(r.status >= 200 && r.status < 300) && r.status !== 403) {
      throw new Error(`OPTIONS 预检返回 ${r.status}`);
    }
  });

  // 4. 无 Authorization 的 POST 应返回 401
  await t.test('无 Authorization 头的 POST 应返回 401', async () => {
    const r = await securityFetch('POST', `${API_PREFIX}/tenants`, {
      body: { name: 'csrf-test', code: 'csrf-test' },
    });
    if (r.status !== 401) {
      throw new Error(`无认证 POST 返回 ${r.status}，应为 401（CSRF 防护）`);
    }
  });

  // 5. 表单格式跨域请求
  await t.test('application/x-www-form-urlencoded 跨域请求应被拦截或要求认证', async () => {
    const r = await securityFetch('POST', `${API_PREFIX}/tenants`, {
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        Origin: 'https://evil.example.com',
      },
      body: 'name=csrf-test&code=csrf-test',
    });
    // 应被 CORS 拦截（无 Access-Control-Allow-Origin）或返回 401
    const allowOrigin = r.headers['access-control-allow-origin'] || '';
    if (allowOrigin === 'https://evil.example.com' && r.status === 200) {
      throw new Error('跨域表单请求被接受（CSRF 风险）');
    }
  });

  // 6. 安全响应头检查
  await t.test('响应应包含安全头（X-Content-Type-Options: nosniff）', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const xcto = r.headers['x-content-type-options'] || '';
    // 推荐存在，但部分 Spring Boot 默认不设置，记为 WARN
    if (!xcto) {
      t.warn_('X-Content-Type-Options 头缺失', '建议添加 X-Content-Type-Options: nosniff');
    } else if (xcto.toLowerCase() !== 'nosniff') {
      throw new Error(`X-Content-Type-Options 值异常: ${xcto}`);
    }
  });

  // 7. X-Frame-Options 检查
  await t.test('响应应包含 X-Frame-Options（防点击劫持）', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const xfo = r.headers['x-frame-options'] || '';
    if (!xfo) {
      t.warn_('X-Frame-Options 头缺失', '建议添加 X-Frame-Options: DENY 或 CSP frame-ancestors');
    }
  });

  // 8. Cache-Control 检查（敏感数据不应被缓存）
  await t.test('敏感 API 响应应包含 Cache-Control: no-store', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    const cc = r.headers['cache-control'] || '';
    if (!cc.toLowerCase().includes('no-store') && !cc.toLowerCase().includes('no-cache')) {
      t.warn_('Cache-Control 缺失', '建议敏感 API 设置 Cache-Control: no-store');
    }
  });

  return t.summary();
}

if (require.main === module) {
  main().catch((e) => { console.error('FATAL:', e); process.exit(1); });
}
module.exports = main;