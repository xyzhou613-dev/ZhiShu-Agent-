-- =============================================================
-- V2: 模拟三条产品线的物理库（表结构各自为政、口径互相割裂）
-- demo_his(住院HIS) / demo_lis(检验LIS) / demo_charge(收费系统)
-- =============================================================

CREATE DATABASE IF NOT EXISTS demo_his DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS demo_lis DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS demo_charge DEFAULT CHARSET utf8mb4;

-- ---------- demo_his：住院HIS ----------
CREATE TABLE IF NOT EXISTS demo_his.inpatient (
    inhos_no       VARCHAR(32) PRIMARY KEY COMMENT '住院号',
    patient_name   VARCHAR(64) NOT NULL,
    sex            VARCHAR(4),
    age            INT,
    ward           VARCHAR(64) COMMENT '病区',
    dept           VARCHAR(64) COMMENT '科室',
    admit_time     DATETIME COMMENT '入院时间',
    discharge_time DATETIME COMMENT '出院时间',
    status         VARCHAR(8) COMMENT '在院/出院',
    doctor         VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='住院患者';

CREATE TABLE IF NOT EXISTS demo_his.medical_order (
    order_id     VARCHAR(32) PRIMARY KEY COMMENT '医嘱号',
    inhos_no     VARCHAR(32) NOT NULL,
    item_code    VARCHAR(32) COMMENT '项目编码',
    item_name    VARCHAR(128) COMMENT '项目名称',
    order_type   VARCHAR(16) COMMENT '检验/检查/药品',
    order_status VARCHAR(4) COMMENT '0未执行 1已执行 2已取消',
    doctor       VARCHAR(64),
    create_time  DATETIME,
    cancel_time  DATETIME,
    KEY idx_inhos (inhos_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='医嘱';

CREATE TABLE IF NOT EXISTS demo_his.fee_detail (
    fee_id      VARCHAR(32) PRIMARY KEY COMMENT '费用流水号',
    inhos_no    VARCHAR(32) NOT NULL,
    order_id    VARCHAR(32) COMMENT '来源医嘱号',
    item_code   VARCHAR(32),
    item_name   VARCHAR(128),
    amount      DECIMAL(10, 2),
    fee_status  VARCHAR(4) COMMENT '1正常 2已退费',
    charge_time DATETIME,
    KEY idx_order (order_id),
    KEY idx_inhos (inhos_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='费用明细';

CREATE TABLE IF NOT EXISTS demo_his.status_map (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    src_system      VARCHAR(32) COMMENT '来源系统',
    src_status      VARCHAR(8) COMMENT '来源状态码',
    src_status_name VARCHAR(64),
    target_action   VARCHAR(16) COMMENT 'CHARGE计费/REFUND退费/KEEP保持',
    updated_at      DATETIME,
    UNIQUE KEY uk_src (src_system, src_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='计费适配器状态映射表（故障点）';

-- ---------- demo_lis：检验系统 ----------
CREATE TABLE IF NOT EXISTS demo_lis.lab_apply (
    apply_id     VARCHAR(32) PRIMARY KEY COMMENT '申请号',
    order_id     VARCHAR(32) COMMENT 'HIS医嘱号',
    patient_no   VARCHAR(32) COMMENT '患者编号（实际存住院号，口径差异）',
    patient_name VARCHAR(64),
    item_code    VARCHAR(32),
    item_name    VARCHAR(128),
    apply_status VARCHAR(4) COMMENT 'N已采样 P已发布 C已撤销(X为v5.1旧码)',
    apply_time   DATETIME,
    update_time  DATETIME,
    KEY idx_order (order_id),
    KEY idx_patient (patient_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='检验申请';

CREATE TABLE IF NOT EXISTS demo_lis.lab_report (
    report_id     VARCHAR(32) PRIMARY KEY,
    apply_id      VARCHAR(32),
    patient_no    VARCHAR(32),
    item_code     VARCHAR(32),
    result_status VARCHAR(4) COMMENT 'N正常 A异常',
    report_time   DATETIME,
    reporter      VARCHAR(64),
    KEY idx_apply (apply_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='检验报告';

CREATE TABLE IF NOT EXISTS demo_lis.lab_dict_status (
    status_code    VARCHAR(4),
    status_name    VARCHAR(64),
    app_version    VARCHAR(16) COMMENT '引入该状态码的版本',
    effective_date DATE,
    end_date       DATE,
    PRIMARY KEY (status_code, app_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='LIS状态字典（含版本演进）';

-- ---------- demo_charge：收费/结算系统 ----------
CREATE TABLE IF NOT EXISTS demo_charge.settlement (
    settle_id     VARCHAR(32) PRIMARY KEY,
    inhos_no      VARCHAR(32) NOT NULL,
    patient_name  VARCHAR(64),
    total_amount  DECIMAL(10, 2),
    settle_time   DATETIME,
    settle_status VARCHAR(4) COMMENT '1已结算 2已冲红',
    KEY idx_inhos (inhos_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='出院结算';

CREATE TABLE IF NOT EXISTS demo_charge.refund_apply (
    refund_id  VARCHAR(32) PRIMARY KEY,
    inhos_no   VARCHAR(32),
    fee_id     VARCHAR(32),
    amount     DECIMAL(10, 2),
    reason     VARCHAR(256),
    apply_time DATETIME,
    status     VARCHAR(4) COMMENT '1待审 2已退 3驳回'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='退费申请';

CREATE TABLE IF NOT EXISTS demo_charge.charge_audit (
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    fee_id    VARCHAR(32),
    action    VARCHAR(32) COMMENT 'CHARGE/REFUND',
    operator  VARCHAR(64),
    op_time   DATETIME,
    KEY idx_fee (fee_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收费审计流水';
