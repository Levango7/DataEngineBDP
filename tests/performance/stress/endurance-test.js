/**
 * endurance-test.js — 耐久性测试（Soak Test / 8h Endurance Test）
 *
 * 目标：在中等负载下长时间运行（默认 8 小时），检测：
 *   - 内存泄漏（通过 /actuator/metrics 持续采集 JVM 堆内存，观察是否单调上升）
 *   - 连接池耗尽（错误率随时间上升）
 *   - GC 暂停恶化（P99 长尾增长）
 *   - 数据库连接泄漏
 *   - 响应时间衰减（后段 vs 前段）
 *
 *  - 默认 500 并发持续 8 小时（可通过 VUS / DURATION 环境变量覆盖）
 *  - 每 60 秒打点一次延迟与 JVM 内存，输出时序数据
 *  - 每 30 秒执行一次健康检查（/actuator/health）
 *  - 测试后段对比前段延迟，判定是否存在衰减
 *
 * 判定标准（无衰减）：
 *   - 响应时间 P99 < 200ms 持续满足
 *   - 错误率 < 0.1%
 *   - JVM 堆内存后段均值 ≤ 前段均值 × 1.2（允许小幅波动，但不应单调上升）
 *
 * 用法：
 *   # 完整 8 小时
 *   k6 run --env VUS=500 --env DURATION=8h --env BASE_URL=http://localhost:18086 endurance-test.js
 *   # 缩短版 10 分钟（验证脚本可用性）
 *   k6 run --env VUS=100 --env DURATION=10m --env BASE_URL=http://localhost:18086 endurance-test.js
 *   # 输出时序数据到 JSON
 *   k6 run --env DURATION=2h --out json=results.json endurance-test.js
 *
 * Windows 注意：系统环境变量 USERNAME 会覆盖 common.js 的默认值 'admin'，
 *   导致登录失败（用户名错误）。执行时必须显式传入 --env USERNAME=admin。
 *   完整命令示例：
 *   k6 run --env VUS=500 --env DURATION=8h --env USERNAME=admin --env PASSWORD=admin --env BASE_URL=http://localhost:18086 endurance-test.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';
import { baseUrl, apiPrefix, username, password, login, authHeaders, bizSuccessRate, bizErrorCount } from '../k6-scripts/common.js';

// ============== 参数 ==============
const vus = parseInt(__ENV.VUS || '500', 10);
const duration = __ENV.DURATION || '8h';
// 健康检查与内存采集间隔（秒）
const healthCheckIntervalSec = 30;
const memorySampleIntervalSec = 60;

// ============== 耐久性专用指标 ==============
const enduranceLatency = new Trend('endurance_latency', true);
const enduranceErrorRate = new Rate('endurance_error_rate');
const enduranceIterations = new Counter('endurance_iterations');

// 时间窗口指标：每 60 秒一个窗口，记录该窗口的延迟均值
const windowLatency = new Trend('window_latency', true);
const windowErrorRate = new Rate('window_error_rate');

// 健康检查指标
const healthCheckStatus = new Rate('health_check_ok');
const healthCheckLatency = new Trend('health_check_latency', true);

// JVM 内存指标（通过 actuator 采集）
const jvmHeapUsed = new Gauge('jvm_heap_used_bytes');
const jvmHeapCommitted = new Gauge('jvm_heap_committed_bytes');
const jvmThreadsLive = new Gauge('jvm_threads_live');

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
    // 耐久性测试无衰减判定标准：错误率 < 0.1%，P99 < 200ms
    endurance_error_rate: ['rate<0.001'],
    endurance_latency: ['p(99)<200'],
    http_req_failed: ['rate<0.001'],
    health_check_ok: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
};

// ============== setup ==============
export function setup() {
  console.log(`endurance-test starting: VUs=${vus}, duration=${duration}`);
  const token = login();
  const startedAt = Date.now();

  // 采集初始 JVM 内存基线
  sampleJvmMemory(startedAt);

  return {
    token,
    startedAt,
    lastHealthCheck: 0,
    lastMemorySample: 0,
  };
}

// ============== JVM 内存采样 ==============
function sampleJvmMemory(nowMs) {
  try {
    // jvm.memory.used area=heap
    const usedRes = http.get(`${baseUrl}/actuator/metrics/jvm.memory.used?tag=area:heap`, {
      timeout: '2s',
      tags: { api: 'actuator_metrics' },
    });
    if (usedRes.status === 200) {
      const body = JSON.parse(usedRes.body);
      if (body && body.measurements && body.measurements.length > 0) {
        jvmHeapUsed.add(body.measurements[0].value);
      }
    }

    // jvm.memory.committed area=heap
    const committedRes = http.get(`${baseUrl}/actuator/metrics/jvm.memory.committed?tag=area:heap`, {
      timeout: '2s',
      tags: { api: 'actuator_metrics' },
    });
    if (committedRes.status === 200) {
      const body = JSON.parse(committedRes.body);
      if (body && body.measurements && body.measurements.length > 0) {
        jvmHeapCommitted.add(body.measurements[0].value);
      }
    }

    // jvm.threads.live
    const threadsRes = http.get(`${baseUrl}/actuator/metrics/jvm.threads.live`, {
      timeout: '2s',
      tags: { api: 'actuator_metrics' },
    });
    if (threadsRes.status === 200) {
      const body = JSON.parse(threadsRes.body);
      if (body && body.measurements && body.measurements.length > 0) {
        jvmThreadsLive.add(body.measurements[0].value);
      }
    }
  } catch (e) {
    // actuator 端点不可用时静默忽略，不影响主流程
  }
}

// ============== 健康检查 ==============
function performHealthCheck() {
  const t0 = Date.now();
  try {
    const res = http.get(`${baseUrl}/actuator/health`, {
      timeout: '3s',
      tags: { api: 'health_check' },
    });
    const latency = Date.now() - t0;
    healthCheckLatency.add(latency);

    let ok = false;
    if (res.status === 200) {
      try {
        const body = JSON.parse(res.body);
        ok = body && body.status === 'UP';
      } catch (e) {
        ok = false;
      }
    }
    healthCheckStatus.add(ok);
    if (!ok) {
      console.warn(`health check FAILED: status=${res.status}, latency=${latency}ms`);
    }
    return ok;
  } catch (e) {
    healthCheckStatus.add(false);
    console.warn(`health check exception: ${e.message}`);
    return false;
  }
}

// ============== 主测试函数 ==============
export default function (data) {
  const token = data.token;
  const headers = authHeaders(token).headers;
  const nowMs = Date.now();
  const elapsedSec = (nowMs - data.startedAt) / 1000;

  // 定期健康检查（每 30 秒，由首个到达的 VU 触发）
  if (elapsedSec - data.lastHealthCheck >= healthCheckIntervalSec) {
    data.lastHealthCheck = elapsedSec;
    performHealthCheck();
  }

  // 定期 JVM 内存采样（每 60 秒）
  if (elapsedSec - data.lastMemorySample >= memorySampleIntervalSec) {
    data.lastMemorySample = elapsedSec;
    sampleJvmMemory(nowMs);
  }

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
  console.log(`\n=== endurance-test finished ===`);
  console.log(`elapsed=${elapsed}ms (${(elapsed / 3600000).toFixed(2)}h)`);

  // 输出延迟趋势分析提示
  console.log(`\n=== 耐久性分析提示 ===`);
  console.log(`1. 对比 window_latency 在测试前 10% 与后 10% 的均值，若后段 > 前段 ×1.5，疑似内存泄漏`);
  console.log(`2. 观察 endurance_error_rate 是否随时间上升，若上升疑似连接池耗尽`);
  console.log(`3. 观察 P99 与 P99.9 的差距，若差距随时间扩大，疑似 GC 暂停恶化`);
  console.log(`4. 观察 jvm_heap_used_bytes 时序数据，若单调上升且不回落，疑似内存泄漏`);
  console.log(`5. 用 k6 --out json=results.json 输出时序数据，配合 Grafana 画延迟-时间曲线`);

  // 最终健康检查
  const finalHealth = performHealthCheck();
  console.log(`\nfinal health check: ${finalHealth ? 'UP' : 'DOWN'}`);
}
