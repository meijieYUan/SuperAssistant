import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import TodoView from '../views/TodoView.vue'
import KnowledgeView from '../views/KnowledgeView.vue'
import PlanView from '../views/PlanView.vue'

const routes = [
  { path: '/', name: 'chat', component: ChatView },
  { path: '/todos', name: 'todos', component: TodoView },
  { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
  { path: '/plans', name: 'plans', component: PlanView }
]

export default createRouter({ history: createWebHistory(), routes })