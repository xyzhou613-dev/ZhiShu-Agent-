-- =============================================================
-- V12: 业务扩展 —— 药房进销存 / 结算管理 / 处方审核闭环 / 数据治理规则
-- =============================================================

-- ---------- 1) 药房进销存（demo_pharmacy，4 张表） ----------
CREATE TABLE IF NOT EXISTS demo_pharmacy.purchase_order (
    po_id VARCHAR(32) PRIMARY KEY COMMENT '采购单号',
    drug_code VARCHAR(32) NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL COMMENT '采购数量',
    unit_price DECIMAL(10,2) NOT NULL,
    supplier VARCHAR(64) NOT NULL COMMENT '供应商',
    status VARCHAR(8) NOT NULL DEFAULT '已入库' COMMENT '在途/已入库',
    create_time DATETIME NOT NULL
) COMMENT '药品采购单';

CREATE TABLE IF NOT EXISTS demo_pharmacy.stock_in (
    in_id VARCHAR(32) PRIMARY KEY COMMENT '入库单号',
    po_id VARCHAR(32) NOT NULL COMMENT '关联采购单',
    drug_code VARCHAR(32) NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    in_time DATETIME NOT NULL,
    operator VARCHAR(32) NOT NULL COMMENT '库管员'
) COMMENT '药品入库单';

CREATE TABLE IF NOT EXISTS demo_pharmacy.stock_out (
    out_id VARCHAR(32) PRIMARY KEY COMMENT '出库单号',
    drug_code VARCHAR(32) NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    out_type VARCHAR(16) NOT NULL COMMENT '发药出库/调拨出库/报损出库',
    ref_id VARCHAR(32) NULL COMMENT '关联单据（发药批次等）',
    out_time DATETIME NOT NULL,
    operator VARCHAR(32) NOT NULL
) COMMENT '药品出库单';

CREATE TABLE IF NOT EXISTS demo_pharmacy.drug_stock (
    drug_code VARCHAR(32) PRIMARY KEY,
    drug_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL COMMENT '当前库存',
    unit VARCHAR(16) NOT NULL DEFAULT '盒',
    warehouse VARCHAR(32) NOT NULL DEFAULT '住院药房',
    updated_at DATETIME NOT NULL
) COMMENT '药品库存（HIS 叫药库、药房叫库房，统一口径：药品库存）';

-- ---------- 2) 处方审核（demo_pharmacy，医嘱闭环的审核环节） ----------
CREATE TABLE IF NOT EXISTS demo_pharmacy.presc_review (
    review_id VARCHAR(32) PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL COMMENT '药品医嘱号',
    inhos_no VARCHAR(32) NOT NULL,
    pharmacist VARCHAR(32) NOT NULL COMMENT '审核药师',
    review_result VARCHAR(8) NOT NULL COMMENT '通过/驳回',
    reject_reason VARCHAR(128) NULL,
    review_time DATETIME NOT NULL,
    KEY idx_review_order (order_id)
) COMMENT '处方审核记录：药品医嘱开立后须经药师审核（闭环审核环节）';

-- ---------- 3) 结算管理（demo_charge，2 张表） ----------
CREATE TABLE IF NOT EXISTS demo_charge.invoice (
    invoice_id VARCHAR(32) PRIMARY KEY COMMENT '发票号',
    settle_id VARCHAR(32) NOT NULL COMMENT '结算单号',
    inhos_no VARCHAR(32) NOT NULL,
    patient_name VARCHAR(32) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    invoice_time DATETIME NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT '已开' COMMENT '已开/作废'
) COMMENT '结算发票';

CREATE TABLE IF NOT EXISTS demo_charge.reconcile_record (
    rec_id VARCHAR(32) PRIMARY KEY,
    rec_date DATE NOT NULL COMMENT '对账日期',
    channel VARCHAR(16) NOT NULL COMMENT '渠道 现金/扫码/医保',
    system_amount DECIMAL(12,2) NOT NULL COMMENT '系统金额',
    channel_amount DECIMAL(12,2) NOT NULL COMMENT '渠道账单金额',
    diff DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '差异',
    status VARCHAR(8) NOT NULL COMMENT '平/不平',
    remark VARCHAR(128) NULL
) COMMENT '收费对账（轧账）记录';

-- ---------- 4) 本体：新概念（进销存 4 + 处方审核 1 + 结算 3 + 运营 2） ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('DRUG_PURCHASE', '药品采购', 'PHARMACY', '药房向供应商发起的药品补货采购，入库后增加库存。', '孙药学', 'PUBLISHED', 1),
('STOCK_IN', '入库单', 'PHARMACY', '采购药品验收入库的单据，入库即增加库存。', '孙药学', 'PUBLISHED', 1),
('STOCK_OUT', '出库单', 'PHARMACY', '药品出库单据：发药出库/调拨出库/报损出库，出库即扣减库存。', '孙药学', 'PUBLISHED', 1),
('DRUG_STOCK', '药品库存', 'PHARMACY', '药品在库房的实时存量。口径：库存=Σ入库-Σ出库。HIS称药库、药房口语称库房，统一为药品库存。', '孙药学', 'PUBLISHED', 1),
('PRESC_REVIEW', '处方审核', 'PHARMACY', '药品医嘱开立后药师的专业审核（剂量/过敏/相互作用），是医嘱闭环的必经环节：审核通过方可调剂发药，驳回须医生调整。', '孙药学', 'PUBLISHED', 1),
('INVOICE', '发票', 'FEE', '结算完成后开具的收费票据，与结算单一一对应。', '陈财务', 'PUBLISHED', 1),
('RECONCILE', '对账', 'FEE', '每日按支付渠道核对系统金额与渠道账单（财务称轧账），差异须当日处理。', '陈财务', 'PUBLISHED', 1),
('REFUND', '退费', 'FEE', '因撤销、多收、客诉等原因对已收费用的退冲处理。', '陈财务', 'PUBLISHED', 1),
('SYS_CHANGE', '系统变更', 'OPS', '产品版本升级、字典调整、接口变更等运维侧变更事件，是跨系统口径漂移的主要来源。', '吴运维', 'PUBLISHED', 1),
('DATA_ISSUE', '数据问题', 'OPS', '数据治理扫描发现的质量问题（主键重复/字典外值/孤立数据/账实不符）。', '吴运维', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('DRUG_PURCHASE', 'po_id', '采购单号', 'STRING', 1, NULL, 1),
('DRUG_PURCHASE', 'drug_code', '药品编码', 'STRING', 0, NULL, 2),
('DRUG_PURCHASE', 'quantity', '采购数量', 'NUMBER', 0, NULL, 3),
('DRUG_PURCHASE', 'supplier', '供应商', 'STRING', 0, NULL, 4),
('STOCK_IN', 'in_id', '入库单号', 'STRING', 1, NULL, 1),
('STOCK_IN', 'po_id', '采购单号', 'STRING', 0, '关联采购', 2),
('STOCK_IN', 'quantity', '入库数量', 'NUMBER', 0, NULL, 3),
('STOCK_OUT', 'out_id', '出库单号', 'STRING', 1, NULL, 1),
('STOCK_OUT', 'out_type', '出库类型', 'ENUM', 0, '发药出库/调拨出库/报损出库', 2),
('STOCK_OUT', 'quantity', '出库数量', 'NUMBER', 0, NULL, 3),
('DRUG_STOCK', 'drug_code', '药品编码', 'STRING', 1, NULL, 1),
('DRUG_STOCK', 'quantity', '库存数量', 'NUMBER', 0, 'Σ入库-Σ出库', 2),
('DRUG_STOCK', 'warehouse', '库房', 'STRING', 0, NULL, 3),
('PRESC_REVIEW', 'review_id', '审核号', 'STRING', 1, NULL, 1),
('PRESC_REVIEW', 'order_id', '医嘱号', 'STRING', 0, '被审核的药品医嘱', 2),
('PRESC_REVIEW', 'review_result', '审核结果', 'ENUM', 0, '通过/驳回', 3),
('PRESC_REVIEW', 'reject_reason', '驳回原因', 'STRING', 0, NULL, 4),
('INVOICE', 'invoice_id', '发票号', 'STRING', 1, NULL, 1),
('INVOICE', 'amount', '开票金额', 'NUMBER', 0, NULL, 2),
('INVOICE', 'status', '发票状态', 'ENUM', 0, '已开/作废', 3),
('RECONCILE', 'rec_id', '对账号', 'STRING', 1, NULL, 1),
('RECONCILE', 'channel', '支付渠道', 'ENUM', 0, '现金/扫码/医保', 2),
('RECONCILE', 'diff', '差异金额', 'NUMBER', 0, '系统金额-渠道金额', 3),
('RECONCILE', 'status', '对账结果', 'ENUM', 0, '平/不平', 4),
('REFUND', 'refund_id', '退费单号', 'STRING', 1, NULL, 1),
('REFUND', 'amount', '退费金额', 'NUMBER', 0, NULL, 2),
('REFUND', 'reason', '退费原因', 'STRING', 0, NULL, 3);

INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('DRUG_PURCHASE', 'STOCK_IN', '验收入库', '采购单到货验收后生成入库单'),
('STOCK_IN', 'DRUG_STOCK', '增加库存', '入库增加库存'),
('STOCK_OUT', 'DRUG_STOCK', '扣减库存', '出库扣减库存'),
('DISPENSE', 'STOCK_OUT', '触发发药出库', '调剂发药触发发药出库'),
('MEDICAL_ORDER', 'PRESC_REVIEW', '经药师审核', '药品医嘱须经药师审核（闭环审核环节）'),
('PRESC_REVIEW', 'DISPENSE', '审核通过方可发药', '审核驳回不得调剂发药'),
('SETTLEMENT', 'INVOICE', '开具发票', '结算完成后开具发票'),
('SETTLEMENT', 'RECONCILE', '参与日对账', '结算流水纳入每日渠道对账'),
('FEE_DETAIL', 'REFUND', '退冲', '费用明细退冲形成退费单'),
('SYS_CHANGE', 'DATA_ISSUE', '可能引入', '变更是口径漂移/数据问题的主要来源');

INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system) VALUES
('药库', 'DRUG_STOCK', 'HIS', 'ALIAS', '平台标准'),
('库房', 'DRUG_STOCK', '药房口语', 'ALIAS', '平台标准'),
('采购单', 'DRUG_PURCHASE', '药库系统', 'ALIAS', '平台标准'),
('轧账', 'RECONCILE', '财务口语', 'ALIAS', '平台标准'),
('票据', 'INVOICE', '收费窗口', 'ALIAS', '平台标准'),
('审方', 'PRESC_REVIEW', '药房口语', 'ALIAS', '平台标准');

-- ---------- 5) 映射（新物理表 ↔ 概念属性） ----------
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_PHARMACY', 'purchase_order', 'po_id', 'DRUG_PURCHASE', 'po_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'purchase_order', 'drug_code', 'DRUG_PURCHASE', 'drug_code', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'purchase_order', 'drug_name', 'DRUG_PURCHASE', 'drug_code', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'purchase_order', 'quantity', 'DRUG_PURCHASE', 'quantity', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'purchase_order', 'supplier', 'DRUG_PURCHASE', 'supplier', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'stock_in', 'in_id', 'STOCK_IN', 'in_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'stock_in', 'po_id', 'STOCK_IN', 'po_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'stock_in', 'quantity', 'STOCK_IN', 'quantity', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'stock_out', 'out_id', 'STOCK_OUT', 'out_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'stock_out', 'out_type', 'STOCK_OUT', 'out_type', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'stock_out', 'quantity', 'STOCK_OUT', 'quantity', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'drug_stock', 'drug_code', 'DRUG_STOCK', 'drug_code', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'drug_stock', 'drug_name', 'DRUG_STOCK', 'drug_code', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'drug_stock', 'quantity', 'DRUG_STOCK', 'quantity', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'drug_stock', 'warehouse', 'DRUG_STOCK', 'warehouse', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'presc_review', 'review_id', 'PRESC_REVIEW', 'review_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'presc_review', 'order_id', 'PRESC_REVIEW', 'order_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'presc_review', 'order_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'presc_review', 'review_result', 'PRESC_REVIEW', 'review_result', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'presc_review', 'reject_reason', 'PRESC_REVIEW', 'reject_reason', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'invoice', 'invoice_id', 'INVOICE', 'invoice_id', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'invoice', 'settle_id', 'SETTLEMENT', 'settle_id', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'invoice', 'amount', 'INVOICE', 'amount', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'invoice', 'status', 'INVOICE', 'status', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'reconcile_record', 'rec_id', 'RECONCILE', 'rec_id', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'reconcile_record', 'channel', 'RECONCILE', 'channel', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'reconcile_record', 'diff', 'RECONCILE', 'diff', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'reconcile_record', 'status', 'RECONCILE', 'status', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'refund_apply', 'refund_id', 'REFUND', 'refund_id', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'refund_apply', 'amount', 'REFUND', 'amount', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'refund_apply', 'reason', 'REFUND', 'reason', NULL, 1, 'MANUAL');

-- ---------- 6) 数据治理规则表 + 种子规则 ----------
CREATE TABLE IF NOT EXISTS bm_gov_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_code VARCHAR(32) NOT NULL UNIQUE,
    rule_name VARCHAR(64) NOT NULL,
    rule_type VARCHAR(24) NOT NULL COMMENT 'PK_UNIQUE/NOT_NULL/DICT_CONSISTENT/REF_INTACT/STOCK_BALANCE',
    concept_code VARCHAR(32) NOT NULL COMMENT '挂在哪个概念上（治理规则是本体产物）',
    severity VARCHAR(8) NOT NULL DEFAULT '中',
    expr_json TEXT NOT NULL COMMENT '规则表达式（编译为探针SQL）',
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) COMMENT '数据治理规则：挂接概念、经映射编译为真实SQL探针';

INSERT INTO bm_gov_rule (rule_code, rule_name, rule_type, concept_code, severity, expr_json) VALUES
('GOV-001', '医嘱主键零重复', 'PK_UNIQUE', 'MEDICAL_ORDER', '高',
 '{"type":"PK_UNIQUE","ds":"DS_HIS","table":"medical_order","column":"order_id"}'),
('GOV-002', '患者主索引唯一', 'PK_UNIQUE', 'PATIENT', '高',
 '{"type":"PK_UNIQUE","ds":"DS_HIS","table":"inpatient","column":"inhos_no"}'),
('GOV-003', '患者关键信息完整', 'NOT_NULL', 'PATIENT', '中',
 '{"type":"NOT_NULL","ds":"DS_HIS","table":"inpatient","columns":["inhos_no","patient_name","sex"]}'),
('GOV-004', '检验状态字典跨库一致', 'DICT_CONSISTENT', 'LAB_APPLY', '高',
 '{"type":"DICT_CONSISTENT","ds":"DS_LIS","table":"lab_apply","column":"apply_status","dictDs":"DS_HIS","dictTable":"status_map","dictColumn":"src_status","dictFilter":{"src_system":"LIS"}}'),
('GOV-005', '费用必须有来源医嘱', 'REF_INTACT', 'FEE_DETAIL', '高',
 '{"type":"REF_INTACT","ds":"DS_HIS","table":"fee_detail","column":"order_id","refDs":"DS_HIS","refTable":"medical_order","refColumn":"order_id"}'),
('GOV-006', '报告必须有申请单', 'REF_INTACT', 'LAB_REPORT', '高',
 '{"type":"REF_INTACT","ds":"DS_LIS","table":"lab_report","column":"apply_id","refDs":"DS_LIS","refTable":"lab_apply","refColumn":"apply_id"}'),
('GOV-007', '药品库存账实相符', 'STOCK_BALANCE', 'DRUG_STOCK', '中',
 '{"type":"STOCK_BALANCE","ds":"DS_PHARMACY","stockTable":"drug_stock","inTable":"stock_in","outTable":"stock_out","keyColumn":"drug_code","qtyColumn":"quantity"}'),
('GOV-008', '发药必须关联医嘱', 'NOT_NULL', 'DISPENSE', '中',
 '{"type":"NOT_NULL","ds":"DS_PHARMACY","table":"dispense_record","columns":["order_id","dispense_id"]}'),
('GOV-009', '结算单一号一结', 'PK_UNIQUE', 'SETTLEMENT', '中',
 '{"type":"PK_UNIQUE","ds":"DS_CHARGE","table":"settlement","column":"settle_id"}'),
('GOV-010', '审核记录必须关联医嘱', 'REF_INTACT', 'PRESC_REVIEW', '高',
 '{"type":"REF_INTACT","ds":"DS_PHARMACY","table":"presc_review","column":"order_id","refDs":"DS_HIS","refTable":"medical_order","refColumn":"order_id"}');

-- ---------- 7) 治理扫描与问题表 ----------
CREATE TABLE IF NOT EXISTS bm_gov_scan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scan_time DATETIME NOT NULL,
    duration_ms BIGINT NOT NULL COMMENT '实测耗时（毫秒）',
    rule_count INT NOT NULL,
    issue_count INT NOT NULL,
    quality_score INT NOT NULL COMMENT '质量分 0-100（按规则严重度加权通过率）'
) COMMENT '治理扫描执行记录';

CREATE TABLE IF NOT EXISTS bm_gov_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scan_id BIGINT NOT NULL COMMENT '所属扫描',
    rule_code VARCHAR(32) NOT NULL,
    rule_name VARCHAR(64) NOT NULL,
    rule_type VARCHAR(24) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    concept_code VARCHAR(32) NOT NULL,
    ds_code VARCHAR(64) NOT NULL,
    table_name VARCHAR(128) NOT NULL,
    hit_count INT NOT NULL COMMENT '命中行数',
    sample_json TEXT COMMENT '样例行（最多5条）',
    status VARCHAR(8) NOT NULL DEFAULT '未处理' COMMENT '未处理/已处理',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_issue_scan (scan_id)
) COMMENT '治理扫描发现的数据问题';
