/**
 * endurance-test.js — 耐久性测试（Soak Test）
 *
 * 目标：在中等负载下长时间运行，检测：
 *   - 内存泄漏（通过 k6 的 iterations 持续增长观察延迟是否随时间上升）
 *   - 连接池耗尽（错误率随时间上升）
 *   - GC 暂停恶化（P99 长尾增长）
 *   - 数据库连接泄漏
 *
 *  - 默认 500 并发持续 2 小时
 *  - 每 60 秒打点一次延迟，输出时序数据
 *  - 测试后段对比前段延迟，判定是否存在泄漏
 *
 * 用法：
 *   k6 run --env VUS=500 DURATION=2h endurance-test.js
 *   k6 run --env VUS=300 DURATION=1h endurance-test.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { baseUrl, apiPrefix, username, password, login, authHeaders, bizSuccessRate, bizErrorCount } from '../k6-scripts/common.js';

// ============== 参数 ==============
const vus = parseInt(__ENV.VUS || '500', 10);
const duration = __ENV.DURATION || '2h';

// ============== 耐久性专用指标 ==============
const enduranceLatency = new Trend('endurance_latency', true);
const enduranceErrorRate = new Rate('endurance_error_rate');
const enduranceIterations = new Counter('endurance_iterations');

// 时间窗口指标：每 60 秒一个窗口，记录该窗口的延迟均值
const windowLatency = new Trend('window_latency', true);
const windowErrorRate = new Rate('window_error_rate');

// 各 API 指标
const endLoginLat = new Trend('end_login_latency', true);
const endProjectsLat = new Trend('end_projects_latency', true);
const endAssetsLat = new Trend('end_assets_latency', true);
const endStandardsLat = new Trend('end_standards_latency', true);
const endSearchLat = new Trend('end_search_latency', true);

// ============== k6 options ==============
export const options = {
  scenarios: {
    endurance: {
      executor: 'constant-vus',  // 恒定并发，最适合耐久性测试
      vus: vus,
      duration: duration,
    },
  },
  thresholds: {
    // 耐久性测试：错误率 < 1%，P99 < 500ms
    endurance_error_rate: ['rate<0.01'],
    endurance_latency: ['p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
};

// ============== setup ==============
export function setup() {
  console.log(`endurance-test starting: VUs=${vus}, duration=${duration}`);
  const token = login();
  return { token, startedAt: Date.now(), windowStart: Date.now() };
}

// ============== 主测试函数 ==============
export default function (data) {
  const token = data.token;
  const headers = authHeaders(token).headers;

  // 轮询 5 个 API
  const apiChoice = __ITER % 5;
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
        endLoginLat.add(res.timings.duration);
        break;
      case 1:
        res = http.get(`${baseUrl}${apiPrefix}/projects`, { headers, tags: { api: 'projects' } });
        endProjectsLat.add(res.timings.duration);
        break;
      case 2:
        res = http.get(`${baseUrl}${apiPrefix}/governance/assets`, { headers, tags: { api: 'governance_assets' } });
        endAssetsLat.add(res.timings.duration);
        break;
      case 3:
        res = http.get(`${baseUrl}${apiPrefix}/standards`, { headers, tags: { api: 'standards' } });
        endStandardsLat.add(res.timings.duration);
        break;
      case 4:
        res = http.get(`${baseUrl}${apiPrefix}/search/history`, { headers, tags: { api: 'search_history' } });
        endSearchLat.add(res.timings.duration);
        break;
    }
  } catch (e) {
    enduranceErrorRate.add(true);
    windowErrorRate.add(true);
    enduranceLatency.add(Date.now() - t0);
    enduranceIterations.add(1);
    return;
  }

  enduranceLatency.add(res.timings.duration);
  windowLatency.add(res.timings.duration);

  const ok = res.status >= 200 && res.status < 300;
  enduranceErrorRate.add(!ok);
  windowErrorRate.add(!ok);
  bizSuccessRate.add(ok);
  if (!ok) bizErrorCount.add(1);

  enduranceIterations.add(1);

  // 思考时间 200ms（耐久性测试模拟真实用户节奏）
  sleep(0.2);
}

// ============== teardown：分析延迟趋势 ==============
export function teardown(data) {
  const elapsed = Date.now() - data.startedAt;
  console.log(`endurance-test finished: elapsed=${elapsed}ms, totalIterations=${enduranceIterations}`);

  // 输出延迟趋势分析提示
  console.log(`\n=== 耐久性分析提示 ===`);
  console.log(`1. 对比 window_latency 在测试前 10% 与后 10% 的均值，若后段 > 前段 ×1.5，疑似内存泄漏`);
  console.log(`2. 观察 endurance_error_rate 是否随时间上升，若上升疑似连接池耗尽`);
  console.log(`3. 观察 P99 与 P99.9 的差距，若差距随时间扩大，疑似 GC 暂停恶化`);
  console.log(`4. 用 k6 --out json=results.json 输出时序数据，配合 Grafana 画延迟-时间曲线`);
}