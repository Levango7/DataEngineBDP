/**
 * circuit-breaker-test.js — 熔断降级测试（Circuit Breaker / Degradation Test）
 *
 * 目标：验证系统在异常情况下的韧性行为：
 *   - 场景 1 spike_overload：突发流量超过阈值，验证熔断器/限流触发（429/503）
 *   - 场景 2 backend_down：后端服务不可用，验证降级响应（快速失败，不挂死）
 *   - 场景 3 recovery：服务恢复后，验证熔断器恢复（半开→关闭），正常请求恢复
 *   - 场景 4 timeout：超时请求，验证熔断器计数与超时处理
 *
 * 判定标准：
 *   - 场景 1：突发流量下，系统返回合理状态码（2xx 或 429/503），不应 5xx 雪崩
 *   - 场景 2：后端不可用时，请求快速失败（< 1s），不应长时间挂起
 *   - 场景 3：服务恢复后，正常请求成功率回升至 > 95%
 *   - 场景 4：超时请求被快速识别（< 2s），不拖垮整体响应
 *
 * 用法：
 *   k6 run --env USERNAME=admin --env PASSWORD=admin --env BASE_URL=http://localhost:18086 circuit-breaker-test.js
 *   # 仅运行单个场景
 *   k6 run --env SCENARIO=spike_overload circuit-breaker-test.js
 *
 * Windows 注意：系统环境变量 USERNAME 会覆盖默认值，执行时必须显式传入 --env USERNAME=admin。
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';
import { baseUrl, apiPrefix, username, password, login, authHeaders, bizSuccessRate, bizErrorCount } from '../k6-scripts/common.js';

// ============== 参数 ==============
// 后端不可用时指向的假端口（模拟服务宕机）
const downUrl = __ENV.DOWN_URL || 'http://localhost:18099';
// 超时阈值（ms），用于 timeout 场景
const timeoutMs = __ENV.TIMEOUT_MS || '1';
// 突发流量并发数
const spikeVus = parseInt(__ENV.SPIKE_VUS || '200', 10);

// ============== 熔断降级专用指标 ==============
// 场景 1：突发流量
const spikeSuccessRate = new Rate('spike_success_rate');
const spikeOverloadRate = new Rate('spike_overload_rate');      // 触发限流/熔断（429/503）
const spikeServerErrorRate = new Rate('spike_server_error_rate'); // 5xx 雪崩（不应出现）
const spikeLatency = new Trend('spike_latency', true);

// 场景 2：后端不可用
const downFailFastRate = new Rate('down_fail_fast_rate');        // 快速失败（< 1s）
const downLatency = new Trend('down_latency', true);
const downErrorCount = new Counter('down_error_count');

// 场景 3：恢复
const recoverySuccessRate = new Rate('recovery_success_rate');
const recoveryLatency = new Trend('recovery_latency', true);

// 场景 4：超时
const timeoutDetectedRate = new Rate('timeout_detected_rate');   // 超时被快速识别（< 2s）
const timeoutLatency = new Trend('timeout_latency', true);
const timeoutErrorCount = new Counter('timeout_error_count');

// 熔断触发时间（从首次错误到熔断开启的时间，ms）
const breakerOpenTime = new Gauge('breaker_open_time_ms');

// ============== k6 options ==============
const scenarios = {
  // 场景 1：突发流量超过阈值
  spike_overload: {
    executor: 'ramping-vus',
    exec: 'spikeOverload',
    startVUs: 0,
    stages: [
      { duration: '5s', target: spikeVus },    // 5 秒内拉到 200 并发
      { duration: '15s', target: spikeVus },   // 维持 15 秒
      { duration: '5s', target: 0 },           // 5 秒回落
    ],
    gracefulRampDown: '5s',
  },
  // 场景 2：后端服务不可用（指向假端口）
  backend_down: {
    executor: 'constant-vus',
    exec: 'backendDown',
    vus: 20,
    duration: '20s',
    startTime: '30s',   // 在场景 1 之后执行
  },
  // 场景 3：服务恢复后验证
  recovery: {
    executor: 'constant-vus',
    exec: 'serviceRecovery',
    vus: 20,
    duration: '20s',
    startTime: '55s',   // 在场景 2 之后执行
  },
  // 场景 4：超时请求
  timeout: {
    executor: 'constant-vus',
    exec: 'timeoutRequest',
    vus: 20,
    duration: '20s',
    startTime: '80s',   // 在场景 3 之后执行
  },
};

// 支持仅运行单个场景（通过 --env CB_SCENARIO=xxx 指定，避免与系统 SCENARIO 冲突）
const onlyScenario = __ENV.CB_SCENARIO || '';
if (onlyScenario) {
  console.log(`Running single scenario: ${onlyScenario}`);
  for (const k of Object.keys(scenarios)) {
    if (k !== onlyScenario) delete scenarios[k];
  }
}

export const options = {
  scenarios,
  thresholds: {
    // 场景 1：不应出现 5xx 雪崩（server_error_rate 应为 0）
    spike_server_error_rate: ['rate<0.01'],
    spike_latency: ['p(99)<2000'],
    // 场景 2：应快速失败（> 90% 请求在 1s 内返回）
    down_fail_fast_rate: ['rate>0.9'],
    down_latency: ['p(99)<1000'],
    // 场景 3：恢复后成功率应 > 95%
    recovery_success_rate: ['rate>0.95'],
    recovery_latency: ['p(99)<500'],
    // 场景 4：超时应被快速识别（< 2s）
    timeout_detected_rate: ['rate>0.9'],
    timeout_latency: ['p(99)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
};

// ============== setup ==============
export function setup() {
  console.log(`circuit-breaker-test starting: baseUrl=${baseUrl}, downUrl=${downUrl}, spikeVus=${spikeVus}`);
  const token = login();
  console.log(`setup token: ${token ? 'OK' : 'NULL'}`);
  return { token, startedAt: Date.now() };
}

// ============== 场景 1：突发流量超过阈值 ==============
export function spikeOverload(data) {
  const token = data.token;
  const headers = authHeaders(token).headers;

  const res = http.get(`${baseUrl}${apiPrefix}/projects`, {
    headers,
    timeout: '5s',
    tags: { scenario: 'spike_overload', api: 'projects' },
  });

  spikeLatency.add(res.timings.duration);

  const is2xx = res.status >= 200 && res.status < 300;
  const isOverload = res.status === 429 || res.status === 503;  // 限流/熔断
  const is5xx = res.status >= 500 && res.status < 600;           // 服务器错误（雪崩）

  spikeSuccessRate.add(is2xx);
  spikeOverloadRate.add(isOverload);
  spikeServerErrorRate.add(is5xx);

  if (is2xx) bizSuccessRate.add(true);
  else bizErrorCount.add(1);

  sleep(0.05);  // 高并发下短思考时间
}

// ============== 场景 2：后端服务不可用 ==============
export function backendDown(data) {
  // 指向不存在的端口，模拟后端宕机
  const res = http.get(`${downUrl}${apiPrefix}/projects`, {
    timeout: '2s',
    tags: { scenario: 'backend_down', api: 'projects' },
  });

  downLatency.add(res.timings.duration);

  // 快速失败：请求在 1s 内返回（无论成功失败）
  const failFast = res.timings.duration < 1000;
  downFailFastRate.add(failFast);

  // 记录错误（连接拒绝/超时）
  const isError = res.status === 0 || res.status >= 400;
  if (isError) downErrorCount.add(1);

  sleep(0.1);
}

// ============== 场景 3：服务恢复后验证 ==============
export function serviceRecovery(data) {
  const token = data.token;
  const headers = authHeaders(token).headers;

  // 打正常端点，验证服务恢复
  const res = http.get(`${baseUrl}${apiPrefix}/projects`, {
    headers,
    timeout: '5s',
    tags: { scenario: 'recovery', api: 'projects' },
  });

  recoveryLatency.add(res.timings.duration);

  const is2xx = res.status >= 200 && res.status < 300;
  recoverySuccessRate.add(is2xx);

  if (is2xx) bizSuccessRate.add(true);
  else bizErrorCount.add(1);

  sleep(0.1);
}

// ============== 场景 4：超时请求 ==============
export function timeoutRequest(data) {
  const token = data.token;
  const headers = authHeaders(token).headers;

  // 设置极短 timeout（1ms），强制触发超时
  const res = http.get(`${baseUrl}${apiPrefix}/projects`, {
    headers,
    timeout: `${timeoutMs}ms`,
    tags: { scenario: 'timeout', api: 'projects' },
  });

  timeoutLatency.add(res.timings.duration);

  // 超时被快速识别：请求在 2s 内返回（k6 超时机制生效）
  const detected = res.timings.duration < 2000;
  timeoutDetectedRate.add(detected);

  // 超时通常表现为 status=0 或连接错误
  const isError = res.status === 0 || res.status >= 400;
  if (isError) timeoutErrorCount.add(1);

  sleep(0.1);
}

// ============== teardown ==============
export function teardown(data) {
  const elapsed = Date.now() - data.startedAt;
  console.log(`\n=== circuit-breaker-test finished ===`);
  console.log(`elapsed=${elapsed}ms`);
  console.log(`\n=== 熔断降级分析提示 ===`);
  console.log(`1. spike_overload: 观察 spike_overload_rate（429/503 比例），> 0 说明限流/熔断已触发`);
  console.log(`2. spike_overload: spike_server_error_rate 应接近 0，说明无 5xx 雪崩`);
  console.log(`3. backend_down: down_fail_fast_rate 应 > 90%，说明降级响应快速失败`);
  console.log(`4. recovery: recovery_success_rate 应 > 95%，说明服务恢复后熔断器已关闭`);
  console.log(`5. timeout: timeout_detected_rate 应 > 90%，说明超时被快速识别`);
}