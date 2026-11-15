<script setup>
import PagePlaceholder from '../components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="学习计划"
    phase="T5"
    summary="把能力差距变成可执行的任务：差距 → 任务 → 完成 → 技能等级更新 → 重新匹配。闭环的每一步都在这一页看得见。"
    :features="[
      '从差距生成任务：一条缺口技能一条任务，优先级取 权重×差距',
      '任务状态机：todo / doing / done / paused，逾期高亮',
      '完成时询问是否更新技能等级，更新后提示可重新匹配',
      '手动任务：不挂技能的通用任务（如「整理项目 README」）也能建',
    ]"
    :apis="[
      'GET /api/tasks',
      'POST /api/tasks',
      'POST /api/tasks/from-gap/{jobId}',
      'PATCH /api/tasks/{id}',
      'POST /api/tasks/{id}/complete',
    ]"
  />
</template>
