/**
 * api-baseline.js — API 基线测试
 *
 * 对核心 API 做基线压测，每个 API 100 并发持续 30s，记录 P99 延迟，目标 < 200ms。
 *
 * 核心 API：
 *   - POST /api/v1/auth/login
 *   - GET  /api/v1/projects
 *   - GET  /api/v1/governance/assets
 *   - GET  /api/v1/standards
 *   - GET  /api/v1/search/history
 *
 * 用法：
 *   k6 run --env VUS=100 DURATION=30s api-baseline.js
 *   k6 run --env VUS=500 DURATION=30s api-baseline.js
 *   k6 run --env VUS=1000 DURATION=30s api-baseline.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { baseUrl, apiPrefix, username, password, login, authHeaders, bizSuccessRate, bizErrorCount } from './common.js';

const vus = parseInt(__ENV.VUS || '100', 10);
const duration = __ENV.DURATION || '30s';

// 每个核心 API 的延迟趋势指标
const projectsLatency = new Trend('latency_projects', true);
const assetsLatency = new Trend('latency_governance_assets', true);
const standardsLatency = new Trend('latency_standards', true);
const searchHistoryLatency = new Trend('latency_search_history', true);
const loginLatencyT = new Trend('latency_auth_login', true);

// 每个 API 的成功率
const projectsOk = new Rate('ok_projects');
const assetsOk = new Rate('ok_governance_assets');
const standardsOk = new Rate('ok_standards');
const searchHistoryOk = new Rate('ok_search_history');
const loginOk = new Rate('ok_auth_login');

export const options = {
  scenarios: {
    api_baseline: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: vus },
        { duration: duration, target: vus },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // P99 目标 < 200ms（基线目标，可能不达标，仅作记录）
    latency_auth_login: ['p(99)<200'],
    latency_projects: ['p(99)<200'],
    latency_governance_assets: ['p(99)<200'],
    latency_standards: ['p(99)<200'],
    latency_search_history: ['p(99)<200'],
    // 业务成功率 > 99%
    biz_success_rate: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  // 预先登录拿 token，供所有 VU 共享（仅用于 GET 类接口）
  const token = login();
  console.log(`api-baseline starting: VUs=${vus}, duration=${duration}, tokenReady=${!!token}`);
  return { token };
}

function record(res, trend, okRate, apiTag) {
  trend.add(res.timings.duration);
  const ok = check(res, {
    [`${apiTag} status 2xx`]: (r) => r.status >= 200 && r.status < 300,
  });
  okRate.add(ok);
  bizSuccessRate.add(ok);
  if (!ok) bizErrorCount.add(1);
}

export default function (data) {
  const token = data.token;
  const headers = authHeaders(token).headers;

  // 1) 登录（每个 VU 也独立打一次登录，便于统计登录基线）
  const loginRes = http.post(
    `${baseUrl}${apiPrefix}/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { api: 'auth_login' } }
  );
  record(loginRes, loginLatencyT, loginOk, 'auth_login');

  // 2) 项目列表
  const projectsRes = http.get(`${baseUrl}${apiPrefix}/projects`, {
    headers, tags: { api: 'projects' },
  });
  record(projectsRes, projectsLatency, projectsOk, 'projects');

  // 3) 治理资产
  const assetsRes = http.get(`${baseUrl}${apiPrefix}/governance/assets`, {
    headers, tags: { api: 'governance_assets' },
  });
  record(assetsRes, assetsLatency, assetsOk, 'governance_assets');

  // 4) 标准列表
  const standardsRes = http.get(`${baseUrl}${apiPrefix}/standards`, {
    headers, tags: { api: 'standards' },
  });
  record(standardsRes, standardsLatency, standardsOk, 'standards');

  // 5) 搜索历史
  const searchRes = http.get(`${baseUrl}${apiPrefix}/search/history`, {
    headers, tags: { api: 'search_history' },
  });
  record(searchRes, searchHistoryLatency, searchHistoryOk, 'search_history');

  // 思考时间 100ms，避免空转打满 CPU
  sleep(0.1);
}