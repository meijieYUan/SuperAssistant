<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-header">
        <span class="logo">S</span>
        <span>SuperAssistant</span>
      </div>
      <nav>
        <router-link to="/" class="nav-item">
          <MessageSquare :size="18" /><span>Chat</span>
        </router-link>
        <router-link to="/todos" class="nav-item">
          <CheckSquare :size="18" /><span>Todos</span>
        </router-link>
        <router-link to="/knowledge" class="nav-item">
          <Upload :size="18" /><span>Knowledge</span>
        </router-link>
        <router-link to="/plans" class="nav-item">
          <GitBranch :size="18" /><span>Plans</span>
        </router-link>
      </nav>
      <div class="sidebar-footer">
        <span class="status-dot"></span>
        <span class="status-text">Backend :8080</span>
      </div>
    </aside>
    <main class="main-content scrollbar">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { MessageSquare, CheckSquare, Upload, GitBranch } from 'lucide-vue-next'
</script>

<style scoped>
.app-shell { display: flex; height: 100%; }
.sidebar {
  width: 220px; background: var(--bg2); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; flex-shrink: 0;
}
.sidebar-header {
  display: flex; align-items: center; gap: 10px; padding: 20px 18px;
  font-size: 15px; font-weight: 700; border-bottom: 1px solid var(--border);
}
.logo {
  width: 32px; height: 32px; background: var(--accent); color: #fff;
  border-radius: 8px; display: flex; align-items: center; justify-content: center;
  font-weight: 800; font-size: 16px;
}
nav { flex: 1; padding: 12px 10px; display: flex; flex-direction: column; gap: 4px; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border-radius: var(--radius); color: var(--text2); font-size: 13px; transition: all .15s;
}
.nav-item:hover { background: var(--bg3); color: var(--text); }
.nav-item.router-link-active { background: rgba(108,140,255,.12); color: var(--accent); }
.sidebar-footer { padding: 14px 18px; border-top: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
.status-dot { width: 8px; height: 8px; background: var(--green); border-radius: 50%; }
.status-text { font-size: 12px; color: var(--text2); }
.main-content { flex: 1; overflow-y: auto; padding: 24px; }
</style>