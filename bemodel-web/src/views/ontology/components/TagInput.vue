<template>
  <div class="tag-input">
    <el-tag
      v-for="(t, i) in modelValue"
      :key="`${t}-${i}`"
      closable
      size="small"
      class="tag-input-tag"
      @close="remove(i)"
    >{{ t }}</el-tag>
    <el-input
      v-model="draft"
      size="small"
      :placeholder="placeholder"
      class="tag-input-field"
      @keyup.enter="add"
    >
      <template #append>
        <el-button @click="add">添加</el-button>
      </template>
    </el-input>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  placeholder: { type: String, default: '输入后回车添加' }
})
const emit = defineEmits(['update:modelValue'])

const draft = ref('')

const add = () => {
  const v = draft.value.trim()
  if (!v) return
  if (!props.modelValue.includes(v)) {
    emit('update:modelValue', [...props.modelValue, v])
  }
  draft.value = ''
}

const remove = (i) => {
  const next = [...props.modelValue]
  next.splice(i, 1)
  emit('update:modelValue', next)
}
</script>

<style scoped>
.tag-input {
  width: 100%;
}

.tag-input-tag {
  margin: 0 6px 6px 0;
}

.tag-input-field {
  width: 220px;
}
</style>
