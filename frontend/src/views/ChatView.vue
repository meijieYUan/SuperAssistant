<template>
  <div class="chat-layout">
    <!-- Thread sidebar -->
    <div class="thread-sidebar">
      <button class="btn-primary new-thread-btn" @click="newThread">
        <Plus :size="16" /> New Chat
      </button>
      <div class="thread-list scrollbar">
        <div
          v-for="t in threadList"
          :key="t.id"
          :class="['thread-item', { active: t.id === activeThreadId }]"
          @click="switchThread(t.id)"
        >
          <div class="thread-item-main">
            <span class="thread-title">{{ t.title || 'New conversation' }}</span>
            <span class="thread-time">{{ formatTime(t.lastActive) }}</span>
          </div>
          <button class="thread-delete" @click.stop="deleteThread(t.id)" title="Delete">
            <Trash2 :size="13" />
          </button>
        </div>
        <div v-if="threadList.length === 0" class="no-threads">
          No conversations yet
        </div>
      </div>
    </div>

    <!-- Chat area -->
    <div class="chat-main">
      <div class="chat-header">
        <span class="thread-label">{{ activeThread ? activeThread.title || 'New conversation' : 'Select a conversation' }}</span>
      </div>

      <div class="chat-messages scrollbar" ref="msgContainer">
        <div v-if="messages.length === 0" class="empty-state">
          <MessageSquare :size="40" />
          <p>Start a conversation with your AI assistant</p>
        </div>

        <div v-for="(msg, i) in messages" :key="i" :class="['message', msg.role]">
          <div class="msg-avatar">{{ msg.role === 'user' ? 'U' : 'S' }}</div>
          <div class="msg-body">
            <div class="msg-text" v-html="renderMarkdown(msg.content)"></div>
            <div v-if="msg.approvals" class="approval-panel card">
              <div class="approval-header">
                <AlertTriangle :size="16" />
                <span>Approval Required — {{ msg.approvals.length }} action(s) pending</span>
              </div>
              <div v-for="(app, j) in msg.approvals" :key="j" class="approval-item">
                <div class="approval-tool">
                  <span class="badge badge-high">{{ app.toolName }}</span>
                  <span class="approval-desc">{{ app.description }}</span>
                </div>
                <div class="approval-args">
                  <pre>{{ formatArgs(app.arguments) }}</pre>
                  <textarea v-if="app.editing" v-model="app.editedArgs" rows="3" placeholder="Edit arguments (JSON)..."></textarea>
                </div>
                <div class="approval-actions">
                  <button class="btn-success btn-sm" @click="decide(app, 'APPROVED')">Approve</button>
                  <button class="btn-danger btn-sm" @click="decide(app, 'REJECTED')">Reject</button>
                  <button class="btn-ghost btn-sm" @click="app.editing = !app.editing">
                    {{ app.editing ? 'Cancel Edit' : 'Edit' }}
                  </button>
                </div>
              </div>
              <button class="btn-primary" @click="submitApprovals(msg)" :disabled="!allDecided(msg)">
                Submit Approvals
              </button>
            </div>
            <div v-if="msg.planPending" class="approval-panel card">
              <div class="approval-header">
                <GitBranch :size="16" />
                <span>Plan Generated — Review and approve to execute</span>
              </div>
              <div class="plan-summary">{{ msg.planSummary }}</div>
              <div class="plan-actions">
                <button class="btn-success" @click="approvePlanAction(msg.planId)">Approve Plan</button>
                <button class="btn-danger" @click="rejectPlanAction(msg.planId)">Reject</button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="loading" class="message assistant">
          <div class="msg-avatar">S</div>
          <div class="msg-body"><span class="loading"></span> Thinking...</div>
        </div>
      </div>

      <div class="chat-input">
        <input
          v-model="input"
          @keydown.enter="send"
          placeholder="Type a message... (Enter to send)"
          :disabled="loading || !activeThreadId"
        />
        <button class="btn-primary" @click="send" :disabled="!input.trim() || loading || !activeThreadId">
          <Send :size="16" />
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch, onMounted } from 'vue'
import { MessageSquare, Send, AlertTriangle, GitBranch, Plus, Trash2 } from 'lucide-vue-next'
import { sendChat, approveChat, approvePlan, rejectPlan } from '../api'

const STORAGE_KEY = 'sa_threads'

// Persistent threads store
const threads = ref({})
const activeThreadId = ref(null)

const input = ref('')
const loading = ref(false)
const msgContainer = ref(null)

// Computed
const activeThread = computed(() => threads.value[activeThreadId.value] || null)
const messages = computed(() => {
  if (!activeThread.value) return []
  return activeThread.value.messages || []
})
const threadList = computed(() => {
  return Object.values(threads.value).sort((a, b) => b.lastActive - a.lastActive)
})

// LocalStorage persistence
function loadThreads() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) threads.value = JSON.parse(raw)
  } catch { threads.value = {} }
}

function saveThreads() {
  // Strip non-serializable reactive flags before saving
  const clean = {}
  for (const [id, t] of Object.entries(threads.value)) {
    clean[id] = {
      id: t.id,
      title: t.title,
      lastActive: t.lastActive,
      messages: (t.messages || []).map(m => {
        const { role, content } = m
        const c = { role, content }
        if (m.approvals) {
          c.approvals = m.approvals.map(a => ({
            toolId: a.toolId, toolName: a.toolName,
            arguments: a.arguments, description: a.description
          }))
        }
        if (m.planPending) {
          c.planPending = true
          c.planId = m.planId
          c.planSummary = m.planSummary
        }
        return c
      })
    }
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
}

function ensureThread() {
  if (!activeThreadId.value) {
    newThread()
  }
}

function newThread() {
  const id = 'thr-' + Date.now().toString(36)
  threads.value[id] = { id, title: '', lastActive: Date.now(), messages: [] }
  activeThreadId.value = id
  input.value = ''
  saveThreads()
  scrollDown()
}

function switchThread(id) {
  activeThreadId.value = id
  input.value = ''
  scrollDown()
}

function deleteThread(id) {
  delete threads.value[id]
  if (activeThreadId.value === id) {
    const remaining = threadList.value
    activeThreadId.value = remaining.length > 0 ? remaining[0].id : null
  }
  saveThreads()
}

function send() {
  if (!input.value.trim() || loading.value || !activeThreadId.value) return
  const msg = input.value.trim()
  const t = threads.value[activeThreadId.value]
  if (!t.title && t.messages.length === 0) {
    t.title = msg.length > 40 ? msg.substring(0, 40) + '...' : msg
  }
  t.messages.push({ role: 'user', content: msg })
  t.lastActive = Date.now()
  input.value = ''
  loading.value = true
  saveThreads()
  scrollDown()
  sendChat(activeThreadId.value, msg)
    .then(r => handleResponse(r.data))
    .finally(() => { loading.value = false; scrollDown() })
}

function handleResponse(data) {
  const t = threads.value[activeThreadId.value]
  if (!t) return
  if (data.type === 'ANSWER') {
    const text = extractText(data.response || data)
    t.messages.push({ role: 'assistant', content: text })
  } else if (data.type === 'INTERRUPTED') {
    const approvals = (data.pendingApprovals || []).map(a => ({
      ...a, decision: null, editing: false, editedArgs: a.arguments
    }))
    t.messages.push({ role: 'assistant', content: data.message || '', approvals, threadId: data.threadId })
  } else if (data.type === 'PLAN_PENDING') {
    t.messages.push({
      role: 'assistant', content: 'A plan has been generated for your request.',
      planPending: true, planId: data.planId,
      planSummary: typeof data.plan === 'object' ? JSON.stringify(data.plan, null, 2) : String(data.plan || '')
    })
  } else if (data.type === 'ERROR') {
    t.messages.push({ role: 'assistant', content: '\u26a0\ufe0f Error: ' + (data.message || 'Unknown error') })
  }
  t.lastActive = Date.now()
  saveThreads()
}

// ANSWER type always contains response as either plain text or NodeOutput{state={...}} string
// Extract just the last assistant message text for display
function extractText(res) {
  if (!res) return ''
  if (typeof res === 'string') {
    const m = res.match(/state=(\{.*\}),?\s*subGraph/)
    if (m) {
      try {
        const s = JSON.parse(m[1])
        const msgs = (s.OverAllState || s).data?.messages || []
        for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].text) return msgs[i].text
      } catch {}
    }
    return res
  }
  return res.text || JSON.stringify(res)
}

function decide(app, result) { app.decision = result }
function allDecided(msg) { return msg.approvals && msg.approvals.every(a => a.decision) }

function submitApprovals(msg) {
  const decisions = msg.approvals.map(a => {
    const d = { toolId: a.toolId, result: a.decision }
    if (a.decision === 'EDITED' && a.editedArgs) d.editedArguments = a.editedArgs
    return d
  })
  loading.value = true
  approveChat(activeThreadId.value, decisions)
    .then(r => handleResponse(r.data))
    .finally(() => { loading.value = false; scrollDown() })
}

function approvePlanAction(planId) {
  loading.value = true
  approvePlan(planId).then(r => handleResponse(r.data)).finally(() => { loading.value = false })
}
function rejectPlanAction(planId) {
  loading.value = true
  rejectPlan(planId, 'User rejected').then(r => handleResponse(r.data)).finally(() => { loading.value = false })
}

function formatArgs(args) {
  try { return JSON.stringify(JSON.parse(args), null, 2) } catch { return args }
}
function renderMarkdown(text) {
  if (!text) return ''
  return text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br>')
}
function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diff = now - d
  if (diff < 60000) return 'Just now'
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago'
  if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago'
  return d.toLocaleDateString()
}
function scrollDown() {
  nextTick(() => {
    if (msgContainer.value) msgContainer.value.scrollTop = msgContainer.value.scrollHeight
  })
}

onMounted(() => {
  loadThreads()
  if (threadList.value.length > 0) {
    activeThreadId.value = threadList.value[0].id
  }
})
</script>

<style scoped>
.chat-layout { display: flex; height: 100%; max-width: 1100px; margin: 0 auto; }

.thread-sidebar {
  width: 240px; flex-shrink: 0; border-right: 1px solid var(--border);
  display: flex; flex-direction: column; padding: 12px;
}
.new-thread-btn { width: 100%; display: flex; align-items: center; justify-content: center; gap: 6px; margin-bottom: 10px; }
.thread-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
.thread-item {
  display: flex; align-items: center; padding: 10px 12px; border-radius: var(--radius);
  cursor: pointer; transition: all .12s; position: relative;
}
.thread-item:hover { background: var(--bg3); }
.thread-item.active { background: rgba(108,140,255,.12); }
.thread-item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.thread-title { font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.thread-time { font-size: 11px; color: var(--text2); }
.thread-delete {
  opacity: 0; padding: 4px; border-radius: 4px; background: transparent; color: var(--red);
  transition: opacity .12s;
}
.thread-item:hover .thread-delete { opacity: 1; }
.thread-delete:hover { background: rgba(248,113,113,.15); }
.no-threads { padding: 20px 12px; font-size: 13px; color: var(--text2); text-align: center; }

.chat-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.chat-header { padding: 12px 20px; border-bottom: 1px solid var(--border); }
.thread-label { font-size: 14px; font-weight: 600; color: var(--text); }

.chat-messages { flex: 1; overflow-y: auto; padding: 20px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; gap: 12px; color: var(--text2); }
.empty-state p { font-size: 15px; }

.message { display: flex; gap: 12px; margin-bottom: 20px; }
.message.user { flex-direction: row-reverse; }
.msg-avatar {
  width: 34px; height: 34px; border-radius: 8px; display: flex;
  align-items: center; justify-content: center; font-size: 13px; font-weight: 700; flex-shrink: 0;
}
.message.assistant .msg-avatar { background: var(--accent); color: #fff; }
.message.user .msg-avatar { background: var(--bg3); color: var(--text); }
.msg-body { max-width: 80%; min-width: 0; }
.msg-text { padding: 10px 14px; border-radius: var(--radius); font-size: 13px; line-height: 1.65; }
.message.assistant .msg-text { background: var(--bg2); border: 1px solid var(--border); }
.message.user .msg-text { background: var(--accent); color: #fff; }
.msg-text :deep(code) { background: rgba(0,0,0,.2); padding: 1px 5px; border-radius: 3px; font-size: 12px; }
.msg-text :deep(strong) { color: var(--accent); }
.approval-panel { margin-top: 10px; }
.approval-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; color: var(--yellow); font-weight: 600; font-size: 13px; }
.approval-item { margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--border); }
.approval-tool { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.approval-desc { font-size: 12px; color: var(--text2); }
.approval-args { margin-bottom: 8px; }
.approval-args pre { font-size: 11px; background: var(--bg); padding: 8px; border-radius: 4px; overflow-x: auto; margin-bottom: 6px; }
.approval-args textarea { width: 100%; }
.approval-actions { display: flex; gap: 6px; }
.plan-summary { font-size: 12px; color: var(--text2); white-space: pre-wrap; max-height: 200px; overflow-y: auto; margin-bottom: 10px; }
.plan-actions { display: flex; gap: 8px; }
.chat-input { display: flex; gap: 8px; padding: 12px 20px; border-top: 1px solid var(--border); }
.chat-input input { flex: 1; }
.chat-input button { padding: 8px 14px; display: flex; align-items: center; }
</style>
