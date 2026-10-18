<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps({
  content: { type: String, default: '' }
})

const html = computed(() => renderMarkdown(props.content))
</script>

<template>
  <!-- v-html 在此是安全的：markdown-it 已关闭 html 直通，渲染结果只含自身生成的标签 -->
  <div class="markdown-body" v-html="html" />
</template>
