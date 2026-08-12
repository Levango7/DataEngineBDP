-- =============================================================================
-- File   : product_profile_ddl.sql
-- Domain : 商品画像域 (Product Profile)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : T038 零售行业模板 - 商品画像
-- Tables : product / product_category / product_brand / product_sales_stat /
--          product_review_profile / product_tag (6 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. product : 商品基本信息主表
--    业务含义：SKU 商品主数据，含属性/类目/品牌等基础信息
--    数据分级：L2 (内部业务)
--    分区策略：按 created_at 日期动态分区
--    外键关系：category_id -> product_category.category_id（弱关联）
--             brand_id    -> product_brand.brand_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product (
    product_id      VARCHAR(64)   NOT NULL                COMMENT '商品ID（业务主键，雪花ID）',
    product_sku     VARCHAR(64)   NOT NULL                COMMENT '商品 SKU 编码（唯一）',
    product_name    VARCHAR(256)  NOT NULL                COMMENT '商品名称',
    category_id     VARCHAR(64)                           COMMENT '类目ID（外键 -> product_category.category_id）',
    brand_id        VARCHAR(64)                           COMMENT '品牌ID（外键 -> product_brand.brand_id）',
    product_type    VARCHAR(32)                           COMMENT '商品类型：PHYSICAL-实物 / VIRTUAL-虚拟 / SERVICE-服务 / BUNDLE-组合',
    weight          DECIMAL(10,3)                         COMMENT '重量（kg）',
    volume          DECIMAL(10,3)                         COMMENT '体积（m³）',
    cost_price      DECIMAL(18,2)                         COMMENT '成本价（L2 财务敏感）',
    retail_price    DECIMAL(18,2)                         COMMENT '零售价（吊牌价）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ON_SALE' COMMENT '商品状态：ON_SALE-在售 / OFF_SHELF-下架 / OUT_OF_STOCK-缺货 / DISCONTINUED-停售',
    shelf_at        DATETIME                              COMMENT '上架时间',
    off_shelf_at    DATETIME                              COMMENT '下架时间',
    attributes      STRING                                COMMENT '商品属性 JSON（颜色/尺码/材质/产地等扩展属性）',
    tags            STRING                                COMMENT '商品标签 JSON 数组（如 [\"新品\",\"爆款\",\"限时折扣\"]）',
    created_at      DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (product_id, created_at)
COMMENT '商品基本信息主表 | 数据分级=L2 | SKU 商品主数据'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (product_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  product               IS '商品基本信息主表 | 数据分级=L2 | SKU 商品主数据';
COMMENT ON COLUMN product.product_id    IS '商品ID（业务主键）';
COMMENT ON COLUMN product.product_sku   IS '商品 SKU 编码（唯一）';
COMMENT ON COLUMN product.product_name  IS '商品名称';
COMMENT ON COLUMN product.category_id   IS '类目ID（外键 -> product_category.category_id）';
COMMENT ON COLUMN product.brand_id      IS '品牌ID（外键 -> product_brand.brand_id）';
COMMENT ON COLUMN product.product_type  IS '商品类型：PHYSICAL/VIRTUAL/SERVICE/BUNDLE';
COMMENT ON COLUMN product.cost_price    IS '成本价（L2 财务敏感）';
COMMENT ON COLUMN product.retail_price  IS '零售价（吊牌价）';
COMMENT ON COLUMN product.status        IS '商品状态：ON_SALE/OFF_SHELF/OUT_OF_STOCK/DISCONTINUED';
COMMENT ON COLUMN product.attributes    IS '商品属性 JSON（颜色/尺码/材质/产地等）';

-- -----------------------------------------------------------------------------
-- 2. product_category : 商品类目表
--    业务含义：多级类目体系（一级/二级/三级），支持类目树查询
--    数据分级：L2 (内部业务)
--    分区策略：无（类目数据量小，单分区即可）
--    外键关系：parent_category_id -> product_category.category_id（自关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_category (
    category_id       VARCHAR(64)   NOT NULL              COMMENT '类目ID（业务主键）',
    category_code     VARCHAR(64)   NOT NULL              COMMENT '类目编码（唯一）',
    category_name     VARCHAR(128)  NOT NULL              COMMENT '类目名称',
    category_level    INT           NOT NULL              COMMENT '类目层级：1-一级 / 2-二级 / 3-三级',
    parent_category_id VARCHAR(64)                        COMMENT '父类目ID（自关联，根类目为 NULL）',
    category_path     VARCHAR(512)                        COMMENT '类目全路径（如 服饰/男装/衬衫）',
    sort_order        INT                                 COMMENT '同级排序',
    is_leaf           BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '是否叶子类目（最底层）',
    status            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '类目状态：ACTIVE-启用 / INACTIVE-停用',
    created_at        DATETIME      NOT NULL              COMMENT '创建时间',
    updated_at        DATETIME      NOT NULL              COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (category_id)
COMMENT '商品类目表 | 数据分级=L2 | 多级类目体系（一级/二级/三级）'
DISTRIBUTED BY HASH (category_id) BUCKETS 4
PROPERTIES ('replication_num' = '3');
COMMENT ON TABLE  product_category                  IS '商品类目表 | 多级类目体系';
COMMENT ON COLUMN product_category.category_id      IS '类目ID（业务主键）';
COMMENT ON COLUMN product_category.category_level   IS '类目层级：1/2/3';
COMMENT ON COLUMN product_category.parent_category_id IS '父类目ID（自关联）';
COMMENT ON COLUMN product_category.category_path    IS '类目全路径';

-- -----------------------------------------------------------------------------
-- 3. product_brand : 商品品牌表
--    业务含义：品牌基础信息，含品牌等级、产地等
--    数据分级：L2 (内部业务)
--    分区策略：无
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_brand (
    brand_id         VARCHAR(64)   NOT NULL              COMMENT '品牌ID（业务主键）',
    brand_code       VARCHAR(64)   NOT NULL              COMMENT '品牌编码（唯一）',
    brand_name       VARCHAR(128)  NOT NULL              COMMENT '品牌名称',
    brand_name_en    VARCHAR(128)                        COMMENT '品牌英文名',
    brand_logo       VARCHAR(512)                        COMMENT '品牌 Logo URL',
    brand_level      VARCHAR(16)                         COMMENT '品牌等级：LUXURY-奢侈 / HIGH-高端 / MID-中端 / LOW-低端 / WHITE_LABEL-白牌',
    origin_country   VARCHAR(64)                         COMMENT '品牌原产国/地区',
    established_year INT                                 COMMENT '品牌创立年份',
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '品牌状态：ACTIVE-启用 / INACTIVE-停用',
    created_at       DATETIME      NOT NULL              COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL              COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (brand_id)
COMMENT '商品品牌表 | 数据分级=L2 | 品牌基础信息'
DISTRIBUTED BY HASH (brand_id) BUCKETS 4
PROPERTIES ('replication_num' = '3');
COMMENT ON TABLE  product_brand              IS '商品品牌表 | 品牌基础信息';
COMMENT ON COLUMN product_brand.brand_id     IS '品牌ID（业务主键）';
COMMENT ON COLUMN product_brand.brand_level  IS '品牌等级：LUXURY/HIGH/MID/LOW/WHITE_LABEL';
COMMENT ON COLUMN product_brand.origin_country IS '品牌原产国/地区';

-- -----------------------------------------------------------------------------
-- 4. product_sales_stat : 商品销量统计表
--    业务含义：商品销量画像指标，含销量/销售额/客单价/复购率等
--    数据分级：L3 (销售额 财务敏感)
--    分区策略：按 stat_date 日期动态分区（按日快照）
--    外键关系：product_id -> product.product_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_sales_stat (
    stat_id            VARCHAR(64)   NOT NULL            COMMENT '统计记录ID（业务主键）',
    product_id         VARCHAR(64)   NOT NULL            COMMENT '商品ID（外键 -> product.product_id）',
    stat_date          DATE          NOT NULL            COMMENT '统计日期',
    stat_period        VARCHAR(16)   NOT NULL            COMMENT '统计周期：DAILY-日 / WEEKLY-周 / MONTHLY-月 / QUARTERLY-季 / YEARLY-年',
    sales_qty          INT           NOT NULL DEFAULT 0  COMMENT '销量（件数）',
    sales_amount       DECIMAL(18,2) NOT NULL DEFAULT 0  COMMENT '销售额（L3 财务敏感）',
    refund_qty         INT                                COMMENT '退货件数',
    refund_amount      DECIMAL(18,2)                     COMMENT '退货金额（L3 财务敏感）',
    net_sales_amount   DECIMAL(18,2)                     COMMENT '净销售额 = 销售额 - 退货金额（L3 财务敏感）',
    order_count        INT                                COMMENT '订单数',
    buyer_count        INT                                COMMENT '购买人数（去重）',
    avg_unit_price     DECIMAL(18,2)                     COMMENT '平均客单价（L3 财务敏感）',
    repurchase_rate    DECIMAL(5,4)                      COMMENT '复购率（0~1）',
    sales_rank         INT                                COMMENT '销量排名（类目内）',
    is_hot_product     BOOLEAN                           COMMENT '是否爆款（销量 TOP 10%）',
    is_long_tail       BOOLEAN                           COMMENT '是否长尾商品（销量后 30%）',
    computed_at        DATETIME      NOT NULL            COMMENT '统计计算时间',
    created_at         DATETIME      NOT NULL            COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_date)
COMMENT '商品销量统计表 | 数据分级=L3 | 销量/销售额/客单价/复购率等画像指标'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (product_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  product_sales_stat                  IS '商品销量统计表 | 销量画像指标';
COMMENT ON COLUMN product_sales_stat.product_id       IS '商品ID（外键 -> product.product_id）';
COMMENT ON COLUMN product_sales_stat.sales_amount     IS '销售额（L3 财务敏感）';
COMMENT ON COLUMN product_sales_stat.net_sales_amount IS '净销售额（L3 财务敏感）';
COMMENT ON COLUMN product_sales_stat.repurchase_rate  IS '复购率（0~1）';
COMMENT ON COLUMN product_sales_stat.is_hot_product   IS '是否爆款（销量 TOP 10%）';
COMMENT ON COLUMN product_sales_stat.is_long_tail     IS '是否长尾商品';

-- -----------------------------------------------------------------------------
-- 5. product_review_profile : 商品评价画像表
--    业务含义：商品评价画像，含评分分布/评价关键词/满意度等
--    数据分级：L2 (内部业务)
--    分区策略：按 stat_date 日期动态分区
--    外键关系：product_id -> product.product_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_review_profile (
    profile_id           VARCHAR(64)   NOT NULL          COMMENT '画像记录ID（业务主键）',
    product_id           VARCHAR(64)   NOT NULL          COMMENT '商品ID（外键 -> product.product_id）',
    stat_date            DATE          NOT NULL          COMMENT '统计日期',
    review_count         INT           NOT NULL DEFAULT 0 COMMENT '评价总数',
    avg_score            DECIMAL(3,2)                    COMMENT '平均评分（0~5）',
    five_star_count      INT                             COMMENT '5 星评价数',
    four_star_count      INT                             COMMENT '4 星评价数',
    three_star_count     INT                             COMMENT '3 星评价数',
    two_star_count       INT                             COMMENT '2 星评价数',
    one_star_count       INT                             COMMENT '1 星评价数',
    positive_rate        DECIMAL(5,4)                    COMMENT '好评率（4~5 星占比，0~1）',
    neutral_rate         DECIMAL(5,4)                    COMMENT '中评率（3 星占比，0~1）',
    negative_rate        DECIMAL(5,4)                    COMMENT '差评率（1~2 星占比，0~1）',
    has_image_rate       DECIMAL(5,4)                    COMMENT '带图评价占比（0~1）',
    keywords             STRING                          COMMENT '评价关键词 JSON 数组（如 [{\"word\":\"质量好\",\"weight\":0.8}]）',
    satisfaction_score   DECIMAL(5,4)                    COMMENT '综合满意度评分（0~1，加权计算）',
    computed_at          DATETIME      NOT NULL          COMMENT '画像计算时间',
    created_at           DATETIME      NOT NULL          COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (profile_id, stat_date)
COMMENT '商品评价画像表 | 数据分级=L2 | 评分分布/评价关键词/满意度'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (product_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  product_review_profile                    IS '商品评价画像表 | 评分分布/评价关键词/满意度';
COMMENT ON COLUMN product_review_profile.avg_score          IS '平均评分（0~5）';
COMMENT ON COLUMN product_review_profile.positive_rate      IS '好评率（4~5 星占比）';
COMMENT ON COLUMN product_review_profile.negative_rate      IS '差评率（1~2 星占比）';
COMMENT ON COLUMN product_review_profile.keywords           IS '评价关键词 JSON 数组';
COMMENT ON COLUMN product_review_profile.satisfaction_score IS '综合满意度评分（0~1）';

-- -----------------------------------------------------------------------------
-- 6. product_tag : 商品标签表
--    业务含义：商品业务标签（爆款/新品/季节性/品类偏好等），由标签引擎计算
--    数据分级：L2 (内部业务)
--    分区策略：按 tagged_at 日期动态分区
--    外键关系：product_id -> product.product_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_tag (
    tag_id          VARCHAR(64)   NOT NULL                COMMENT '标签记录ID（业务主键）',
    product_id      VARCHAR(64)   NOT NULL                COMMENT '商品ID（外键 -> product.product_id）',
    tag_code        VARCHAR(64)   NOT NULL                COMMENT '标签编码（如 HOT_PRODUCT / NEW_ARRIVAL / SEASONAL / LONG_TAIL）',
    tag_value       VARCHAR(128)                          COMMENT '标签值（L2 内部业务标签）',
    tag_category    VARCHAR(32)   NOT NULL                COMMENT '标签分类：SALES-销量 / REVIEW-评价 / SEASON-季节 / CATEGORY-品类 / LIFECYCLE-生命周期',
    tag_source      VARCHAR(16)   NOT NULL                COMMENT '标签来源：MANUAL-人工 / RULE-规则 / MODEL-模型 / IMPORT-导入',
    confidence      DECIMAL(5,4)           DEFAULT 1.0000 COMMENT '标签置信度（0~1，模型打标时输出）',
    expire_at       DATETIME                              COMMENT '标签过期时间',
    tagged_at       DATETIME      NOT NULL                COMMENT '打标时间',
    created_at      DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (tag_id, tagged_at)
COMMENT '商品标签表 | 数据分级=L2 | 商品业务标签 | 外键：product_id -> product.product_id'
PARTITION BY RANGE (tagged_at) ()
DISTRIBUTED BY HASH (product_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  product_tag              IS '商品标签表 | 商品业务标签 | 外键：product_id -> product.product_id';
COMMENT ON COLUMN product_tag.tag_code     IS '标签编码（HOT_PRODUCT/NEW_ARRIVAL/SEASONAL/LONG_TAIL）';
COMMENT ON COLUMN product_tag.tag_category IS '标签分类：SALES/REVIEW/SEASON/CATEGORY/LIFECYCLE';
COMMENT ON COLUMN product_tag.tag_source   IS '标签来源：MANUAL/RULE/MODEL/IMPORT';
COMMENT ON COLUMN product_tag.confidence   IS '标签置信度（0~1）';

-- =============================================================================
-- 商品画像域 DDL 完成：6 张表
-- product / product_category / product_brand / product_sales_stat /
-- product_review_profile / product_tag
-- =============================================================================