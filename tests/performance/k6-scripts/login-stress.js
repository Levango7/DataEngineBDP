/**
 * login-stress.js — 登录接口压测
 *
 * 目标：
 *  - 并发 100/500/1000 虚拟用户
 *  - 持续 30s/60s/120s
 *  - 测量 P50/P95/P99 延迟
 *  - 验证成功率 > 99%
 *
 * 用法：
 *   k6 run --env VUS=100  DURATION=30s login-stress.js
 *   k6 run --env VUS=500  DURATION=60s login-stress.js
 *   k6 run --env VUS=1000 DURATION=120s login-stress.js
 *
 * 输出阈值失败时退出码非 0，便于 CI 卡点。
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { baseUrl, apiPrefix, username, password, loginLatency, bizSuccessRate, bizErrorCount } from './common.js';

// 从环境变量读取并发与持续时间，默认 100 * 30s
const vus = parseInt(__ENV.VUS || '100', 10);
const duration = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    login_stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: Math.floor(vus / 2) }, // 10s 内拉到一半
        { duration: '10s', target: vus },                 // 再 10s 拉到目标
        { duration: duration, target: vus },              // 保持
        { duration: '10s', target: 0 },                   // 10s 优雅下降
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 业务成功率必须 > 99%
    biz_success_rate: ['rate>0.99'],
    // P99 登录延迟 < 200ms（目标值，可能因环境不达标）
    login_latency: ['p(99)<200'],
    // HTTP 错误率 < 1%
    http_req_failed: ['rate<0.01'],
  },
  // 输出趋势指标
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  console.log(`login-stress starting: VUs=${vus}, duration=${duration}`);
  return { startedAt: Date.now() };
}

export default function () {
  const url = `${baseUrl}${apiPrefix}/auth/login`;
  const payload = JSON.stringify({ username, password });
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { api: 'auth_login' },
  };

  const res = http.post(url, payload, params);
  loginLatency.add(res.timings.duration);

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has token': (r) => {
      try {
        const b = JSON.parse(r.body);
        return b && (b.success === true || b.code === 0) && b.data && b.data.token;
      } catch (e) {
        return false;
      }
    },
  });

  bizSuccessRate.add(ok);
  if (!ok) bizErrorCount.add(1);

  // 轻量场景下不思考，连续打
  sleep(0.05);
}

export function teardown(data) {
  const elapsed = Date.now() - data.startedAt;
  console.log(`login-stress finished: elapsed=${elapsed}ms`);
}