/**
 * k6 共享配置与工具函数
 * 提供 baseURL、登录、通用请求头、自定义指标等
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 从环境变量读取配置，默认指向本地 18086
const baseUrl = __ENV.BASE_URL || 'http://localhost:18086';
const apiPrefix = __ENV.API_PREFIX || '/api/v1';
const username = __ENV.USERNAME || 'admin';
const password = __ENV.PASSWORD || 'admin';

// 自定义指标（snake_case 命名）
export const bizSuccessRate = new Rate('biz_success_rate');       // 业务成功率
export const bizErrorCount = new Counter('biz_error_count');      // 业务错误计数
export const loginLatency = new Trend('login_latency', true);     // 登录延迟(ms)
export const apiLatency = new Trend('api_latency', true);         // 通用API延迟(ms)
export const txnDuration = new Trend('txn_duration', true);       // 事务总耗时(ms)

/**
 * 登录并返回 token
 * @returns {string|null} token
 */
export function login() {
  const url = `${baseUrl}${apiPrefix}/auth/login`;
  const payload = JSON.stringify({ username, password });
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { api: 'auth_login' },
  };
  const res = http.post(url, payload, params);
  loginLatency.add(res.timings.duration);

  const ok = check(res, {
    'login status 200': (r) => r.status === 200,
    'login has token': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body && (body.success === true || body.code === 0) && body.data && body.data.token;
      } catch (e) {
        return false;
      }
    },
  });

  bizSuccessRate.add(ok);
  if (!ok) bizErrorCount.add(1);

  if (ok) {
    try {
      return JSON.parse(res.body).data.token;
    } catch (e) {
      return null;
    }
  }
  return null;
}

/**
 * 构造带 Authorization 的请求头
 * @param {string} token
 * @returns {object}
 */
export function authHeaders(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: token ? `Bearer ${token}` : '',
    },
  };
}

/**
 * 发起 GET 请求并记录指标
 * @param {string} path 相对 /api/v1 的路径
 * @param {string} token
 * @param {string} apiTag 用于分组的 api 标签
 * @returns {object} k6 response
 */
export function getApi(path, token, apiTag) {
  const url = `${baseUrl}${apiPrefix}${path}`;
  const params = authHeaders(token);
  params.tags = { api: apiTag || path };
  const res = http.get(url, params);
  apiLatency.add(res.timings.duration);
  const ok = check(res, {
    [`${apiTag} status 2xx`]: (r) => r.status >= 200 && r.status < 300,
  });
  bizSuccessRate.add(ok);
  if (!ok) bizErrorCount.add(1);
  return res;
}

/**
 * 思考时间（think time），默认 1-3 秒
 * @param {number} minMs
 * @param {number} maxMs
 */
export function thinkTime(minMs = 1000, maxMs = 3000) {
  const ms = minMs + Math.floor(Math.random() * (maxMs - minMs));
  sleep(ms / 1000);
}

export { baseUrl, apiPrefix, username, password };