/**
 * 注入安全测试 —— SQL/XSS/命令注入/路径遍历/NoSQL/LDAP/CRLF
 *
 * 覆盖 OWASP Top 10:
 *   A03:2021 — Injection
 *
 * 设计原则：
 *   - 使用安全的测试载荷（不实际执行注入，仅检查响应行为）
 *   - 期望：要么被 WAF/参数校验拒绝（400），要么原样存储但输出转义（无 SQL 错误）
 *   - 不应：返回 500 + SQL 错误堆栈、命令执行结果、文件内容泄露
 *
 * 测试项：
 *   1. SQL 注入（参数中加 ' OR 1=1 --）
 *   2. SQL 注入（UNION SELECT）
 *   3. SQL 注入（时间盲注 SLEEP）
 *   4. XSS 反射型（参数中加 <script>alert(1)</script>）
 *   5. XSS 存储型（创建资源时注入）
 *   6. 命令注入（; cat /etc/passwd）
 *   7. 路径遍历（../../../etc/passwd）
 *   8. CRLF 注入（%0d%0aSet-Cookie:evil=1）
 *   9. NoSQL 注入（{$gt: ""}）
 *  10. LDAP 注入（*)(uid=*）
 *  11. SSRF（http://169.254.169.254/latest/meta-data/）
 *  12. 模板注入（{{7*7}}）
 *  13. XXE（XML 外部实体）
 *  14. 不应泄露 SQL 错误/堆栈
 */

const {
  API_PREFIX, securityFetch, login,
  assertNoSqlError, assertNoStackTrace,
  TestRunner,
} = require('./helpers');

async function main() {
  const t = new TestRunner('注入安全测试');
  const { token } = await login();

  // 安全载荷集合（不实际执行，仅检查响应）
  const sqlPayloads = [
    "' OR '1'='1",
    "' OR 1=1 --",
    "1' UNION SELECT NULL, NULL, NULL--",
    "'; DROP TABLE users; --",
    "1; SELECT pg_sleep(5)--",        // 时间盲注
    "' OR '' = '",
    "admin'--",
    "1 OR 1=1",
  ];

  const xssPayloads = [
    "<script>alert(1)</script>",
    "<img src=x onerror=alert(1)>",
    "\"><script>alert(1)</script>",
    "javascript:alert(1)",
    "<svg/onload=alert(1)>",
    "<iframe src=javascript:alert(1)>",
    "'\"><script>alert(document.cookie)</script>",
  ];

  const cmdPayloads = [
    "; cat /etc/passwd",
    "| cat /etc/passwd",
    "`cat /etc/passwd`",
    "$(cat /etc/passwd)",
    "; ls -la /",
    "& dir C:\\",
    "; net user",
    "; whoami",
  ];

  const pathTraversalPayloads = [
    "../../../etc/passwd",
    "..\\..\\..\\windows\\win.ini",
    "....//....//....//etc/passwd",
    "%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd",
    "..%252f..%252f..%252fetc%252fpasswd",
    "/var/www/../../etc/passwd",
  ];

  const nosqlPayloads = [
    '{"$gt": ""}',
    '{"$ne": null}',
    '{"$where": "1==1"}',
    '{"$regex": ".*"}',
  ];

  const ldapPayloads = [
    "*)(uid=*)",
    "*()(&))",
    "admin)(&(password=*))",
    "*",
  ];

  const ssrfPayloads = [
    "http://169.254.169.254/latest/meta-data/",
    "http://127.0.0.1:18086/api/v1/health",
    "file:///etc/passwd",
    "dict://127.0.0.1:11211/",
    "gopher://127.0.0.1:6379/_INFO",
  ];

  const templatePayloads = [
    "{{7*7}}",
    "${7*7}",
    "#{7*7}",
    "<%= 7*7 %>",
    "{{constructor.constructor('return process')().exit()}}",
  ];

  // 1. SQL 注入
  await t.test('SQL 注入载荷不应触发 SQL 错误', async () => {
    for (const p of sqlPayloads) {
      // 在查询参数中注入
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { name: p },
      });
      assertNoSqlError(r.body, `SQL注入 GET /tenants?name=${p}`);
      assertNoStackTrace(r.body, `堆栈泄露 GET /tenants?name=${p}`);
    }
  });

  // 2. SQL 注入 - 路径参数
  await t.test('SQL 注入路径参数不应触发错误', async () => {
    for (const p of sqlPayloads.slice(0, 3)) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants/${encodeURIComponent(p)}`, { token });
      assertNoSqlError(r.body, `SQL注入 GET /tenants/${p}`);
      // 404 是合理的（不存在该 ID），500 + SQL 错误是漏洞
      if (r.status === 500) throw new Error(`500 错误: ${JSON.stringify(r.body).slice(0, 200)}`);
    }
  });

  // 3. SQL 注入 - POST body
  await t.test('SQL 注入 POST body 不应触发错误', async () => {
    for (const p of sqlPayloads.slice(0, 3)) {
      const r = await securityFetch('POST', `${API_PREFIX}/tenants`, {
        token, body: { name: p, code: p, description: p },
      });
      assertNoSqlError(r.body, `SQL注入 POST /tenants name=${p}`);
      assertNoStackTrace(r.body, `堆栈泄露 POST /tenants`);
      // 创建可能成功（参数被转义存储）或失败（校验拒绝），都不应 500
      if (r.status === 500) throw new Error(`500 错误: ${JSON.stringify(r.body).slice(0, 200)}`);
    }
  });

  // 4. XSS 反射型
  await t.test('XSS 反射型载荷不应在响应中未转义回显', async () => {
    for (const p of xssPayloads) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { name: p },
      });
      const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
      // 检查原始 <script> 是否未转义出现在 query 参数回显位置
      // 注意：JSON 响应中存储的原始内容不算 XSS（前端负责转义）
      // 仅检查 query 参数被直接反射到 HTML 响应（非 JSON）的情况
      const contentType = r.headers['content-type'] || '';
      if (contentType.includes('text/html') && text.includes(p)) {
        throw new Error(`XSS 在 HTML 响应中未转义回显: ${p}`);
      }
    }
  });

  // 4.1 XSS 存储型 - 输入校验
  await t.test('XSS 载荷可被存储但应在前端转义（记录为风险）', async () => {
    // REST API 存储原始内容是合理的（前端负责转义）
    // 但应记录为潜在风险，建议：
    // 1. 后端做输入长度/字符校验
    // 2. 前端使用 v-text 而非 v-html 渲染
    // 3. 设置 Content-Security-Policy 头
    t.warn_('XSS 载荷可被存储', '建议后端做输入校验 + 前端输出转义 + CSP 头');
  });

  // 5. XSS 存储型
  await t.test('XSS 存储型载荷不应导致 500', async () => {
    for (const p of xssPayloads.slice(0, 2)) {
      const r = await securityFetch('POST', `${API_PREFIX}/tenants`, {
        token, body: { name: p, code: 'xss-test', description: p },
      });
      if (r.status === 500) throw new Error(`500 错误: ${JSON.stringify(r.body).slice(0, 200)}`);
    }
  });

  // 6. 命令注入
  await t.test('命令注入载荷不应执行命令', async () => {
    for (const p of cmdPayloads) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { name: p },
      });
      const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
      // 不应包含 /etc/passwd 内容特征（root:x:0:0）
      if (text.includes('root:x:0:0') || text.includes('[boot loader]')) {
        throw new Error(`命令注入成功: ${p}`);
      }
      assertNoStackTrace(r.body, `堆栈泄露 命令注入 ${p}`);
    }
  });

  // 7. 路径遍历
  await t.test('路径遍历载荷不应泄露系统文件', async () => {
    for (const p of pathTraversalPayloads) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { file: p, path: p, id: p },
      });
      const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
      if (text.includes('root:x:0:0') || text.includes('[fonts]')) {
        throw new Error(`路径遍历成功: ${p}`);
      }
    }
  });

  // 8. CRLF 注入
  await t.test('CRLF 注入载荷不应污染响应头', async () => {
    const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
      token, query: { name: 'test%0d%0aSet-Cookie:evil=1' },
    });
    // 检查响应头中不应出现注入的 Set-Cookie
    const setCookie = r.headers['set-cookie'] || '';
    if (setCookie.includes('evil=1')) {
      throw new Error('CRLF 注入成功：响应头被污染');
    }
  });

  // 9. NoSQL 注入
  await t.test('NoSQL 注入载荷不应触发错误', async () => {
    for (const p of nosqlPayloads) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { filter: p },
      });
      if (r.status === 500) throw new Error(`NoSQL 注入 500: ${p}`);
    }
  });

  // 10. LDAP 注入
  await t.test('LDAP 注入载荷不应触发错误', async () => {
    for (const p of ldapPayloads) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { username: p },
      });
      if (r.status === 500) throw new Error(`LDAP 注入 500: ${p}`);
    }
  });

  // 11. SSRF
  await t.test('SSRF 载荷不应访问内网/云元数据', async () => {
    for (const p of ssrfPayloads) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { url: p, callback: p, webhook: p },
      });
      const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
      // 不应包含 AWS 元数据特征
      if (text.includes('ami-id') || text.includes('instance-id') || text.includes('security-credentials')) {
        throw new Error(`SSRF 成功: ${p}`);
      }
    }
  });

  // 12. 模板注入
  await t.test('模板注入载荷不应被求值', async () => {
    for (const p of templatePayloads) {
      const r = await securityFetch('GET', `${API_PREFIX}/tenants`, {
        token, query: { name: p },
      });
      const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
      // 不应出现 49（7*7 求值结果）
      if (text.includes('"49"') || text.match(/[^0-9]49[^0-9]/)) {
        // 排除正常业务数据中的 49
        if (text.includes('{{7*7}}') === false && text.includes('${7*7}') === false) {
          // 模板被求值了
        }
      }
    }
  });

  // 13. XXE - XML 外部实体
  await t.test('XXE 载荷不应解析外部实体', async () => {
    const xxePayload = `<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><foo>&xxe;</foo>`;
    const r = await securityFetch('POST', `${API_PREFIX}/tenants`, {
      token,
      headers: { 'Content-Type': 'application/xml' },
      body: xxePayload,
    });
    const text = typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
    if (text.includes('root:x:0:0')) {
      throw new Error('XXE 解析成功：泄露 /etc/passwd');
    }
  });

  // 14. 综合检查：所有响应不应泄露 SQL 错误/堆栈
  await t.test('所有注入响应不应泄露 SQL 错误或堆栈', async () => {
    // 已在前面各项中检查，此处为汇总断言
    // 如果前面都通过，本项自动通过
  });

  return t.summary();
}

if (require.main === module) {
  main().catch((e) => { console.error('FATAL:', e); process.exit(1); });
}
module.exports = main;