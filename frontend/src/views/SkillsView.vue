<script setup>
import PagePlaceholder from '../components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="技能图谱"
    phase="T3"
    summary="技能分类体系与技能关系的只读视图。它回答「这个方向由哪些能力构成、先学哪个再学哪个」，不掺个人数据。"
    :features="[
      '6 个能力维度：编程基础、技术栈、数据与存储、工程能力、业务与协作、学习与成长',
      '61 项技能：按维度分组，点开看说明与同义词（同义词只服务解析，不进页面主视图）',
      '技能关系：前置（prerequisite）、相关（related）、生态（ecosystem）三类',
      '搜索：按技能名或别名搜索，命中标注所属维度',
    ]"
    :apis="['GET /api/skills/categories', 'GET /api/skills', 'GET /api/skills/relations']"
  />
</template>
