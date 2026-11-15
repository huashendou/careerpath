// 7 个页面 = 模块 1 / 3 / 4 / 5 / 6 / 7（c3.1 范围裁决：模块 2 求职工作台、8 面试复盘、
// 9 数据分析中心不做；模块 10 项目成果并入模块 5，作为证据来源，不单设页面）。
//
// 页面清单：登录 / 概览 / 岗位解析 / 技能图谱 / 技能画像 / 岗位匹配 / 学习计划。
// 岗位匹配是详情页（从岗位解析进入），因此侧边菜单只有 5 项。
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AppLayout from '../layouts/AppLayout.vue'

// T0 阶段账号体系尚未交付（T1 实装），此刻强行拦截会让评审打开任何页面都被弹回登录页。
// T1 完成后置为 true，并补一条「未登录访问 /jobs 跳登录」的测试。
const AUTH_ENFORCED = false

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { guest: true, title: '登录' },
  },
  {
    path: '/',
    component: AppLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/DashboardView.vue'),
        meta: { title: '概览', phase: 'T1', icon: 'Odometer' },
      },
      {
        path: 'jobs',
        name: 'jobs',
        component: () => import('../views/JobsView.vue'),
        meta: { title: '岗位解析', phase: 'T3', icon: 'Document' },
      },
      {
        path: 'skills',
        name: 'skills',
        component: () => import('../views/SkillsView.vue'),
        meta: { title: '技能图谱', phase: 'T3', icon: 'Share' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('../views/ProfileView.vue'),
        meta: { title: '技能画像', phase: 'T3', icon: 'User' },
      },
      {
        path: 'match/:jobId',
        name: 'match',
        component: () => import('../views/MatchView.vue'),
        meta: { title: '岗位匹配', phase: 'T4', activeMenu: '/jobs' },
      },
      {
        path: 'tasks',
        name: 'tasks',
        component: () => import('../views/TasksView.vue'),
        meta: { title: '学习计划', phase: 'T5', icon: 'Notebook' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (AUTH_ENFORCED && !to.meta.guest && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 职航 CareerPath` : '职航 CareerPath'
})

export default router
