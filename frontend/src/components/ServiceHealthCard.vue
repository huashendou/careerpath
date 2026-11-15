<script setup>
/**
 * T0 环境自检卡：真实请求 GET /api/health，不做任何假数据。
 *
 * 存在的理由：看板第五节的「交付定义」要求「页面能打开并完成一次真实请求」。
 * T0 还没有业务接口，唯一能打通全链路的请求就是 health——它同时验证了
 * 浏览器 → Vite 代理 → Java → MySQL / Python 这一整条链路。
 *
 * T1 做概览页时这张卡会被真实业务数字替换，届时直接删掉本组件即可。
 */
import { onMounted, ref } from 'vue'
import http, { ApiError } from '../api/client'

const loading = ref(true)
const data = ref(null)
const error = ref('')

async function check() {
  loading.value = true
  error.value = ''
  try {
    data.value = await http.get('/health')
  } catch (e) {
    // 后端不可达时 axios 抛的是 NETWORK；此时 data 必须清空，
    // 否则页面会继续显示上一次的 ok，比报错更误导。
    data.value = null
    error.value = e instanceof ApiError ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(check)

function tagType(status) {
  if (status === 'ok' || status === 'up') return 'success'
  if (status === 'degraded') return 'warning'
  return 'danger'
}
</script>

<template>
  <el-card shadow="never" class="card">
    <template #header>
      <div class="head">
        <span class="card-title">环境自检</span>
        <div class="head-right">
          <el-tag size="small" type="info" effect="plain">T0 · T1 概览页会替换</el-tag>
          <el-button size="small" text :loading="loading" @click="check">重新检测</el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      title="后端不可达或返回错误"
      :description="error"
    />

    <template v-else-if="data">
      <div class="row">
        <span class="label">主服务</span>
        <el-tag size="small" :type="tagType(data.status)" effect="dark">{{ data.status }}</el-tag>
        <span class="muted">{{ data.service }} · v{{ data.version }}</span>
      </div>

      <div class="row">
        <span class="label">数据库</span>
        <el-tag size="small" :type="tagType(data.database.status)" effect="dark">
          {{ data.database.status }}
        </el-tag>
        <span class="muted">
          {{ data.database.name }} ·
          {{ data.database.skillCount }} 项技能 / {{ data.database.categoryCount }} 个分类
        </span>
      </div>

      <div class="row">
        <span class="label">分析服务</span>
        <el-tag size="small" :type="tagType(data.analysis.status)" effect="dark">
          {{ data.analysis.status }}
        </el-tag>
        <span class="muted">
          词典 {{ data.analysis.dictionary.skillCount }} 项 · 耗时 {{ data.analysis.latencyMs }} ms
        </span>
      </div>

      <p class="hint">
        这张卡走的是「浏览器 → Vite 代理 → Java → MySQL / Python」全链路。
        分析服务单独挂掉时主服务仍是 <code>ok</code>，只标 <code>degraded</code>——这是刻意的降级策略。
      </p>
    </template>

    <el-skeleton v-else :rows="3" animated />
  </el-card>
</template>

<style scoped>
.card {
  border-radius: 10px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-title {
  font-size: 13.5px;
  font-weight: 600;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  font-size: 13px;
}

.row + .row {
  border-top: 1px dashed var(--cp-border);
}

.label {
  width: 64px;
  color: var(--cp-text);
  font-weight: 500;
}

.muted {
  color: var(--cp-text-2);
}

.hint {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--cp-text-3);
}

.hint code {
  padding: 1px 4px;
  border-radius: 4px;
  background: #f6f7fb;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
