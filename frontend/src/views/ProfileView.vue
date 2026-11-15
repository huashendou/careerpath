<script setup>
import PagePlaceholder from '../components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="技能画像"
    phase="T3"
    summary="按 6 个维度维护 0-5 级自评，并为技能挂证据、项目。掌握程度与证据是两条正交信息，永远分开展示——证据不参与算分，只决定「这份能力能不能拿出材料」。"
    :features="[
      '自评：等级用行为锚定（做过什么），不是「熟练/精通」',
      '证据：课程/项目/实习/证书/仓库，记录类型、完整度、相关性、核验状态',
      '项目：项目作为证据来源，记录角色、技术栈、本人工作、仓库链接',
      '缺证据标记：已掌握但没有任何证据的技能单独列一份清单',
      '个人档案：学校/专业/年级/目标岗位/地区（最小必要收集）',
    ]"
    :apis="[
      'GET /api/profile',
      'PUT /api/profile',
      'GET /api/user-skills',
      'PUT /api/user-skills',
      'GET /api/evidence',
      'POST /api/evidence',
      'GET /api/projects',
      'POST /api/projects',
    ]"
  />
</template>
