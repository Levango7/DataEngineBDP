/**
 * mixed-workload.js — 混合工作负载
 *
 * 模拟真实用户行为：登录 → 浏览资产 → 查看项目 → 搜索 → 查看标准
 *  - 1000 并发用户，持续 60s
 *  - 思考时间 1-3s
 *  - 记录事务成功率
 *
 * 用法：
 *   k6 run --env VUS=1000 DURATION=60s mixed-workload.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { baseUrl, apiPrefix, login, authHeaders, thinkTime, bizSuccessRate, bizErrorCount, txnDuration } from './common.js';

const vus = parseInt(__ENV.VUS || '1000', 10);
const duration = __ENV.DURATION || '60s';

// 事务级指标
const txnLogin = new Trend('txn_login', true);
const txnBrowseAssets = new Trend('txn_browse_assets', true);
const txnViewProjects = new Trend('txn_view_projects', true);
const txnSearch = new Trend('txn_search', true);
const txnViewStandards = new Trend('txn_view_standards', true);
const txnFullFlow = new Trend('txn_full_flow', true);

const txnSuccess = new Rate('txn_success_rate');
const txnFailCount = new Counter('txn_fail_count');

export const options = {
  scenarios: {
    mixed_workload: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: Math.floor(vus / 4) },  // 25%
        { duration: '10s', target: Math.floor(vus / 2) },  // 50%
        { duration: '10s', target: vus },                  // 100%
        { duration: duration, target: vus },               // 保持
        { duration: '10s', target: 0 },                    // 下降
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 事务成功率 > 95%（混合场景略放宽）
    txn_success_rate: ['rate>0.95'],
    // 全流程 P95 < 1s（含思考时间外的网络耗时）
    txn_full_flow: ['p(95)<1000'],
    http_req_failed: ['rate<0.02'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  console.log(`mixed-workload starting: VUs=${vus}, duration=${duration}`);
  return { startedAt: Date.now() };
}

/**
 * 单次完整用户流程，返回是否全部成功
 */
function userFlow() {
  const flowStart = Date.now();
  let allOk = true;

  // 1) 登录
  const tLoginStart = Date.now();
  const token = login();
  const tLogin = Date.now() - tLoginStart;
  txnLogin.add(tLogin);
  if (!token) allOk = false;
  thinkTime();

  // 2) 浏览治理资产
  const tAssetsStart = Date.now();
  const assetsRes = http.get(`${baseUrl}${apiPrefix}/governance/assets`, {
    ...authHeaders(token), tags: { api: 'governance_assets' },
  });
  const tAssets = Date.now() - tAssetsStart;
  txnBrowseAssets.add(tAssets);
  if (!check(assetsRes, { 'assets 2xx': (r) => r.status >= 200 && r.status < 300 })) allOk = false;
  thinkTime();

  // 3) 查看项目
  const tProjStart = Date.now();
  const projRes = http.get(`${baseUrl}${apiPrefix}/projects`, {
    ...authHeaders(token), tags: { api: 'projects' },
  });
  const tProj = Date.now() - tProjStart;
  txnViewProjects.add(tProj);
  if (!check(projRes, { 'projects 2xx': (r) => r.status >= 200 && r.status < 300 })) allOk = false;
  thinkTime();

  // 4) 搜索（用 history 接口模拟）
  const tSearchStart = Date.now();
  const searchRes = http.get(`${baseUrl}${apiPrefix}/search/history`, {
    ...authHeaders(token), tags: { api: 'search_history' },
  });
  const tSearch = Date.now() - tSearchStart;
  txnSearch.add(tSearch);
  if (!check(searchRes, { 'search 2xx': (r) => r.status >= 200 && r.status < 300 })) allOk = false;
  thinkTime();

  // 5) 查看标准
  const tStdStart = Date.now();
  const stdRes = http.get(`${baseUrl}${apiPrefix}/standards`, {
    ...authHeaders(token), tags: { api: 'standards' },
  });
  const tStd = Date.now() - tStdStart;
  txnViewStandards.add(tStd);
  if (!check(stdRes, { 'standards 2xx': (r) => r.status >= 200 && r.status < 300 })) allOk = false;
  thinkTime();

  const flowTotal = Date.now() - flowStart;
  txnFullFlow.add(flowTotal);

  txnSuccess.add(allOk);
  bizSuccessRate.add(allOk);
  if (!allOk) {
    txnFailCount.add(1);
    bizErrorCount.add(1);
  }
  return allOk;
}

export default function () {
  userFlow();
}

export function teardown(data) {
  const elapsed = Date.now() - data.startedAt;
  console.log(`mixed-workload finished: elapsed=${elapsed}ms`);
}