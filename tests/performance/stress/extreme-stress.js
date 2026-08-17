/**
 * extreme-stress.js — 极限压测脚本
 *
 * 目标：测量系统在极限负载下的表现，找到熔断点与恢复时间。
 *  - 默认 2000 并发，可通过 VUS=5000 升级
 *  - 默认持续 30 分钟（30m），可通过 DURATION 环境变量调整
 *  - 阶梯加压：500 → 1000 → 2000 → 5000（可选），每阶段保持 5 分钟
 *  - 实时记录 TPS / 延迟 / 错误率 / 熔断信号
 *  - 测试结束后空跑 2 分钟，测量恢复时间
 *
 * 用法：
 *   k6 run --env VUS=2000 DURATION=30m extreme-stress.js
 *   k6 run --env VUS=5000 DURATION=30m extreme-stress.js
 *   k6 run --env STAGES=auto extreme-stress.js
 *
 * 输出指标：
 *   - extreme_tps：每秒事务数
 *   - extreme_latency_p99：P99 延迟
 *   - extreme_error_rate：错误率
 *   - circuit_breaker_hits：熔断触发次数
 *   - recovery_time_ms：恢复时间
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';
import { baseUrl, apiPrefix, username, password, login, authHeaders, bizSuccessRate, bizErrorCount } from '../k6-scripts/common.js';

// ============== 参数解析 ==============
const targetVus = parseInt(__ENV.VUS || '2000', 10);
const duration = __ENV.DURATION || '30m';
const stageMode = __ENV.STAGES || 'auto';

// ============== 极限压测专用指标 ==============
const extremeLatency = new Trend('extreme_latency', true);          // 单请求延迟
const extremeTpsGauge = new Gauge('extreme_tps');                   // 实时 TPS（k6 自动计算 http_reqs，这里仅打点）
const extremeErrorRate = new Rate('extreme_error_rate');            // 错误率
const circuitBreakerHits = new Counter('circuit_breaker_hits');     // 熔断触发计数
const recoveryTimeMs = new Trend('recovery_time_ms', true);         // 恢复时间
const cooldownLatency = new Trend('cooldown_latency', true);        // 冷却期延迟

// 各 API 单独指标
const loginLatencyExtreme = new Trend('extreme_login_latency', true);
const projectsLatencyExtreme = new Trend('extreme_projects_latency', true);
const assetsLatencyExtreme = new Trend('extreme_assets_latency', true);
const standardsLatencyExtreme = new Trend('extreme_standards_latency', true);
const searchLatencyExtreme = new Trend('extreme_search_latency', true);

// ============== 阶梯设计 ==============
function buildStages(vus, mode) {
  // 自动阶梯：500 → 1000 → 2000 → (5000) → 0
  if (mode === 'auto') {
    const stages = [
      { duration: '2m', target: Math.min(500, vus) },        // 阶段1：500 并发预热
      { duration: '5m', target: Math.min(500, vus) },        // 保持 500
      { duration: '2m', target: Math.min(1000, vus) },       // 阶段2：升到 1000
      { duration: '5m', target: Math.min(1000, vus) },       // 保持 1000
      { duration: '2m', target: Math.min(2000, vus) },       // 阶段3：升到 2000
      { duration: '5m', target: Math.min(2000, vus) },       // 保持 2000
    ];
    if (vus >= 5000) {
      stages.push({ duration: '2m', target: 5000 });          // 阶段4：升到 5000
      stages.push({ duration: '5m', target: 5000 });          // 保持 5000
    }
    stages.push({ duration: '2m', target: 0 });               // 冷却：降到 0，测恢复时间
    return stages;
  }
  // 简单模式：直接打满 + 冷却
  return [
    { duration: '2m', target: vus },
    { duration: duration, target: vus },
    { duration: '2m', target: 0 },
  ];
}

const stages = buildStages(targetVus, stageMode);

// ============== k6 options ==============
export const options = {
  scenarios: {
    extreme_stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: stages,
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    // 极限压测阈值放宽：错误率 < 10%，P99 < 5s
    extreme_error_rate: ['rate<0.10'],
    extreme_latency: ['p(99)<5000'],
    http_req_failed: ['rate<0.10'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
};

// ============== setup：预热登录 ==============
export function setup() {
  console.log(`extreme-stress starting: targetVus=${targetVus}, duration=${duration}, stages=${stages.length}`);
  const token = login();
  return { token, startedAt: Date.now() };
}

// ============== 主测试函数 ==============
export default function (data) {
  const token = data.token;
  const headers = authHeaders(token).headers;

  // 轮询 5 个 API，模拟混合极限负载
  const apiChoice = Math.floor(Math.random() * 5);
  let res;
  const t0 = Date.now();

  try {
    switch (apiChoice) {
      case 0: // 登录（CPU 密集）
        res = http.post(
          `${baseUrl}${apiPrefix}/auth/login`,
          JSON.stringify({ username, password }),
          { headers: { 'Content-Type': 'application/json' }, tags: { api: 'auth_login' } }
        );
        loginLatencyExtreme.add(res.timings.duration);
        break;
      case 1:
        res = http.get(`${baseUrl}${apiPrefix}/projects`, { headers, tags: { api: 'projects' } });
        projectsLatencyExtreme.add(res.timings.duration);
        break;
      case 2:
        res = http.get(`${baseUrl}${apiPrefix}/governance/assets`, { headers, tags: { api: 'governance_assets' } });
        assetsLatencyExtreme.add(res.timings.duration);
        break;
      case 3:
        res = http.get(`${baseUrl}${apiPrefix}/standards`, { headers, tags: { api: 'standards' } });
        standardsLatencyExtreme.add(res.timings.duration);
        break;
      case 4:
        res = http.get(`${baseUrl}${apiPrefix}/search/history`, { headers, tags: { api: 'search_history' } });
        searchLatencyExtreme.add(res.timings.duration);
        break;
    }
  } catch (e) {
    // 异常（连接拒绝、超时等）记为熔断信号
    circuitBreakerHits.add(1);
    extremeErrorRate.add(true);
    extremeLatency.add(Date.now() - t0);
    return;
  }

  extremeLatency.add(res.timings.duration);

  // 状态码判定
  const ok = res.status >= 200 && res.status < 300;
  extremeErrorRate.add(!ok);
  bizSuccessRate.add(ok);
  if (!ok) {
    bizErrorCount.add(1);
    // 5xx 或连接异常视为熔断信号
    if (res.status >= 500 || res.status === 0) {
      circuitBreakerHits.add(1);
    }
  }

  // 极短思考时间（极限压测不打满 CPU 也要给后端喘息）
  sleep(0.05);
}

// ============== teardown：测恢复时间 ==============
export function teardown(data) {
  const elapsed = Date.now() - data.startedAt;
  console.log(`extreme-stress finished: elapsed=${elapsed}ms, circuitBreakerHits=${circuitBreakerHits}`);

  // 冷却期：每 5 秒打一次健康检查，直到 P99 < 200ms 视为恢复
  const cooldownStart = Date.now();
  let recovered = false;
  const samples = [];
  for (let i = 0; i < 24; i++) {  // 最多等 2 分钟
    sleep(5);
    const t = Date.now();
    try {
      const r = http.get(`${baseUrl}${apiPrefix}/projects`, {
        headers: authHeaders(data.token).headers,
        tags: { api: 'cooldown_check' },
      });
      const dur = Date.now() - t;
      cooldownLatency.add(dur);
      samples.push(dur);
      if (r.status === 200 && dur < 200) {
        recovered = true;
        break;
      }
    } catch (e) {
      // 仍未恢复
    }
  }
  const recoveryMs = Date.now() - cooldownStart;
  recoveryTimeMs.add(recoveryMs);
  console.log(`recovery: recovered=${recovered}, recoveryTime=${recoveryMs}ms, samples=${JSON.stringify(samples)}`);
}