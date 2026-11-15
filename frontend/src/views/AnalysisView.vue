<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/client'
import { useCatalogStore } from '../stores/catalog'
import RadarChart from '../components/RadarChart.vue'

const router = useRouter()
const catalog = useCatalogStore()

const profiles = ref([])
const profileId = ref(null)
const jobId = ref('')
const result = ref(null)
const loading = ref(true)
const analyzing = ref(false)
const error = ref('')
const archiving = ref(false)
const showMath = ref(false)

const currentProfile = computed(() => profiles.value.find((p) => p.id === profileId.value) || null)
const currentJob = computed(() => catalog.jobs.find((j) => j.id === jobId.value) || null)

const status = computed(() => (result.value ? result.value.status : ''))

const hasScore = computed(
  () => result.value && result.value.matchScore !== null && result.value.matchScore !== undefined,
)

const scoreText = computed(() => (hasScore.value ? result.value.matchScore.toFixed(1) : '—'))
const scoreExact = computed(() => (hasScore.value ? result.value.matchScore.toFixed(4) : ''))

const axes = computed(() => (result.value ? result.value.radar.axes : []))
const dataAxisCount = computed(() => (result.value ? result.value.radar.dataAxisCount : 0))
const missingDims = computed(() => axes.value.filter((a) => !a.hasCore))

const reachedDims = computed(() => {
  return axes.value.filter(
    (a) => a.hasCore && a.target !== null && a.current !== null && a.current >= a.target,
  ).length
})

const topActionNames = computed(() => {
  if (!result.value) return []
  return (result.value.topActions || []).map((id) => {
    const s = catalog.skillMap[id]
    return s ? s.name : id
  })
})

const gapRows = computed(() => (result.value ? result.value.gaps : []))
const plusRows = computed(() => (result.value ? result.value.plusItems : []))

function statusLabel(item) {
  if (item.status === 'mastered') return '已达标'
  if (item.status === 'weak') return '部分达标'
  if (item.status === 'missing') return '未掌握'
  return item.status
}

function statusTone(item) {
  if (item.status === 'mastered') return 'green'
  if (item.status === 'weak') return 'amber'
  return 'red'
}

function levelPct(v) {
  return `${(v / 5) * 100}%`
}

async function loadProfiles() {
  const data = await http.get('/profiles')
  profiles.value = data.profiles
  if (!data.profiles.length) {
    profileId.value = null
    return
  }
  if (!data.profiles.some((p) => p.id === profileId.value)) {
    profileId.value = data.profiles[0].id
  }
}

async function analyze() {
  if (!jobId.value) return
  if (!profileId.value) {
    result.value = null
    return
  }
  analyzing.value = true
  error.value = ''
  try {
    result.value = await http.post('/analysis/match', {
      profileId: profileId.value,
      jobId: jobId.value,
    })
  } catch (e) {
    error.value = e.message
    result.value = null
  } finally {
    analyzing.value = false
  }
}

async function archive() {
  if (!result.value || !profileId.value || archiving.value) return
  archiving.value = true
  error.value = ''
  try {
    const report = await http.post('/reports', {
      profileId: profileId.value,
      jobId: jobId.value,
    })
    router.push(`/reports/${report.id}`)
  } catch (e) {
    error.value = e.message
  } finally {
    archiving.value = false
  }
}

watch([profileId, jobId], analyze)

onMounted(async () => {
  try {
    await catalog.ensure()
    jobId.value = catalog.jobs.length ? catalog.jobs[0].id : ''
    await loadProfiles()
    if (profileId.value) await analyze()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="shell">
    <header class="page-head">
      <div>
        <h1 class="page-title">匹配分析</h1>
        <p class="page-sub">只用核心要求算分，加分项单独列出、不参与百分比。</p>
      </div>
      <button
        v-if="hasScore"
        class="btn btn-primary"
        type="button"
        :disabled="archiving"
        @click="archive"
      >
        {{ archiving ? '存档中…' : '存为报告' }}
      </button>
    </header>

    <!-- 控制条 -->
    <div class="card controls">
      <label class="ctrl">
        <span class="ctrl-label">画像</span>
        <select v-if="profiles.length" v-model="profileId" class="input ctrl-select">
          <option v-for="p in profiles" :key="p.id" :value="p.id">
            {{ p.name }}（{{ p.skillCount }} 项）
          </option>
        </select>
        <RouterLink v-else to="/profile" class="ctrl-empty">还没有画像，去创建 →</RouterLink>
      </label>

      <div class="ctrl ctrl-grow">
        <span class="ctrl-label">岗位</span>
        <div class="job-tabs">
          <button
            v-for="j in catalog.jobs"
            :key="j.id"
            type="button"
            class="job-tab"
            :class="{ on: jobId === j.id }"
            @click="jobId = j.id"
          >
            <span class="job-name">{{ j.shortName || j.name }}</span>
            <span class="job-meta num">核心权重 {{ j.coreWeightSum }}</span>
          </button>
        </div>
      </div>
    </div>

    <div v-if="error" class="alert alert-error page-alert">{{ error }}</div>

    <div v-if="loading" class="card card-pad">
      <div class="skeleton" style="height: 18px; width: 30%"></div>
      <div class="skeleton" style="height: 180px; margin-top: 14px"></div>
    </div>

    <!-- 无画像 -->
    <div v-else-if="!profiles.length" class="card empty">
      <p class="empty-title">先建一份技能画像</p>
      <p class="empty-sub">算分需要你的自评数据</p>
      <RouterLink to="/profile" class="btn btn-primary">去技能自评</RouterLink>
    </div>

    <!-- 一条自评都没有 -->
    <div v-else-if="status === 'EMPTY_PROFILE'" class="card state-card">
      <p class="state-code">EMPTY_PROFILE</p>
      <h2 class="state-title">这份画像还没有任何自评</h2>
      <p class="state-desc">
        空自评不显示 0%。0% 会被读成「要求为零，随便怎样都行」，与实际含义正好相反。
      </p>
      <RouterLink to="/profile" class="btn btn-primary">去自评</RouterLink>
    </div>

    <!-- 岗位没有核心要求 -->
    <div v-else-if="status === 'NO_CORE_REQUIREMENT'" class="card state-card">
      <p class="state-code">NO_CORE_REQUIREMENT</p>
      <h2 class="state-title">该岗位没有核心要求</h2>
      <p class="state-desc">分母为零，匹配度无意义，因此不返回数字。</p>
    </div>

    <template v-else-if="result">
      <div class="result-grid">
        <!-- 分数卡 -->
        <section class="card score-card">
          <div class="score-head">
            <span class="eyebrow">基础匹配度</span>
            <span class="score-job">{{ result.jobName }}</span>
          </div>

          <div class="score-main">
            <span class="score-num num">{{ scoreText }}</span>
            <span class="score-unit">%</span>
          </div>
          <div class="score-exact num">精确值 {{ scoreExact }}%</div>

          <p class="score-note">只统计核心要求。加分项「没有也能干」，进分母就变成「没有就扣分」。</p>

          <button class="math-toggle" type="button" @click="showMath = !showMath">
            {{ showMath ? '收起算分口径' : '看算分口径' }}
          </button>

          <div v-if="showMath" class="math-box">
            <div class="math-line">
              <span>分子（实际得分）</span>
              <b class="num">{{ result.numerator }}</b>
            </div>
            <div class="math-line">
              <span>分母（core 权重 × 目标等级）</span>
              <b class="num">{{ result.denominator }}</b>
            </div>
            <p class="math-hint">
              分母不是 100。每个岗位的 core 权重合计 79 / 89 / 83，乘以目标等级后得到
              {{ result.denominator }}。
            </p>
          </div>

          <div class="counts">
            <div class="count-item">
              <span class="count-v num tone-green">{{ result.counts.mastered }}</span>
              <span class="count-l">已达标</span>
            </div>
            <div class="count-item">
              <span class="count-v num tone-amber">{{ result.counts.weak }}</span>
              <span class="count-l">部分达标</span>
            </div>
            <div class="count-item">
              <span class="count-v num tone-red">{{ result.counts.missing }}</span>
              <span class="count-l">未掌握</span>
            </div>
            <div class="count-item">
              <span class="count-v num tone-muted">{{ result.counts.plusMastered }}</span>
              <span class="count-l">加分项</span>
            </div>
          </div>
        </section>

        <!-- 雷达 -->
        <section class="card radar-card">
          <div class="card-head">
            <h2>六维能力对照</h2>
            <span class="muted num">本岗位有要求 {{ dataAxisCount }} / {{ axes.length }} 维</span>
          </div>
          <div class="radar-wrap">
            <RadarChart :axes="axes" />
          </div>
          <p v-if="missingDims.length" class="radar-note">
            灰色维度（{{ missingDims.map((d) => d.name).join('、') }}）本岗位无核心要求，返回 null
            而不是 0，图上相应位置断开。
          </p>
          <p class="radar-reach">
            <template v-if="reachedDims">
              6 维中 <b class="num">{{ reachedDims }}</b> 个维度按加权口径达到目标
            </template>
            <template v-else>6 个维度都还没达到目标，按下面的优先顺序补</template>
          </p>
        </section>
      </div>

      <!-- 优先补齐 -->
      <section v-if="topActionNames.length" class="card actions-card">
        <div class="actions-head">
          <span class="eyebrow">优先补齐</span>
          <span class="muted">按「权重 × 缺口」排序，同样的努力先投在影响最大的地方</span>
        </div>
        <div class="action-list">
          <div v-for="(name, i) in topActionNames" :key="name" class="action-item">
            <span class="action-rank num">{{ i + 1 }}</span>
            <span class="action-name">{{ name }}</span>
          </div>
        </div>
      </section>

      <!-- 缺口表 -->
      <section class="card table-card">
        <div class="card-head">
          <h2>能力差距</h2>
          <span class="muted num">{{ gapRows.length }} 项待补</span>
        </div>
        <div v-if="!gapRows.length" class="empty">
          核心要求全部达标。可以看一下下面的加分项，作为额外优势。
        </div>
        <div v-else class="table-scroll">
          <table class="gap-table">
            <thead>
              <tr>
                <th class="col-skill">技能</th>
                <th class="col-dim">维度</th>
                <th class="col-num">权重</th>
                <th class="col-level">目标</th>
                <th class="col-level">当前</th>
                <th class="col-num">缺口</th>
                <th class="col-bar">达标度</th>
                <th class="col-status">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="g in gapRows" :key="g.skillId">
                <td class="col-skill">
                  <span class="cell-skill">{{ g.skillName }}</span>
                  <span v-if="g.effectiveLevel !== g.level" class="cell-eff">
                    按 {{ g.effectiveLevel }} 级计
                  </span>
                </td>
                <td class="col-dim"><span class="dim-name">{{ catalog.dimName(g.dim) }}</span></td>
                <td class="col-num num">{{ g.weight }}</td>
                <td class="col-level num">{{ g.target }}</td>
                <td class="col-level num">{{ g.level }}</td>
                <td class="col-num num gap-v">−{{ g.gap }}</td>
                <td class="col-bar">
                  <div class="bar">
                    <div class="bar-fill" :style="{ width: levelPct(g.effectiveLevel || 0) }"></div>
                    <div class="bar-target" :style="{ left: levelPct(g.target) }"></div>
                  </div>
                </td>
                <td class="col-status">
                  <span class="tag" :class="`tag-${g.status}`">{{ statusLabel(g) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 加分项 -->
      <section class="card table-card">
        <div class="card-head">
          <h2>额外优势（加分项）</h2>
          <span class="muted">不计入匹配度，仅作简历亮点参考</span>
        </div>
        <div v-if="!plusRows.length" class="empty">该岗位没有设置加分项。</div>
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

      <p class="disclaimer">
        {{ result.meta.disclaimer }}
        <span class="disclaimer-sep">·</span>
        <span class="num">{{ result.meta.displayBadge }}</span>
        <span class="disclaimer-sep">·</span>
        计分口径 {{ result.meta.scoringVersion }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.controls {
  display: flex;
  align-items: flex-end;
  gap: 22px;
  padding: 14px 18px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.ctrl {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ctrl-grow {
  flex: 1;
  min-width: 260px;
}

.ctrl-label {
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--text-3);
}

.ctrl-select {
  height: 34px;
  width: 220px;
  padding: 0 8px;
  font-size: 13px;
}

.ctrl-empty {
  font-size: 13px;
  color: var(--primary);
}

.job-tabs {
  display: flex;
  gap: 6px;
}

.job-tab {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 6px 13px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: var(--surface);
  text-align: left;
  transition: all 0.15s;
}

.job-tab:hover {
  border-color: #c3c7d6;
}

.job-tab.on {
  background: var(--primary-weak);
  border-color: #c7c9f7;
}

.job-name {
  font-size: 13px;
  font-weight: 550;
  color: var(--text);
}

.job-tab.on .job-name {
  color: var(--primary-dark);
}

.job-meta {
  font-size: 11px;
  color: var(--text-3);
}

.page-alert {
  margin-bottom: 16px;
}

.state-card {
  padding: 40px 24px;
  text-align: center;
}

.state-code {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--text-3);
}

.state-title {
  margin-top: 8px;
  font-size: 17px;
  font-weight: 620;
}

.state-desc {
  max-width: 460px;
  margin: 8px auto 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-2);
}

.state-card .btn {
  display: inline-flex;
}

.result-grid {
  display: grid;
  grid-template-columns: minmax(0, 340px) minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.score-card {
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
}

.score-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.score-job {
  font-size: 12.5px;
  color: var(--text-3);
}

.score-main {
  display: flex;
  align-items: baseline;
  margin-top: 10px;
  line-height: 1;
}

.score-num {
  font-size: 56px;
  font-weight: 660;
  letter-spacing: -0.035em;
  color: var(--text);
}

.score-unit {
  margin-left: 4px;
  font-size: 20px;
  font-weight: 500;
  color: var(--text-3);
}

.score-exact {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
}

.score-note {
  margin-top: 14px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-2);
}

.math-toggle {
  align-self: flex-start;
  margin-top: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 12.5px;
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.math-box {
  margin-top: 10px;
  padding: 11px 13px;
  border-radius: 8px;
  background: var(--surface-2);
  border: 1px dashed var(--border-strong);
}

.math-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--text-2);
}

.math-line + .math-line {
  margin-top: 5px;
}

.math-line b {
  font-size: 13.5px;
  color: var(--text);
}

.math-hint {
  margin-top: 8px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-3);
}

.counts {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  margin-top: auto;
  padding-top: 18px;
}

.count-item {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 9px 8px;
  border-radius: 8px;
  background: var(--surface-2);
  text-align: center;
}

.count-v {
  font-size: 19px;
  font-weight: 620;
  line-height: 1.2;
}

.count-l {
  font-size: 11px;
  color: var(--text-3);
}

.tone-green {
  color: var(--green);
}
.tone-amber {
  color: var(--amber);
}
.tone-red {
  color: var(--red);
}
.tone-muted {
  color: var(--text-2);
}

.radar-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.radar-wrap {
  flex: 1;
  width: 100%;
  max-width: 520px;
  margin: 0 auto;
  padding: 6px 10px 0;
  min-height: 390px;
}

.radar-note {
  padding: 0 22px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-3);
}

.radar-reach {
  padding: 8px 22px 16px;
  font-size: 12.5px;
  color: var(--text-2);
}

.radar-reach b {
  color: var(--text);
}

.actions-card {
  padding: 16px 22px;
  margin-bottom: 16px;
}

.actions-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12.5px;
}

.action-list {
  display: flex;
  gap: 10px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.action-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 13px 7px 8px;
  border-radius: 8px;
  background: var(--primary-weak);
  border: 1px solid #dcdefb;
}

.action-rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 19px;
  height: 19px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
}

.action-name {
  font-size: 13px;
  font-weight: 550;
  color: var(--primary-dark);
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
  vertical-align: middle;
}

.gap-table tbody tr:last-child td {
  border-bottom: 0;
}

.gap-table tbody tr:hover {
  background: #fbfbfe;
}

.col-num {
  text-align: right;
  white-space: nowrap;
}

.col-level {
  text-align: center;
  white-space: nowrap;
}

.col-skill {
  min-width: 128px;
}

.cell-skill {
  font-weight: 500;
}

.cell-eff {
  margin-left: 6px;
  font-size: 11px;
  color: var(--primary);
}

.dim-name {
  font-size: 12px;
  color: var(--text-2);
}

.gap-v {
  color: var(--red);
  font-weight: 550;
}

.col-bar {
  min-width: 96px;
}

.bar {
  position: relative;
  height: 5px;
  border-radius: 3px;
  background: #eef0f5;
  overflow: visible;
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

.disclaimer {
  margin-top: 8px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-3);
  text-align: center;
}

.disclaimer-sep {
  margin: 0 6px;
}

@media (max-width: 900px) {
  .result-grid {
    grid-template-columns: 1fr;
  }
  .score-num {
    font-size: 46px;
  }
}

@media (max-width: 600px) {
  .page-head {
    flex-direction: column;
    align-items: stretch;
  }
  .job-tabs {
    flex-wrap: wrap;
  }
  .job-tab {
    flex: 1;
  }
  .ctrl-select {
    width: 100%;
  }
}
</style>
