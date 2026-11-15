<script setup>
import { computed } from 'vue'

/**
 * 六维雷达（手写 SVG，不引图表库）。
 *
 * 为什么不引 echarts：本页只需要一个静态六边形，引 900KB 的图表库来画 6 个点，
 * 构建产物和首屏都会为它买单；而且「空轴不画点」这种口径改起来比配置项直接得多。
 *
 * 空值口径严格照契约：某维度没有必备要求时 target/current 为 null，
 * **不画点、不连到 0**，只留一条灰色轴和「不要求」标注——连到 0 会被读成「这项你拿 0 分」。
 */
const props = defineProps({
  axes: { type: Array, default: () => [] },
  size: { type: Number, default: 300 },
  max: { type: Number, default: 5 },
})

const R = computed(() => props.size / 2 - 42)
const center = computed(() => props.size / 2)

function point(index, value) {
  const angle = (-90 + index * (360 / Math.max(props.axes.length, 1))) * (Math.PI / 180)
  const radius = (Math.max(0, Math.min(props.max, value)) / props.max) * R.value
  return [center.value + Math.cos(angle) * radius, center.value + Math.sin(angle) * radius]
}

function polygon(values) {
  return values.map((value, i) => point(i, value).join(',')).join(' ')
}

const rings = computed(() => [1, 2, 3, 4, 5].map((level) => polygon(props.axes.map(() => level))))

const targetPolygon = computed(() => {
  if (!props.axes.length || props.axes.some((axis) => axis.target == null)) return null
  return polygon(props.axes.map((axis) => axis.target))
})

const currentPolygon = computed(() => {
  if (!props.axes.length || props.axes.some((axis) => axis.current == null)) return null
  return polygon(props.axes.map((axis) => axis.current))
})

const spokes = computed(() => props.axes.map((axis, i) => {
  const end = point(i, props.max)
  const label = point(i, props.max * 1.18)
  return { axis, end, label, index: i }
}))

const dots = computed(() => props.axes
  .map((axis, i) => (axis.current == null ? null : { key: axis.dim, xy: point(i, axis.current) }))
  .filter(Boolean))

const incomplete = computed(() => props.axes.some((axis) => axis.target == null || axis.current == null))
</script>

<template>
  <div class="radar">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`" role="img" aria-label="六维能力雷达">
      <polygon v-for="(ring, i) in rings" :key="`ring-${i}`" :points="ring" class="ring" />
      <line
        v-for="spoke in spokes"
        :key="`spoke-${spoke.index}`"
        :x1="center"
        :y1="center"
        :x2="spoke.end[0]"
        :y2="spoke.end[1]"
        class="spoke"
        :class="{ muted: spoke.axis.target == null }"
      />
      <polygon v-if="targetPolygon" :points="targetPolygon" class="target" />
      <polygon v-if="currentPolygon" :points="currentPolygon" class="current" />
      <circle v-for="dot in dots" :key="dot.key" :cx="dot.xy[0]" :cy="dot.xy[1]" r="3.2" class="dot" />
      <text
        v-for="spoke in spokes"
        :key="`label-${spoke.index}`"
        :x="spoke.label[0]"
        :y="spoke.label[1]"
        class="axis-label"
        :class="{ muted: spoke.axis.target == null }"
        text-anchor="middle"
        dominant-baseline="middle"
      >
        {{ spoke.axis.short || spoke.axis.name }}
      </text>
      <text
        v-for="spoke in spokes"
        :key="`value-${spoke.index}`"
        :x="spoke.label[0]"
        :y="spoke.label[1] + 13"
        class="axis-value"
        text-anchor="middle"
      >
        {{ spoke.axis.target == null ? '不要求' : `${spoke.axis.current ?? '-'} / ${spoke.axis.target}` }}
      </text>
    </svg>
    <p v-if="incomplete" class="hint">空心轴为本岗位不要求，不画点、不参与连线</p>
  </div>
</template>

<style scoped>
.radar {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.ring {
  fill: none;
  stroke: #e9ebf3;
  stroke-width: 1;
}

.spoke {
  stroke: #e2e5ef;
  stroke-width: 1;
}

.spoke.muted {
  stroke: #f0f1f6;
  stroke-dasharray: 3 3;
}

.target {
  fill: none;
  stroke: #9aa0b5;
  stroke-width: 1.4;
  stroke-dasharray: 4 3;
}

.current {
  fill: rgba(79, 70, 229, 0.16);
  stroke: var(--cp-primary);
  stroke-width: 1.8;
}

.dot {
  fill: var(--cp-primary);
}

.axis-label {
  font-size: 11.5px;
  fill: var(--cp-text-2);
}

.axis-label.muted {
  fill: var(--cp-text-3);
}

.axis-value {
  font-size: 10.5px;
  fill: var(--cp-text-3);
}

.hint {
  font-size: 11.5px;
  color: var(--cp-text-3);
}
</style>
