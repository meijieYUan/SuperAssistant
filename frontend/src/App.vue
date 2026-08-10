<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-header">
        <span class="logo">S</span>
        <span class="brand">SuperAssistant</span>
      </div>
      <nav>
        <router-link to="/" class="nav-item" title="Chat">
          <MessageSquare :size="18" /><span class="nav-label">Chat</span>
        </router-link>
        <router-link to="/todos" class="nav-item" title="Todos">
          <CheckSquare :size="18" /><span class="nav-label">Todos</span>
        </router-link>
        <router-link to="/knowledge" class="nav-item" title="Knowledge">
          <Upload :size="18" /><span class="nav-label">Knowledge</span>
        </router-link>
        <router-link to="/plans" class="nav-item" title="Plans">
          <GitBranch :size="18" /><span class="nav-label">Plans</span>
        </router-link>
      </nav>
      <div class="sidebar-footer">
        <span :class="['status-dot', backendUp ? 'up' : 'down']"></span>
        <span class="status-text">{{ backendUp ? 'Backend online' : 'Backend offline' }}</span>
      </div>
    </aside>
    <main class="main-content scrollbar">
      <router-view />
    </main>
    <ToastHost />
  </div>
</template>

<script setup>
import { MessageSquare, CheckSquare, Upload, GitBranch } from 'lucide-vue-next'
import { ref, onMounted, onUnmounted } from 'vue'
import ToastHost from './components/ToastHost.vue'
import { getHealth } from './api'

const backendUp = ref(true)
let timer = null

async function checkHealth() {
  try {
    await getHealth()
    backendUp.value = true
  } catch {
    backendUp.value = false
  }
}

onMounted(() => {
  checkHealth()
  timer = setInterval(checkHealth, 10000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.app-shell { display: flex; height: 100%; }
.sidebar {
  width: 220px; background: var(--bg2); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; flex-shrink: 0;
  transition: width .2s ease;
}
.sidebar-header {
  display: flex; align-items: center; gap: 10px; padding: 20px 18px;
  font-size: 15px; font-weight: 700; border-bottom: 1px solid var(--border);
  white-space: nowrap; overflow: hidden;
}
.logo {
  width: 32px; height: 32px; flex-shrink: 0;
  background: linear-gradient(135deg, var(--accent), var(--accent2)); color: #fff;
  border-radius: 8px; display: flex; align-items: center; justify-content: center;
  font-weight: 800; font-size: 16px;
  box-shadow: 0 2px 8px rgba(108, 140, 255, .35);
}
nav { flex: 1; padding: 12px 10px; display: flex; flex-direction: column; gap: 4px; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border-radius: var(--radius); color: var(--text2); font-size: 13px; transition: all .15s;
  white-space: nowrap; overflow: hidden;
}
.nav-item svg { flex-shrink: 0; }
.nav-item:hover { background: var(--bg3); color: var(--text); }
.nav-item.router-link-active {
  background: rgba(108, 140, 255, .12); color: var(--accent);
  box-shadow: inset 2px 0 0 var(--accent);
}
.sidebar-footer { padding: 14px 18px; border-top: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.status-dot.up { background: var(--green); box-shadow: 0 0 6px rgba(52, 211, 153, .6); }
.status-dot.down { background: var(--red); box-shadow: 0 0 6px rgba(248, 113, 113, .6); }
.status-text { font-size: 12px; color: var(--text2); }
.main-content { flex: 1; overflow-y: auto; padding: 24px; }

@media (max-width: 900px) {
  .sidebar { width: 60px; }
  .brand, .nav-label, .status-text { display: none; }
  .sidebar-header { justify-content: center; padding: 16px 0; }
  .nav-item { justify-content: center; padding: 10px 0; }
  .sidebar-footer { justify-content: center; padding: 14px 0; }
  .main-content { padding: 12px; }
}
</style>
