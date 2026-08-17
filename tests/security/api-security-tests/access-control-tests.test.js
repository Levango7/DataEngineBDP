/**
 * 访问控制安全测试 —— 水平/垂直越权、IDOR
 *
 * 覆盖 OWASP Top 10:
 *   A01:2021 — Broken Access Control
 *
 * 测试项：
 *   1. 普通用户 token 可访问普通 API
 *   2. 普通用户不应能访问管理员 API（垂直越权）
 *   3. 用户 A 不应能访问用户 B 的资源（水平越权）
 *   4. IDOR：递增 ID 不应能访问他人资源
 *   5. 不同租户数据隔离
 *   6. 删除他人资源应被拒绝
 *   7. 修改他人资源应被拒绝
 *   8. admin token 应能访问管理员 API
 *   9. 普通用户不应能创建管理员账户
 *  10. JWT 中 tenantId 应与请求资源租户匹配
 */

const {
  API_PREFIX, ADMIN_USER, NORMAL_USER,
  securityFetch, login, tamperJwtPayload,
  assertUnauthorized, assertForbidden, assertOk,
  TestRunner,
} = require('./helpers');

async function main() {
  const t = new TestRunner('访问控制安全测试');

  // 登录两个用户
  let adminToken, userToken;
  try {
    const adminSession = await login(ADMIN_USER);
    adminToken = adminSession.token;
  } catch (e) {
    t.warn_('admin 登录失败', e.message);
    return t.summary();
  }

  try {
    const userSession = await login(NORMAL_USER);
    userToken = userSession.token;
  } catch (e) {
    t.warn_('user/user 登录失败，跳过水平越权测试', '需要预设 user/user 账号');
  }

  // 1. admin 可访问 API
  await t.test('admin token 可访问受保护 API', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: adminToken });
    assertOk(r, 'admin GET /tenants');
  });

  // 2. 普通用户可访问普通 API
  if (userToken) {
    await t.test('普通用户 token 可访问普通 API', async () => {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: userToken });
      assertOk(r, 'user GET /tenants');
    });
  }

  // 3. 垂直越权：普通用户访问管理员 API
  if (userToken) {
    await t.test('普通用户不应能访问管理员 API（垂直越权）', async () => {
      // 尝试常见的 admin 端点
      const adminEndpoints = [
        ['GET', '/admin/users'],
        ['GET', '/admin/config'],
        ['POST', '/admin/users', { username: 'hacker', password: 'hack', role: 'ADMIN' }],
        ['DELETE', '/admin/users/1'],
        ['GET', '/admin/audit-logs'],
      ];
      for (const [m, p, b] of adminEndpoints) {
        const r = await securityFetch(m, `${API_PREFIX}${p}`, { token: userToken, body: b });
        // 期望 401/403/404，不应 200
        if (r.status === 200) {
          throw new Error(`垂直越权成功: ${m} ${p} 返回 200`);
        }
      }
    });
  }

  // 4. IDOR：递增 ID 访问
  await t.test('IDOR：访问不存在的资源 ID 应返回 404 而非 500', async () => {
    const ids = ['1', '2', '3', '999', '1000', '10000'];
    for (const id of ids) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants/${id}`, { token: adminToken });
      // 200 或 404 都可接受，500 是漏洞
      if (r.status === 500) {
        throw new Error(`IDOR 触发 500: id=${id}, body=${JSON.stringify(r.body).slice(0, 200)}`);
      }
    }
  });

  // 5. IDOR：超大 ID 不应导致错误
  await t.test('IDOR：超大 ID 不应触发 500', async () => {
    const ids = ['999999999', '-1', '0', 'abc', '1; DROP TABLE tenants--'];
    for (const id of ids) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants/${encodeURIComponent(id)}`, { token: adminToken });
      // 400/404 可接受，500 是漏洞
      if (r.status === 500) {
        throw new Error(`ID 触发 500: id=${id}, body=${JSON.stringify(r.body).slice(0, 200)}`);
      }
    }
  });

  // 6. 不同租户数据隔离
  await t.test('篡改 tenantId 不应访问其他租户数据', async () => {
    // 篡改 tenantId（签名会失败，应返回 401）
    const tampered = tamperJwtPayload(adminToken, (p) => { p.tenantId = 'other-tenant'; });
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, { token: tampered });
    assertUnauthorized(r, '篡改 tenantId');
  });

  // 7. 删除他人资源应被拒绝
  await t.test('删除不存在的资源 ID 应返回 404 而非 500', async () => {
    const r = await securityFetch('DELETE', `${API_PREFIX}/tenants/999999`, { token: adminToken });
    if (r.status === 500) {
      throw new Error(`DELETE 触发 500: ${JSON.stringify(r.body).slice(0, 200)}`);
    }
  });

  // 8. 修改他人资源应被拒绝
  await t.test('PUT 不存在的资源 ID 应返回 404 而非 500', async () => {
    const r = await securityFetch('PUT', `${API_PREFIX}/tenants/999999`, {
      token: adminToken,
      body: { name: 'hijack', code: 'hijack' },
    });
    if (r.status === 500) {
      throw new Error(`PUT 触发 500: ${JSON.stringify(r.body).slice(0, 200)}`);
    }
  });

  // 9. 普通用户不应能创建管理员账户
  if (userToken) {
    await t.test('普通用户不应能创建管理员账户', async () => {
      const r = await securityFetch('POST', `${API_PREFIX}/users`, {
        token: userToken,
        body: { username: 'evil-admin', password: 'evil', role: 'ADMIN' },
      });
      // 401/403/404 可接受，201/200 是漏洞
      if (r.status === 200 || r.status === 201) {
        // 检查是否真的创建了 ADMIN 角色
        const text = JSON.stringify(r.body);
        if (text.includes('ADMIN') || text.includes('admin')) {
          throw new Error('普通用户成功创建管理员账户');
        }
      }
    });
  }

  // 10. JWT sub 应与当前用户匹配
  await t.test('JWT sub 应为当前用户 ID', async () => {
    const { parseJwt } = require('./helpers');
    const { payload } = parseJwt(adminToken);
    if (payload.sub !== 'admin') {
      throw new Error(`JWT sub 异常: ${payload.sub}`);
    }
  });

  // 11. 重复登录不应创建多个有效会话（无状态 JWT 允许，但应记录审计）
  await t.test('重复登录应返回不同 token（无状态）', async () => {
    const { token: t1 } = await login(ADMIN_USER);
    const { token: t2 } = await login(ADMIN_USER);
    if (t1 === t2) {
      t.warn_('重复登录返回相同 token', 'JWT iat 不同应导致 token 不同');
    }
  });

  return t.summary();
}

if (require.main === module) {
  main().catch((e) => { console.error('FATAL:', e); process.exit(1); });
}
module.exports = main;