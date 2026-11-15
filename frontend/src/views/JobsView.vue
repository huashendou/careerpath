<script setup>
import PagePlaceholder from '../components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="岗位解析"
    phase="T3"
    summary="粘贴一段真实 JD → 结构化出必备/加分技能。解析结果只是预填建议，必须逐条确认或修改后才落库；原文逐字保留，每条要求都能回指到 JD 的哪一句。"
    :features="[
      '岗位列表：我的岗位 + 3 条示例岗位（标注「示例数据」，只读）',
      '新建岗位：标题、公司名、城市、薪资、来源、JD 原文（原文不改写）',
      '解析高亮：命中技能按 sourceSpan 在原文标色，鼠标悬停看命中的别名',
      '逐条确认：改必备/加分、改目标等级与权重；权重不强制合计 100',
      '落库：确认后才写 job_skill_requirement，解析本身不落库',
    ]"
    :apis="[
      'GET /api/jobs',
      'POST /api/jobs',
      'GET /api/jobs/{id}',
      'POST /api/jobs/{id}/parse',
      'GET /api/jobs/{id}/requirements',
      'PUT /api/jobs/{id}/requirements',
    ]"
  />
</template>
