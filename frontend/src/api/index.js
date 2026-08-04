import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 120000 })

// Chat
export const sendChat = (threadId, message) =>
  api.post(`/chat/${threadId}`, { message })

export const approveChat = (threadId, decisions) =>
  api.post(`/chat/${threadId}/approve`, { decisions })

// Todos
export const getTodos = () => api.get('/todos')
export const getPendingTodos = () => api.get('/todos/pending')
export const getOverdueTodos = () => api.get('/todos/overdue')
export const queryTodos = (status, priority, keyword) =>
  api.post('/todos/query', { status, priority, keyword })

// Knowledge
export const uploadKnowledge = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/knowledge/upload', fd)
}

// Plans
export const getPlan = (planId) => api.get(`/plans/${planId}`)
export const approvePlan = (planId) => api.post(`/plans/${planId}/approve`)
export const rejectPlan = (planId, reason) =>
  api.post(`/plans/${planId}/reject`, { reason })
export const revisePlan = (planId) => api.post(`/plans/${planId}/revise`)
export const getPlanEvents = (planId) =>
  api.get(`/plans/${planId}/events`)
export const getPlanRuns = (planId) => api.get(`/plans/${planId}/runs`)