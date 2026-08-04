<template>
  <div class="view-container">
    <div class="view-header"><h2>Plan Management</h2></div>
    <div class="plan-load">
      <input v-model="planId" placeholder="Plan ID" class="thread-input" @keydown.enter="loadPlan" />
      <button class="btn-primary btn-sm" @click="loadPlan" :disabled="!planId">Load</button>
    </div>

    <div v-if="plan" class="plan-detail card">
      <div class="plan-status-row">
        <span :class="'badge ' + planStatusBadge(plan.status)">{{ plan.status }}</span>
        <span class="plan-date">{{ plan.createdAt }}</span>
      </div>
      <h3 class="plan-objective">{{ plan.objective }}</h3>

      <div v-if="plan.steps && plan.steps.length" class="plan-steps">
        <h4>Steps ({{ plan.steps.length }})</h4>
        <div v-for="s in plan.steps" :key="s.stepNo" class="step-item">
          <span class="step-no">{{ s.stepNo }}</span>
          <div class="step-info">
            <span class="step-agent">{{ s.agentName }}</span>
            <span class="step-goal">{{ s.goal }}</span>
            <span :class="'badge ' + stepStatusBadge(s.status)">{{ s.status }}</span>
            <span v-if="s.outputSummary" class="step-output">{{ s.outputSummary }}</span>
          </div>
        </div>
      </div>

      <div v-if="plan.runs && plan.runs.length" class="plan-runs">
        <h4>Run Logs</h4>
        <div v-for="r in plan.runs" :key="r.id" class="run-item">
          <span class="run-agent">{{ r.agentName }}</span>
          <span class="run-phase">{{ r.phase }}</span>
          <span :class="'badge ' + (r.status === 'SUCCESS' ? 'badge-low' : 'badge-high')">{{ r.status }}</span>
          <span class="run-time">{{ r.createdAt }}</span>
        </div>
      </div>
    </div>

    <div v-if="!plan && planId" class="empty-state"><GitBranch :size="36" /><p>No plan found for ID: {{ planId }}</p></div>
    <div v-if="!planId" class="empty-state"><GitBranch :size="36" /><p>Enter a Plan ID to view details</p></div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { GitBranch } from 'lucide-vue-next'
import { getPlan } from '../api'

const planId = ref('')
const plan = ref(null)

function loadPlan() {
  plan.value = null
  getPlan(planId.value).then(r => { plan.value = r.data }).catch(() => { plan.value = null })
}

function planStatusBadge(s) {
  const m = { AWAITING_APPROVAL: 'badge-medium', APPROVED: 'badge-low', EXECUTING: 'badge-low', COMPLETED: 'badge-low', FAILED: 'badge-high', REJECTED: 'badge-high' }
  return m[s] || ''
}
function stepStatusBadge(s) {
  const m = { PENDING: 'badge-medium', RUNNING: 'badge-low', COMPLETED: 'badge-low', FAILED: 'badge-high', SKIPPED: '' }
  return m[s] || ''
}
</script>

<style scoped>
.view-container { max-width: 700px; margin: 0 auto; }
.view-header h2 { font-size: 20px; margin-bottom: 16px; }
.plan-load { display: flex; gap: 8px; margin-bottom: 20px; }
.thread-input { flex: 1; font-family: monospace; }
.plan-detail { display: flex; flex-direction: column; gap: 12px; }
.plan-status-row { display: flex; align-items: center; gap: 12px; }
.plan-date { font-size: 12px; color: var(--text2); }
.plan-objective { font-size: 16px; }
.plan-steps h4, .plan-runs h4 { font-size: 14px; margin-bottom: 8px; color: var(--text2); }
.step-item, .run-item { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--border); font-size: 12px; }
.step-no { width: 24px; height: 24px; background: var(--bg3); border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 11px; flex-shrink: 0; }
.step-info { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; flex: 1; }
.step-agent { font-weight: 600; color: var(--accent); }
.step-goal { color: var(--text); }
.step-output { color: var(--text2); font-style: italic; }
.run-agent { font-weight: 600; color: var(--accent); min-width: 100px; }
.run-phase { color: var(--text2); min-width: 50px; }
.run-time { font-size: 11px; color: var(--text2); margin-left: auto; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 60px 0; color: var(--text2); }
</style>