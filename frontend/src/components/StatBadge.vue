<script setup>
defineProps({
  value: { type: [Number, String], default: 0 },
  label: { type: String, default: '' },
  tone: { type: String, default: 'primary' }, // primary | amber | red | green | muted
  size: { type: String, default: 'md' }, // sm | md
})
</script>

<template>
  <span class="badge" :class="[`tone-${tone}`, `size-${size}`]">
    <span v-if="value !== '' && value !== null" class="v num">{{ value }}</span>
    <span class="l">{{ label }}</span>
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 5px;
  font-size: 12px;
  line-height: 1.6;
  white-space: nowrap;
}

.v {
  font-weight: 600;
}

.tone-primary {
  background: var(--primary-weak);
  color: var(--primary-dark);
}

.tone-amber {
  background: var(--amber-weak);
  color: var(--amber);
}

.tone-red {
  background: var(--red-weak);
  color: #8f2a1f;
}

.tone-green {
  background: var(--green-weak);
  color: var(--green);
}

.tone-muted {
  background: #f1f2f6;
  color: var(--text-3);
}

.size-sm {
  font-size: 11.5px;
  padding: 1px 6px;
}
</style>
