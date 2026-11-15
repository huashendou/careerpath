<script setup>
import PagePlaceholder from '../components/PagePlaceholder.vue'
import ServiceHealthCard from '../components/ServiceHealthCard.vue'
</script>

<template>
  <div class="page">
    <ServiceHealthCard />

    <PagePlaceholder
      title="概览"
      phase="T1"
      summary="登录后的第一屏：一句话告诉你现在处于哪一步、下一步该做什么。数字全部来自真实库，没有数据就显示空态，不显示 0。"
      :features="[
        '档案完成度：学校/专业/年级/目标岗位是否填齐，缺哪项提示补哪项',
        '最近一次匹配：必备分与加分分分开显示，附 Top 3 优先补强',
        '待办：未完成的学习任务、尚未挂证据的技能数量',
        '引导：无岗位 → 去岗位解析；无自评 → 去技能画像',
      ]"
      :apis="['GET /api/stats/overview', 'GET /api/profile']"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
