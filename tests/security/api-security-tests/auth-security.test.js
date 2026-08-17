/**
 * 认证安全测试 —— JWT 验证、未认证访问、Token 篡改/过期
 *
 * 覆盖 OWASP Top 10:
 *   A07:2021 — Identification and Authentication Failures
 *   A01:2021 — Broken Access Control（认证缺失场景）
 *
 * 测试项：
 *   1. 未认证访问受保护 API 应返回 401
 *   2. 错误格式 Authorization 头应返回 401
 *   3. 错误密码登录应返回 401
 *   4. 篡改 JWT payload 应返回 401（签名不匹配）
 *   5. 随机签名 JWT 应返回 401
 *   6. 过期 JWT 应返回 401
 *   7. 篡改 issuer 应返回 401
 *   8. 篡改 tenantId 应被识别（垂直越权场景）
 *   9. 空 Authorization 头应返回 401
 *  10. 非 Bearer 前缀应返回 401
 *  11. 公开端点（health）应可匿名访问
 *  12. 公开端点（actuator/health）应可匿名访问
 *  13. 登录成功应返回 token 且可访问受保护 API
 *  14. Token 不应包含敏感信息（密码/密钥）
 */

const {
  API_PREFIX, securityFetch, login,
  parseJwt, tamperJwtPayload, jwtWithRandomSignature, expiredJwt,
  assertUnauthorized, assertOk,
  TestRunner,
} = require('./helpers');

async function main() {
  const t = new TestRunner('认证安全测试');

  // 先登录获取合法 token
  const { token } = await login();
  console.log(`  ℹ️  已获取合法 token: ${token.slice(0, 30)}...`);

  // 受保护端点列表
  const protectedEndpoints = [
    ['GET',  '/tenants'],
    ['GET',  '/tenants/1'],
    ['GET',  '/tenants/all'],
    ['GET',  '/datasources'],
    ['GET',  '/projects'],
    ['GET',  '/users'],
    ['GET',  '/accounts'],
    ['POST', '/tenants', { name: 'sec-test', code: 'sec-test' }],
  ];

  // 1. 未认证访问受保护 API
  await t.test('未认证访问受保护 API 应返回 401', async () => {
    for (const [m, p, b] of protectedEndpoints) {
      const r = await securityFetch(m, `${API_PREFIX}${p}`, { body: b });
      assertUnauthorized(r, `匿名 ${m} ${p}`);
    }
  });

  // 2. 错误格式 Authorization 头
  await t.test('错误格式 Authorization 头应返回 401', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
      headers: { Authorization: 'Basic admin:admin' },
    });
    assertUnauthorized(r, 'Basic 认证头');
  });

  // 3. 错误密码登录
  await t.test('错误密码登录应返回 401', async () => {
    const r = await securityFetch('POST', `${API_PREFIX}/auth/login`, {
      body: { username: 'admin', password: 'wrongpass' },
    });
    assertUnauthorized(r, '错误密码');
  });

  // 4. 篡改 JWT payload
  await t.test('篡改 JWT payload 应返回 401', async () => {
    const tampered = tamperJwtPayload(token, (p) => { p.sub = 'root'; });
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: tampered });
    assertUnauthorized(r, '篡改 sub=root');
  });

  // 5. 随机签名 JWT
  await t.test('随机签名 JWT 应返回 401', async () => {
    const fake = jwtWithRandomSignature(token);
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: fake });
    assertUnauthorized(r, '随机签名');
  });

  // 6. 过期 JWT
  await t.test('过期 JWT 应返回 401', async () => {
    const expired = expiredJwt(token);
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: expired });
    assertUnauthorized(r, '过期 token');
  });

  // 7. 篡改 issuer
  await t.test('篡改 issuer 应返回 401', async () => {
    const tampered = tamperJwtPayload(token, (p) => { p.iss = 'evil-issuer'; });
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: tampered });
    assertUnauthorized(r, '篡改 iss');
  });

  // 8. 篡改 tenantId
  await t.test('篡改 tenantId 应返回 401（签名不匹配）', async () => {
    const tampered = tamperJwtPayload(token, (p) => { p.tenantId = 'other-tenant'; });
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: tampered });
    assertUnauthorized(r, '篡改 tenantId');
  });

  // 9. 空 Authorization 头
  await t.test('空 Authorization 头应返回 401', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
      headers: { Authorization: '' },
    });
    assertUnauthorized(r, '空 Authorization');
  });

  // 10. 非 Bearer 前缀
  await t.test('非 Bearer 前缀应返回 401', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
      headers: { Authorization: `Token ${token}` },
    });
    assertUnauthorized(r, 'Token 前缀');
  });

  // 11. 公开端点 health
  await t.test('公开端点 /api/v1/health 应可匿名访问', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/health`);
    assertOk(r, 'health 匿名');
  });

  // 12. 公开端点 actuator/health
  await t.test('公开端点 /actuator/health 应可匿名访问', async () => {
    const r = await securityFetch('GET', '/actuator/health');
    assertOk(r, 'actuator/health 匿名');
  });

  // 13. 合法 token 可访问受保护 API
  await t.test('合法 token 可访问受保护 API', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token });
    assertOk(r, '合法 token GET /tenants');
  });

  // 14. Token 不应包含敏感信息
  await t.test('JWT payload 不应包含敏感信息（password/secret）', async () => {
    const { payload } = parseJwt(token);
    const sensitiveKeys = ['password', 'secret', 'passwd', 'credential', 'apiKey'];
    for (const k of sensitiveKeys) {
      const found = JSON.stringify(payload).toLowerCase().includes(k);
      if (found) throw new Error(`JWT payload 包含敏感字段: ${k}`);
    }
  });

  return t.summary();
}

if (require.main === module) {
  main().catch((e) => { console.error('FATAL:', e); process.exit(1); });
}
module.exports = main;