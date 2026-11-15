<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ServiceHealthCard from '../components/ServiceHealthCard.vue'
import http from '../api/client'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const data = ref(null)
const insights = ref(null)
const failed = ref(false)

/** 四张统计卡。数字全部来自 /api/stats/overview，没有一条是写死的示例值。 */
const cards = computed(() => {
  const d = data.value
  if (!d) return []
  const appTotal = (insights.value?.applicationStatus || []).reduce((sum, item) => sum + item.count, 0)
  const interviewTotal = (insights.value?.interviewResult || []).reduce((sum, item) => sum + item.count, 0)
  return [
    {
      key: 'jobs',
      label: '目标岗位',
      value: d.jobCount,
      unit: '个',
      hint: d.jobCount === 0 ? '还没有录入岗位' : '已录入并可解析',
      to: '/jobs',
    },
    {
      key: 'tasks',
      label: '待办学习任务',
      value: d.taskOpenCount,
      unit: '项',
      hint: d.taskOpenCount === 0 ? '暂无待办' : '含进行中',
      to: '/tasks',
    },
    {
      key: 'evidence',
      label: '缺证据的技能',
      value: d.evidenceMissingCount,
      unit: '项',
      hint: d.evidenceMissingCount === 0 ? '都挂上材料了' : '会但拿不出材料',
      to: '/profile',
    },
    {
      key: 'profile',
      label: '个人档案',
      value: d.profileCompleted ? '已完善' : '未填写',
      unit: '',
      hint: d.profileCompleted ? '学校专业已记录' : '补齐后匹配更准',
      to: '/profile',
    },
    {
      key: 'applications',
      label: '投递清单',
      value: appTotal,
      unit: '条',
      hint: appTotal === 0 ? '还没开始投' : '含待投与已结束',
      to: '/applications',
    },
    {
      key: 'interviews',
      label: '面试复盘',
      value: interviewTotal,
      unit: '场',
      hint: interviewTotal === 0 ? '面试后记得复盘' : '已记录的场次',
      to: '/interviews',
    },
  ]
})

/** 最近一次匹配。null 时给引导，不展示 0 分——0 分和「没测过」是两件事。 */
const lastMatch = computed(() => data.value?.lastMatch ?? null)

function scoreText(value) {
  // null 表示该侧没有要求（无必备/无加分），不是 0 分
  return value === null || value === undefined ? '无要求' : `${Number(value).toFixed(2)}%`
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [overview, dash] = await Promise.all([
      http.get('/stats/overview'),
      http.get('/stats/insights').catch(() => null),
    ])
    data.value = overview
    insights.value = dash
  } catch (error) {
    failed.value = true
    ElMessage.error(error.message || '概览数据加载失败')
  } finally {
    loading.value = false
  }
}

/** 分数条宽度：0–100 直接映射，null 不画条（「无要求」不是 0 分）。 */
function barWidth(value) {
  if (value == null) return 0
  return Math.max(2, Math.min(100, Number(value)))
}

const statusText = { todo: '待投', applied: '已投', interview: '面试中', closed: '已结束' }
const resultText = { pending: '待定', pass: '通过', reject: '未通过' }

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="greet">
      <h1>{{ auth.displayName ? `${auth.displayName}，` : '' }}今天先做哪一步？</h1>
      <p class="sub">录一个目标岗位 → 解析技能要求 → 对照自己的能力 → 看差在哪 → 生成学习任务。</p>
    </header>

    <ServiceHealthCard />

    <!-- 统计卡：有数据看数字，没数据看引导语；两种状态都不虚构数字 -->
    <div v-if="data" class="cards">
      <article
        v-for="card in cards"
        :key="card.key"
        class="card"
        role="button"
        tabindex="0"
        @click="router.push(card.to)"
        @keyup.enter="router.push(card.to)"
      >
        <div class="card-label">{{ card.label }}</div>
        <div class="card-value">
          {{ card.value }}<span v-if="card.unit" class="card-unit">{{ card.unit }}</span>
        </div>
        <div class="card-hint">{{ card.hint }}</div>
      </article>
    </div>

    <el-skeleton v-else-if="loading" :rows="2" animated />

    <el-card v-else shadow="never">
      <p class="muted">概览数据加载失败。<el-link type="primary" :underline="false" @click="load">重试</el-link></p>
    </el-card>

    <!-- 最近匹配 -->
    <section class="block">
      <div class="block-head">
        <h2>最近一次匹配</h2>
        <el-link v-if="lastMatch" type="primary" :underline="false" @click="router.push('/jobs')">
          查看全部岗位
        </el-link>
      </div>

      <div v-if="lastMatch" class="match">
        <div class="match-title">{{ lastMatch.jobTitle }}</div>
        <div class="match-scores">
          <div class="score">
            <span class="score-label">必备匹配</span>
            <span class="score-value">{{ scoreText(lastMatch.requiredScore) }}</span>
          </div>
          <div class="score">
            <span class="score-label">加分匹配</span>
            <span class="score-value">{{ scoreText(lastMatch.bonusScore) }}</span>
          </div>
        </div>
        <p class="muted small">必备与加分分开计算，不合成总分；证据不参与算分。</p>
      </div>

      <el-empty v-else-if="!loading" description="还没有匹配记录">
        <el-button type="primary" @click="router.push('/jobs')">录入第一个岗位</el-button>
      </el-empty>
    </section>

    <!-- 数据看板（二期模块 9）：不单开页面，趋势与分布直接进概览 -->
    <section v-if="insights" class="block">
      <div class="block-head">
        <h2>数据看板</h2>
        <span class="cp-muted small">只做已有数据的另一种切法，不额外埋点</span>
      </div>

      <div class="board">
        <div class="board-col">
          <h3 class="board-title">最近匹配分数</h3>
          <div v-if="insights.matchTrend.length" class="trend">
            <div v-for="(point, index) in insights.matchTrend" :key="index" class="trend-row">
              <span class="trend-date cp-num">{{ (point.at || '').slice(5, 10) }}</span>
              <div class="bar">
                <div class="bar-fill" :style="{ width: `${barWidth(point.requiredScore)}%` }" />
                <span v-if="point.requiredScore == null" class="bar-empty">无法算分</span>
              </div>
              <span class="trend-score cp-num">{{ point.requiredScore == null ? '—' : Number(point.requiredScore).toFixed(1) }}</span>
            </div>
          </div>
          <p v-else class="cp-muted small">还没有匹配记录</p>
        </div>

        <div class="board-col">
          <h3 class="board-title">反复出现的差距</h3>
          <div v-if="insights.topGaps.length" class="gaps">
            <div v-for="gap in insights.topGaps" :key="gap.skillId" class="gap">
              <span class="gap-name">{{ gap.skillName }}</span>
              <el-tag size="small" type="warning" effect="plain">{{ gap.count }} 个岗位</el-tag>
            </div>
          </div>
          <p v-else class="cp-muted small">没有重复出现的差距</p>
        </div>

        <div class="board-col">
          <h3 class="board-title">投递与面试</h3>
          <div class="chips">
            <el-tag v-for="item in insights.applicationStatus" :key="item.status" size="small" effect="plain">
              {{ statusText[item.status] || item.status }} {{ item.count }}
            </el-tag>
            <span v-if="!insights.applicationStatus.length" class="cp-muted small">投递清单为空</span>
          </div>
          <div class="chips">
            <el-tag v-for="item in insights.interviewResult" :key="item.result" size="small" type="info" effect="plain">
              {{ resultText[item.result] || item.result }} {{ item.count }}
            </el-tag>
            <span v-if="!insights.interviewResult.length" class="cp-muted small">还没有面试记录</span>
          </div>
          <p class="small cp-muted">
            学习任务完成率：
            <template v-if="insights.taskCompletion.rate == null">暂无任务</template>
            <template v-else>
              <b class="cp-num">{{ insights.taskCompletion.rate }}%</b>
              （{{ insights.taskCompletion.done }} / {{ insights.taskCompletion.total }}）
            </template>
          </p>
        </div>
      </div>
    </section>

    <p class="disclaimer">
      技能权重与目标等级为示例数据，依据公开招聘信息的常见要求归纳，不是任何企业官方标准。
    </p>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.greet h1 {
  font-size: 19px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.sub {
  margin-top: 4px;
  font-size: 13px;
  color: var(--cp-text-3);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(168px, 1fr));
  gap: 12px;
}

.card {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.card:hover,
.card:focus-visible {
  border-color: var(--cp-primary);
  box-shadow: 0 4px 16px -10px rgba(20, 24, 40, 0.3);
  outline: none;
}

.card-label {
  font-size: 12px;
  color: var(--cp-text-3);
}

.card-value {
  margin: 6px 0 4px;
  font-size: 22px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: var(--cp-text-1);
}

.card-unit {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: var(--cp-text-3);
}

.card-hint {
  font-size: 11.5px;
  color: var(--cp-text-3);
}

.block {
  padding: 16px 18px;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 10px;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.block-head h2 {
  font-size: 14px;
  font-weight: 600;
}

.match-title {
  font-size: 15px;
  font-weight: 600;
}

.match-scores {
  display: flex;
  gap: 32px;
  margin: 12px 0 10px;
}

.score {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.score-label {
  font-size: 12px;
  color: var(--cp-text-3);
}

.score-value {
  font-size: 20px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: var(--cp-primary);
}

.muted {
  color: var(--cp-text-3);
  font-size: 13px;
}

.small {
  font-size: 11.5px;
}

.disclaimer {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--cp-text-3);
}

.board {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 18px;
}

.board-title {
  margin-bottom: 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--cp-text-2);
}

.trend {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.trend-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--cp-text-3);
}

.trend-date {
  width: 42px;
  flex: none;
}

.bar {
  position: relative;
  flex: 1;
  height: 8px;
  background: #f1f2f8;
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: var(--cp-primary);
  border-radius: 4px;
}

.bar-empty {
  position: absolute;
  left: 4px;
  top: -3px;
  font-size: 9.5px;
  color: var(--cp-text-3);
}

.trend-score {
  width: 38px;
  text-align: right;
  flex: none;
  color: var(--cp-text-2);
}

.gaps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.gap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}

.gap-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}
</style>
