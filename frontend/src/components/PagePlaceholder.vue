<script setup>
/**
 * 阶段占位页。
 *
 * T0 只交付骨架，页面内容按 T1–T6 分批实现。这里刻意**不放假数据、不画假图表**：
 * 空页面 + 明确写出「本页交付什么、调哪些接口」比一张漂亮但假的图可信得多。
 */
defineProps({
  title: { type: String, required: true },
  phase: { type: String, required: true },
  summary: { type: String, default: '' },
  features: { type: Array, default: () => [] },
  apis: { type: Array, default: () => [] },
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="title">{{ title }}</h2>
        <p v-if="summary" class="summary">{{ summary }}</p>
      </div>
      <el-tag type="info" effect="plain">{{ phase }} 交付</el-tag>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :md="14">
        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">本页要做什么</span>
          </template>
          <ul class="feature-list">
            <li v-for="item in features" :key="item">{{ item }}</li>
          </ul>
          <el-empty description="骨架已就位，内容在对应阶段实现" :image-size="72" />
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">依赖接口</span>
          </template>
          <div v-if="apis.length" class="api-list">
            <code v-for="api in apis" :key="api" class="api">{{ api }}</code>
          </div>
          <el-empty v-else description="暂无接口" :image-size="56" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.title {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.summary {
  margin-top: 4px;
  font-size: 13px;
  color: var(--cp-text-2);
  max-width: 720px;
}

.card {
  border-radius: 10px;
}

.card-title {
  font-size: 13.5px;
  font-weight: 600;
}

.feature-list {
  margin: 0 0 8px;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--cp-text-2);
}

.api-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.api {
  display: block;
  padding: 7px 10px;
  border-radius: 6px;
  background: #f6f7fb;
  border: 1px solid var(--cp-border);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--cp-text-2);
}
</style>
