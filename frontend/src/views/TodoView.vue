<template>
  <div class="view-container">
    <div class="view-header">
      <h2>Todos</h2>
      <div class="filters">
        <select v-model="filterStatus">
          <option value="">All Status</option>
          <option>PENDING</option><option>RUNNING</option><option>COMPLETED</option><option>FAILED</option><option>CANCELLED</option>
        </select>
        <select v-model="filterPriority">
          <option value="">All Priority</option>
          <option>URGENT</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option>
        </select>
        <input v-model="filterKeyword" placeholder="Search..." @keydown.enter="doSearch" />
        <button class="btn-primary btn-sm" @click="doSearch">Search</button>
      </div>
    </div>

    <div class="tabs">
      <button :class="{ active: tab === 'all' }" @click="switchTab('all')">All</button>
      <button :class="{ active: tab === 'pending' }" @click="switchTab('pending')">Pending</button>
      <button :class="{ active: tab === 'overdue' }" @click="switchTab('overdue')">Overdue</button>
      <button :class="{ active: tab === 'search' }" @click="switchTab('search')">Search</button>
    </div>

    <div class="todo-list scrollbar" v-if="todos.length">
      <div v-for="t in todos" :key="t.id" class="todo-item card">
        <div class="todo-main">
          <span v-if="t.stepKey" class="todo-key">{{ t.stepKey }}</span>
          <span class="todo-title">{{ t.title }}</span>
          <div class="todo-meta">
            <span :class="'badge badge-' + prioClass(t.priority)">{{ t.priority }}</span>
            <span class="badge" :style="statusStyle(t.status)">{{ t.status }}</span>
            <span v-if="t.dueDate" class="todo-date">Due: {{ t.dueDate }}</span>
            <span v-if="t.assignedTo" class="todo-assignee">{{ t.assignedTo }}</span>
          </div>
        </div>
        <p v-if="t.objective" class="todo-obj">目标: {{ t.objective }}</p>
        <p v-if="t.description" class="todo-desc">{{ t.description }}</p>
        <p v-if="t.acceptanceCriteria" class="todo-accept">验收: {{ t.acceptanceCriteria }}</p>
        <p v-if="t.outputSummary" class="todo-output">产出: {{ t.outputSummary }}</p>
        <p v-if="t.errorMessage" class="todo-error">错误: {{ t.errorMessage }}</p>
      </div>
    </div>
    <div v-else class="empty-state">
      <CheckSquare :size="36" /><p>No todos found</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { CheckSquare } from 'lucide-vue-next'
import { getTodos, getPendingTodos, getOverdueTodos, queryTodos } from '../api'

const tab = ref('all')
const todos = ref([])
const filterStatus = ref(''), filterPriority = ref(''), filterKeyword = ref('')

function switchTab(t) {
  tab.value = t
  if (t === 'all') fetchTodos()
  else if (t === 'pending') fetchPending()
  else if (t === 'overdue') fetchOverdue()
  else if (t === 'search') doSearch()
}

function fetchTodos() { getTodos().then(r => todos.value = r.data || []).catch(() => {}) }
function fetchPending() { getPendingTodos().then(r => todos.value = r.data || []).catch(() => {}) }
function fetchOverdue() { getOverdueTodos().then(r => todos.value = r.data || []).catch(() => {}) }
function doSearch() {
  queryTodos(filterStatus.value, filterPriority.value, filterKeyword.value)
    .then(r => todos.value = r.data || []).catch(() => {})
}

function prioClass(p) {
  const m = { URGENT: 'high', HIGH: 'high', MEDIUM: 'medium', LOW: 'low' }
  return m[p] || 'low'
}
function statusStyle(s) {
  const m = { PENDING: '#fbbf24', RUNNING: '#6c8cff', COMPLETED: '#34d399', FAILED: '#ef4444', CANCELLED: '#8b90a0' }
  return { background: (m[s]||'#8b90a0')+'22', color: m[s]||'#8b90a0' }
}

onMounted(fetchTodos)
</script>

<style scoped>
.view-container { max-width: 800px; margin: 0 auto; }
.view-header { margin-bottom: 16px; }
.view-header h2 { font-size: 20px; margin-bottom: 12px; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; }
.filters select, .filters input { width: 140px; }
.tabs { display: flex; gap: 4px; margin-bottom: 16px; }
.tabs button {
  padding: 6px 14px; background: var(--bg2); color: var(--text2); border: 1px solid var(--border);
  border-radius: var(--radius); font-size: 12px;
}
.tabs button.active { background: var(--accent); color: #fff; border-color: var(--accent); }
.todo-list { display: flex; flex-direction: column; gap: 8px; max-height: 70vh; overflow-y: auto; }
.todo-item { display: flex; flex-direction: column; gap: 4px; }
.todo-main { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.todo-title { font-weight: 600; font-size: 14px; }
.todo-key { font-family: monospace; font-size: 11px; background: var(--bg3); padding: 1px 6px; border-radius: 3px; color: var(--accent); }
.todo-meta { display: flex; align-items: center; gap: 6px; }
.todo-date, .todo-assignee { font-size: 11px; color: var(--text2); }
.todo-obj { font-size: 12px; color: var(--accent); margin-top: 4px; }
.todo-desc { font-size: 12px; color: var(--text2); margin-top: 4px; }
.todo-accept { font-size: 12px; color: var(--text2); margin-top: 2px; font-style: italic; }
.todo-output { font-size: 12px; color: #34d399; margin-top: 2px; }
.todo-error { font-size: 12px; color: var(--red); margin-top: 2px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 60px 0; color: var(--text2); }
</style>
