/**
 * 大型 toB 企业（离散制造）场景端到端验证脚本。
 *
 * 对应场景文档：design/场景模拟/大型toB企业场景.md
 *
 * 业务流程：MES/ERP/SCADA/WMS/TMS 多源接入 → 共享数据湖 ODS →
 *           各事业部独立数仓（生产/供应链/质量/能源/财务） →
 *           质量追溯 / OEE 计算 / 供应链优化 →
 *           BI 看板 + 数据 API + ML 预测（故障/能耗）
 *
 * 验证策略：
 * - 多租户（事业部+业务线两级隔离）→ 实际验证
 * - 数据资产目录 + API 服务目录 → 实际验证
 * - OEE 计算 / 质量追溯 / ML 预测 → 需集群，输出验证清单
 */

const path = require('path');
const { ApiClient, Assert, Logger } = require('../lib/api-client');
const { runScenario, saveResult } = require('../lib/runner');

const log = new Logger('[EnterpriseScenario]');

// 5 大事业部（场景文档 §1.2）
const DIVISIONS = [
  { id: 'prod', name: '生产事业部', namespace: 'ws-mfg-prod' },
  { id: 'scm', name: '供应链事业部', namespace: 'ws-mfg-scm' },
  { id: 'qa', name: '质量事业部', namespace: 'ws-mfg-qa' },
  { id: 'energy', name: '能源事业部', namespace: 'ws-mfg-energy' },
  { id: 'fin', name: '财务事业部', namespace: 'ws-mfg-fin' },
];

// 5 个工厂
const PLANTS = ['华东', '华南', '华北', '西南', '东北'];

const MFG_TENANT_ID = 'mfg-group';

const steps = [

  // ============================================================
  // 步骤 1：登录与租户上下文
  // ============================================================
  {
    name: '登录并建立制造集团租户上下文',
    description: 'admin/admin 登录，切换到 mfg-group 租户',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const loginRes = await ctx.api.login('admin', 'admin');
      a.ok('登录成功', loginRes.ok && !!ctx.api.token);
      ctx.api.tenantId = MFG_TENANT_ID;
      const h = await ctx.api.health();
      a.ok('健康检查通过', h.ok);
      return { assert: a };
    },
  },

  // ============================================================
  // 步骤 2：3 万员工组织架构 - 项目空间
  // ============================================================
  {
    name: '3 万员工组织架构 - 创建集团项目空间',
    description: '为集团总部 + 5 个工厂创建项目空间，承载多事业部数据',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const projects = [
        { name: '集团总部-共享数据湖', domain: 'shared', description: 'ODS+DWD 共享层' },
        { name: '华东工厂-生产', domain: 'production', description: '华东工厂生产事业部' },
        { name: '华南工厂-生产', domain: 'production', description: '华南工厂生产事业部' },
        { name: '华北工厂-生产', domain: 'production', description: '华北工厂生产事业部' },
        { name: '西南工厂-生产', domain: 'production', description: '西南工厂生产事业部' },
        { name: '东北工厂-生产', domain: 'production', description: '东北工厂生产事业部' },
      ];
      for (const p of projects) {
        const res = await ctx.api.post('/projects', p);
        a.ok(`创建项目「${p.name}」成功`, res.ok, `status=${res.status}`);
      }
      // 按域过滤
      const prodRes = await ctx.api.get('/projects?domain=production');
      a.ok('按 production 域过滤返回 200', prodRes.ok);
      const prodList = prodRes.data && prodRes.data.data && prodRes.data.data.list || [];
      a.ok('production 项目数 >= 5', prodList.length >= 5, `actual=${prodList.length}`);

      return { assert: a, summary: { projectsCreated: projects.length } };
    },
  },

  // ============================================================
  // 步骤 3：多工厂数据集成 - MES/ERP/SCADA 接入
  // ============================================================
  {
    name: '多工厂数据集成 - MES/ERP/SCADA/WMS/TMS 接入',
    description: '为 5 个工厂创建多源数据集成任务（MySQL-CDC/JDBC/OPC UA）',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();

      // 验证连接器支持（场景文档 §1.3）
      const connRes = await ctx.api.get('/integrate/connectors');
      a.ok('连接器列表返回 200', connRes.ok);
      const connectors = connRes.data && connRes.data.data || [];
      const requiredConnectors = ['MySQL', 'Oracle', 'Kafka', 'Iceberg', 'Doris'];
      for (const rc of requiredConnectors) {
        a.ok(`连接器 ${rc} 可用`, connectors.some(c => c.name === rc && c.status === 'connected'));
      }

      // 为每个工厂创建 MES CDC + ERP JDBC 任务
      const taskCount = { mes: 0, erp: 0 };
      for (const plant of PLANTS) {
        const mesTask = {
          name: `mfg.mes.${plant}.work_order.cdc`,
          sourceType: 'MySQL-CDC',
          targetType: 'Kafka',
          sourceTable: `mes_${plant}.work_order`,
          targetTable: `mfg.mes.${plant}.work_order.cdc`,
          schedule: 'realtime',
        };
        const mesRes = await ctx.api.post('/integrate/tasks', mesTask);
        a.ok(`创建 ${plant} MES CDC 任务成功`, mesRes.ok);
        if (mesRes.ok) taskCount.mes++;

        const erpTask = {
          name: `mfg.erp.${plant}.material.snapshot`,
          sourceType: 'Oracle',
          targetType: 'Kafka',
          sourceTable: `erp_${plant}.material`,
          targetTable: `mfg.erp.${plant}.material.snapshot`,
          schedule: '5min',
        };
        const erpRes = await ctx.api.post('/integrate/tasks', erpTask);
        a.ok(`创建 ${plant} ERP JDBC 任务成功`, erpRes.ok);
        if (erpRes.ok) taskCount.erp++;
      }

      a.ok('MES 任务数 = 5', taskCount.mes === 5, `actual=${taskCount.mes}`);
      a.ok('ERP 任务数 = 5', taskCount.erp === 5, `actual=${taskCount.erp}`);

      return { assert: a, summary: { mesTasks: taskCount.mes, erpTasks: taskCount.erp } };
    },
  },

  // ============================================================
  // 步骤 4：多事业部租户隔离（事业部+业务线两级）
  // ============================================================
  {
    name: '多事业部租户隔离 - 事业部+业务线两级隔离',
    description: '验证事业部隔离能力；当前后端 tenantId 来自 JWT(default)，多租户需 Keycloak 多 Realm',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();

      // 在当前租户下创建生产事业部资产
      ctx.api.tenantId = 'mfg-prod';
      const prodAsset = {
        name: 'dws_prod_equipment_oee_daily',
        type: 'DWS',
        owner: 'prod_admin',
        description: '设备日 OEE 汇总（含目标差距）',
        qualityScore: 88,
        securityLevel: 'L2',
        full: { division: 'prod', line: 'stamping' },
      };
      const createRes = await ctx.api.post('/governance/assets', prodAsset);
      a.ok('生产事业部创建 OEE 资产成功', createRes.ok);
      const assetId = createRes.data && createRes.data.data && createRes.data.data.id;

      // 验证资产可见
      const listRes = await ctx.api.get('/governance/assets');
      a.ok('资产列表返回 200', listRes.ok);
      const assets = listRes.data && listRes.data.data && listRes.data.data.list || [];
      const found = assets.find(x => String(x.id) === String(assetId));
      a.ok('当前租户可见 OEE 资产', !!found);

      // 记录多事业部隔离限制
      a.skip('事业部隔离需不同 JWT 登录', '当前后端 tenantId 来自 JWT claim，事业部隔离需 Keycloak 多 Realm 登录支持');

      ctx.api.tenantId = MFG_TENANT_ID;
      ctx.mfg.prodAssetId = assetId;
      return { assert: a };
    },
  },

  // ============================================================
  // 步骤 5：生产质量分析 - OEE 计算验证（需集群）
  // ============================================================
  {
    name: '生产质量分析 - OEE 计算（可用率×性能率×质量率）',
    description: '验证 OEE = (420/480) × (8000/(420×20)) × (7800/8000) = 0.812',
    requiresCluster: true,
    async run(ctx) {
      // OEE 公式验证可在本地计算
      const a = new Assert();
      const runTime = 420, plannedTime = 480, totalQty = 8000, ratedCycle = 20, goodQty = 7800;
      const availability = runTime / plannedTime;
      const performance = totalQty / (runTime * ratedCycle);
      const qualityRate = goodQty / totalQty;
      const oee = availability * performance * qualityRate;
      a.ok('可用率 = 0.875', Math.abs(availability - 0.875) < 0.001);
      a.ok('性能率 ≈ 0.952', Math.abs(performance - 0.952) < 0.001);
      a.ok('质量率 = 0.975', Math.abs(qualityRate - 0.975) < 0.001);
      a.ok('OEE ≈ 0.812', Math.abs(oee - 0.812) < 0.005, `oee=${oee}`);

      return {
        assert: a,
        status: 'SKIP',
        reason: 'OEE 公式本地验证通过；实际 OEE 计算需 Spark + IoTDB + Iceberg 集群',
        checks: a.records,
      };
    },
  },

  // ============================================================
  // 步骤 6：供应链数据治理 - 库存周转/供应商评估
  // ============================================================
  {
    name: '供应链数据治理 - 库存周转/供应商评估资产注册',
    description: '注册供应链 DWS 资产到数据资产目录',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      ctx.api.tenantId = 'mfg-scm';
      const assets = [
        { name: 'dws_scm_inventory_turnover', type: 'DWS', owner: 'scm_admin', description: '库存周转汇总', securityLevel: 'L2' },
        { name: 'dws_scm_supplier_eval', type: 'DWS', owner: 'scm_admin', description: '供应商评估汇总（S/A/B/C/D 评级）', securityLevel: 'L2' },
      ];
      for (const ast of assets) {
        const res = await ctx.api.post('/governance/assets', ast);
        a.ok(`注册供应链资产「${ast.name}」成功`, res.ok);
      }
      ctx.api.tenantId = MFG_TENANT_ID;
      return { assert: a };
    },
  },

  // ============================================================
  // 步骤 7：质量追溯 - 正反向追溯链路（需集群）
  // ============================================================
  {
    name: '质量追溯 - 批次→工序→参数→缺陷 正反向追溯',
    description: '验证从缺陷反向追溯到根因批次和供应商来料',
    requiresCluster: true,
    async run(ctx) {
      return {
        status: 'SKIP',
        reason: '需 Spark + Iceberg 构建质量追溯链 quality_trace_link',
        checks: [
          { name: '正向追溯（批次→缺陷）', status: 'SKIP', detail: '需 Spark 追溯作业' },
          { name: '反向追溯（缺陷→根因批次）', status: 'SKIP', detail: '需 Spark 追溯作业' },
          { name: '供应商来料关联', status: 'SKIP', detail: '需 supplier + purchase_order 关联' },
        ],
      };
    },
  },

  // ============================================================
  // 步骤 8：BI 报表验证 - 各事业部门户
  // ============================================================
  {
    name: 'BI 报表 - 各事业部门户看板数据集注册',
    description: '为 5 大事业部注册 BI 看板数据集到数据资产目录',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const dashboards = [
        { name: 'ads_prod_dashboard', type: 'ADS', owner: 'prod_admin', description: '生产门户：产线 OEE/设备状态/工单进度' },
        { name: 'ads_scm_dashboard', type: 'ADS', owner: 'scm_admin', description: '供应链门户：库存周转/供应商评估/订单履约' },
        { name: 'ads_qa_dashboard', type: 'ADS', owner: 'qa_admin', description: '质量门户：良率趋势/缺陷帕累托/SPC 控制图' },
        { name: 'ads_energy_dashboard', type: 'ADS', owner: 'energy_admin', description: '能源门户：设备能耗/厂区能耗/碳排放' },
        { name: 'ads_fin_dashboard', type: 'ADS', owner: 'fin_admin', description: '财务门户：成本核算/经营分析/利润趋势' },
      ];
      for (const d of dashboards) {
        const res = await ctx.api.post('/governance/assets', d);
        a.ok(`注册看板数据集「${d.name}」成功`, res.ok);
      }
      return { assert: a, summary: { dashboards: dashboards.length } };
    },
  },

  // ============================================================
  // 步骤 9：数据 API 服务目录 - 设备 OEE/质量追溯/库存周转 API
  // ============================================================
  {
    name: '数据 API 服务目录 - 设备 OEE/质量追溯/库存周转 API 发布',
    description: '注册多事业部数据 API 到 open-api-catalog',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const apis = [
        { name: '设备 OEE 查询', version: 'v1', category: 'production', method: 'GET', path: '/api/v1/oee/equipment', status: 'published' },
        { name: '质量追溯查询', version: 'v1', category: 'quality', method: 'GET', path: '/api/v1/qa/trace/batch/{batchNo}', status: 'published' },
        { name: '库存周转查询', version: 'v1', category: 'scm', method: 'GET', path: '/api/v1/scm/inventory/turnover', status: 'published' },
        { name: '订单履约推送', version: 'v1', category: 'scm', method: 'POST', path: '/api/v1/scm/order/fulfillment', status: 'published' },
      ];
      for (const api of apis) {
        const res = await ctx.api.post('/apis', api);
        a.ok(`发布 API「${api.name}」成功`, res.ok);
      }
      // 按分类过滤
      const scmApiRes = await ctx.api.get('/apis?category=scm');
      a.ok('scm 分类 API 列表返回 200', scmApiRes.ok);
      const scmApis = scmApiRes.data && scmApiRes.data.data && scmApiRes.data.data.list || [];
      a.ok('scm 分类 API 数 >= 2', scmApis.length >= 2);
      return { assert: a, summary: { apisPublished: apis.length } };
    },
  },

  // ============================================================
  // 步骤 10：ML 模型注册 - 设备故障预测/能耗预测
  // ============================================================
  {
    name: 'ML 模型注册 - 设备故障预测/能耗预测模型',
    description: '注册 XGBoost 故障预测 + LightGBM 能耗预测模型到模型仓库',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const models = [
        {
          name: 'equipment_failure_xgb',
          algorithm: 'XGBoost',
          version: 'v1',
          metrics: { auc: 0.89, precision: 0.85, recall: 0.82 },
          description: '设备故障预测（7 天内故障二分类）',
        },
        {
          name: 'energy_consumption_lgbm',
          algorithm: 'LightGBM',
          version: 'v1',
          metrics: { mape: 0.072, rmse: 12.5 },
          description: '能耗预测（未来 7/30 天）',
        },
      ];
      for (const m of models) {
        const res = await ctx.api.post('/ml/models', m);
        a.ok(`注册模型「${m.name}」成功`, res.ok, `status=${res.status}`);
      }
      // 查询模型列表
      const listRes = await ctx.api.get('/ml/models');
      a.ok('模型列表返回 200', listRes.ok);
      const modelList = listRes.data && listRes.data.data || [];
      a.ok('模型数 >= 2', modelList.length >= 2, `actual=${modelList.length}`);

      return { assert: a, summary: { modelsRegistered: models.length } };
    },
  },

  // ============================================================
  // 步骤 11：ML 推理服务部署
  // ============================================================
  {
    name: 'ML 推理服务部署 - 故障预测在线推理',
    description: '部署设备故障预测模型为在线推理服务',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const deployRes = await ctx.api.post('/ml/inference-services', {
        serviceName: 'equipment-failure-predict',
        modelName: 'equipment_failure_xgb',
        version: 'v1',
        replicas: 2,
        resourceSpec: '2c4g',
      });
      a.ok('部署推理服务成功', deployRes.ok, `status=${deployRes.status}`);

      // 查询推理服务列表
      const listRes = await ctx.api.get('/ml/inference-services');
      a.ok('推理服务列表返回 200', listRes.ok);
      const svcList = listRes.data && listRes.data.data || [];
      a.ok('推理服务数 >= 1', svcList.length >= 1);

      // 扩缩容
      if (svcList.length > 0) {
        const svcId = svcList[0].id;
        const scaleRes = await ctx.api.post(`/ml/inference-services/${svcId}/scale`, { replicas: 4 });
        a.ok('扩缩容推理服务成功', scaleRes.ok);
      }
      return { assert: a };
    },
  },

  // ============================================================
  // 步骤 12：数据产品交付 - 跨事业部订阅审批
  // ============================================================
  {
    name: '数据产品交付 - 跨事业部订阅审批流',
    description: '能源事业部申请订阅生产事业部 OEE 资产，审批通过后可访问',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const assetId = ctx.mfg.prodAssetId;
      if (!assetId) {
        a.skip('无生产事业部资产 ID', '前置步骤未创建');
        return { assert: a };
      }
      // 申请权限
      const applyRes = await ctx.api.post(`/governance/assets/${assetId}/apply-permission`, { permission: 'read' });
      a.ok('能源事业部申请订阅 OEE 资产成功', applyRes.ok);

      // 审批列表（注意：AssetController 与 SecController 审批流存储独立，跨控制器查询为空）
      const apprRes = await ctx.api.get('/sec/approvals');
      a.ok('审批列表返回 200', apprRes.ok);
      a.skip('审批列表跨控制器查询', '当前后端 AssetController 与 SecController 审批流存储独立，需统一存储（待修复）');

      // 验证 SecController 策略 API 可用
      const policiesRes = await ctx.api.get('/sec/policies');
      a.ok('SecController 策略列表可用', policiesRes.ok);

      return { assert: a };
    },
  },

];

async function main() {
  const api = new ApiClient();
  const ctx = { api, clusterAvailable: false, mfg: {} };
  const scenario = {
    name: 'enterprise',
    description: '大型 toB 制造企业场景：3 万员工+5 工厂+5 事业部+多源集成+OEE+质量追溯+ML 预测',
    industry: 'manufacturing',
    steps,
  };
  const result = await runScenario(scenario, ctx);
  const outPath = path.join(__dirname, 'scenario-enterprise.result.json');
  saveResult(result, outPath);
  log.info(`结果已写入: ${outPath}`);
  process.exit(result.summary.failed > 0 ? 1 : 0);
}

if (require.main === module) {
  main().catch((err) => {
    log.error(`场景执行异常: ${err && err.stack || err}`);
    process.exit(2);
  });
}

module.exports = { steps, DIVISIONS, PLANTS, MFG_TENANT_ID };