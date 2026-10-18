<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <div class="nf-code">404</div>
    <h2>页面不存在</h2>
    <p>你访问的地址可能已被移动或删除。</p>
    <div class="nf-actions">
      <el-button type="primary" @click="router.push('/dashboard')">返回首页</el-button>
      <el-button @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--page-plane);
  text-align: center;
}

.nf-code {
  font-size: 88px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.04em;
  background: linear-gradient(135deg, #2a78d6, #4a3aa7);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

h2 {
  margin: 18px 0 8px;
  font-size: 20px;
  font-weight: 620;
}

p {
  color: var(--ink-muted);
  font-size: 14px;
  margin: 0 0 26px;
}
</style>
