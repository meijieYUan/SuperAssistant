<template>
  <div class="view-container">
    <div class="view-header"><h2>Knowledge Base</h2></div>
    <div class="upload-zone card" @dragover.prevent @drop.prevent="onDrop" @click="triggerFile">
      <input ref="fileInput" type="file" accept=".pdf,.md,.txt,.java,.py,.xml,.json" @change="onFile" hidden />
      <Upload :size="36" />
      <p>Drop files here or click to upload</p>
      <p class="upload-hint">Supports PDF, Markdown, TXT, Java, Python, XML, JSON</p>
    </div>
    <div v-if="uploading" class="upload-progress">
      <span class="loading"></span> Uploading {{ uploadFile }}...
    </div>
    <div v-if="result" class="upload-result card">
      <CheckCircle :size="18" class="result-icon" />
      <div><strong>Uploaded:</strong> {{ result.filename }}</div>
      <div><strong>Chunks:</strong> {{ result.chunks }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Upload, CheckCircle } from 'lucide-vue-next'
import { uploadKnowledge } from '../api'

const fileInput = ref(null)
const uploading = ref(false)
const uploadFile = ref('')
const result = ref(null)

function triggerFile() { fileInput.value.click() }
function onFile(e) { if (e.target.files.length) doUpload(e.target.files[0]) }
function onDrop(e) { if (e.dataTransfer.files.length) doUpload(e.dataTransfer.files[0]) }

function doUpload(file) {
  uploadFile.value = file.name
  uploading.value = true
  result.value = null
  uploadKnowledge(file).then(r => { result.value = r.data }).finally(() => { uploading.value = false })
}
</script>

<style scoped>
.view-container { max-width: 600px; margin: 0 auto; }
.view-header h2 { font-size: 20px; margin-bottom: 20px; }
.upload-zone {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 48px; border: 2px dashed var(--border); border-radius: 12px;
  cursor: pointer; transition: all .2s;
}
.upload-zone:hover { border-color: var(--accent); background: rgba(108,140,255,.04); }
.upload-zone p { font-size: 14px; }
.upload-hint { font-size: 12px; color: var(--text2); }
.upload-progress { display: flex; align-items: center; gap: 8px; margin-top: 16px; font-size: 13px; }
.upload-result { margin-top: 16px; display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
.result-icon { color: var(--green); margin-bottom: 4px; }
</style>