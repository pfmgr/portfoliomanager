<template>
  <section class="panel">
    <h2>Imports & Exports</h2>

    <div class="section">
      <h3>Import Depot Statement</h3>
      <p class="hint">Upload a depot statement to refresh instruments, instruments metadata, and snapshots.</p>

      <form class="form" @submit.prevent="submitImport">
        <label class="field">
          <span>Depot</span>
          <select v-model="form.depotCode" required>
            <option value="" disabled>Select depot</option>
            <option v-for="depot in depots" :key="depot.depotId" :value="depot.depotCode">
              {{ depot.depotCode }} - {{ depot.name }}
            </option>
          </select>
        </label>

        <label class="field">
          <span>Statement File</span>
          <input type="file" @change="onFileChange" required />
        </label>

        <label class="checkbox">
          <input type="checkbox" v-model="form.forceReimport" />
          Force reimport (ignore duplicate hash)
        </label>
        <label class="checkbox">
          <input type="checkbox" v-model="form.pruneMissing" />
          Mark missing instruments as deleted
        </label>
        <label class="checkbox">
          <input type="checkbox" v-model="form.applyRules" />
          Apply active reclassification ruleset to imported instruments
        </label>

        <button class="primary" type="submit" :disabled="busy">Run Import</button>
      </form>
    </div>

    <div class="section">
      <h3>Overrides CSV</h3>
      <div class="actions">
        <button class="ghost" type="button" @click="downloadOverrides">Export Overrides</button>
        <label class="ghost file">
          Import Overrides
          <input type="file" @change="importOverrides" />
        </label>
      </div>
    </div>

    <div class="section">
      <h3>Full Database Backup</h3>
      <p class="hint" role="note">
        Warning: full backups can include sensitive LLM API keys. Use a strong password for encrypted exports and store
        the archive securely.
      </p>
      <p class="hint">
        Full database backups are versioned (v2). Enter a password with at least 12 characters to create a protected
        backup. Older backups without saved LLM settings leave the current LLM configuration unchanged.
      </p>
      <label class="field">
        <span>Backup Password</span>
        <input v-model="backupPassword" type="password" autocomplete="new-password" minlength="12" required />
      </label>
      <p class="hint" id="backup-import-help">
        For encrypted `.pmbk` backups, enter the matching password before importing. Legacy plaintext `.zip` backups
        still import without one.
      </p>
      <div class="actions">
        <button class="ghost" type="button" :disabled="backupBusy || backupPassword.trim().length < 12" @click="exportBackup">Export Backup</button>
        <label class="ghost file" :aria-disabled="!backupConfirmed">
          Import Backup
          <input
            type="file"
            accept=".zip,.pmbk"
            :disabled="backupBusy || !backupConfirmed"
            aria-describedby="backup-import-help"
            @change="importBackup"
          />
        </label>
      </div>
      <label class="checkbox" id="backup-import-warning">
        <input type="checkbox" v-model="backupConfirmed" />
        I understand that importing a full backup may replace application data. Password-protected backups require the
        matching password, while legacy plaintext backups still import without one.
      </label>
    </div>

    <div class="section">
      <h3>Knowledge Base (KB) Backup</h3>
      <p class="hint">
        Exports include dossiers and extractions only. They do not include LLM configuration or API keys. Importing
        replaces existing KB data.
      </p>
      <div class="actions">
        <button class="ghost" type="button" :disabled="kbBusy" @click="exportKnowledgeBase">Export Knowledge Base</button>
        <label class="ghost file" :aria-disabled="!kbConfirmed">
          Import Knowledge Base
          <input
            type="file"
            accept=".zip"
            :disabled="kbBusy || !kbConfirmed"
            aria-describedby="kb-import-warning"
            @change="importKnowledgeBase"
          />
        </label>
      </div>
      <label class="checkbox" id="kb-import-warning">
        <input type="checkbox" v-model="kbConfirmed" />
        I understand that importing a Knowledge Base backup deletes existing dossiers and extractions.
      </label>
    </div>

    <p v-if="message" class="toast success" role="status" aria-live="polite">{{ message }}</p>
    <p v-if="error" class="toast error" role="status" aria-live="polite">{{ error }}</p>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { apiDownload, apiRequest, apiUpload } from '../api'

const KB_EXPORT_STORAGE_KEY = 'kb.lastExport'
const KB_IMPORT_STORAGE_KEY = 'kb.lastImport'

const depots = ref([])
const busy = ref(false)
const backupBusy = ref(false)
const kbBusy = ref(false)
const message = ref('')
const error = ref('')
const backupPassword = ref('')

const form = ref({
  depotCode: '',
  file: null,
  forceReimport: false,
  pruneMissing: true,
  applyRules: true
})
const backupConfirmed = ref(false)
const kbConfirmed = ref(false)

function onFileChange(event) {
  const file = event.target.files[0]
  form.value.file = file || null
}

async function loadDepots() {
  try {
    depots.value = await apiRequest('/depots')
  } catch (err) {
    error.value = err.message || 'Unable to load depots.'
  }
}

async function submitImport() {
  if (!form.value.file) {
    error.value = 'Please select a file.'
    return
  }
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const data = new FormData()
    data.append('depotCode', form.value.depotCode)
    data.append('file', form.value.file)
    data.append('forceReimport', form.value.forceReimport)
    data.append('pruneMissing', form.value.pruneMissing)
    data.append('applyRules', form.value.applyRules)

    const result = await apiUpload('/imports/depot-statement', data)
    message.value = `Import complete: instruments=${result.instrumentsImported}, positions=${result.positions}, snapshot=${result.snapshotStatus}.`
  } catch (err) {
    error.value = err.message || 'Import failed'
  } finally {
    busy.value = false
  }
}

async function downloadOverrides() {
  message.value = ''
  error.value = ''
  try {
    const response = await apiDownload('/overrides/export')
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'overrides.csv'
    link.click()
    window.URL.revokeObjectURL(url)
  } catch (err) {
    error.value = err.message || 'Download failed'
  }
}

async function importOverrides(event) {
  const file = event.target.files[0]
  if (!file) {
    return
  }
  message.value = ''
  error.value = ''
  try {
    const data = new FormData()
    data.append('file', file)
    const result = await apiUpload('/overrides/import', data)
    message.value = `Overrides imported: ${result.imported} (skipped missing=${result.skippedMissing}).`
  } catch (err) {
    error.value = err.message || 'Import failed'
  } finally {
    event.target.value = ''
  }
}

async function exportBackup() {
  if (backupPassword.value.trim().length < 12) {
    error.value = 'Please enter a backup password with at least 12 characters.'
    return
  }
  backupBusy.value = true
  message.value = ''
  error.value = ''
  try {
    const response = await apiDownload('/backups/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: backupPassword.value })
    })
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const disposition = response.headers.get('content-disposition') || ''
    const filenameMatch = disposition.match(/filename="?([^";]+)"?/i)
    const link = document.createElement('a')
    link.href = url
    link.download = filenameMatch?.[1] || 'backup.pmbk'
    link.click()
    window.URL.revokeObjectURL(url)
    message.value = 'Backup exported with password protection (format v2).'
  } catch (err) {
    error.value = err.message || 'Backup export failed'
  } finally {
    backupBusy.value = false
  }
}

async function importBackup(event) {
  const file = event.target.files[0]
  if (!file) {
    return
  }
  if (!backupConfirmed.value) {
    error.value = 'Please confirm that importing a backup replaces application data, including LLM configuration and API keys.'
    event.target.value = ''
    return
  }
  backupBusy.value = true
  message.value = ''
  error.value = ''
  try {
    const data = new FormData()
    data.append('file', file)
    if (backupPassword.value.trim()) {
      data.append('password', backupPassword.value)
    }
    const result = await apiUpload('/backups/import', data)
    message.value = `Backup imported: tables=${result.tablesImported}, rows=${result.rowsImported}, format=v${result.formatVersion}.`
  } catch (err) {
    error.value = err.message || 'Backup import failed'
  } finally {
    backupBusy.value = false
    event.target.value = ''
  }
}

async function exportKnowledgeBase() {
  kbBusy.value = true
  message.value = ''
  error.value = ''
  try {
    const response = await apiDownload('/kb/backup/export')
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'knowledge-base.zip'
    link.click()
    window.URL.revokeObjectURL(url)
    try {
      localStorage.setItem(KB_EXPORT_STORAGE_KEY, new Date().toISOString())
    } catch (err) {
      // ignore storage errors
    }
    message.value = 'Knowledge Base exported (format v1).'
  } catch (err) {
    error.value = err.message || 'Knowledge Base export failed'
  } finally {
    kbBusy.value = false
  }
}

async function importKnowledgeBase(event) {
  const file = event.target.files[0]
  if (!file) {
    return
  }
  if (!kbConfirmed.value) {
    error.value = 'Please confirm that importing a Knowledge Base backup deletes existing data.'
    event.target.value = ''
    return
  }
  kbBusy.value = true
  message.value = ''
  error.value = ''
  try {
    const data = new FormData()
    data.append('file', file)
    const result = await apiUpload('/kb/backup/import', data)
    try {
      localStorage.setItem(KB_IMPORT_STORAGE_KEY, new Date().toISOString())
    } catch (err) {
      // ignore storage errors
    }
    message.value = `Knowledge Base imported: dossiers=${result.dossiersImported}, extractions=${result.dossierExtractionsImported}, kbExtractions=${result.knowledgeBaseExtractionsImported}, format=v${result.formatVersion}.`
  } catch (err) {
    error.value = err.message || 'Knowledge Base import failed'
  } finally {
    kbBusy.value = false
    event.target.value = ''
  }
}

onMounted(() => {
  loadDepots()
})
</script>
