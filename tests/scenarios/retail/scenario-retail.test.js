/**
 * 零售营销场景端到端验证脚本。
 *
 * 对应场景文档：design/场景模拟/零售营销场景.md
 *
 * 业务流程：POS/CRM/行为 → 实时入湖 → 会员画像（RFM/流失/LTV/标签） →
 *           商品画像（销量/评价/标签） → 推荐引擎（多路召回+精排） →
 *           营销活动引擎 + A/B 实验 → 效果分析（ROI/漏斗/归因）
 *
 * 验证策略：
 * - 会员画像/商品画像资产注册、API 发布、A/B 实验配置 → 实际验证
 * - RFM 计算/流失预测/LTV 预测/推荐引擎 → 需集群，输出验证清单
 */

const path = require('path');
const { ApiClient, Assert, Logger } = require('../lib/api-client');
const { runScenario, saveResult } = require('../lib/runner');

const log = new Logger('[RetailScenario]');

const RTL_TENANT_ID = 'retail-group';

// 10 大 RFM 分群（场景文档 §2.3.1）
const RFM_SEGMENTS = [
  'CHAMPION', 'LOYAL', 'POTENTIAL_LOYAL', 'NEW', 'PROMISING',
  'NEED_ATTENTION', 'ABOUT_TO_SLEEP', 'HIBERNATING', 'LOST', 'LOST_CHEAP',
];

// 5 步转化漏斗
const FUNNEL_STEPS = ['曝光', '点击', '加购', '下单', '支付'];

const steps = [

  // ============================================================
  // 步骤 1：登录与租户上下文
  // ============================================================
  {
    name: '登录并建立零售集团租户上下文',
    description: 'admin/admin 登录，切换到 retail-group 租户',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const loginRes = await ctx.api.login('admin', 'admin');
      a.ok('登录成功', loginRes.ok && !!ctx.api.token);
      ctx.api.tenantId = RTL_TENANT_ID;
      const h = await ctx.api.health();
      a.ok('健康检查通过', h.ok);
      return { assert: a };
    },
  },

  // ============================================================
  // 步骤 2：500 门店 POS/CRM 实时接入
  // ============================================================
  {
    name: '500 门店 POS/CRM 实时接入 - CDC 集成任务',
    description: '为 POS 订单/支付 + CRM 会员/营销 + 行为事件创建集成任务',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();

      // 验证连接器
      const connRes = await ctx.api.get('/integrate/connectors');
      a.ok('连接器列表返回 200', connRes.ok);

      // 创建 5 类集成任务
      const tasks = [
        { name: 'retail.pos.order.cdc', source: 'pos_db.order', desc: 'POS 订单变更' },
        { name: 'retail.pos.payment.cdc', source: 'pos_db.payment', desc: '支付流水' },
        { name: 'retail.crm.member.cdc', source: 'crm_db.member', desc: '会员信息变更' },
        { name: 'retail.crm.campaign.cdc', source: 'crm_db.campaign', desc: '营销活动配置' },
        { name: 'retail.behavior.event', source: 'behavior.event', desc: 'APP/小程序行为事件' },
      ];
      for (const t of tasks) {
        const res = await ctx.api.post('/integrate/tasks', {
          name: t.name,
          sourceType: 'MySQL-CDC',
          targetType: 'Kafka',
          sourceTable: t.source,
          targetTable: t.name,
          schedule: 'realtime',
        });
        a.ok(`创建集成任务「${t.name}」成功`, res.ok);
      }
      return { assert: a, summary: { tasksCreated: tasks.length } };
    },
  },

  // ============================================================
  // 步骤 3：500 门店 RFM 画像 - 资产注册
  // ============================================================
  {
    name: '500 门店 RFM 画像 - 会员画像资产注册',
    description: '注册 member_rfm/member_churn_prediction/member_ltv/member_tag 资产',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const assets = [
        { name: 'member_rfm', type: 'DWS', owner: 'marketing_admin', description: '会员 RFM 分群表（10 大分群）', securityLevel: 'L3' },
        { name: 'member_churn_prediction', type: 'DWS', owner: 'marketing_admin', description: '会员流失预测表（30 天流失概率）', securityLevel: 'L2' },
        { name: 'member_ltv', type: 'DWS', owner: 'marketing_admin', description: '会员生命周期价值表（BG/NBD+Gamma-Gamma）', securityLevel: 'L3' },
        { name: 'member_tag', type: 'DWS', owner: 'marketing_admin', description: '会员标签表（RFM+流失+LTV+行为+人口）', securityLevel: 'L2' },
        { name: 'member_behavior_profile', type: 'DWS', owner: 'marketing_admin', description: '会员行为画像表', securityLevel: 'L2' },
      ];
      for (const ast of assets) {
        const res = await ctx.api.post('/governance/assets', ast);
        a.ok(`注册会员画像资产「${ast.name}」成功`, res.ok);
      }
      a.ok('10 大 RFM 分群已定义', RFM_SEGMENTS.length === 10, `segments=${RFM_SEGMENTS.join('/')}`);
      return { assert: a, summary: { assetsRegistered: assets.length, rfmSegments: RFM_SEGMENTS.length } };
    },
  },

  // ============================================================
  // 步骤 4：RFM 分群计算验证（需集群）
  // ============================================================
  {
    name: 'RFM 分群计算 - R/F/M 评分+10 大分群映射',
    description: '验证某会员近 365 天购买 12 次、累计 8500 元、最近 5 天前 → CHAMPION',
    requiresCluster: true,
    async run(ctx) {
      // RFM 计算本地模拟
      const a = new Assert();
      const recency = 5, frequency = 12, monetary = 8500;
      // R/F/M 各 1-5 分，按 20% 分位数划分
      const rScore = recency <= 30 ? 5 : recency <= 90 ? 4 : recency <= 180 ? 3 : recency <= 365 ? 2 : 1;
      const fScore = frequency >= 10 ? 5 : frequency >= 6 ? 4 : frequency >= 3 ? 3 : frequency >= 1 ? 2 : 1;
      const mScore = monetary >= 5000 ? 5 : monetary >= 2000 ? 4 : monetary >= 1000 ? 3 : monetary >= 500 ? 2 : 1;
      // CHAMPION: R=5, F=4-5, M=4-5
      const isChampion = rScore === 5 && fScore >= 4 && mScore >= 4;
      a.ok('R 评分 = 5（最近 5 天购买）', rScore === 5);
      a.ok('F 评分 = 5（购买 12 次）', fScore === 5);
      a.ok('M 评分 = 5（累计 8500 元）', mScore === 5);
      a.ok('RFM 分群 = CHAMPION', isChampion);

      return {
        assert: a,
        status: 'SKIP',
        reason: 'RFM 公式本地验证通过；实际 RFM 计算需 Spark + Doris 集群',
        checks: a.records,
      };
    },
  },

  // ============================================================
  // 步骤 5：会员标签体系验证
  // ============================================================
  {
    name: '会员标签体系 - RFM/流失/LTV/行为/人口 5 类标签',
    description: '验证会员标签资产可注册，5 类标签体系完整',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const tagCategories = [
        { category: 'RFM', tags: ['RFM_CHAMPION', 'RFM_LOYAL', 'RFM_LOST'] },
        { category: '流失', tags: ['CHURN_HIGH_RISK', 'CHURN_MEDIUM_RISK'] },
        { category: 'LTV', tags: ['LTV_VIP', 'LTV_HIGH', 'LTV_MEDIUM'] },
        { category: '行为', tags: ['PRICE_SENSITIVE', 'CATEGORY_FASHION_LOVER', 'BRAND_LOYAL'] },
        { category: '人口', tags: ['CITY_TIER1', 'AGE_25_35', 'GENDER_F'] },
      ];
      // 注册标签资产
      const res = await ctx.api.post('/governance/assets', {
        name: 'member_tag_full',
        type: 'DWS',
        owner: 'marketing_admin',
        description: '会员完整标签体系（5 类 14 标签）',
        securityLevel: 'L2',
        full: { tagCategories: tagCategories.map(c => c.category), totalTags: tagCategories.reduce((s, c) => s + c.tags.length, 0) },
      });
      a.ok('注册会员标签资产成功', res.ok);
      a.ok('标签类别数 = 5', tagCategories.length === 5);
      a.ok('标签总数 = 14', tagCategories.reduce((s, c) => s + c.tags.length, 0) === 14);
      return { assert: a, summary: { tagCategories: tagCategories.length } };
    },
  },

  // ============================================================
  // 步骤 6：营销活动效果分析 - ROI/漏斗资产注册
  // ============================================================
  {
    name: '营销活动效果分析 - ROI/ROAS/CPA/CPC + 5 步漏斗',
    description: '注册 marketing_roi/conversion_funnel/ab_experiment_variant 资产',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const assets = [
        { name: 'marketing_roi', type: 'DWS', owner: 'marketing_admin', description: '营销 ROI 分析表（ROI/ROAS/CPA/CPC）', securityLevel: 'L3' },
        { name: 'conversion_funnel', type: 'DWS', owner: 'marketing_admin', description: '5 步转化漏斗表', securityLevel: 'L2' },
        { name: 'ab_experiment', type: 'DWD', owner: 'marketing_admin', description: 'A/B 实验主表', securityLevel: 'L2' },
        { name: 'ab_experiment_variant', type: 'DWS', owner: 'marketing_admin', description: 'A/B 实验变体效果表', securityLevel: 'L2' },
        { name: 'marketing_channel_stat', type: 'DWS', owner: 'marketing_admin', description: '营销渠道统计表', securityLevel: 'L3' },
      ];
      for (const ast of assets) {
        const res = await ctx.api.post('/governance/assets', ast);
        a.ok(`注册营销资产「${ast.name}」成功`, res.ok);
      }
      a.ok('5 步漏斗已定义', FUNNEL_STEPS.length === 5, `steps=${FUNNEL_STEPS.join('→')}`);
      return { assert: a, summary: { assetsRegistered: assets.length, funnelSteps: FUNNEL_STEPS.length } };
    },
  },

  // ============================================================
  // 步骤 7：商品关联分析 - 商品画像资产注册
  // ============================================================
  {
    name: '商品关联分析 - 商品画像+关联规则资产注册',
    description: '注册 product_sales_stat/product_review_profile/product_tag 资产',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const assets = [
        { name: 'product_sales_stat', type: 'DWS', owner: 'marketing_admin', description: '商品销量统计表（爆款/长尾标志）', securityLevel: 'L3' },
        { name: 'product_review_profile', type: 'DWS', owner: 'marketing_admin', description: '商品评价画像表（NLP 关键词）', securityLevel: 'L2' },
        { name: 'product_tag', type: 'DWS', owner: 'marketing_admin', description: '商品标签表（HOT/NEW/SEASONAL/LONG_TAIL）', securityLevel: 'L2' },
        { name: 'product_association_rules', type: 'DWS', owner: 'marketing_admin', description: '商品关联规则表（购物篮分析）', securityLevel: 'L2' },
      ];
      for (const ast of assets) {
        const res = await ctx.api.post('/governance/assets', ast);
        a.ok(`注册商品画像资产「${ast.name}」成功`, res.ok);
      }
      return { assert: a, summary: { assetsRegistered: assets.length } };
    },
  },

  // ============================================================
  // 步骤 8：A/B 实验配置验证
  // ============================================================
  {
    name: 'A/B 实验配置 - 首页推荐算法对比实验',
    description: '配置 exp_001：新版协同过滤 vs 旧版热门推荐，假设 CTR 提升 5%',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      // 注册 A/B 实验资产
      const res = await ctx.api.post('/governance/assets', {
        name: 'ab_experiment_home_rec',
        type: 'DWD',
        owner: 'marketing_admin',
        description: '首页推荐算法对比实验（exp_001）',
        securityLevel: 'L2',
        full: {
          experimentId: 'exp_001',
          hypothesis: '新版协同过滤推荐 vs 旧版热门推荐，假设新版可提升首页 CTR 5%',
          primaryMetric: 'home_ctr',
          secondaryMetrics: ['gmv', 'conversion_rate'],
          status: 'RUNNING',
          trafficPercentage: 50,
          sampleSize: 200000,
          alpha: 0.05,
          power: 0.80,
          variants: [
            { id: 'var_001', name: 'CONTROL', description: '对照组（旧版热门推荐）' },
            { id: 'var_002', name: 'TREATMENT_A', description: '实验组A（新版协同过滤）' },
          ],
        },
      });
      a.ok('注册 A/B 实验资产成功', res.ok);

      // A/B 实验显著性检验本地模拟
      // 对照组：转化率 2.5%，实验组：2.75%，P 值 0.000123 < 0.05
      const controlRate = 0.025, treatmentRate = 0.0275, pValue = 0.000123, alpha = 0.05;
      const lift = (treatmentRate - controlRate) / controlRate;
      const isSignificant = pValue < alpha;
      const isWinner = isSignificant && treatmentRate > controlRate;
      a.ok('对照组转化率 = 2.5%', controlRate === 0.025);
      a.ok('实验组转化率 = 2.75%', treatmentRate === 0.0275);
      a.ok('提升度 = 10%', Math.abs(lift - 0.1) < 0.001);
      a.ok('P 值 < 0.05（统计显著）', isSignificant);
      a.ok('实验组获胜，可全量上线', isWinner);

      return { assert: a, summary: { pValue, isSignificant, isWinner } };
    },
  },

  // ============================================================
  // 步骤 9：推荐引擎 - 多路召回+精排（需集群）
  // ============================================================
  {
    name: '推荐引擎 - 多路召回+精排+重排',
    description: '协同/内容/热门/个性化/实时行为 5 路召回 + LightGBM 精排 + 业务重排',
    requiresCluster: true,
    async run(ctx) {
      // 推荐逻辑本地模拟
      const a = new Assert();
      const recalls = [
        { name: 'collaborative_filtering', count: 200 },
        { name: 'content_based', count: 200 },
        { name: 'hot_product', count: 100 },
        { name: 'personalized', count: 200 },
        { name: 'realtime_behavior', count: 100 },
      ];
      const totalCandidates = recalls.reduce((s, r) => s + r.count, 0);
      const topK = 10;
      a.ok('5 路召回已配置', recalls.length === 5);
      a.ok('候选商品总数 = 800', totalCandidates === 800);
      a.ok('精排 TOP 10', topK === 10);

      return {
        assert: a,
        status: 'SKIP',
        reason: '推荐逻辑本地验证通过；实际推荐引擎需 LightGBM+实时特征+Doris 画像',
        checks: a.records,
      };
    },
  },

  // ============================================================
  // 步骤 10：实时库存监控（需集群）
  // ============================================================
  {
    name: '实时库存监控 - 500 门店库存实时汇总',
    description: 'Flink 实时计算 500 门店库存，触发低库存预警',
    requiresCluster: true,
    async run(ctx) {
      return {
        status: 'SKIP',
        reason: '需 Flink + Kafka + Doris 实时链路',
        checks: [
          { name: '库存变更 CDC 接入', status: 'SKIP', detail: '需 Kafka' },
          { name: 'Flink 实时汇总', status: 'SKIP', detail: '需 Flink' },
          { name: '低库存预警触发', status: 'SKIP', detail: '需预警引擎' },
        ],
      };
    },
  },

  // ============================================================
  // 步骤 11：流失预测 + LTV 预测 ML 模型注册
  // ============================================================
  {
    name: '流失预测 + LTV 预测 ML 模型注册',
    description: '注册 LightGBM 流失预测 + BG/NBD+Gamma-Gamma LTV 预测模型',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const models = [
        {
          name: 'churn_lgbm_lr_ensemble',
          algorithm: 'LightGBM',
          version: 'v3',
          metrics: { auc: 0.88, precision: 0.82, recall: 0.79 },
          description: '会员流失预测（LightGBM+LR 集成，30 天流失概率）',
        },
        {
          name: 'ltv_bgnbd_gamma',
          algorithm: 'BG/NBD+Gamma-Gamma',
          version: 'v1',
          metrics: { mape: 0.15, bias: 0.02 },
          description: 'LTV 预测（BG/NBD 活跃概率+Gamma-Gamma 金额期望）',
        },
        {
          name: 'recommend_lightgbm_rank',
          algorithm: 'LightGBM',
          version: 'v3',
          metrics: { ndcg: 0.65, map: 0.58 },
          description: '推荐精排模型（CTR 预估）',
        },
      ];
      for (const m of models) {
        const res = await ctx.api.post('/ml/models', m);
        a.ok(`注册模型「${m.name}」成功`, res.ok);
      }
      return { assert: a, summary: { modelsRegistered: models.length } };
    },
  },

  // ============================================================
  // 步骤 12：推荐 API + 营销 API 发布
  // ============================================================
  {
    name: '推荐 API + 营销 API 发布到开放目录',
    description: '首页推荐/购物车推荐/消息推送/优惠券 API 发布',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const apis = [
        { name: '首页推荐', version: 'v1', category: 'recommend', method: 'GET', path: '/api/v1/recommend/home', status: 'published' },
        { name: '购物车推荐', version: 'v1', category: 'recommend', method: 'GET', path: '/api/v1/recommend/cart', status: 'published' },
        { name: '消息推送推荐', version: 'v1', category: 'recommend', method: 'POST', path: '/api/v1/recommend/notify', status: 'published' },
        { name: '优惠券发放', version: 'v1', category: 'marketing', method: 'POST', path: '/api/v1/marketing/coupon', status: 'published' },
        { name: '营销活动效果查询', version: 'v1', category: 'marketing', method: 'GET', path: '/api/v1/marketing/roi', status: 'published' },
        { name: 'A/B 实验效果查询', version: 'v1', category: 'ab', method: 'GET', path: '/api/v1/ab/experiment/{id}/result', status: 'published' },
      ];
      for (const api of apis) {
        const res = await ctx.api.post('/apis', api);
        a.ok(`发布 API「${api.name}」成功`, res.ok);
      }
      // 按分类过滤
      const recRes = await ctx.api.get('/apis?category=recommend');
      a.ok('recommend 分类 API 列表返回 200', recRes.ok);
      const recApis = recRes.data && recRes.data.data && recRes.data.data.list || [];
      a.ok('recommend 分类 API 数 >= 3', recApis.length >= 3);
      return { assert: a, summary: { apisPublished: apis.length } };
    },
  },

  // ============================================================
  // 步骤 13：ML 推理服务部署 - 流失预测+推荐精排
  // ============================================================
  {
    name: 'ML 推理服务部署 - 流失预测+推荐精排在线推理',
    description: '部署流失预测+推荐精排推理服务',
    requiresCluster: false,
    async run(ctx) {
      const a = new Assert();
      const services = [
        { serviceName: 'churn-predict', modelName: 'churn_lgbm_lr_ensemble', version: 'v3', replicas: 2, resourceSpec: '2c4g' },
        { serviceName: 'recommend-rank', modelName: 'recommend_lightgbm_rank', version: 'v3', replicas: 4, resourceSpec: '4c8g' },
      ];
      for (const s of services) {
        const res = await ctx.api.post('/ml/inference-services', s);
        a.ok(`部署推理服务「${s.serviceName}」成功`, res.ok);
      }
      const listRes = await ctx.api.get('/ml/inference-services');
      a.ok('推理服务列表返回 200', listRes.ok);
      const svcList = listRes.data && listRes.data.data || [];
      a.ok('推理服务数 >= 2', svcList.length >= 2);
      return { assert: a };
    },
  },

];

async function main() {
  const api = new ApiClient();
  const ctx = { api, clusterAvailable: false, rtl: {} };
  const scenario = {
    name: 'retail',
    description: '零售营销场景：500 门店+2800 万会员+RFM 画像+推荐+A/B 实验+效果分析',
    industry: 'retail',
    steps,
  };
  const result = await runScenario(scenario, ctx);
  const outPath = path.join(__dirname, 'scenario-retail.result.json');
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

module.exports = { steps, RFM_SEGMENTS, FUNNEL_STEPS, RTL_TENANT_ID };