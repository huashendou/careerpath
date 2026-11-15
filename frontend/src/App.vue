<script setup>
// 布局外壳在路由层决定：登录页裸渲染，其余页面套 AppLayout。
// 这样 App.vue 只负责挂 RouterView，不掺业务判断。
</script>

<template>
  <RouterView />
</template>
