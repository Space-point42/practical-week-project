<script setup>
import { onMounted } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

onMounted(() => {
  // 刷新页面后用本地令牌换回最新的用户信息（角色可能在别处被调整过）
  if (userStore.isLoggedIn) {
    userStore.fetchMe().catch(() => {
      /* 令牌失效由请求拦截器统一处理，这里静默即可 */
    })
  }
})
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>
