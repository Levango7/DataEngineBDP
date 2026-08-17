/**
 * 场景验证公共 API 客户端。
 *
 * 封装对封装层后端（http://127.0.0.1:18086/api/v1/）的调用，
 * 提供登录、自动注入 JWT、统一错误处理、断言辅助等能力。
 *
 * 设计原则：
 * - 仅依赖 Node.js 内置 http 模块，零外部依赖。
 * - 所有方法返回 { ok, status, data, raw }，由调用方决定断言。
 * - 支持租户切换（X-Tenant-Id header）以验证多租户隔离。
 */

const http = require('http');

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = 18086;
const API_PREFIX = '/api/v1';

/**
 * 发起 HTTP 请求并返回结构化结果。
 * @param {object} opts - { method, path, body, headers, host, port, timeoutMs }
 * @returns {Promise<{ok:boolean,status:number,data:any,raw:string}>}
 */
function request(opts) {
  return new Promise((resolve) => {
    const method = (opts.method || 'GET').toUpperCase();
    const host = opts.host || DEFAULT_HOST;
    const port = opts.port || DEFAULT_PORT;
    const path = opts.path || '/';
    const headers = Object.assign({
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    }, opts.headers || {});
    const body = opts.body == null ? null : (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body));
    if (body != null && !headers['Content-Length']) {
      headers['Content-Length'] = Buffer.byteLength(body);
    }
    const timeoutMs = opts.timeoutMs || 10000;

    const req = http.request({ host, port, path, method, headers }, (res) => {
      let chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let data = null;
        try { data = JSON.parse(raw); } catch (_) { data = raw; }
        resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode, data, raw });
      });
    });
    req.on('error', (err) => {
      resolve({ ok: false, status: 0, data: null, raw: String(err && err.message || err) });
    });
    req.setTimeout(timeoutMs, () => {
      req.destroy(new Error('timeout'));
    });
    if (body != null) req.write(body);
    req.end();
  });
}

/**
 * 封装层 API 客户端。
 */
class ApiClient {
  constructor(opts = {}) {
    this.host = opts.host || DEFAULT_HOST;
    this.port = opts.port || DEFAULT_PORT;
    this.token = opts.token || null;
    this.tenantId = opts.tenantId || null;
  }

  /**
   * 登录并保存 token。
   * @param {string} username
   * @param {string} password
   */
  async login(username = 'admin', password = 'admin') {
    const res = await request({
      host: this.host, port: this.port,
      method: 'POST',
      path: `${API_PREFIX}/auth/login`,
      body: { username, password },
    });
    if (res.ok && res.data && res.data.data && res.data.data.token) {
      this.token = res.data.data.token;
    }
    return res;
  }

  /**
   * 构造带认证的 headers。
   */
  authHeaders(extra = {}) {
    const h = Object.assign({}, extra);
    if (this.token) h['Authorization'] = `Bearer ${this.token}`;
    if (this.tenantId) h['X-Tenant-Id'] = this.tenantId;
    return h;
  }

  /**
   * 通用 GET。
   */
  async get(path, opts = {}) {
    return request({
      host: this.host, port: this.port,
      method: 'GET',
      path: path.startsWith(API_PREFIX) ? path : `${API_PREFIX}${path}`,
      headers: this.authHeaders(opts.headers),
      timeoutMs: opts.timeoutMs,
    });
  }

  /**
   * 通用 POST。
   */
  async post(path, body, opts = {}) {
    return request({
      host: this.host, port: this.port,
      method: 'POST',
      path: path.startsWith(API_PREFIX) ? path : `${API_PREFIX}${path}`,
      headers: this.authHeaders(opts.headers),
      body,
      timeoutMs: opts.timeoutMs,
    });
  }

  /**
   * 通用 PUT。
   */
  async put(path, body, opts = {}) {
    return request({
      host: this.host, port: this.port,
      method: 'PUT',
      path: path.startsWith(API_PREFIX) ? path : `${API_PREFIX}${path}`,
      headers: this.authHeaders(opts.headers),
      body,
      timeoutMs: opts.timeoutMs,
    });
  }

  /**
   * 通用 DELETE。
   */
  async delete(path, opts = {}) {
    return request({
      host: this.host, port: this.port,
      method: 'DELETE',
      path: path.startsWith(API_PREFIX) ? path : `${API_PREFIX}${path}`,
      headers: this.authHeaders(opts.headers),
      timeoutMs: opts.timeoutMs,
    });
  }

  /**
   * 健康检查。
   */
  async health() {
    return this.get('/health');
  }
}

/**
 * 简单断言工具，记录 pass/fail。
 */
class Assert {
  constructor() {
    this.passed = 0;
    this.failed = 0;
    this.skipped = 0;
    this.records = [];
  }

  ok(name, condition, detail = '') {
    if (condition) {
      this.passed++;
      this.records.push({ name, status: 'PASS', detail });
    } else {
      this.failed++;
      this.records.push({ name, status: 'FAIL', detail });
    }
    return condition;
  }

  skip(name, reason = '') {
    this.skipped++;
    this.records.push({ name, status: 'SKIP', detail: reason });
  }

  equal(name, actual, expected, detail = '') {
    const cond = actual === expected;
    this.ok(name, cond, detail || `actual=${JSON.stringify(actual)} expected=${JSON.stringify(expected)}`);
    return cond;
  }

  notNull(name, value, detail = '') {
    const cond = value != null;
    this.ok(name, cond, detail || `value=${JSON.stringify(value)}`);
    return cond;
  }

  contains(name, haystack, needle, detail = '') {
    const cond = haystack != null && String(haystack).indexOf(needle) >= 0;
    this.ok(name, cond, detail || `expected to contain "${needle}"`);
    return cond;
  }

  summary() {
    return { passed: this.passed, failed: this.failed, skipped: this.skipped, total: this.passed + this.failed + this.skipped };
  }
}

/**
 * 简单日志器。
 */
class Logger {
  constructor(prefix = '') {
    this.prefix = prefix;
  }
  _fmt(level, msg) {
    const ts = new Date().toISOString();
    return `[${ts}] [${level}] ${this.prefix ? this.prefix + ' ' : ''}${msg}`;
  }
  info(msg) { console.log(this._fmt('INFO', msg)); }
  warn(msg) { console.warn(this._fmt('WARN', msg)); }
  error(msg) { console.error(this._fmt('ERROR', msg)); }
  step(msg) { console.log(this._fmt('STEP', msg)); }
}

/**
 * 等待 ms 毫秒。
 */
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

module.exports = { ApiClient, Assert, Logger, request, sleep, API_PREFIX, DEFAULT_HOST, DEFAULT_PORT };