import axios from 'axios'
import { toast } from '../utils/toast'

const api = axios.create({ baseURL: '/api', timeout: 120000 })

api.interceptors.response.use(
  response => response,
  error => {
    const msg = error.response?.data?.message || error.message || 'Request failed'
    toast(msg, 'error')
    return Promise.reject(error)
  }
)

// Health
export const getHealth = () => api.get('/health')

// Chat — mode is 'Default' or 'PlanMode'
export const sendChat = (threadId, message, mode = 'Default') =>
  api.post(`/chat/${threadId}`, { message, mode })

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

// Plans (read-only queries; approve/reject go through chat)
export const getPlan = (planId) => api.get(`/plans/${planId}`)
export const getPlanEvents = (planId) => api.get(`/plans/${planId}/events`)
export const getPlanRuns = (planId) => api.get(`/plans/${planId}/runs`)