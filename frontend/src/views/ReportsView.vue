<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api/client'
import { fmtScore, fmtTime, relativeTime } from '../utils/format'

const reports = ref([])
const loading = ref(true)
const error = ref('')

const best = computed(() => {
  if (!reports.value.length) return null
  return reports.value.reduce((a, b) => {
    if (a.matchScore === null) return b
    if (b.matchScore === null) return a
    return b.matchScore > a.matchScore ? b : a
  })
})

onMounted(async () => {
  try {
    const data = await http.get('/reports')
    reports.value = data.reports
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
        <h1 class="page-title">历史报告</h1>
        <p class="page-sub">每次存档保存当时的自评快照。之后修改自评，不会影响已有报告。</p>
      </div>
      <RouterLink to="/analysis" class="btn btn-primary">做新的分析</RouterLink>
    </header>

    <div v-if="error" class="alert alert-error page-alert">{{ error }}</div>

    <div v-if="loading" class="card card-pad">
      <div class="skeleton" style="height: 18px; width: 34%"></div>
      <div class="skeleton" style="height: 54px; margin-top: 14px"></div>
      <div class="skeleton" style="height: 54px; margin-top: 8px"></div>
    </div>

    <div v-else-if="!reports.length" class="card empty">
      <p class="empty-title">还没有存档的报告</p>
      <p class="empty-sub">在匹配分析页点「存为报告」，就会留在这里做对比</p>
      <RouterLink to="/analysis" class="btn btn-primary">去分析</RouterLink>
    </div>

    <template v-else>
      <p class="list-meta">
        共 <b class="num">{{ reports.length }}</b> 份
        <template v-if="best">
          <span class="sep">·</span>
          最高 <b class="num">{{ fmtScore(best.matchScore) }}%</b>（{{ best.jobName }}）
        </template>
      </p>

      <div class="report-list">
        <RouterLink
          v-for="r in reports"
          :key="r.id"
          :to="`/reports/${r.id}`"
          class="report-row card"
        >
          <div class="row-main">
            <span class="row-job">{{ r.jobName }}</span>
            <span class="row-time">{{ fmtTime(r.createdAt) }} · {{ relativeTime(r.createdAt) }}</span>
          </div>
          <div class="row-score">
            <span class="num score-v">{{ fmtScore(r.matchScore) }}</span>
            <span class="score-pct">%</span>
          </div>
          <svg class="row-arrow" viewBox="0 0 16 16" aria-hidden="true">
            <path d="M6 3l5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </RouterLink>
      </div>
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

.page-alert {
  margin-bottom: 16px;
}

.list-meta {
  margin-bottom: 10px;
  font-size: 12.5px;
  color: var(--text-2);
}

.list-meta b {
  color: var(--text);
  font-weight: 600;
}

.sep {
  margin: 0 7px;
  color: var(--text-3);
}

.report-list {
  display: grid;
  gap: 8px;
}

.report-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.1s;
}

.report-row:hover {
  border-color: #cdd0e2;
  box-shadow: var(--shadow);
}

.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.row-job {
  font-size: 14px;
  font-weight: 550;
}

.row-time {
  font-size: 12px;
  color: var(--text-3);
}

.row-score {
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.score-v {
  font-size: 22px;
  font-weight: 620;
  letter-spacing: -0.02em;
}

.score-pct {
  font-size: 12px;
  color: var(--text-3);
}

.row-arrow {
  width: 16px;
  height: 16px;
  color: var(--text-3);
  flex-shrink: 0;
}

@media (max-width: 600px) {
  .page-head {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
