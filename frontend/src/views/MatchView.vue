<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/client'
import SkillRadar from '../components/SkillRadar.vue'

/**
 * 岗位匹配（模块 6）。
 *
 * 这一页的存在意义是「可解释」，因此三条口径必须肉眼可见：
 *  1. 必备分与加分分分开，**没有总分**；某侧无要求显示 null 而不是 0%；
 *  2. 证据单独一行，不乘进分数；
 *  3. 差距清单给出 权重×差距 的优先级，而不是一个笼统的「匹配度 42%」。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(true)
const match = ref(null)
const errorText = ref('')
const saving = ref(false)
const generating = ref(false)
const dimNames = ref({})

const jobId = computed(() => route.params.jobId)

const statusHint = computed(() => {
  if (!match.value) return ''
  if (match.value.status === 'EMPTY_PROFILE') return '尚未完成自评：先到「技能画像 → 技能自评」给技能打分，才谈得上匹配度。'
  if (match.value.status === 'NO_REQUIRED_REQUIREMENT') return '该岗位没有必备要求，无法计算必备分——这不是 0 分。'
  return ''
})

const scoreText = (value) => (value == null ? '—' : `${Number(value).toFixed(1)}%`)

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    match.value = await http.post(`/match/${jobId.value}`)
    try {
      const cats = await http.get('/skills/categories')
      dimNames.value = Object.fromEntries(cats.items.map((item) => [item.id, item.name]))
    } catch {
      dimNames.value = {}
    }
  } catch (error) {
    errorText.value = error.message || '匹配失败'
  } finally {
    loading.value = false
  }
}

async function saveReport() {
  saving.value = true
  try {
    const data = await http.post(`/reports/${jobId.value}`)
    ElMessage.success(`报告已存档（#${data.id}）`)
  } catch (error) {
    ElMessage.error(error.message || '存档失败')
  } finally {
    saving.value = false
  }
}

async function generateTasks() {
  generating.value = true
  try {
    const data = await http.post(`/tasks/from-gap/${jobId.value}`, { topN: 3 })
    if (data.created) {
      ElMessage.success(`已按差距生成 ${data.created} 个学习任务，去「学习计划」查看`)
    } else {
      ElMessage.info('这几个差距都已有未完成任务，不用重复创建')
    }
  } catch (error) {
    ElMessage.error(error.message || '生成任务失败')
  } finally {
    generating.value = false
  }
}

function printPage() {
  window.print()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="top">
      <div>
        <h2 class="title">{{ match?.jobTitle || '岗位匹配' }}</h2>
        <p class="sub">
          算分只用到「自评等级 + 岗位要求」；证据只决定覆盖面和「拿不拿得出材料」，不参与分数。
        </p>
      </div>
      <div class="top-actions no-print">
        <el-button @click="router.push('/jobs')">返回岗位</el-button>
        <el-button :loading="saving" @click="saveReport">存档报告</el-button>
        <el-button @click="printPage">打印</el-button>
      </div>
    </div>

    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" show-icon />

    <template v-if="match">
      <el-alert v-if="statusHint" type="warning" :closable="false" :title="statusHint" show-icon class="no-print" />

      <div class="scores">
        <div class="score">
          <span class="score-label">必备匹配</span>
          <span class="score-value" :class="{ null: match.requiredScore == null }">{{ scoreText(match.requiredScore) }}</span>
          <span class="score-foot cp-num">分子/分母 {{ match.numerator }} / {{ match.denominator }}</span>
        </div>
        <div class="score">
          <span class="score-label">加分匹配</span>
          <span class="score-value" :class="{ null: match.bonusScore == null }">{{ scoreText(match.bonusScore) }}</span>
          <span class="score-foot cp-num">
            {{ match.bonusDenominator ? `分子/分母 ${match.bonusNumerator} / ${match.bonusDenominator}` : '该岗位无加分要求' }}
          </span>
        </div>
        <div class="score">
          <span class="score-label">证据覆盖</span>
          <span class="score-value" :class="{ null: match.evidenceCoverage == null }">{{ scoreText(match.evidenceCoverage) }}</span>
          <span class="score-foot">必备技能中有材料的比例，独立展示</span>
        </div>
        <div class="score">
          <span class="score-label">要求分布</span>
          <span class="score-value small cp-num">
            必备 {{ match.counts.requiredTotal }} · 加分 {{ match.counts.bonusMastered + match.counts.bonusPending }}
          </span>
          <span class="score-foot cp-num">
            已掌握 {{ match.counts.requiredMastered }} / 偏弱 {{ match.counts.requiredWeak }} / 缺失 {{ match.counts.requiredMissing }}
          </span>
        </div>
      </div>

      <div class="cols">
        <el-card shadow="never" class="card">
          <template #header><span class="card-title">六维雷达</span></template>
          <SkillRadar :axes="match.radar.axes" :size="320" />
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-head">
              <span class="card-title">优先补齐（按 权重×差距）</span>
              <el-button class="no-print" size="small" type="primary" plain :loading="generating" @click="generateTasks">
                生成学习任务
              </el-button>
            </div>
          </template>
          <div v-if="match.gaps.length" class="gaps">
            <div v-for="gap in match.gaps" :key="gap.skillId" class="gap">
              <div class="gap-main">
                <span class="gap-name">{{ gap.skillName }}</span>
                <el-tag size="small" :type="gap.status === 'missing' ? 'danger' : 'warning'" effect="plain">
                  {{ gap.status === 'missing' ? '缺失' : '偏弱' }}
                </el-tag>
                <el-tag v-if="!gap.hasEvidence" size="small" type="info" effect="plain">缺证据</el-tag>
              </div>
              <div class="gap-meta cp-num">
                <span>L{{ gap.level }} → L{{ gap.target }}</span>
                <span v-if="gap.effectiveLevel !== gap.level">（按替代技能算 L{{ gap.effectiveLevel }}）</span>
                <span>权重 {{ gap.weight }}</span>
                <span>优先级 {{ gap.priority }}</span>
              </div>
            </div>
          </div>
          <el-empty v-else description="必备技能都达到了目标等级" :image-size="64" />
        </el-card>
      </div>

      <el-card shadow="never" class="card">
        <template #header><span class="card-title">加分项（不参与任何百分比）</span></template>
        <div v-if="match.bonusItems.length" class="bonus">
          <el-tag
            v-for="item in match.bonusItems"
            :key="item.skillId"
            :type="item.status === 'mastered' ? 'success' : 'info'"
            effect="plain"
          >
            {{ item.skillName }} · L{{ item.level }}/L{{ item.target }} · {{ item.status === 'mastered' ? '已具备' : '待补齐' }}
          </el-tag>
        </div>
        <el-empty v-else description="该岗位没有加分要求" :image-size="56" />
      </el-card>

      <p class="meta cp-muted">
        算分口径 {{ match.meta?.scoringVersion }} · {{ match.meta?.displayBadge }} ·
        {{ match.meta?.disclaimer }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.title {
  font-size: 18px;
  font-weight: 600;
}

.sub {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--cp-text-3);
  max-width: 640px;
}

.top-actions {
  display: flex;
  gap: 8px;
}

.scores {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 10px;
}

.score {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 9px;
}

.score-label {
  font-size: 11.5px;
  color: var(--cp-text-3);
}

.score-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--cp-primary);
  font-variant-numeric: tabular-nums;
}

.score-value.small {
  font-size: 15px;
  padding-top: 7px;
}

.score-value.null {
  color: var(--cp-text-3);
}

.score-foot {
  font-size: 11px;
  color: var(--cp-text-3);
}

.cols {
  display: grid;
  grid-template-columns: minmax(320px, 380px) 1fr;
  gap: 12px;
}

@media (max-width: 980px) {
  .cols {
    grid-template-columns: 1fr;
  }
}

.card {
  border-radius: 10px;
}

.card-title {
  font-size: 13px;
  font-weight: 600;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.gaps {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.gap {
  padding: 8px 10px;
  border: 1px solid var(--cp-border);
  border-radius: 7px;
}

.gap-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.gap-name {
  font-size: 12.5px;
  font-weight: 500;
}

.gap-meta {
  display: flex;
  gap: 12px;
  margin-top: 2px;
  font-size: 11px;
  color: var(--cp-text-3);
}

.bonus {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.meta {
  font-size: 11px;
  line-height: 1.7;
}

/* 打印：只留结论，按钮与提示不占版面 */
@media print {
  .no-print {
    display: none !important;
  }

  .page {
    gap: 10px;
  }

  .score,
  .card {
    border-color: #ccc;
    box-shadow: none;
  }
}
</style>
