/**
 * spike-test.js — 突发流量测试
 *
 * 目标：模拟瞬间流量激增，验证：
 *   - 系统是否能承受突发流量
 *   - 突发后是否能快速恢复
 *   - 是否触发限流/熔断保护
 *   - 错误率是否在可接受范围
 *
 *  - 瞬间从 100 并发跳到 5000
 *  - 持续 30 秒后回落到 100
 *  - 重复 3 轮，观察系统恢复能力
 *
 * 用法：
 *   k6 run --env SPIKE_PEAK=5000 spike-test.js
 *   k6 run --env SPIKE_PEAK=3000 SPIKE_ROUNDS=5 spike-test.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { baseUrl, apiPrefix, username, password, login, authHeaders, bizSuccessRate, bizErrorCount } from '../k6-scripts/common.js';

// ============== 参数 ==============
const spikePeak = parseInt(__ENV.SPIKE_PEAK || '5000', 10);
const spikeRounds = parseInt(__ENV.SPIKE_ROUNDS || '3', 10);
const baselineVus = parseInt(__ENV.BASELINE_VUS || '100', 10);
const spikeDuration = __ENV.SPIKE_DURATION || '30s';
const baselineDuration = __ENV.BASELINE_DURATION || '30s';

// ============== 突发流量专用指标 ==============
const spikeLatency = new Trend('spike_latency', true);
const spikeErrorRate = new Rate('spike_error_rate');
const spikePeakHits = new Counter('spike_peak_hits');          // 峰值期请求数
const baselineHits = new Counter('baseline_hits');             // 基线期请求数
const spikeRecoveryMs = new Trend('spike_recovery_ms', true);  // 每轮恢复时间

// ============== 阶梯设计：3 轮突发 ==============
function buildSpikeStages() {
  const stages = [];
  for (let i = 0; i < spikeRounds; i++) {
    // 起始基线
    stages.push({ duration: baselineDuration, target: baselineVus });
    // 瞬间跳到峰值（ramp-up 极短）
    stages.push({ duration: '2s', target: spikePeak });
    // 保持峰值
    stages.push({ duration: spikeDuration, target: spikePeak });
    // 瞬间回落
    stages.push({ duration: '2s', target: baselineVus });
    // 基线恢复观察
    stages.push({ duration: baselineDuration, target: baselineVus });
  }
  return stages;
}

const stages = buildSpikeStages();

// ============== k6 options ==============
export const options = {
  scenarios: {
    spike_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: stages,
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 突发流量阈值放宽：错误率 < 20%，P99 < 3s
    spike_error_rate: ['rate<0.20'],
    spike_latency: ['p(99)<3000'],
    http_req_failed: ['rate<0.20'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
};

// ============== setup ==============
export function setup() {
  console.log(`spike-test starting: peak=${spikePeak}, rounds=${spikeRounds}, baseline=${baselineVus}`);
  const token = login();
  return { token, startedAt: Date.now(), roundStart: Date.now() };
}

// ============== 主测试函数 ==============
export default function (data) {
  const token = data.token;
  const headers = authHeaders(token).headers;

  // 轮询 5 个 API
  const apiChoice = Math.floor(Math.random() * 5);
  let res;
  const t0 = Date.now();

  try {
    switch (apiChoice) {
      case 0:
        res = http.post(
          `${baseUrl}${apiPrefix}/auth/login`,
          JSON.stringify({ username, password }),
          { headers: { 'Content-Type': 'application/json' }, tags: { api: 'auth_login' } }
        );
        break;
      case 1:
        res = http.get(`${baseUrl}${apiPrefix}/projects`, { headers, tags: { api: 'projects' } });
        break;
      case 2:
        res = http.get(`${baseUrl}${apiPrefix}/governance/assets`, { headers, tags: { api: 'governance_assets' } });
        break;
      case 3:
        res = http.get(`${baseUrl}${apiPrefix}/standards`, { headers, tags: { api: 'standards' } });
        break;
      case 4:
        res = http.get(`${baseUrl}${apiPrefix}/search/history`, { headers, tags: { api: 'search_history' } });
        break;
    }
  } catch (e) {
    spikeErrorRate.add(true);
    spikeLatency.add(Date.now() - t0);
    spikePeakHits.add(1);
    return;
  }

  spikeLatency.add(res.timings.duration);

  const ok = res.status >= 200 && res.status < 300;
  spikeErrorRate.add(!ok);
  bizSuccessRate.add(ok);
  if (!ok) bizErrorCount.add(1);

  // 根据当前 VU 数判定是峰值期还是基线期
  if (__VU > baselineVus * 2) {
    spikePeakHits.add(1);
  } else {
    baselineHits.add(1);
  }

  // 极短思考时间
  sleep(0.05);
}

// ============== teardown ==============
export function teardown(data) {
  const elapsed = Date.now() - data.startedAt;
  console.log(`spike-test finished: elapsed=${elapsed}ms`);
  console.log(`peakHits=${spikePeakHits}, baselineHits=${baselineHits}`);

  console.log(`\n=== 突发流量分析提示 ===`);
  console.log(`1. 对比 spike_latency 在峰值期与基线期的差异，评估降级幅度`);
  console.log(`2. 观察 spike_error_rate 在峰值期是否突增，判定是否触发熔断`);
  console.log(`3. 对比每轮峰值后的基线期延迟，若延迟逐轮上升，说明系统未完全恢复`);
  console.log(`4. 用 k6 --out json=results.json 输出时序数据，画 VU-延迟-错误率三轴曲线`);
}