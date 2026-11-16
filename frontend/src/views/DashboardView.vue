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

/**
 * 主线四步。每一步的「完成与否」都取自 /api/stats/overview 的真实统计，
 * 不靠前端记忆「用户点没点过按钮」——刷新一次就失忆的进度条比没有更糟。
 *
 * 第 3 步判 parsedJobCount 而不是 jobCount：录了岗位但没解析，匹配根本跑不出来。
 * 若这里报「已完成」，用户会带着「都做完了」的预期走到第四步撞上「该岗位没有技能要求」，
 * 错在进度条说了假话。所以显示「N 个岗位，M 个已解析」，把差的那一步露出来。
 */
const steps = computed(() => {
  const d = data.value
  if (!d) return []
  const last = d.lastMatch
  return [
    {
      key: 'profile',
      no: 1,
      title: '补齐基本档案',
      done: d.profileCompleted,
      hint: d.profileCompleted ? '学校专业已记录' : '学校、专业任缺一项',
      action: d.profileCompleted ? '查看' : '去填写',
      to: '/profile?tab=basic',
    },
    {
      key: 'self',
      no: 2,
      title: '给技能自评',
      done: d.selfRatedCount > 0,
      hint: d.selfRatedCount > 0 ? `已自评 ${d.selfRatedCount} 项` : '一项都还没打分',
      action: d.selfRatedCount > 0 ? '继续补' : '去自评',
      to: '/profile?tab=skills',
    },
    {
      key: 'job',
      no: 3,
      title: '录入并解析岗位',
      done: d.parsedJobCount > 0,
      hint: d.jobCount === 0
        ? '还没录入岗位'
        : `${d.jobCount} 个岗位，${d.parsedJobCount} 个已解析`,
      action: d.parsedJobCount > 0 ? '管理' : '去解析',
      to: '/jobs',
    },
    {
      key: 'match',
      no: 4,
      title: '看差距在哪',
      done: Boolean(last),
      hint: last ? `上次：${last.jobTitle}` : '还没做过匹配',
      action: last ? '看报告' : '去匹配',
      to: last ? `/match/${last.jobPositionId}` : '/jobs',
    },
  ]
})

/** 当前步 = 第一个没完成的。全部完成时为 null，页面切成「已走通」的收尾提示。 */
const currentStep = computed(() => steps.value.find((step) => !step.done) || null)

/** 辅助数字：只留主线相关的三个。投递/面试是二期，数据看板里有它们的切法，不占首屏。 */
const cards = computed(() => {
  const d = data.value
  if (!d) return []
  return [
    {
      key: 'jobs',
      label: '目标岗位',
      value: d.jobCount,
      unit: '个',
      hint: d.parsedJobCount === 0 ? '都还没解析' : `${d.parsedJobCount} 个已解析`,
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
      hint: d.evidenceMissingCount === 0 ? '都挂上材料了' : '会做但拿不出材料',
      to: '/profile?tab=evidence',
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
      <h1>{{ auth.displayName ? `${auth.displayName}，` : '' }}{{ currentStep ? `第 ${currentStep.no} 步：${currentStep.title}` : '主线四步都走通了' }}</h1>
      <p class="sub">
        <template v-if="currentStep">跟着下面的第 {{ currentStep.no }} 步走，其余步骤灰着不用管。</template>
        <template v-else>继续补证据、生成学习任务，或者到「岗位」页换一个岗位再比一次。</template>
      </p>
    </header>

    <!-- 主线四步：概览页的第一屏只回答「下一步做什么」，不铺数字 -->
    <section v-if="data" class="flow">
      <ol class="steps">
        <li
          v-for="step in steps"
          :key="step.key"
          class="step"
          :class="{ done: step.done, current: currentStep && currentStep.key === step.key }"
        >
          <button type="button" class="step-btn" @click="router.push(step.to)">
            <span class="step-no">{{ step.done ? '✓' : step.no }}</span>
            <span class="step-body">
              <span class="step-title">{{ step.title }}</span>
              <span class="step-hint">{{ step.hint }}</span>
            </span>
            <span class="step-action">{{ step.action }} ›</span>
          </button>
        </li>
      </ol>

      <!-- 四步全绿时没有「当前步」可高亮，页面会失去落点。给一条收尾指引，别让首屏空转 -->
      <p v-if="!currentStep" class="flow-done">
        四步都走通了。接下来可以
        <el-link type="primary" :underline="false" @click="router.push('/profile?tab=evidence')">给技能挂上证据</el-link>
        或
        <el-link type="primary" :underline="false" @click="router.push('/jobs')">换一个岗位再比一次</el-link>
        。
      </p>
    </section>

    <el-skeleton v-else-if="loading" :rows="2" animated />

    <el-card v-else shadow="never">
      <p class="muted">概览数据加载失败。<el-link type="primary" :underline="false" @click="load">重试</el-link></p>
    </el-card>

    <!-- 最近一次匹配：结论直接摊开，不用回岗位列表里找 -->
    <section class="block">
      <div class="block-head">
        <h2>最近一次匹配</h2>
        <el-link v-if="lastMatch" type="primary" :underline="false" @click="router.push('/jobs')">
          换一个岗位
        </el-link>
      </div>

      <div v-if="lastMatch" class="match">
        <div class="match-top">
          <div class="match-title">{{ lastMatch.jobTitle }}</div>
          <el-button type="primary" size="small" @click="router.push(`/match/${lastMatch.jobPositionId}`)">
            看完整差距
          </el-button>
        </div>
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
        <p class="muted small">
          必备与加分分开计算，不合成总分；证据不参与算分。打开报告会按你当前的自评重算一次。
        </p>
      </div>

      <el-empty v-else-if="!loading" :description="currentStep ? '还没有匹配记录，先完成前面几步' : '还没有匹配记录'">
        <el-button type="primary" @click="router.push('/jobs')">录入第一个岗位</el-button>
      </el-empty>
    </section>

    <!-- 辅助数字：只放和主线相关的三个，点进去都落在对应位置 -->
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

    <!-- 数据看板（二期模块 9）：默认收起，它讲的是「已经攒下的数据」，不该抢主线的地方 -->
    <el-collapse v-if="insights" class="board-collapse">
      <el-collapse-item name="board">
        <template #title>
          <span class="board-tab">数据看板</span>
          <el-tag size="small" type="info" effect="plain">二期</el-tag>
        </template>

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
      </el-collapse-item>
    </el-collapse>

    <!-- 服务自检是开发排障用的，不该和业务内容平铺在第一屏。
         它独立成一个折叠项、不挂在 insights 的 v-if 下：接口挂了的时候，恰恰最需要看它。 -->
    <el-collapse class="board-collapse">
      <el-collapse-item name="health">
        <template #title>
          <span class="board-tab">服务自检</span>
          <el-tag size="small" effect="plain">开发</el-tag>
        </template>
        <ServiceHealthCard />
      </el-collapse-item>
    </el-collapse>

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

/* ---------------------------------------------------------------- 主线四步 */

.steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.step-btn {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  height: 100%;
  padding: 12px 13px;
  text-align: left;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.step-btn:hover,
.step-btn:focus-visible {
  border-color: var(--cp-primary);
  outline: none;
}

.step-no {
  flex: none;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #eef0f6;
  color: var(--cp-text-3);
  font-size: 11.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.step-body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.step-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--cp-text-2);
}

.step-hint {
  font-size: 11px;
  line-height: 1.4;
  color: var(--cp-text-3);
}

.step-action {
  margin-left: auto;
  flex: none;
  font-size: 11px;
  color: var(--cp-text-3);
}

/* 已完成：勾 + 弱化，让它退到背景里，眼睛自然落在没完成的那步 */
.step.done .step-btn {
  background: #fbfbfd;
}

.step.done .step-no {
  background: var(--cp-primary-weak);
  color: var(--cp-primary);
}

.step.done .step-title {
  color: var(--cp-text-3);
}

/* 当前步：唯一一处强调色，页面上「现在该干什么」只有这一个答案 */
.step.current .step-btn {
  border-color: var(--cp-primary);
  box-shadow: 0 6px 18px -14px rgba(79, 70, 229, 0.85);
}

.step.current .step-no {
  background: var(--cp-primary);
  color: #fff;
}

.step.current .step-title {
  color: var(--cp-text);
}

.step.current .step-action {
  color: var(--cp-primary);
  font-weight: 600;
}

@media (max-width: 900px) {
  .steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.flow-done {
  margin-top: 10px;
  font-size: 12px;
  color: var(--cp-text-3);
}

@media (max-width: 560px) {
  .steps {
    grid-template-columns: 1fr;
  }
  .step-action {
    display: none;
  }
}

/* ---------------------------------------------------------------- 匹配结论 */

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

.match-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
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

/* ---------------------------------------------------------------- 辅助数字 */

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

/* ---------------------------------------------------------------- 数据看板 */

.board-collapse {
  border-top: 1px solid var(--cp-border);
  border-bottom: none;
}

.board-tab {
  margin-right: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--cp-text-2);
}

.board {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 18px;
  padding: 4px 0 8px;
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

.disclaimer {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--cp-text-3);
}
</style>
