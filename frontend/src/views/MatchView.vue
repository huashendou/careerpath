<script setup>
import PagePlaceholder from '../components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="岗位匹配"
    phase="T4"
    summary="对着某个岗位算匹配度与能力差距。必备分与加分分分开出，不合并成一个总分；证据单独成一行，不乘进分数。每份结论都要能回答「为什么」。"
    :features="[
      '必备分 / 加分分独立显示；某侧无要求时显示「无必备要求」而不是 0%',
      '未做自评时显示「尚未完成自评」，不显示 0 分',
      '差距清单：按 权重×差距 降序，标缺失 / 偏弱，并标 hasEvidence（缺证据）',
      '六维雷达：无要求的维度保留轴标签并标注「本岗位不要求」，不画点',
      '报告快照：落库时写入 algorithmVersion，岗位模型后续变化不影响历史报告',
    ]"
    :apis="[
      'POST /api/match/{jobId}',
      'POST /api/reports/{jobId}',
      'GET /api/reports',
      'GET /api/reports/{id}',
    ]"
  />
</template>
