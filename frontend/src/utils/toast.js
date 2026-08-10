import { reactive } from 'vue'

let seq = 0
export const toasts = reactive([])

export function toast(message, type = 'info', duration = 3500) {
  const id = ++seq
  toasts.push({ id, message: String(message), type })
  setTimeout(() => {
    const i = toasts.findIndex(t => t.id === id)
    if (i >= 0) toasts.splice(i, 1)
  }, duration)
}
