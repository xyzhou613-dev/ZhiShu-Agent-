<template>
  <div class="page flow-page">
    <!-- 左栏：患者列表 -->
    <div class="patient-panel">
      <el-card>
        <el-radio-group v-model="mode" class="mode-switch" @change="onModeChange">
          <el-radio-button value="INP">住院</el-radio-button>
          <el-radio-button value="OPD">门诊</el-radio-button>
        </el-radio-group>
        <div class="search-row">
          <el-input
            v-model="keyword"
            :placeholder="mode === 'INP' ? '输入姓名 / 住院号搜索' : '输入姓名 / 就诊卡号搜索'"
            clearable
            :prefix-icon="Search"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-button type="primary" @click="onSearch">查询</el-button>
        </div>
        <div class="patient-list" v-loading="mode === 'INP' ? loadingPatients : loadingOpdPatients">
          <template v-if="mode === 'INP'">
            <div
              v-for="p in patients"
              :key="p.inhos_no"
              class="patient-item"
              :class="{ active: current?.inhos_no === p.inhos_no }"
              @click="selectPatient(p)"
            >
              <div class="patient-line1">
                <span class="patient-name">{{ p.patient_name }}</span>
                <el-tag size="small" :type="p.status === '出院' ? 'success' : 'primary'">
                  {{ p.status }}
                </el-tag>
              </div>
              <div class="patient-line2">{{ p.inhos_no }}</div>
              <div class="patient-line3">{{ p.dept }} / {{ p.ward }} · {{ p.doctor }}</div>
              <div class="patient-line4">医嘱 {{ p.orderCount }} 条 · 费用 ¥{{ p.feeTotal }}</div>
            </div>
            <el-empty v-if="!patients.length" description="未找到患者" :image-size="50" />
          </template>
          <template v-else>
            <div
              v-for="p in opdPatients"
              :key="p.pat_card_no"
              class="patient-item"
              :class="{ active: opdCurrent?.pat_card_no === p.pat_card_no }"
              @click="selectOpdPatient(p)"
            >
              <div class="patient-line1">
                <span class="patient-name">{{ p.pat_name }}</span>
                <el-tag size="small" :type="p.regStatusName === '已退号' ? 'info' : 'success'">
                  {{ p.regStatusName }}
                </el-tag>
              </div>
              <div class="patient-line2">{{ p.pat_card_no }}</div>
              <div class="patient-line3">{{ p.reg_dept }} · {{ p.reg_doctor }}</div>
              <div class="patient-line4">处方 {{ p.prescCount }} 条 · 已执行 ¥{{ p.execTotal }}</div>
            </div>
            <el-empty v-if="!opdPatients.length" description="未找到患者" :image-size="50" />
          </template>
        </div>
        <el-pagination
          v-if="(mode === 'INP' ? inpTotal : opdTotal) > 0"
          class="patient-pager"
          small
          layout="total, prev, pager, next"
          :current-page="mode === 'INP' ? inpPage : opdPage"
          :page-size="pageSize"
          :total="mode === 'INP' ? inpTotal : opdTotal"
          @current-change="onPageChange"
        />
      </el-card>
    </div>

    <!-- 右栏：住院闭环看板 -->
    <div class="loop-panel" v-loading="mode === 'INP' ? loadingLoop : loadingOpdLoop">
      <template v-if="mode === 'INP'">
        <template v-if="loop">
          <!-- 1. 就诊信息 -->
          <el-card>
            <template #header>
              <div class="card-header">
                <span>就诊信息</span>
                <el-button size="small" @click="exportPatientRdf(current.inhos_no)">
                  导出该患者ABox
                </el-button>
              </div>
            </template>
            <div class="visit-row">
              <el-descriptions :column="3" border class="visit-desc">
                <el-descriptions-item label="姓名">{{ loop.visit.patient_name }}</el-descriptions-item>
                <el-descriptions-item label="性别">{{ loop.visit.sex }}</el-descriptions-item>
                <el-descriptions-item label="年龄">{{ loop.visit.age }}</el-descriptions-item>
                <el-descriptions-item label="科室">{{ loop.visit.dept }}</el-descriptions-item>
                <el-descriptions-item label="病区">{{ loop.visit.ward }}</el-descriptions-item>
                <el-descriptions-item label="主管医生">
                  <el-link type="primary" :underline="false" @click="openStaff(loop.visit.doctor)">
                    {{ loop.visit.doctor }}
                  </el-link>
                </el-descriptions-item>
                <el-descriptions-item label="入院时间">{{ loop.visit.admit_time }}</el-descriptions-item>
                <el-descriptions-item label="出院时间">{{ loop.visit.discharge_time || '-' }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                  <el-tag size="small" :type="loop.visit.status === '出院' ? 'success' : 'primary'">
                    {{ loop.visit.status }}
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>
              <div class="fee-stats">
                <div class="fee-stat">
                  <div class="fee-stat-label">费用合计</div>
                  <div class="fee-stat-value">¥{{ fmtMoney(current?.feeTotal) }}</div>
                </div>
                <div class="fee-stat">
                  <div class="fee-stat-label">已缴金额</div>
                  <div class="fee-stat-value">¥{{ fmtMoney(paidTotal) }}</div>
                </div>
                <div class="fee-stat">
                  <div class="fee-stat-label">结算总额</div>
                  <div class="fee-stat-value">
                    {{ loop.settlement ? '¥' + fmtMoney(loop.settlement.total_amount) : '未结算' }}
                  </div>
                </div>
              </div>
            </div>
          </el-card>

          <!-- 2. 医嘱闭环 -->
          <el-card style="margin-top: 16px">
            <template #header><span>医嘱闭环（{{ loop.orders.length }} 条）</span></template>
            <el-table :data="loop.orders" size="small">
              <el-table-column prop="orderId" label="医嘱号" width="150" />
              <el-table-column label="项目" min-width="180">
                <template #default="{ row }">
                  {{ row.itemName }}
                  <el-tag
                    size="small"
                    effect="plain"
                    :type="orderTypeTag(row.orderType)"
                    style="margin-left: 4px"
                  >{{ row.orderType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="医生" width="90">
                <template #default="{ row }">
                  <el-link type="primary" :underline="false" @click="openStaff(row.doctor)">
                    {{ row.doctor }}
                  </el-link>
                </template>
              </el-table-column>
              <el-table-column prop="createTime" label="开立时间" width="160" />
              <el-table-column label="计费" width="130">
                <template #default="{ row }">
                  <template v-if="row.fee">¥{{ row.fee.amount }} {{ row.fee.feeStatusName }}</template>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="执行环节" width="150">
                <template #default="{ row }">
                  <template v-if="row.orderType === '检查' && row.examReport">
                    <el-tooltip
                      :content="`${row.examReport.conclusion}（审核：${row.examReport.reviewer}）`"
                      placement="top"
                    >
                      <span :class="{ 'abnormal-text': row.examReport.abnormalName === '异常' }">
                        报告 {{ row.examReport.abnormalName }}
                      </span>
                    </el-tooltip>
                  </template>
                  <span v-else>{{ execStage(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="药师审核" width="200">
                <template #default="{ row }">
                  <template v-if="row.prescReview">
                    <div class="review-line">
                      <el-tag
                        size="small"
                        :type="row.prescReview.review_result === '通过' ? 'success' : 'danger'"
                      >
                        {{ row.prescReview.review_result === '通过' ? '审核通过' : '审核驳回' }}
                      </el-tag>
                      <el-link
                        type="primary"
                        :underline="false"
                        class="review-pharmacist"
                        @click="openStaff(row.prescReview.pharmacist)"
                      >{{ row.prescReview.pharmacist }}</el-link>
                    </div>
                    <div class="review-meta">{{ row.prescReview.review_time }}</div>
                    <div v-if="row.prescReview.review_result === '驳回'" class="reject-reason">
                      {{ row.prescReview.reject_reason }}
                    </div>
                  </template>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="护士执行确认" width="200">
                <template #default="{ row }">
                  <template v-if="row.nurseExec">
                    <div class="nurse-exec-line">
                      {{ row.nurseExec.exec_type }}
                      <el-tag size="small" type="success" effect="plain" style="margin-left: 4px">
                        {{ row.nurseExec.execStatusName }}
                      </el-tag>
                    </div>
                    <div class="nurse-exec-meta">
                      {{ row.nurseExec.exec_time }} ·
                      <el-link
                        type="primary"
                        :underline="false"
                        @click="openStaff(row.nurseExec.nurse)"
                      >{{ row.nurseExec.nurse }}</el-link>
                    </div>
                  </template>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="闭环状态" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="loopTagType(row.loopStatus)">{{ row.loopStatusName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button
                    v-if="row.loopStatus === 'BROKEN'"
                    link
                    type="danger"
                    @click="goRca"
                  >根因分析</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <!-- 3. 缴费与结算 -->
          <el-card style="margin-top: 16px">
            <template #header><span>缴费与结算</span></template>
            <el-row :gutter="16">
              <el-col :span="15">
                <el-table :data="loop.payments" size="small">
                  <el-table-column prop="pay_id" label="流水号" width="160" />
                  <el-table-column label="类型" width="100">
                    <template #default="{ row }">
                      <el-tag size="small" :type="payTypeTag(row.payTypeName)">{{ row.payTypeName }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="金额" width="100">
                    <template #default="{ row }">¥{{ row.amount }}</template>
                  </el-table-column>
                  <el-table-column prop="channel" label="渠道" width="80" />
                  <el-table-column prop="pay_time" label="时间" width="160" />
                  <el-table-column prop="operator" label="收费员" />
                </el-table>
                <el-empty v-if="!loop.payments.length" description="无缴费记录" :image-size="50" />
              </el-col>
              <el-col :span="9">
                <el-card shadow="never" class="settle-card">
                  <template v-if="loop.settlement">
                    <div class="settle-title">结算单 {{ loop.settlement.settle_id }}</div>
                    <div class="settle-amount">¥{{ fmtMoney(loop.settlement.total_amount) }}</div>
                    <div class="settle-meta">结算时间：{{ loop.settlement.settle_time }}</div>
                    <el-tag size="small" type="success" style="margin-top: 8px">已结算</el-tag>
                  </template>
                  <el-empty v-else description="未结算" :image-size="50" />
                </el-card>
              </el-col>
            </el-row>
          </el-card>

          <!-- 4. 全流程时间线 -->
          <el-card style="margin-top: 16px">
            <template #header>
              <div class="card-header">
                <span>全流程时间线（{{ loop.timeline.length }} 个事件）</span>
                <el-button v-if="loop.timeline.length > 10" size="small" @click="expanded = !expanded">
                  {{ expanded ? '收起' : `展开全部（${loop.timeline.length}）` }}
                </el-button>
              </div>
            </template>
            <div class="tl2">
              <div v-for="(t, i) in displayTimeline" :key="i" class="tl2-item">
                <div class="tl2-rail"><span class="tl2-dot" :class="systemTagClass(t.system)" /></div>
                <div class="tl2-body">
                  <div class="tl2-head">
                    <span class="tl2-time">{{ fmtTime(t.time) }}</span>
                    <el-tag size="small" class="sys-tag" :class="systemTagClass(t.system)">
                      {{ t.system }}
                    </el-tag>
                    <b class="tl-event" :class="{ 'tl-event-reject': isRejectEvent(t.event) }">
                      {{ t.event }}
                    </b>
                  </div>
                  <div v-if="t.detail" class="tl2-detail">
                    <template v-for="(seg, j) in detailSegments(t)" :key="j">
                      <el-link
                        v-if="seg.link"
                        type="primary"
                        :underline="false"
                        class="tl2-name"
                        @click="openStaff(seg.text)"
                      >{{ seg.text }}</el-link>
                      <span v-else>{{ seg.text }}</span>
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </el-card>
        </template>
        <el-card v-else>
          <el-empty description="请选择左侧患者查看闭环" />
        </el-card>
      </template>

      <!-- 右栏：门诊闭环看板 -->
      <template v-else>
        <template v-if="opdLoopData">
          <!-- 1. 挂号与就诊信息 -->
          <el-card>
            <template #header><span>挂号与就诊信息</span></template>
            <div class="visit-row">
              <el-descriptions :column="3" border class="visit-desc">
                <el-descriptions-item label="姓名">{{ opdLoopData.register.pat_name }}</el-descriptions-item>
                <el-descriptions-item label="就诊卡号">{{ opdLoopData.register.pat_card_no }}</el-descriptions-item>
                <el-descriptions-item label="科室">{{ opdLoopData.register.reg_dept }}</el-descriptions-item>
                <el-descriptions-item label="挂号医生">
                  <el-link
                    type="primary"
                    :underline="false"
                    @click="openStaff(opdLoopData.register.reg_doctor)"
                  >{{ opdLoopData.register.reg_doctor }}</el-link>
                </el-descriptions-item>
                <el-descriptions-item label="挂号费">¥{{ fmtMoney(opdLoopData.register.reg_fee) }}</el-descriptions-item>
                <el-descriptions-item label="挂号时间">{{ opdLoopData.register.reg_time }}</el-descriptions-item>
                <el-descriptions-item label="诊断">{{ opdLoopData.visit?.diag || '-' }}</el-descriptions-item>
                <el-descriptions-item label="看诊时间">{{ opdLoopData.visit?.visit_time || '-' }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                  <el-tag size="small" :type="opdLoopData.visit ? 'success' : 'info'">
                    {{ opdLoopData.visit ? opdLoopData.visit.statusName : opdLoopData.register.regStatusName }}
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>
              <div v-if="opdLoopData.visit" class="fee-stats">
                <div class="fee-stat">
                  <div class="fee-stat-label">处方数</div>
                  <div class="fee-stat-value">{{ opdLoopData.prescriptions.length }}</div>
                </div>
                <div class="fee-stat">
                  <div class="fee-stat-label">已执行金额</div>
                  <div class="fee-stat-value">¥{{ fmtMoney(opdCurrent?.execTotal) }}</div>
                </div>
                <div class="fee-stat">
                  <div class="fee-stat-label">缴费合计</div>
                  <div class="fee-stat-value">¥{{ fmtMoney(opdPaidTotal) }}</div>
                </div>
              </div>
            </div>
          </el-card>

          <template v-if="opdLoopData.visit">
            <!-- 2. 处方闭环 -->
            <el-card style="margin-top: 16px">
              <template #header><span>处方闭环（{{ opdLoopData.prescriptions.length }} 条）</span></template>
              <el-table :data="opdLoopData.prescriptions" size="small">
                <el-table-column prop="prescId" label="处方号" width="150" />
                <el-table-column label="项目" min-width="190">
                  <template #default="{ row }">
                    {{ row.itemName }}
                    <el-tag
                      size="small"
                      effect="plain"
                      :type="orderTypeTag(row.itemType)"
                      style="margin-left: 4px"
                    >{{ row.itemType }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="quantity" label="数量" width="70" />
                <el-table-column label="金额" width="90">
                  <template #default="{ row }">¥{{ row.price }}</template>
                </el-table-column>
                <el-table-column prop="createTime" label="开立时间" width="160" />
                <el-table-column label="缴费与执行" width="180">
                  <template #default="{ row }">
                    <template v-if="row.itemType === '检查' && row.examReport">
                      <el-tooltip
                        :content="`${row.examReport.conclusion}（审核：${row.examReport.reviewer}）`"
                        placement="top"
                      >
                        <span :class="{ 'abnormal-text': row.examReport.abnormalName === '异常' }">
                          报告 {{ row.examReport.abnormalName }}
                        </span>
                      </el-tooltip>
                    </template>
                    <span v-else>{{ opdExecStage(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="闭环状态" width="120">
                  <template #default="{ row }">
                    <el-tag size="small" :type="opdLoopTagType(row.loopStatus)">
                      {{ row.loopStatusName }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>

            <!-- 3. 缴费记录 -->
            <el-card style="margin-top: 16px">
              <template #header><span>缴费记录</span></template>
              <el-table :data="opdLoopData.payments" size="small">
                <el-table-column prop="pay_id" label="流水号" width="160" />
                <el-table-column label="类型" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" :type="payTypeTag(row.payTypeName)">{{ row.payTypeName }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="金额" width="100">
                  <template #default="{ row }">¥{{ row.amount }}</template>
                </el-table-column>
                <el-table-column prop="channel" label="渠道" width="80" />
                <el-table-column prop="pay_time" label="时间" width="160" />
                <el-table-column prop="operator" label="收费员" />
              </el-table>
              <el-empty v-if="!opdLoopData.payments.length" description="无缴费记录" :image-size="50" />
            </el-card>

            <!-- 4. 全流程时间线 -->
            <el-card style="margin-top: 16px">
              <template #header>
                <div class="card-header">
                  <span>全流程时间线（{{ opdLoopData.timeline.length }} 个事件）</span>
                  <el-button
                    v-if="opdLoopData.timeline.length > 10"
                    size="small"
                    @click="opdExpanded = !opdExpanded"
                  >
                    {{ opdExpanded ? '收起' : `展开全部（${opdLoopData.timeline.length}）` }}
                  </el-button>
                </div>
              </template>
              <div class="tl2">
                <div v-for="(t, i) in opdDisplayTimeline" :key="i" class="tl2-item">
                  <div class="tl2-rail"><span class="tl2-dot" :class="systemTagClass(t.system)" /></div>
                  <div class="tl2-body">
                    <div class="tl2-head">
                      <span class="tl2-time">{{ fmtTime(t.time) }}</span>
                      <el-tag size="small" class="sys-tag" :class="systemTagClass(t.system)">
                        {{ t.system }}
                      </el-tag>
                      <b class="tl-event" :class="{ 'tl-event-reject': isRejectEvent(t.event) }">
                        {{ t.event }}
                      </b>
                    </div>
                    <div v-if="t.detail" class="tl2-detail">
                      <template v-for="(seg, j) in detailSegments(t)" :key="j">
                        <el-link
                          v-if="seg.link"
                          type="primary"
                          :underline="false"
                          class="tl2-name"
                          @click="openStaff(seg.text)"
                        >{{ seg.text }}</el-link>
                        <span v-else>{{ seg.text }}</span>
                      </template>
                    </div>
                  </div>
                </div>
              </div>
            </el-card>
          </template>

          <!-- 已退号：仅挂号信息 + 空态 -->
          <el-card v-else style="margin-top: 16px">
            <el-empty description="已退号，无后续就诊" />
          </el-card>
        </template>
        <el-card v-else>
          <el-empty description="请选择左侧患者查看闭环" />
        </el-card>
      </template>

      <!-- 人员下钻：流程页姓名 → 人事组织域主数据（医嘱—由谁开立/执行 的实例化） -->
      <el-dialog
        v-model="staffDialog"
        width="560px"
        :title="staffData?.found ? `人员档案：${staffData.staff.staff_name}` : '人员主数据下钻'"
      >
        <div v-loading="loadingStaff" style="min-height: 120px">
          <template v-if="staffData">
            <template v-if="staffData.found">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="姓名">{{ staffData.staff.staff_name }}</el-descriptions-item>
                <el-descriptions-item label="工号">{{ staffData.staff.staff_id }}</el-descriptions-item>
                <el-descriptions-item label="角色">{{ staffData.staff.role }}</el-descriptions-item>
                <el-descriptions-item label="职称">{{ staffData.staff.title || '-' }}</el-descriptions-item>
                <el-descriptions-item label="科室">{{ staffData.dept?.dept_name || '-' }}</el-descriptions-item>
                <el-descriptions-item label="科室类别">{{ staffData.dept?.category || '-' }}</el-descriptions-item>
                <el-descriptions-item v-if="staffData.dept?.ward" label="病区">
                  {{ staffData.dept.ward }}
                </el-descriptions-item>
                <el-descriptions-item v-if="staffData.dept?.bed_count" label="床位数">
                  {{ staffData.dept.bed_count }}
                </el-descriptions-item>
              </el-descriptions>
              <div v-if="footprintEntries.length" class="staff-footprint">
                <span v-for="[k, v] in footprintEntries" :key="k" class="footprint-item">
                  {{ k }} <b>{{ v }}</b>
                </span>
              </div>
              <div v-if="staffData.colleagues.length" class="staff-colleagues">
                <div class="colleagues-title">
                  同科室人员（{{ staffData.colleagues.length }}）
                </div>
                <el-table :data="staffData.colleagues" size="small" max-height="200">
                  <el-table-column prop="staff_name" label="姓名" width="110" />
                  <el-table-column prop="role" label="角色" width="90" />
                  <el-table-column prop="title" label="职称" />
                </el-table>
              </div>
            </template>
            <el-alert
              v-else
              type="warning"
              :closable="false"
              show-icon
              :title="`「${staffData.queryName}」未纳入人员主数据`"
              description="该姓名出现在业务单据中，但人事组织域人员主数据无对应记录——主数据覆盖缺口，建议补录。"
            />
          </template>
        </div>
      </el-dialog>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { listFlowPatients, flowLoop, listOpdPatients, opdLoop as fetchOpdLoop, staffDetail } from '../../api/flow'
import { exportPatientRdf } from '../../api/rdf'

const router = useRouter()

// ---------- 就诊模式 ----------
const mode = ref('INP')
const keyword = ref('')
const pageSize = 20

const onModeChange = () => {
  keyword.value = ''
  if (mode.value === 'INP') {
    if (!patients.value.length) loadPatients()
  } else if (!opdPatients.value.length) {
    loadOpdPatients()
  }
}

// 搜索（回车 / 点查询 / 清空）重置到第 1 页
const onSearch = () => (mode.value === 'INP' ? loadPatients(1) : loadOpdPatients(1))

const onPageChange = (page) => (mode.value === 'INP' ? loadPatients(page) : loadOpdPatients(page))

// ---------- 住院：患者列表 ----------
const patients = ref([])
const inpPage = ref(1)
const inpTotal = ref(0)
const loadingPatients = ref(false)
const current = ref(null)

const loadPatients = async (page = inpPage.value) => {
  loadingPatients.value = true
  try {
    const res = await listFlowPatients(keyword.value.trim() || undefined, page, pageSize)
    patients.value = res.list
    inpTotal.value = res.total
    inpPage.value = res.pageNum
    if (!patients.value.length) {
      current.value = null
      loop.value = null
      return
    }
    // 首次加载默认选中第一个；翻页/搜索后保留当前选中患者
    if (!current.value) {
      selectPatient(patients.value[0])
    }
  } finally {
    loadingPatients.value = false
  }
}

// ---------- 住院：闭环看板 ----------
const loop = ref(null)
const loadingLoop = ref(false)
const expanded = ref(false)

const selectPatient = async (p) => {
  current.value = p
  expanded.value = false
  loadingLoop.value = true
  try {
    loop.value = await flowLoop(p.inhos_no)
  } finally {
    loadingLoop.value = false
  }
}

// 已缴金额：预交金/结算补缴为正，退费计负
const paidTotal = computed(() =>
  (loop.value?.payments || []).reduce(
    (sum, p) => sum + (p.payTypeName === '退费' ? -p.amount : p.amount),
    0
  )
)

const displayTimeline = computed(() => {
  const tl = loop.value?.timeline || []
  return expanded.value ? tl : tl.slice(-10)
})

// ---------- 门诊：患者列表 ----------
const opdPatients = ref([])
const opdPage = ref(1)
const opdTotal = ref(0)
const loadingOpdPatients = ref(false)
const opdCurrent = ref(null)

const loadOpdPatients = async (page = opdPage.value) => {
  loadingOpdPatients.value = true
  try {
    const res = await listOpdPatients(keyword.value.trim() || undefined, page, pageSize)
    opdPatients.value = res.list
    opdTotal.value = res.total
    opdPage.value = res.pageNum
    if (!opdPatients.value.length) {
      opdCurrent.value = null
      opdLoopData.value = null
      return
    }
    if (!opdCurrent.value) {
      selectOpdPatient(opdPatients.value[0])
    }
  } finally {
    loadingOpdPatients.value = false
  }
}

// ---------- 门诊：闭环看板 ----------
const opdLoopData = ref(null)
const loadingOpdLoop = ref(false)
const opdExpanded = ref(false)

const selectOpdPatient = async (p) => {
  opdCurrent.value = p
  opdExpanded.value = false
  loadingOpdLoop.value = true
  try {
    opdLoopData.value = await fetchOpdLoop(p.pat_card_no)
  } finally {
    loadingOpdLoop.value = false
  }
}

// 缴费合计：门诊缴费为正，退费计负
const opdPaidTotal = computed(() =>
  (opdLoopData.value?.payments || []).reduce(
    (sum, p) => sum + (p.payTypeName === '退费' ? -p.amount : p.amount),
    0
  )
)

const opdDisplayTimeline = computed(() => {
  const tl = opdLoopData.value?.timeline || []
  return opdExpanded.value ? tl : tl.slice(-10)
})

// ---------- 字典与工具 ----------
const fmtMoney = (v) => (v == null ? '-' : Number(v).toFixed(2))

const orderTypeTag = (t) => ({ 检验: 'primary', 药品: 'success', 检查: 'warning' }[t] || 'info')

const loopTagType = (s) =>
  ({ CLOSED: 'success', CLOSED_CANCELED: 'info', BROKEN: 'danger', PENDING: 'primary' }[s] || 'info')

const opdLoopTagType = (s) =>
  ({ CLOSED: 'success', CLOSED_CANCELED: 'info', PENDING: 'primary', UNPAID: 'warning' }[s] ||
  'info')

const payTypeTag = (t) =>
  ({ 预交金: 'primary', 结算补缴: 'success', 退费: 'danger', 门诊缴费: 'primary' }[t] || 'info')

const systemTagClass = (s) =>
  ({ HIS: 'sys-his', LIS: 'sys-lis', 收费: 'sys-fee', 药房: 'sys-pharm', 门诊: 'sys-opd', PACS: 'sys-pacs', 护士站: 'sys-nurse' }[s] || '')

// 驳回类事件（如「处方审核驳回」）红色警示
const isRejectEvent = (e) => (e || '').includes('驳回')

const execStage = (row) => {
  if (row.orderType === '药品') {
    return row.dispense ? `${row.dispense.statusName} ${row.dispense.pharmacist}` : '-'
  }
  if (row.orderType === '检验') {
    if (row.labReport) return `报告 ${row.labReport.statusName}`
    if (row.labApply) return `申请 ${row.labApply.statusName}`
    return '-'
  }
  if (row.orderType === '检查') return '待执行'
  return '-'
}

const opdExecStage = (row) => {
  if (row.loopStatus === 'UNPAID') return '-'
  if (row.itemType === '检验') {
    if (row.labReport) return '已出报告'
    if (row.labApply) return `申请 ${row.labApply.statusName}`
    return '-'
  }
  if (row.itemType === '药品') {
    return row.dispense ? `${row.dispense.statusName} ${row.dispense.pharmacist}` : '-'
  }
  if (row.itemType === '检查') return '待执行'
  return '-'
}

// ---------- 时间线：格式化时间 + 责任人名可下钻 ----------
// 2026-09-10T13:00:00 → 09-10 13:00
const fmtTime = (t) => {
  if (!t) return ''
  const m = String(t).match(/^\d{4}-(\d{2}-\d{2})[T ](\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : String(t)
}

// detail 里带责任人姓名（t.staff）时切成片段，姓名段渲染为可点击链接
const detailSegments = (t) => {
  const d = t.detail || ''
  const name = t.staff
  if (!name || !d.includes(name)) return [{ text: d, link: false }]
  const idx = d.indexOf(name)
  const segs = []
  if (idx > 0) segs.push({ text: d.slice(0, idx), link: false })
  segs.push({ text: name, link: true })
  if (idx + name.length < d.length) segs.push({ text: d.slice(idx + name.length), link: false })
  return segs
}

const goRca = () => router.push('/rca')

// ---------- 人事组织域下钻 ----------
const staffDialog = ref(false)
const staffData = ref(null)
const loadingStaff = ref(false)

const openStaff = async (name) => {
  if (!name) return
  staffDialog.value = true
  loadingStaff.value = true
  staffData.value = null
  try {
    staffData.value = await staffDetail(name)
  } finally {
    loadingStaff.value = false
  }
}

// 业务足迹只展示非零项（医生看开立/主管，护士看执行，药师看审核/发药）
const footprintEntries = computed(() =>
  Object.entries(staffData.value?.footprint || {}).filter(([, v]) => Number(v) > 0)
)

onMounted(loadPatients)
</script>

<style scoped>
.flow-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.patient-panel {
  width: 360px;
  flex-shrink: 0;
}

.mode-switch {
  width: 100%;
  margin-bottom: 12px;
}

.search-row {
  display: flex;
  gap: 8px;
}

.patient-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.patient-list {
  margin-top: 12px;
  max-height: calc(100vh - 270px);
  overflow-y: auto;
}

.patient-item {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 10px;
  cursor: pointer;
}

.patient-item:hover {
  border-color: #409eff;
}

.patient-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.patient-line1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.patient-name {
  font-size: 15px;
  font-weight: 600;
}

.patient-line2 {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.patient-line3 {
  font-size: 12px;
  color: #606266;
  margin-top: 2px;
}

.patient-line4 {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.loop-panel {
  flex: 1;
  min-width: 0;
}

.visit-row {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.visit-desc {
  flex: 1;
}

.fee-stats {
  display: flex;
  gap: 24px;
}

.fee-stat {
  text-align: center;
  min-width: 96px;
}

.fee-stat-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.fee-stat-value {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}

.settle-card {
  background: #fafafa;
}

.settle-title {
  font-size: 13px;
  color: #909399;
}

.settle-amount {
  font-size: 24px;
  font-weight: 700;
  margin: 6px 0;
}

.settle-meta {
  font-size: 12px;
  color: #909399;
}

/* 全流程时间线：系统色圆点 + 连接线 + 悬浮详情 */
.tl2 {
  padding: 4px 0;
}

.tl2-item {
  display: flex;
  gap: 12px;
}

.tl2-rail {
  width: 16px;
  position: relative;
  display: flex;
  justify-content: center;
  flex-shrink: 0;
}

.tl2-rail::before {
  content: '';
  position: absolute;
  top: 18px;
  bottom: -2px;
  width: 2px;
  background: #ebeef5;
}

.tl2-item:last-child .tl2-rail::before {
  display: none;
}

.tl2-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-top: 7px;
  border: 2px solid #c0c4cc;
  background: #fff;
  z-index: 1;
  flex-shrink: 0;
}

.tl2-dot.sys-his {
  border-color: #409eff;
}

.tl2-dot.sys-lis {
  border-color: #67c23a;
}

.tl2-dot.sys-fee {
  border-color: #e6a23c;
}

.tl2-dot.sys-pharm {
  border-color: #722ed1;
}

.tl2-dot.sys-opd {
  border-color: #13c2c2;
}

.tl2-dot.sys-pacs {
  border-color: #8b5a2b;
}

.tl2-dot.sys-nurse {
  border-color: #389e0d;
}

.tl2-body {
  flex: 1;
  min-width: 0;
  padding-bottom: 16px;
}

.tl2-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tl2-time {
  font-size: 12px;
  color: #a8abb2;
  font-variant-numeric: tabular-nums;
  min-width: 68px;
}

.tl2-detail {
  display: inline-block;
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
  background: #f7f8fa;
  border-radius: 4px;
  padding: 4px 10px;
  line-height: 1.7;
}

.tl2-item:hover .tl2-detail {
  background: #f0f7ff;
}

.tl2-name {
  font-size: 12px;
  vertical-align: baseline;
}

.tl-event {
  font-size: 13px;
}

.tl-event.tl-event-reject {
  color: #f56c6c;
}

/* 系统泳道 tag：HIS 蓝 / LIS 绿 / 收费 橙 / 药房 紫 / 门诊 青 / PACS 棕 / 护士站 深绿 */
.sys-tag.sys-his {
  color: #409eff;
  border-color: #b3d8ff;
  background: #ecf5ff;
}

.sys-tag.sys-lis {
  color: #67c23a;
  border-color: #c2e7b0;
  background: #f0f9eb;
}

.sys-tag.sys-fee {
  color: #e6a23c;
  border-color: #f5dab1;
  background: #fdf6ec;
}

.sys-tag.sys-pharm {
  color: #722ed1;
  border-color: #d3adf7;
  background: #f9f0ff;
}

.sys-tag.sys-opd {
  color: #13c2c2;
  border-color: #87e8de;
  background: #e6fffb;
}

.sys-tag.sys-pacs {
  color: #8b5a2b;
  border-color: #d6b48c;
  background: #f7f0e6;
}

.sys-tag.sys-nurse {
  color: #389e0d;
  border-color: #95de64;
  background: #f6ffed;
}

.nurse-exec-line {
  font-size: 13px;
}

.nurse-exec-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.abnormal-text {
  color: #f56c6c;
  font-weight: 600;
}

.review-line {
  display: flex;
  align-items: center;
  font-size: 13px;
}

.review-pharmacist {
  margin-left: 6px;
}

.review-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.reject-reason {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 2px;
  line-height: 1.5;
}

/* 人员下钻弹窗 */
.staff-footprint {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.footprint-item {
  font-size: 12px;
  color: #606266;
  background: #f4f4f5;
  border-radius: 4px;
  padding: 4px 10px;
}

.footprint-item b {
  color: #409eff;
}

.staff-colleagues {
  margin-top: 12px;
}

.colleagues-title {
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
}
</style>
