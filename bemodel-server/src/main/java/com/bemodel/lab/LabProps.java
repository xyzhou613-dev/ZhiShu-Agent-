package com.bemodel.lab;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 对比实验室配置面(Plan 1)。A/B 两臂的 LLM 超时与输出上限不在本处——
 * 统一走 deepseek.call-timeouts / call-max-tokens 的 LAB_A/LAB_B 覆盖(F1 既有机制,yml 配置即得,零代码)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "bemodel.lab")
public class LabProps {

    /** 沙箱库名(平台同实例;reset/ensureCloned 有库名守卫,平台库/demo_* /系统库拒绝) */
    private String dbName = "bemodel_lab";

    /** 克隆清单(源库.表名):覆盖四实验(过敏开药/越权改库存/合法退费/关系下钻)所需读写的全部表。
     *  patient_allergy=E1 过敏史(QC ALLERGY_DISJOINT 读 emr 库);dept=E4 科室下钻 */
    private List<String> cloneTables = List.of(
            "demo_his.inpatient", "demo_his.medical_order", "demo_his.fee_detail", "demo_his.staff",
            "demo_his.dept",
            "demo_pharmacy.drug_stock", "demo_pharmacy.drug_dict", "demo_pharmacy.dispense_record",
            "demo_pharmacy.presc_review", "demo_pharmacy.stock_in", "demo_pharmacy.stock_out",
            "demo_charge.refund_apply", "demo_charge.settlement",
            "demo_emr.patient_allergy");

    /** A 臂(AI+本体)步数上限 */
    private int maxStepsA = 12;

    /** B 臂(AI+裸SQL)步数上限(文章口径:B 组试错轨迹更长) */
    private int maxStepsB = 20;

    /** 单臂墙钟预算(秒):超时熔断出部分轨迹,不伪装结论 */
    private int armBudgetSeconds = 90;
}
