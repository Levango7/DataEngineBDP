/**
 * 安全测试公共工具 —— HTTP 客户端 + 断言 + JWT 操作
 *
 * 提供统一的 HTTP 请求封装、JWT 解析/篡改、安全断言辅助。
 * 所有测试用例基于 Node.js 内置 fetch（Node 18+），无外部依赖。
 *
 * 用法：
 *   const { securityFetch, assertUnauthorized, tamperJwt } = require('./helpers');
 */

const crypto = require('crypto');
const assert = require('assert');

const BASE_URL = process.env.BASE_URL || 'http://localhost:18086';
const API_PREFIX = '/api/v1';
const ADMIN_USER = { username: 'admin', password: 'admin' };
const NORMAL_USER = { username: 'user', password: 'user' };

/**
 * 发起 HTTP 请求并返回结构化结果。
 *
 * @param {string} method - HTTP 方法
 * @param {string} path - 请求路径（相对 BASE_URL）
 * @param {object} [opts] - { body, headers, token, query, timeout }
 * @returns {Promise<{status:number, body:any, headers:object, durationMs:number}>}
 */
async function securityFetch(method, path, opts = {}) {
  const url = new URL(BASE_URL + path);
  if (opts.query) {
    for (const [k, v] of Object.entries(opts.query)) {
      url.searchParams.append(k, typeof v === 'string' ? v : String(v));
    }
  }
  const headers = { ...(opts.headers || {}) };
  if (opts.token) headers['Authorization'] = `Bearer ${opts.token}`;
  if (opts.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';

  const start = Date.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), opts.timeout || 10000);

  try {
    const resp = await fetch(url.toString(), {
      method,
      headers,
      body: opts.body ? (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)) : undefined,
      signal: controller.signal,
    });
    const text = await resp.text();
    let body;
    try { body = JSON.parse(text); } catch { body = text; }
    return {
      status: resp.status,
      body,
      headers: Object.fromEntries(resp.headers.entries()),
      durationMs: Date.now() - start,
    };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * 登录获取 token。
 *
 * @param {{username:string,password:string}} [creds]
 * @returns {Promise<{token:string, user:object, expiresIn:number}>}
 */
async function login(creds = ADMIN_USER) {
  const r = await securityFetch('POST', `${API_PREFIX}/auth/login`, { body: creds });
  assert.strictEqual(r.status, 200, `登录失败: ${JSON.stringify(r.body)}`);
  // 兼容 {data:{token}} 与 {token}
  const data = r.body.data || r.body;
  return {
    token: data.token,
    user: data.user,
    expiresIn: data.expiresIn,
  };
}

/**
 * 解析 JWT 三段（不验签），用于篡改测试。
 */
function parseJwt(token) {
  const parts = token.split('.');
  assert.strictEqual(parts.length, 3, 'JWT 必须有 3 段');
  const decode = (s) => JSON.parse(Buffer.from(s, 'base64url').toString('utf8'));
  return { header: decode(parts[0]), payload: decode(parts[1]), signature: parts[2], raw: parts };
}

/**
 * 重新组装 JWT（不重新签名，用于篡改签名测试）。
 */
function reassembleJwt(header, payload, signature) {
  const h = Buffer.from(JSON.stringify(header)).toString('base64url');
  const p = Buffer.from(JSON.stringify(payload)).toString('base64url');
  return `${h}.${p}.${signature}`;
}

/**
 * 篡改 JWT payload 的某个字段，签名保持原样（必然验签失败）。
 */
function tamperJwtPayload(token, mutator) {
  const { header, payload, signature } = parseJwt(token);
  mutator(payload);
  return reassembleJwt(header, payload, signature);
}

/**
 * 生成一个完全无效签名的 JWT（payload 合法但签名随机）。
 */
function jwtWithRandomSignature(token) {
  const { header, payload } = parseJwt(token);
  const fakeSig = crypto.randomBytes(48).toString('base64url');
  return reassembleJwt(header, payload, fakeSig);
}

/**
 * 生成一个过期 JWT（exp 设为过去时间）。
 */
function expiredJwt(token) {
  return tamperJwtPayload(token, (p) => {
    p.exp = Math.floor(Date.now() / 1000) - 3600; // 1 小时前
  });
}

/**
 * 生成一个未来签发时间 JWT（iat 在未来，部分实现会拒绝）。
 */
function futureIatJwt(token) {
  return tamperJwtPayload(token, (p) => {
    p.iat = Math.floor(Date.now() / 1000) + 86400;
  });
}

// ===== 断言辅助 =====

function assertUnauthorized(r, label) {
  assert.strictEqual(r.status, 401, `[${label}] 期望 401，实际 ${r.status}: ${JSON.stringify(r.body).slice(0, 200)}`);
}

function assertForbidden(r, label) {
  assert.strictEqual(r.status, 403, `[${label}] 期望 403，实际 ${r.status}`);
}

function assertOk(r, label) {
  assert.ok(r.status >= 200 && r.status < 300, `[${label}] 期望 2xx，实际 ${r.status}: ${JSON.stringify(r.body).slice(0, 200)}`);
}

function assertNotFound(r, label) {
  assert.strictEqual(r.status, 404, `[${label}] 期望 404，实际 ${r.status}`);
}

function assertNoSqlError(body, label) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  const sqlErrPatterns = [
    /SQL\s*syntax/i, /ORA-\d+/, /MySQL/i, /PostgreSQL/i, /sqlite3?\.\w+/i,
    /Unclosed quotation mark/, /PG::\w+Error/i, /mysql2::\w+Error/i,
    /SQLSTATE/i, /syntax error at or near/i, /You have an error in your SQL syntax/i,
  ];
  for (const re of sqlErrPatterns) {
    assert.ok(!re.test(text), `[${label}] 响应中泄露 SQL 错误: ${text.slice(0, 200)}`);
  }
}

function assertNoStackTrace(body, label) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  const stackPatterns = [
    /at\s+[\w.$]+\s+\([^)]+:\d+:\d+\)/, /java\.lang\.\w+Exception/, /org\.springframework\.\w+/,
    /com\.\w+\.\w+\.\w+/, /Caused by:/, /nested exception is/,
  ];
  for (const re of stackPatterns) {
    assert.ok(!re.test(text), `[${label}] 响应中泄露堆栈: ${text.slice(0, 200)}`);
  }
}

/**
 * 测试结果收集器，最后输出汇总。
 */
class TestRunner {
  constructor(name) {
    this.name = name;
    this.results = [];
    this.pass = 0;
    this.fail = 0;
    this.warn = 0;
  }

  async test(label, fn) {
    try {
      await fn();
      this.pass++;
      this.results.push({ label, status: 'PASS' });
      console.log(`  ✅ ${label}`);
    } catch (e) {
      this.fail++;
      this.results.push({ label, status: 'FAIL', error: e.message });
      console.log(`  ❌ ${label}\n     ${e.message}`);
    }
  }

  warn_(label, reason) {
    this.warn++;
    this.results.push({ label, status: 'WARN', reason });
    console.log(`  ⚠️  ${label} — ${reason}`);
  }

  summary() {
    const total = this.pass + this.fail + this.warn;
    console.log(`\n── ${this.name} 汇总 ──`);
    console.log(`  PASS: ${this.pass}/${total}  FAIL: ${this.fail}  WARN: ${this.warn}`);
    return { name: this.name, pass: this.pass, fail: this.fail, warn: this.warn, total, results: this.results };
  }
}

module.exports = {
  BASE_URL, API_PREFIX, ADMIN_USER, NORMAL_USER,
  securityFetch, login,
  parseJwt, reassembleJwt, tamperJwtPayload, jwtWithRandomSignature, expiredJwt, futureIatJwt,
  assertUnauthorized, assertForbidden, assertOk, assertNotFound,
  assertNoSqlError, assertNoStackTrace,
  TestRunner,
};