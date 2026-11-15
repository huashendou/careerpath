<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/client'
import RadarChart from '../components/RadarChart.vue'
import { useCatalogStore } from '../stores/catalog'
import { fmtScore, fmtTime } from '../utils/format'

const route = useRoute()
const catalog = useCatalogStore()

const report = ref(null)
const loading = ref(true)
const error = ref('')

const hasScore = computed(
  () => report.value && report.value.matchScore !== null && report.value.matchScore !== undefined,
)

const axes = computed(() => (report.value ? report.value.radar.axes : []))
const gapRows = computed(() => (report.value ? report.value.gaps : []))
const plusRows = computed(() => (report.value ? report.value.plusItems : []))

const missingDims = computed(() => axes.value.filter((a) => !a.hasCore))
const dataAxisCount = computed(() => (report.value ? report.value.radar.dataAxisCount : 0))

const reachedDims = computed(
  () =>
    axes.value.filter(
      (a) => a.hasCore && a.target !== null && a.current !== null && a.current >= a.target,
    ).length,
)

function statusLabel(s) {
  return { mastered: '已达标', weak: '部分达标', missing: '未掌握' }[s] || s
}

function levelPct(v) {
  return `${((v || 0) / 5) * 100}%`
}

onMounted(async () => {
  try {
    // 报告里存的是维度 id，需要词典才能显示中文名
    const [data] = await Promise.all([http.get(`/reports/${route.params.id}`), catalog.ensure()])
    report.value = data
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="shell">
    <RouterLink to="/reports" class="back">← 返回历史报告</RouterLink>

    <div v-if="error" class="alert alert-error page-alert">{{ error }}</div>

    <div v-if="loading" class="card card-pad">
      <div class="skeleton" style="height: 20px; width: 30%"></div>
      <div class="skeleton" style="height: 140px; margin-top: 16px"></div>
    </div>

    <template v-else-if="report">
      <header class="report-head">
        <div>
          <p class="eyebrow">匹配报告 · 快照</p>
          <h1 class="page-title">{{ report.jobName }}</h1>
          <p class="page-sub">
            存档于 {{ fmtTime(report.createdAt) }} · 报告 #{{
              report.id
            }} · 基于画像 #{{ report.profileId }}
          </p>
        </div>
        <RouterLink to="/analysis" class="btn">再做一次分析</RouterLink>
      </header>

      <div class="summary card">
        <div class="summary-score">
          <span class="eyebrow">基础匹配度</span>
          <div class="score-line">
            <span class="score-v num">{{ hasScore ? fmtScore(report.matchScore) : '—' }}</span>
            <span class="score-unit">%</span>
          </div>
          <span class="score-exact num">
            {{ hasScore ? report.matchScore.toFixed(4) : report.status }}
          </span>
        </div>
        <div class="summary-math">
          <div class="math-item">
            <span class="math-label">得分</span>
            <span class="math-v num">{{ report.numerator }}</span>
          </div>
          <div class="math-div">/</div>
          <div class="math-item">
            <span class="math-label">分母</span>
            <span class="math-v num">{{ report.denominator }}</span>
          </div>
          <p class="math-note">分母 = core 权重 × 目标等级之和，不是 100</p>
        </div>
        <div class="summary-counts">
          <div class="c-item"><span class="c-v num g">{{ report.counts.mastered }}</span><span class="c-l">已达标</span></div>
          <div class="c-item"><span class="c-v num a">{{ report.counts.weak }}</span><span class="c-l">部分达标</span></div>
          <div class="c-item"><span class="c-v num r">{{ report.counts.missing }}</span><span class="c-l">未掌握</span></div>
          <div class="c-item"><span class="c-v num m">{{ report.counts.plusMastered }}</span><span class="c-l">加分项</span></div>
        </div>
      </div>

      <div class="split">
        <section class="card radar-card">
          <div class="card-head">
            <h2>六维能力对照</h2>
            <span class="muted num">{{ dataAxisCount }} / 6 维有要求</span>
          </div>
          <div class="radar-wrap"><RadarChart :axes="axes" /></div>
          <p v-if="missingDims.length" class="radar-note">
            {{ missingDims.map((d) => d.name).join('、') }} 本岗位无核心要求，图上断开。
          </p>
          <p class="radar-reach">
            <template v-if="reachedDims">
              6 维中 <b class="num">{{ reachedDims }}</b> 维达到目标
            </template>
            <template v-else>6 个维度都未达到目标</template>
          </p>
        </section>

        <section class="card detail-card">
          <div class="card-head"><h2>档位口径</h2></div>
          <div class="detail-body">
            <p class="detail-p">
              {{ report.meta.disclaimer }}
            </p>
            <dl class="detail-dl">
              <div><dt>计分口径</dt><dd class="num">{{ report.meta.scoringVersion }}</dd></div>
              <div><dt>数据版本</dt><dd class="num">{{ report.meta.displayBadge }}</dd></div>
              <div><dt>优先补齐</dt><dd>{{ report.topActions.length }} 项</dd></div>
            </dl>
          </div>
        </section>
      </div>

      <section class="card table-card">
        <div class="card-head">
          <h2>能力差距</h2>
          <span class="muted num">{{ gapRows.length }} 项</span>
        </div>
        <div v-if="!gapRows.length" class="empty">核心要求全部达标。</div>
        <div v-else class="table-scroll">
          <table class="gap-table">
            <thead>
              <tr>
                <th>技能</th>
                <th>维度</th>
                <th class="r">权重</th>
                <th class="c">目标</th>
                <th class="c">当前</th>
                <th class="r">缺口</th>
                <th class="bar-col">达标度</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="g in gapRows" :key="g.skillId">
                <td class="skill-col">{{ g.skillName }}</td>
                <td class="dim-col">{{ catalog.dimName(g.dim) }}</td>
                <td class="r num">{{ g.weight }}</td>
                <td class="c num">{{ g.target }}</td>
                <td class="c num">{{ g.level }}</td>
                <td class="r num gap-v">−{{ g.gap }}</td>
                <td class="bar-col">
                  <div class="bar">
                    <div class="bar-fill" :style="{ width: levelPct(g.effectiveLevel) }"></div>
                    <div class="bar-target" :style="{ left: levelPct(g.target) }"></div>
                  </div>
                </td>
                <td><span class="tag" :class="`tag-${g.status}`">{{ statusLabel(g.status) }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card table-card">
        <div class="card-head"><h2>额外优势（不计分）</h2></div>
        <div v-if="!plusRows.length" class="empty">无加分项。</div>
        <div v-else class="plus-list">
          <div v-for="p in plusRows" :key="p.skillId" class="plus-row">
            <span class="plus-name">{{ p.skillName }}</span>
            <span class="plus-dim">{{ catalog.dimName(p.dim) }}</span>
            <span class="plus-meta">目标 {{ p.target }} 级 · 权重 {{ p.weight }}</span>
            <span class="plus-level num">{{ p.level }} 级</span>
            <span class="tag" :class="p.status === 'mastered' ? 'tag-mastered' : 'tag-plus'">
              {{ p.status === 'mastered' ? '已具备' : '待补' }}
            </span>
          </div>
        </div>
      </section>

      <p class="foot-note">
        报告是存档快照，修改自评不会回写历史记录。
      </p>
    </template>
  </div>
</template>

<style scoped>
.back {
  display: inline-block;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--text-2);
}

.back:hover {
  color: var(--primary);
}

.page-alert {
  margin-bottom: 16px;
}

.report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.summary {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 32px;
  padding: 20px 24px;
  margin-bottom: 16px;
}

.summary-score {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.score-line {
  display: flex;
  align-items: baseline;
  line-height: 1;
}

.score-v {
  font-size: 44px;
  font-weight: 660;
  letter-spacing: -0.035em;
}

.score-unit {
  margin-left: 3px;
  font-size: 17px;
  color: var(--text-3);
}

.score-exact {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--text-3);
}

.summary-math {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 26px;
  border-left: 1px solid var(--border);
}

.math-item {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.math-label {
  font-size: 11px;
  color: var(--text-3);
}

.math-v {
  font-size: 18px;
  font-weight: 600;
}

.math-div {
  font-size: 15px;
  color: var(--text-3);
  margin-top: 12px;
}

.math-note {
  margin-left: 6px;
  max-width: 180px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--text-3);
}

.summary-counts {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  justify-self: end;
  width: 100%;
  max-width: 380px;
}

.c-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 8px 6px;
  border-radius: 8px;
  background: var(--surface-2);
}

.c-v {
  font-size: 18px;
  font-weight: 620;
  line-height: 1.2;
}

.c-l {
  font-size: 11px;
  color: var(--text-3);
}

.g {
  color: var(--green);
}
.a {
  color: var(--amber);
}
.r {
  color: var(--red);
}
.m {
  color: var(--text-2);
}

.split {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.radar-card {
  overflow: hidden;
}

.radar-wrap {
  min-height: 380px;
  width: 100%;
  max-width: 520px;
  margin: 0 auto;
  padding: 4px 8px 0;
}

.radar-note {
  padding: 0 20px;
  font-size: 11.5px;
  color: var(--text-3);
}

.radar-reach {
  padding: 6px 20px 14px;
  font-size: 12.5px;
  color: var(--text-2);
}

.detail-body {
  padding: 16px 20px;
}

.detail-p {
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--text-2);
}

.detail-dl {
  margin: 16px 0 0;
  border-top: 1px solid var(--border);
}

.detail-dl > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
  font-size: 12.5px;
}

.detail-dl > div:last-child {
  border-bottom: 0;
}

.detail-dl dt {
  color: var(--text-3);
}

.detail-dl dd {
  margin: 0;
  color: var(--text);
}

.table-card {
  margin-bottom: 16px;
  overflow: hidden;
}

.table-scroll {
  overflow-x: auto;
}

.gap-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.gap-table th {
  padding: 9px 12px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--text-3);
  text-align: left;
  white-space: nowrap;
}

.gap-table td {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
}

.gap-table tbody tr:last-child td {
  border-bottom: 0;
}

.r {
  text-align: right;
}

.c {
  text-align: center;
}

.skill-col {
  font-weight: 500;
  min-width: 120px;
}

.dim-col {
  font-size: 12px;
  color: var(--text-2);
  white-space: nowrap;
}

.gap-v {
  color: var(--red);
  font-weight: 550;
}

.bar-col {
  min-width: 90px;
}

.bar {
  position: relative;
  height: 5px;
  border-radius: 3px;
  background: #eef0f5;
}

.bar-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--primary);
}

.bar-target {
  position: absolute;
  top: -2px;
  width: 2px;
  height: 9px;
  background: #8f93a6;
  border-radius: 1px;
}

.plus-list {
  display: flex;
  flex-direction: column;
}

.plus-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 20px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}

.plus-row:last-child {
  border-bottom: 0;
}

.plus-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.plus-dim {
  width: 88px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-3);
}

.plus-meta {
  width: 132px;
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--text-3);
  text-align: right;
}

.plus-level {
  width: 42px;
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--text-2);
  text-align: right;
}

.foot-note {
  text-align: center;
  font-size: 11.5px;
  color: var(--text-3);
}

@media (max-width: 900px) {
  .summary {
    grid-template-columns: 1fr;
    gap: 18px;
  }
  .summary-math {
    padding-left: 0;
    border-left: 0;
    border-top: 1px solid var(--border);
    padding-top: 14px;
  }
  .math-note {
    max-width: none;
  }
  .summary-counts {
    justify-self: stretch;
    max-width: none;
  }
  .split {
    grid-template-columns: 1fr;
  }
  .report-head {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
