<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([RadarChart, TooltipComponent, CanvasRenderer])

const props = defineProps({
  axes: { type: Array, required: true },
})

const el = ref(null)
let chart = null
let ro = null

const LEVEL_MAX = 5

function buildOption() {
  const axes = props.axes || []
  // 无 core 的维度返回 null，这里保持 null 让折线断开，绝不用 0 顶替
  const target = axes.map((a) => (a.hasCore && a.target !== null ? a.target : null))
  const current = axes.map((a) => (a.hasCore && a.current !== null ? a.current : null))

  return {
    animationDuration: 420,
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(28,31,42,.94)',
      borderWidth: 0,
      padding: [9, 12],
      textStyle: { color: '#fff', fontSize: 12 },
      formatter: () => {
        const rows = axes
          .map((a) => {
            if (!a.hasCore) {
              return `<div style="color:rgba(255,255,255,.55)">${a.name}　本岗位无硬性要求</div>`
            }
            const t = a.target === null ? '—' : a.target.toFixed(2)
            const c = a.current === null ? '—' : a.current.toFixed(2)
            const ok = a.target !== null && a.current !== null && a.current >= a.target
            return `<div style="display:flex;justify-content:space-between;gap:18px">
              <span>${a.name}</span>
              <span style="font-variant-numeric:tabular-nums;color:${ok ? '#6ee7b7' : '#fcd34d'}">${c} / ${t}</span>
            </div>`
          })
          .join('')
        return `<div style="font-weight:600;margin-bottom:5px">当前 / 目标</div>${rows}`
      },
    },
    radar: {
      center: ['50%', '50%'],
      radius: '74%',
      splitNumber: 5,
      axisName: {
        color: '#5b6172',
        fontSize: 12,
        formatter: (name) => {
          const axis = axes.find((a) => a.name === name)
          const label = name.length > 6 ? name.slice(0, 5) + '…' : name
          return axis && !axis.hasCore ? `{off|${label}}` : label
        },
        rich: {
          off: { color: '#b6bac7', fontSize: 12 },
        },
      },
      splitLine: { lineStyle: { color: '#dcdfeb' } },
      splitArea: {
        areaStyle: { color: ['rgba(246,247,251,.85)', 'rgba(255,255,255,0)'] },
      },
      axisLine: { lineStyle: { color: '#dcdfeb' } },
      indicator: axes.map((a) => ({ name: a.name, max: LEVEL_MAX, min: 0 })),
    },
    series: [
      {
        type: 'radar',
        symbolSize: 4,
        data: [
          {
            value: target,
            name: '岗位要求',
            lineStyle: { color: '#b9a06a', width: 1.5, type: 'dashed' },
            itemStyle: { color: '#b9a06a' },
            areaStyle: { color: 'rgba(185,160,106,.07)' },
            connectNulls: false,
          },
          {
            value: current,
            name: '我的水平',
            lineStyle: { color: '#4f46e5', width: 2 },
            itemStyle: { color: '#4f46e5' },
            areaStyle: { color: 'rgba(79,70,229,.13)' },
            connectNulls: false,
          },
        ],
      },
    ],
  }
}

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value, null, { renderer: 'canvas' })
  chart.setOption(buildOption(), true)
}

watch(() => props.axes, render, { deep: true })

onMounted(() => {
  render()
  ro = new ResizeObserver(() => chart && chart.resize())
  ro.observe(el.value)
})

onBeforeUnmount(() => {
  if (ro) ro.disconnect()
  if (chart) chart.dispose()
  chart = null
})
</script>

<template>
  <div class="radar-block">
    <div ref="el" class="radar"></div>
    <div class="legend">
      <span class="legend-item"><i class="dot dot-target"></i>岗位要求</span>
      <span class="legend-item"><i class="dot dot-current"></i>我的水平</span>
    </div>
  </div>
</template>

<style scoped>
.radar-block {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
}

.radar {
  flex: 1;
  width: 100%;
  min-height: 320px;
}

.legend {
  display: flex;
  justify-content: center;
  gap: 20px;
  padding: 4px 0 6px;
  font-size: 12px;
  color: var(--text-2);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 14px;
  height: 3px;
  border-radius: 2px;
}

.dot-target {
  background: repeating-linear-gradient(
    to right,
    #b9a06a 0,
    #b9a06a 4px,
    transparent 4px,
    transparent 7px
  );
}

.dot-current {
  background: var(--primary);
}
</style>
