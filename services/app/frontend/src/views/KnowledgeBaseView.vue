<template>
  <section class="panel kb-root">
    <h2>Knowledge Base</h2>
    <p class="hint">Maintain dossiers, extractions, alternatives, and refresh workflows.</p>

    <p v-if="kbDisabled" class="toast error">
      Knowledge Base is disabled. Configure an LLM provider and enable KB to continue.
    </p>
    <p v-else-if="error" class="toast error">{{ error }}</p>

    <div v-if="!kbDisabled">
      <div class="kb-backup-status" role="status" aria-live="polite">
        <div class="kb-backup-item">
          <span class="kb-backup-label">Last KB export</span>
          <span class="kb-backup-value">{{ formatBackupDate(lastKbExportAt) }}</span>
        </div>
        <div class="kb-backup-item">
          <span class="kb-backup-label">Last KB import</span>
          <span class="kb-backup-value">{{ formatBackupDate(lastKbImportAt) }}</span>
        </div>
      </div>
      <div
        class="kb-main-tabs"
        role="tablist"
        aria-label="Knowledge Base sections"
        @keydown="handleTabListKeydown"
      >
        <button
          v-for="section in sections"
          :key="section.key"
          ref="tabRefs"
          type="button"
          class="ghost"
          :class="{ 'tab-active': activeSection === section.key }"
          role="tab"
          :id="tabId(section.key)"
          :aria-controls="panelId(section.key)"
          :aria-selected="activeSection === section.key"
          :tabindex="activeSection === section.key ? 0 : -1"
          :data-key="section.key"
          @click="activateSection(section.key)"
        >
          {{ section.label }}
        </button>
      </div>

      <div
        v-if="activeSection === 'DOSSIERS'"
        class="section"
        role="tabpanel"
        tabindex="0"
        :id="panelId('DOSSIERS')"
        :aria-labelledby="tabId('DOSSIERS')"
      >
        <form class="form inline kb-filter-form" @submit.prevent="loadDossiers">
          <label class="field">
            <span>Search</span>
            <input v-model="dossierFilters.query" placeholder="ISIN or name" />
          </label>
          <label class="field">
            <span>Status</span>
            <select v-model="dossierFilters.status">
              <option value="">Any</option>
              <option v-for="status in dossierStatusOptions" :key="status" :value="status">{{ status }}</option>
            </select>
          </label>
          <label class="field">
            <span>Stale</span>
            <select v-model="dossierFilters.stale">
              <option value="">Any</option>
              <option value="true">Stale</option>
              <option value="false">Fresh</option>
            </select>
          </label>
          <label class="field">
            <span>Page size</span>
            <select v-model.number="dossierPage.size">
              <option :value="25">25</option>
              <option :value="50">50</option>
              <option :value="100">100</option>
            </select>
          </label>
          <button class="ghost" type="submit" :disabled="dossiersLoading">Refresh</button>
        </form>

        <div class="actions">
          <span class="muted">Total: {{ dossierPage.total }}</span>
          <span class="muted">Selected: {{ selectedIsinsCount }}</span>
          <button
            class="secondary"
            type="button"
            :disabled="selectedIsinsCount === 0"
            @click="useSelectedForBulk"
          >
            Use selected in Bulk
          </button>
          <button
            class="ghost danger"
            type="button"
            :disabled="selectedIsinsCount === 0"
            @click="deleteSelectedDossiers"
          >
            Delete selected
          </button>
          <button class="ghost" type="button" :disabled="missingOnPageCount === 0" @click="selectMissingOnPage">
            Select missing on page
          </button>
          <button class="ghost" type="button" :disabled="selectedIsinsCount === 0" @click="clearSelection">
            Clear selection
          </button>
          <button class="ghost" type="button" :disabled="!hasPrevDossierPage" @click="prevDossierPage">Prev</button>
          <button class="ghost" type="button" :disabled="!hasNextDossierPage" @click="nextDossierPage">Next</button>
        </div>

        <p v-if="dossiersError" class="toast error">{{ dossiersError }}</p>
        <p v-if="dossiersMessage" class="toast success">{{ dossiersMessage }}</p>

        <div class="kb-split" :class="{ 'kb-split-stacked': !hasDossierDetail }">
          <div class="kb-pane">
            <p
              v-if="!hasDossierDetail"
              class="hint kb-inline-hint"
              :class="{ 'kb-inline-hint-compact': isHintCompact }"
              role="note"
            >
              Select a dossier to view details, or use Open to create a manual draft.
            </p>
            <div
              ref="dossierTableWrap"
              class="table-wrap kb-table-wrap"
              :class="{ 'kb-table-wrap-scrollable': showDossierScrollHint }"
              @scroll="updateDossierScrollHint"
            >
              <table class="table kb-dossier-table" :class="{ 'kb-dossier-table-scrollable': showDossierScrollHint }">
                <caption class="sr-only">Knowledge base dossiers</caption>
                <thead>
                  <tr>
                    <th scope="col" class="kb-select-col">
                      <input
                        type="checkbox"
                        :checked="allSelectedOnPage"
                        :disabled="dossiersLoading || dossierItems.length === 0"
                        @change="toggleSelectAllOnPage($event)"
                        aria-label="Select all dossiers on page"
                      />
                    </th>
                    <th scope="col" :aria-sort="ariaSort(dossierSort, 'isin')">
                      <button
                        type="button"
                        class="sort-button"
                        :aria-label="sortButtonLabel('ISIN', dossierSort, 'isin')"
                        @click="toggleSort(dossierSort, 'isin')"
                      >
                        ISIN <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(dossierSort, 'isin') }}</span>
                      </button>
                    </th>
                    <th scope="col">Name</th>
                    <th scope="col" :aria-sort="ariaSort(dossierSort, 'status')">
                      <button
                        type="button"
                        class="sort-button"
                        :aria-label="sortButtonLabel('Status', dossierSort, 'status')"
                        @click="toggleSort(dossierSort, 'status')"
                      >
                        Status <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(dossierSort, 'status') }}</span>
                      </button>
                    </th>
                    <th scope="col">Version</th>
                    <th scope="col">Approved</th>
                    <th scope="col">Extraction</th>
                    <th scope="col">Stale</th>
                    <th scope="col" :aria-sort="ariaSort(dossierSort, 'updatedAt')">
                      <button
                        type="button"
                        class="sort-button"
                        :aria-label="sortButtonLabel('Updated', dossierSort, 'updatedAt')"
                        @click="toggleSort(dossierSort, 'updatedAt', 'desc')"
                      >
                        Updated <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(dossierSort, 'updatedAt') }}</span>
                      </button>
                    </th>
                    <th scope="col" class="kb-actions-col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="dossiersLoading">
                    <td colspan="10">Loading dossiers...</td>
                  </tr>
                  <tr v-else-if="dossierItems.length === 0">
                    <td colspan="10">No dossiers found.</td>
                  </tr>
                  <tr
                    v-else
                    v-for="item in dossierRows"
                    :key="item.isin"
                    :class="{ selected: selectedIsin === item.isin }"
                  >
                    <td class="kb-select-col">
                      <input
                        type="checkbox"
                        :checked="isSelected(item.isin)"
                        @change="toggleSelection(item.isin)"
                        :aria-label="`Select ISIN ${item.isin}`"
                      />
                    </td>
                    <th scope="row" :id="`dossier-isin-${item.isin}`">
                      <div class="kb-isin-cell">
                        <span class="kb-isin">{{ item.isin }}</span>
                        <button
                          class="kb-open-inline"
                          type="button"
                          @click="openDossier(item)"
                          :aria-label="`Open dossier for ${item.isin}`"
                        >
                          <span class="kb-open-text">Open</span>
                          <span class="kb-open-icon" aria-hidden="true">&gt;</span>
                        </button>
                      </div>
                    </th>
                    <td>{{ item.name || 'Unknown' }}</td>
                    <td>
                      <span :class="['badge', statusBadgeClass(item.latestDossierStatus)]">
                        {{ item.latestDossierStatus || (item.hasDossier ? 'UNKNOWN' : 'NONE') }}
                      </span>
                    </td>
                    <td>{{ item.latestDossierVersion ?? '-' }}</td>
                    <td>{{ item.hasApprovedDossier ? 'Yes' : 'No' }}</td>
                    <td>
                      <span :class="['badge', extractionBadgeClass(item.extractionFreshness)]">
                        {{ formatExtractionFreshness(item.extractionFreshness) }}
                      </span>
                    </td>
                    <td>{{ item.stale ? 'Yes' : 'No' }}</td>
                    <td>{{ item.latestUpdatedAt ? formatDate(item.latestUpdatedAt) : '-' }}</td>
                    <td class="kb-actions-col">
                      <button class="ghost" type="button" @click="openDossier(item)">Open</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div class="kb-pane">
            <div v-if="dossierDetail" class="kb-detail">
              <div class="kb-detail-head">
                <div>
                  <h3>Dossier detail</h3>
                  <p class="hint">{{ dossierDetail.isin }} | {{ dossierDetail.displayName || 'Unknown' }}</p>
                </div>
                <button class="ghost" type="button" @click="closeDossier">Close</button>
              </div>

              <div class="kb-meta">
                <span class="badge">Status: {{ dossierDetail.latestDossier.status }}</span>
                <span class="badge neutral">Version {{ dossierDetail.latestDossier.version }}</span>
                <span class="badge neutral">Origin {{ dossierDetail.latestDossier.origin }}</span>
                <span class="badge neutral">Authored {{ dossierDetail.latestDossier.authoredBy }}</span>
                <span v-if="dossierDetail.latestDossier.autoApproved" class="badge warn">Auto-approved</span>
                <span
                  v-if="dossierQualityGate"
                  :class="['badge', gateBadgeClass(dossierQualityGate.passed)]"
                >
                  Quality gate: {{ dossierQualityGate.passed ? 'PASS' : 'FAIL' }}
                </span>
              </div>

              <p v-if="isNewDossier" class="hint">No dossier exists yet. Fill in the form and save to create one.</p>

              <div class="actions">
                <button class="secondary" type="button" :disabled="!canApproveDossier" @click="approveDossier">
                  Approve dossier
                </button>
                <button class="ghost" type="button" :disabled="!canRejectDossier" @click="rejectDossier">
                  Reject dossier
                </button>
                <button class="ghost" type="button" :disabled="!editMode" @click="saveDossier">
                  Save changes
                </button>
                <button class="ghost" type="button" @click="toggleEditMode">
                  {{ editMode ? 'Cancel edit' : 'Edit dossier' }}
                </button>
              </div>

              <p v-if="dossierActionError" class="toast error">{{ dossierActionError }}</p>

              <div v-if="editMode" class="section">
                <label class="field">
                  <span>Display name</span>
                  <input v-model="dossierForm.displayName" placeholder="Optional display name" />
                </label>
                <label class="field">
                  <span>Status</span>
                  <select v-model="dossierForm.status">
                    <option v-for="status in dossierStatusOptions" :key="status" :value="status">{{ status }}</option>
                  </select>
                </label>
                <label class="field">
                  <span>Content (Markdown)</span>
                  <textarea v-model="dossierForm.contentMd" rows="10"></textarea>
                </label>
                <label class="field">
                  <span>Citations (JSON array)</span>
                  <textarea v-model="dossierForm.citationsText" rows="6"></textarea>
                </label>
              </div>

              <div v-else class="section">
                <h4>Content</h4>
                <pre class="code-block">{{ dossierDetail.latestDossier.contentMd }}</pre>

                <div class="section">
                  <h4>Sources</h4>
                  <ul class="kb-citations">
                    <li v-for="(source, index) in dossierSources" :key="index">
                      <a :href="source.url" target="_blank" rel="noreferrer">{{ source.title || source.url }}</a>
                      <span class="muted" v-if="source.publisher">{{ source.publisher }}</span>
                      <span class="muted" v-if="source.accessed_at || source.accessedAt">
                        ({{ source.accessed_at || source.accessedAt }})
                      </span>
                    </li>
                  </ul>
                  <p v-if="dossierSources.length === 0" class="hint">No citations listed.</p>
                </div>
                <div v-if="dossierQualityReasons.length" class="section">
                  <h4>Quality gate reasons</h4>
                  <ul>
                    <li v-for="reason in dossierQualityReasons" :key="reason">{{ reason }}</li>
                  </ul>
                </div>
              </div>

              <div class="section">
                <h4>Extraction</h4>
                <div class="actions">
                  <button
                    class="secondary"
                    type="button"
                    :disabled="!dossierDetail.latestDossier.dossierId || isIsinBusy(dossierDetail.isin)"
                    @click="runExtraction"
                  >
                    Run extraction
                  </button>
                  <button
                    class="ghost"
                    type="button"
                    :disabled="!dossierDetail.latestDossier.dossierId || isIsinBusy(dossierDetail.isin)"
                    @click="fillMissingData"
                  >
                    Fill missing data
                  </button>
                  <button class="ghost" type="button" :disabled="!canApproveExtraction" @click="approveExtraction">
                    Approve extraction
                  </button>
                  <button class="ghost" type="button" :disabled="!canRejectExtraction" @click="rejectExtraction">
                    Reject extraction
                  </button>
                  <button class="ghost" type="button" :disabled="!canApplyExtraction" @click="applyExtraction">
                    Apply to overrides
                  </button>
                </div>
                <p v-if="extractionError" class="toast error">{{ extractionError }}</p>
                <p v-if="isIsinBusy(dossierDetail.isin)" class="hint">LLM action already running for this ISIN.</p>
                <p v-if="extractionAction" class="hint">
                  {{ formatActionType(extractionAction.type) }} action {{ formatActionStatus(extractionAction.status) }}:
                  {{ extractionAction.message || '-' }}
                </p>

                <div v-if="latestExtraction">
                  <p class="hint">
                    Status: {{ latestExtraction.status }} | Model: {{ latestExtraction.model }}
                    <span v-if="latestExtraction.autoApproved">(auto-approved)</span>
                  </p>
                  <p v-if="extractionEvidenceGate" class="hint">
                    Evidence gate:
                    <span :class="['badge', gateBadgeClass(extractionEvidenceGate.passed)]">
                      {{ extractionEvidenceGate.passed ? 'PASS' : 'FAIL' }}
                    </span>
                  </p>
                  <dl class="kb-dl">
                    <template v-for="field in extractionBaseFields" :key="field.label">
                      <dt>
                        <abbr v-if="field.title" :title="field.title">{{ field.label }}</abbr>
                        <template v-else>{{ field.label }}</template>
                      </dt>
                      <dd>{{ field.value }}</dd>
                    </template>
                  </dl>
                  <details class="section kb-details kb-valuation">
                    <summary>Valuation metrics</summary>
                    <p class="hint">
                      Long-term P/E uses smoothed EPS. Holdings P/E aggregates trailing earnings from
                      the ETF's holdings. Current P/E and P/B may be used when history is missing.
                      EBITDA and REIT profitability metrics are normalized to EUR when FX rates are available.
                    </p>
                    <p v-if="!hasValuationData" class="hint">No valuation data found in this extraction.</p>
                    <dl v-else class="kb-dl">
                      <template v-for="field in extractionValuationFields" :key="field.label">
                        <dt>
                          <abbr v-if="field.title" :title="field.title">{{ field.label }}</abbr>
                          <template v-else>{{ field.label }}</template>
                        </dt>
                        <dd>{{ field.value }}</dd>
                      </template>
                    </dl>
                  </details>
                  <details class="section">
                    <summary>Raw extraction JSON</summary>
                    <pre class="code-block">{{ formattedExtraction }}</pre>
                  </details>
                  <div v-if="extractionMissingFields.length" class="section">
                    <h5>Missing fields</h5>
                    <ul>
                      <li v-for="field in extractionMissingFields" :key="field">{{ field }}</li>
                    </ul>
                  </div>
                  <div v-if="extractionWarnings.length" class="section">
                    <h5>Warnings</h5>
                    <ul>
                      <li v-for="warning in extractionWarnings" :key="warning">{{ warning }}</li>
                    </ul>
                  </div>
                  <div v-if="extractionEvidenceMissing.length" class="section">
                    <h5>Evidence gate missing fields</h5>
                    <ul>
                      <li v-for="field in extractionEvidenceMissing" :key="field">{{ field }}</li>
                    </ul>
                  </div>
                </div>
                <p v-else class="hint">No extractions yet.</p>
              </div>

              <div class="section">
                <h4>Version history</h4>
                <div class="table-wrap">
                  <table class="table">
                    <caption class="sr-only">Dossier versions</caption>
                    <thead>
                      <tr>
                        <th scope="col" :aria-sort="ariaSort(versionSort, 'version')">
                          <button
                            type="button"
                            class="sort-button"
                            :aria-label="sortButtonLabel('Version', versionSort, 'version')"
                            @click="toggleSort(versionSort, 'version', 'desc')"
                          >
                            Version
                            <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(versionSort, 'version') }}</span>
                          </button>
                        </th>
                        <th scope="col">Status</th>
                        <th scope="col">Created</th>
                        <th scope="col" :aria-sort="ariaSort(versionSort, 'updatedAt')">
                          <button
                            type="button"
                            class="sort-button"
                            :aria-label="sortButtonLabel('Updated', versionSort, 'updatedAt')"
                            @click="toggleSort(versionSort, 'updatedAt', 'desc')"
                          >
                            Updated
                            <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(versionSort, 'updatedAt') }}</span>
                          </button>
                        </th>
                        <th scope="col">Approved</th>
                        <th scope="col">Created by</th>
                        <th scope="col">Origin</th>
                        <th scope="col">Auto</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="version in dossierVersionRows" :key="version.dossierId">
                        <th scope="row">{{ version.version }}</th>
                        <td>{{ version.status }}</td>
                        <td>{{ version.createdAt ? formatDate(version.createdAt) : '-' }}</td>
                        <td>{{ version.updatedAt ? formatDate(version.updatedAt) : '-' }}</td>
                        <td>{{ version.approvedAt ? formatDate(version.approvedAt) : '-' }}</td>
                        <td>{{ version.createdBy || '-' }}</td>
                        <td>{{ version.origin }}</td>
                        <td>{{ version.autoApproved ? 'Yes' : 'No' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>

              <div class="section">
                <h4>Last refresh run</h4>
                <dl class="kb-dl" v-if="dossierDetail.lastRefreshRun">
                  <dt>Status</dt>
                  <dd>{{ dossierDetail.lastRefreshRun.status }}</dd>
                  <dt>Started</dt>
                  <dd>{{ dossierDetail.lastRefreshRun.startedAt ? formatDate(dossierDetail.lastRefreshRun.startedAt) : '-' }}</dd>
                  <dt>Finished</dt>
                  <dd>{{ dossierDetail.lastRefreshRun.finishedAt ? formatDate(dossierDetail.lastRefreshRun.finishedAt) : '-' }}</dd>
                  <dt>Attempts</dt>
                  <dd>{{ dossierDetail.lastRefreshRun.attempts }}</dd>
                  <dt>Error</dt>
                  <dd>{{ dossierDetail.lastRefreshRun.error || '-' }}</dd>
                </dl>
                <p v-else class="hint">No refresh runs recorded.</p>
              </div>
            </div>
            <div v-else class="kb-detail-empty">
              <p class="hint">Select a dossier to view details.</p>
            </div>
          </div>
        </div>
      </div>

      <div
        v-else-if="activeSection === 'BULK'"
        class="section"
        role="tabpanel"
        tabindex="0"
        :id="panelId('BULK')"
        :aria-labelledby="tabId('BULK')"
      >
        <h3>Bulk research</h3>
        <p class="hint">Paste ISINs to generate dossiers and extractions in bulk.</p>

        <div v-if="bulkAutoApprove" class="callout warn">
          Auto-approve is enabled. LLM output can be wrong; spot-check results.
        </div>

        <form class="form" @submit.prevent>
          <label class="field">
            <span>ISINs</span>
            <textarea v-model="bulkIsinsText" rows="4" placeholder="One per line, or separated by spaces/commas"></textarea>
          </label>
          <label class="checkbox">
            <input type="checkbox" v-model="bulkAutoApprove" />
            Auto-approve dossiers and extractions
          </label>
          <label class="checkbox">
            <input type="checkbox" v-model="bulkApplyOverrides" :disabled="!canApplyOverrides" />
            Apply approved extractions to overrides
          </label>
          <p v-if="!canApplyOverrides" class="hint">
            Applying to overrides is disabled in config.
          </p>
          <div class="actions">
            <button
              class="primary"
              type="button"
              :disabled="bulkBusy || bulkIsins.length === 0 || bulkAllBusy"
              @click="runBulkResearch"
            >
              Run bulk research
            </button>
            <button class="ghost" type="button" :disabled="selectedIsinsCount === 0" @click="useSelectedForBulk">
              Use selected ISINs
            </button>
          </div>
        </form>

        <p v-if="bulkError" class="toast error">{{ bulkError }}</p>
        <p v-if="bulkWarning" class="hint">{{ bulkWarning }}</p>
        <p v-if="bulkAction" class="hint">
          Bulk action {{ formatActionStatus(bulkAction.status) }}: {{ bulkAction.message || '-' }}
        </p>

        <div v-if="bulkResult" class="section">
          <h4>Results</h4>
          <p class="hint">
            Total {{ bulkResult.total }} | Succeeded {{ bulkResult.succeeded }} | Skipped {{ bulkResult.skipped }} | Failed {{ bulkResult.failed }}
          </p>
          <div class="table-wrap">
            <table class="table">
              <caption class="sr-only">Bulk research results</caption>
              <thead>
                <tr>
                  <th scope="col" :aria-sort="ariaSort(bulkSort, 'isin')">
                    <button
                      type="button"
                      class="sort-button"
                      :aria-label="sortButtonLabel('ISIN', bulkSort, 'isin')"
                      @click="toggleSort(bulkSort, 'isin')"
                    >
                      ISIN <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(bulkSort, 'isin') }}</span>
                    </button>
                  </th>
                  <th scope="col" :aria-sort="ariaSort(bulkSort, 'status')">
                    <button
                      type="button"
                      class="sort-button"
                      :aria-label="sortButtonLabel('Status', bulkSort, 'status')"
                      @click="toggleSort(bulkSort, 'status')"
                    >
                      Status <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(bulkSort, 'status') }}</span>
                    </button>
                  </th>
                  <th scope="col">Dossier ID</th>
                  <th scope="col">Extraction ID</th>
                  <th scope="col">Manual approval</th>
                  <th scope="col">Error</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in bulkResultItems" :key="item.isin">
                  <th scope="row">{{ item.isin }}</th>
                  <td>{{ item.status }}</td>
                  <td>{{ item.dossierId ?? '-' }}</td>
                  <td>{{ item.extractionId ?? '-' }}</td>
                  <td>{{ formatManualApproval(item.manualApproval) }}</td>
                  <td>{{ item.error || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div
        v-else-if="activeSection === 'ALTERNATIVES'"
        class="section"
        role="tabpanel"
        tabindex="0"
        :id="panelId('ALTERNATIVES')"
        :aria-labelledby="tabId('ALTERNATIVES')"
      >
        <h3>Find alternatives</h3>
        <p class="hint">Generate alternative ISINs with rationale and sources.</p>

        <div v-if="alternativesAutoApprove" class="callout warn">
          Auto-approve is enabled. LLM output can be wrong; spot-check results.
        </div>

        <form class="form" @submit.prevent>
          <label class="field">
            <span>Base ISIN</span>
            <input v-model="alternativesIsin" placeholder="e.g. IE00B4L5Y983" />
          </label>
          <label class="checkbox">
            <input type="checkbox" v-model="alternativesAutoApprove" />
            Auto-approve dossiers and extractions
          </label>
          <div class="actions">
            <button
              class="primary"
              type="button"
              :disabled="alternativesBusy || !alternativesIsinNormalized || isIsinBusy(alternativesIsinNormalized)"
              @click="runAlternatives"
            >
              Find alternatives
            </button>
            <button
              class="ghost"
              type="button"
              :disabled="selectedAlternativeCount === 0"
              @click="sendAlternativesToBulk"
            >
              Send selected to Bulk
            </button>
          </div>
        </form>

        <p v-if="alternativesError" class="toast error">{{ alternativesError }}</p>
        <p v-if="alternativesIsinNormalized && isIsinBusy(alternativesIsinNormalized)" class="hint">
          LLM action already running for this ISIN.
        </p>
        <p v-if="alternativesAction" class="hint">
          Alternatives action {{ formatActionStatus(alternativesAction.status) }}: {{ alternativesAction.message || '-' }}
        </p>

        <div v-if="alternativesItems.length" class="section">
          <h4>Alternatives</h4>
          <div class="table-wrap">
            <table class="table">
              <caption class="sr-only">Alternative ISINs</caption>
              <thead>
                <tr>
                  <th scope="col" class="kb-select-col"></th>
                  <th scope="col" :aria-sort="ariaSort(alternativesSort, 'isin')">
                    <button
                      type="button"
                      class="sort-button"
                      :aria-label="sortButtonLabel('ISIN', alternativesSort, 'isin')"
                      @click="toggleSort(alternativesSort, 'isin')"
                    >
                      ISIN
                      <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(alternativesSort, 'isin') }}</span>
                    </button>
                  </th>
                  <th scope="col" :aria-sort="ariaSort(alternativesSort, 'status')">
                    <button
                      type="button"
                      class="sort-button"
                      :aria-label="sortButtonLabel('Status', alternativesSort, 'status')"
                      @click="toggleSort(alternativesSort, 'status')"
                    >
                      Status
                      <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(alternativesSort, 'status') }}</span>
                    </button>
                  </th>
                  <th scope="col">Manual approval</th>
                  <th scope="col">Rationale</th>
                  <th scope="col">Sources</th>
                  <th scope="col">Error</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in alternativesRows" :key="item.isin">
                  <td class="kb-select-col">
                    <input
                      type="checkbox"
                      :disabled="!alternativeSelectable(item)"
                      :checked="isAlternativeSelected(item.isin)"
                      @change="toggleAlternativeSelection(item.isin)"
                      :aria-label="`Select alternative ${item.isin}`"
                    />
                  </td>
                  <th scope="row">{{ item.isin }}</th>
                  <td>
                    <span :class="['badge', statusBadgeClass(item.status)]">{{ item.status }}</span>
                  </td>
                  <td>{{ formatManualApproval(item.manualApproval) }}</td>
                  <td>
                    <details v-if="shouldUseDetails(item.rationale)" class="kb-details">
                      <summary>{{ summarizeText(item.rationale) }}</summary>
                      <p>{{ item.rationale }}</p>
                    </details>
                    <span v-else>{{ item.rationale || '-' }}</span>
                  </td>
                  <td>
                    <details>
                      <summary>{{ (item.citations || []).length }} sources</summary>
                      <ul class="kb-citations">
                        <li v-for="(source, idx) in item.citations" :key="idx">
                          <a :href="source.url" target="_blank" rel="noreferrer">{{ source.title || source.url }}</a>
                          <span class="muted" v-if="source.publisher">{{ source.publisher }}</span>
                        </li>
                      </ul>
                    </details>
                  </td>
                  <td>
                    <details v-if="shouldUseDetails(item.error)" class="kb-details">
                      <summary>{{ summarizeText(item.error) }}</summary>
                      <p>{{ item.error }}</p>
                    </details>
                    <span v-else>{{ item.error || '-' }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="hint">
            Only failed alternatives can be sent to bulk research.
          </p>
        </div>
        <p v-else-if="!alternativesBusy && !alternativesRunning" class="hint">No alternatives yet.</p>
      </div>

      <div
        v-else-if="activeSection === 'REFRESH'"
        class="section"
        role="tabpanel"
        tabindex="0"
        :id="panelId('REFRESH')"
        :aria-labelledby="tabId('REFRESH')"
      >
        <h3>Auto-refresh</h3>
        <p class="hint">Monitor stale dossiers and trigger refresh batches.</p>

        <div v-if="configForm.enabled" class="callout warn">
          Auto-refresh is enabled. Automatic updates can introduce mistakes and should be reviewed.
        </div>

        <div class="kb-refresh-stats">
          <dl class="kb-dl">
            <dt>Stale dossiers</dt>
            <dd>{{ refreshStats.staleCount }}</dd>
            <dt>Refresh interval (days)</dt>
            <dd>{{ configForm.refreshIntervalDays }}</dd>
            <dt>Last refresh run</dt>
            <dd>{{ refreshStats.lastRun ? refreshStats.lastRun.status : 'None' }}</dd>
            <dt>Last run started</dt>
            <dd>{{ refreshStats.lastRun?.startedAt ? formatDate(refreshStats.lastRun.startedAt) : '-' }}</dd>
            <dt>Last run finished</dt>
            <dd>{{ refreshStats.lastRun?.finishedAt ? formatDate(refreshStats.lastRun.finishedAt) : '-' }}</dd>
            <dt>Last run error</dt>
            <dd>{{ refreshStats.lastRun?.error || '-' }}</dd>
          </dl>
        </div>

        <form class="form" @submit.prevent>
          <label class="field">
            <span>Limit instruments (optional)</span>
            <input type="number" min="1" v-model.number="refreshForm.limit" placeholder="Defaults to config" />
          </label>
          <label class="field">
            <span>Batch size (optional)</span>
            <input type="number" min="1" v-model.number="refreshForm.batchSize" placeholder="Defaults to config" />
          </label>
          <label class="checkbox">
            <input type="checkbox" v-model="refreshForm.dryRun" />
            Dry run (no LLM calls)
          </label>
          <label class="field">
            <span>Scope ISINs (optional)</span>
            <textarea v-model="refreshForm.scopeText" rows="3" placeholder="Limit refresh to listed ISINs"></textarea>
          </label>
          <div class="actions">
            <button class="primary" type="button" :disabled="refreshBusy" @click="runRefreshBatch">
              Run refresh now
            </button>
          </div>
        </form>

        <p v-if="refreshError" class="toast error">{{ refreshError }}</p>
        <p v-if="refreshAction" class="hint">
          Refresh action {{ formatActionStatus(refreshAction.status) }}: {{ refreshAction.message || '-' }}
        </p>

        <div v-if="refreshResult" class="section">
          <h4>Refresh results</h4>
          <p class="hint">
            Candidates {{ refreshResult.totalCandidates }} | Processed {{ refreshResult.processed }} | Succeeded {{ refreshResult.succeeded }} |
            Skipped {{ refreshResult.skipped }} | Failed {{ refreshResult.failed }}
          </p>
          <div class="table-wrap">
            <table class="table">
              <caption class="sr-only">Refresh results</caption>
              <thead>
                <tr>
                  <th scope="col" :aria-sort="ariaSort(refreshSort, 'isin')">
                    <button
                      type="button"
                      class="sort-button"
                      :aria-label="sortButtonLabel('ISIN', refreshSort, 'isin')"
                      @click="toggleSort(refreshSort, 'isin')"
                    >
                      ISIN <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(refreshSort, 'isin') }}</span>
                    </button>
                  </th>
                  <th scope="col" :aria-sort="ariaSort(refreshSort, 'status')">
                    <button
                      type="button"
                      class="sort-button"
                      :aria-label="sortButtonLabel('Status', refreshSort, 'status')"
                      @click="toggleSort(refreshSort, 'status')"
                    >
                      Status <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(refreshSort, 'status') }}</span>
                    </button>
                  </th>
                  <th scope="col">Dossier ID</th>
                  <th scope="col">Extraction ID</th>
                  <th scope="col">Error</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in refreshResultItems" :key="item.isin">
                  <th scope="row">{{ item.isin }}</th>
                  <td>{{ item.status }}</td>
                  <td>{{ item.dossierId ?? '-' }}</td>
                  <td>{{ item.extractionId ?? '-' }}</td>
                  <td>{{ item.error || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div
        v-else-if="activeSection === 'ACTIONS'"
        class="section"
        role="tabpanel"
        tabindex="0"
        :id="panelId('ACTIONS')"
        :aria-labelledby="tabId('ACTIONS')"
      >
        <h3>LLM actions</h3>
        <p class="hint">Track running and recent LLM jobs. Actions are stored in memory only.</p>

        <p v-if="llmActionsError" class="toast error">{{ llmActionsError }}</p>

        <div class="table-wrap">
          <table class="table">
            <caption class="sr-only">LLM actions</caption>
            <thead>
              <tr>
                <th scope="col" :aria-sort="ariaSort(llmActionsSort, 'createdAt')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Created', llmActionsSort, 'createdAt')"
                    @click="toggleSort(llmActionsSort, 'createdAt', 'desc')"
                  >
                    Created <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(llmActionsSort, 'createdAt') }}</span>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(llmActionsSort, 'updatedAt')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Updated', llmActionsSort, 'updatedAt')"
                    @click="toggleSort(llmActionsSort, 'updatedAt', 'desc')"
                  >
                    Updated <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(llmActionsSort, 'updatedAt') }}</span>
                  </button>
                </th>
                <th scope="col">Type</th>
                <th scope="col">Trigger</th>
                <th scope="col" :aria-sort="ariaSort(llmActionsSort, 'status')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Status', llmActionsSort, 'status')"
                    @click="toggleSort(llmActionsSort, 'status')"
                  >
                    Status <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(llmActionsSort, 'status') }}</span>
                  </button>
                </th>
                <th scope="col">ISINs</th>
                <th scope="col">Manual approvals</th>
                <th scope="col">Message</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="llmActionsLoading">
                <td colspan="9">Loading LLM actions...</td>
              </tr>
              <tr v-else-if="llmActionRows.length === 0">
                <td colspan="9">No actions yet.</td>
              </tr>
              <tr v-else v-for="action in llmActionRows" :key="action.actionId">
                <th scope="row">{{ action.createdAt ? formatDate(action.createdAt) : '-' }}</th>
                <td>{{ action.updatedAt ? formatDate(action.updatedAt) : '-' }}</td>
                <td>{{ formatActionType(action.type) }}</td>
                <td>{{ formatActionTrigger(action.trigger) }}</td>
                <td>
                  <span :class="['badge', statusBadgeClass(action.status)]">{{ action.status }}</span>
                </td>
                <td>
                  <details v-if="shouldUseDetails(formatIsinList(action.isins))" class="kb-details">
                    <summary>{{ summarizeText(formatIsinList(action.isins)) }}</summary>
                    <p>{{ formatIsinList(action.isins) }}</p>
                  </details>
                  <span v-else>{{ formatIsinList(action.isins) }}</span>
                </td>
                <td>
                  <details v-if="shouldUseDetails(formatManualApprovals(action.manualApprovals))" class="kb-details">
                    <summary>{{ summarizeText(formatManualApprovals(action.manualApprovals)) }}</summary>
                    <p>{{ formatManualApprovals(action.manualApprovals) }}</p>
                  </details>
                  <span v-else>{{ formatManualApprovals(action.manualApprovals) }}</span>
                </td>
                <td>
                  <details v-if="shouldUseDetails(action.message)" class="kb-details">
                    <summary>{{ summarizeText(action.message) }}</summary>
                    <p>{{ action.message }}</p>
                  </details>
                  <span v-else>{{ action.message || '-' }}</span>
                </td>
                <td>
                  <button
                    v-if="action.status === 'RUNNING'"
                    class="ghost"
                    type="button"
                    :disabled="isActionBusy(action.actionId)"
                    @click="cancelLlmAction(action.actionId)"
                  >
                    Cancel
                  </button>
                  <button
                    v-else
                    class="ghost"
                    type="button"
                    :disabled="isActionBusy(action.actionId)"
                    @click="dismissLlmAction(action.actionId)"
                  >
                    Dismiss
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div
        v-else-if="activeSection === 'RUNS'"
        class="section"
        role="tabpanel"
        tabindex="0"
        :id="panelId('RUNS')"
        :aria-labelledby="tabId('RUNS')"
      >
        <h3>Runs</h3>
        <form class="form inline kb-filter-form" @submit.prevent="loadRuns">
          <label class="field">
            <span>ISIN</span>
            <input v-model="runsFilters.isin" placeholder="Filter by ISIN" />
          </label>
          <label class="field">
            <span>Status</span>
            <select v-model="runsFilters.status">
              <option value="">Any</option>
              <option v-for="status in runStatusOptions" :key="status" :value="status">{{ status }}</option>
            </select>
          </label>
          <label class="field">
            <span>Page size</span>
            <select v-model.number="runsPage.size">
              <option :value="25">25</option>
              <option :value="50">50</option>
              <option :value="100">100</option>
            </select>
          </label>
          <button class="ghost" type="submit" :disabled="runsLoading">Refresh</button>
        </form>

        <p v-if="runsError" class="toast error">{{ runsError }}</p>

        <div class="table-wrap">
          <table class="table">
            <caption class="sr-only">Knowledge base runs</caption>
            <thead>
              <tr>
                <th scope="col" :aria-sort="ariaSort(runsSort, 'startedAt')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Started', runsSort, 'startedAt')"
                    @click="toggleSort(runsSort, 'startedAt', 'desc')"
                  >
                    Started <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(runsSort, 'startedAt') }}</span>
                  </button>
                </th>
                <th scope="col">Action</th>
                <th scope="col" :aria-sort="ariaSort(runsSort, 'isin')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('ISIN', runsSort, 'isin')"
                    @click="toggleSort(runsSort, 'isin')"
                  >
                    ISIN <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(runsSort, 'isin') }}</span>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(runsSort, 'status')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Status', runsSort, 'status')"
                    @click="toggleSort(runsSort, 'status')"
                  >
                    Status <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(runsSort, 'status') }}</span>
                  </button>
                </th>
                <th scope="col">Manual approval</th>
                <th scope="col">Attempts</th>
                <th scope="col">Error</th>
                <th scope="col">Batch</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="runsLoading">
                <td colspan="8">Loading runs...</td>
              </tr>
              <tr v-else-if="runsItems.length === 0">
                <td colspan="8">No runs found.</td>
              </tr>
              <tr v-else v-for="run in runsRows" :key="run.runId">
                <th scope="row">{{ run.startedAt ? formatDate(run.startedAt) : '-' }}</th>
                <td>{{ run.action }}</td>
                <td>{{ run.isin }}</td>
                <td>
                  <span :class="['badge', statusBadgeClass(run.status)]">{{ run.status }}</span>
                </td>
                <td>{{ formatManualApproval(run.manualApproval) }}</td>
                <td>{{ run.attempts }}</td>
                <td>{{ run.error || '-' }}</td>
                <td>{{ run.batchId || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="actions">
          <button class="ghost" type="button" :disabled="!hasPrevRunsPage" @click="prevRunsPage">Prev</button>
          <button class="ghost" type="button" :disabled="!hasNextRunsPage" @click="nextRunsPage">Next</button>
        </div>
      </div>

      <div
        v-else-if="activeSection === 'SETTINGS'"
        class="section"
        role="tabpanel"
        tabindex="0"
        :id="panelId('SETTINGS')"
        :aria-labelledby="tabId('SETTINGS')"
      >
        <h3>Settings</h3>
        <p class="hint">These settings are stored in the database.</p>

        <div v-if="configForm.autoApprove" class="callout warn">
          Auto-approve is enabled. LLM output can be wrong; spot-check results.
        </div>
        <div v-if="configForm.enabled" class="callout warn">
          Auto-refresh is enabled. Automatic updates can introduce mistakes and should be reviewed.
        </div>

        <form class="form kb-settings" @submit.prevent>
          <label class="checkbox">
            <input type="checkbox" v-model="configForm.enabled" />
            Enable auto-refresh
          </label>
          <label class="checkbox">
            <input type="checkbox" v-model="configForm.autoApprove" />
            Enable auto-approve for dossiers and extractions
          </label>
          <label class="checkbox">
            <input type="checkbox" v-model="configForm.applyExtractionsToOverrides" />
            Apply approved extractions to overrides
          </label>
          <label class="checkbox">
            <input type="checkbox" v-model="configForm.overwriteExistingOverrides" />
            Overwrite existing overrides
          </label>

          <div class="kb-settings-grid">
            <label class="field">
              <span>Refresh interval (days)</span>
              <input type="number" min="1" v-model.number="configForm.refreshIntervalDays" />
            </label>
            <label class="field">
              <span>Batch size (instruments)</span>
              <input type="number" min="1" v-model.number="configForm.batchSizeInstruments" />
            </label>
            <label class="field">
              <span>Batch max input chars</span>
              <input type="number" min="1" v-model.number="configForm.batchMaxInputChars" />
            </label>
            <label class="field">
              <span>Parallel bulk batches</span>
              <input type="number" min="1" v-model.number="configForm.maxParallelBulkBatches" />
            </label>
            <label class="field">
              <span>Max batches per run</span>
              <input type="number" min="1" v-model.number="configForm.maxBatchesPerRun" />
            </label>
            <label class="field">
              <span>Poll interval (seconds)</span>
              <input type="number" min="1" v-model.number="configForm.pollIntervalSeconds" />
            </label>
            <label class="field">
              <span>Max instruments per run</span>
              <input type="number" min="1" v-model.number="configForm.maxInstrumentsPerRun" />
            </label>
            <label class="field">
              <span>Max retries per instrument</span>
              <input type="number" min="1" v-model.number="configForm.maxRetriesPerInstrument" />
            </label>
            <label class="field">
              <span>Base backoff (seconds)</span>
              <input type="number" min="1" v-model.number="configForm.baseBackoffSeconds" />
            </label>
            <label class="field">
              <span>Max backoff (seconds)</span>
              <input type="number" min="1" v-model.number="configForm.maxBackoffSeconds" />
            </label>
            <label class="field">
              <span>Dossier max chars</span>
              <input type="number" min="1" v-model.number="configForm.dossierMaxChars" />
            </label>
            <label class="field">
              <span>Min days between refresh per ISIN</span>
              <input type="number" min="1" v-model.number="configForm.kbRefreshMinDaysBetweenRunsPerInstrument" />
            </label>
            <label class="field">
              <span>Run timeout (minutes)</span>
              <input type="number" min="1" v-model.number="configForm.runTimeoutMinutes" />
            </label>
            <label class="field">
              <span>Websearch reasoning effort</span>
              <select class="input" v-model="configForm.websearchReasoningEffort">
                <option value="low">Low (default)</option>
                <option value="medium">Medium</option>
                <option value="high">High</option>
              </select>
            </label>
            <label class="field">
              <span>Bulk min citations</span>
              <input type="number" min="1" v-model.number="configForm.bulkMinCitations" />
            </label>
            <label class="field">
              <span>Require primary source</span>
              <select class="input" v-model="configForm.bulkRequirePrimarySource">
                <option :value="true">Yes</option>
                <option :value="false">No</option>
              </select>
            </label>
            <label class="field">
              <span>Alternatives similarity threshold</span>
              <input type="number" step="0.05" min="0" max="1" v-model.number="configForm.alternativesMinSimilarityScore" />
            </label>
            <label class="field">
              <span>Require extraction evidence</span>
              <select class="input" v-model="configForm.extractionEvidenceRequired">
                <option :value="true">Yes</option>
                <option :value="false">No</option>
              </select>
            </label>
          </div>

          <label class="field">
            <span>Websearch allowed domains (one per line)</span>
            <textarea v-model="domainsText" rows="5"></textarea>
          </label>

          <div class="actions">
            <button class="primary" type="button" :disabled="configBusy" @click="saveConfig">Save settings</button>
            <button class="ghost" type="button" :disabled="configBusy" @click="loadConfig">Reload</button>
          </div>
          <p v-if="configError" class="toast error">{{ configError }}</p>
          <p v-if="configSaved" class="toast success">Settings saved.</p>
        </form>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { apiRequest } from '../api'

const sections = [
  { key: 'DOSSIERS', label: 'Dossiers' },
  { key: 'BULK', label: 'Bulk research' },
  { key: 'ALTERNATIVES', label: 'Alternatives' },
  { key: 'REFRESH', label: 'Auto-refresh' },
  { key: 'ACTIONS', label: 'LLM actions' },
  { key: 'RUNS', label: 'Runs' },
  { key: 'SETTINGS', label: 'Settings' }
]

const dossierStatusOptions = ['APPROVED', 'PENDING_REVIEW', 'FAILED', 'REJECTED', 'DRAFT', 'SUPERSEDED']
const runStatusOptions = ['IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'FAILED_TIMEOUT', 'SKIPPED']

const activeSection = ref('DOSSIERS')
const tabRefs = ref([])
const kbDisabled = ref(false)
const error = ref('')
const lastKbExportAt = ref(null)
const lastKbImportAt = ref(null)

const configForm = ref({
  enabled: false,
  refreshIntervalDays: 30,
  autoApprove: false,
  applyExtractionsToOverrides: false,
  overwriteExistingOverrides: false,
  batchSizeInstruments: 10,
  batchMaxInputChars: 120000,
  maxParallelBulkBatches: 2,
  maxBatchesPerRun: 5,
  pollIntervalSeconds: 300,
  maxInstrumentsPerRun: 100,
  maxRetriesPerInstrument: 3,
  baseBackoffSeconds: 2,
  maxBackoffSeconds: 30,
  dossierMaxChars: 15000,
  kbRefreshMinDaysBetweenRunsPerInstrument: 7,
  runTimeoutMinutes: 30,
  websearchReasoningEffort: 'low',
  bulkMinCitations: 2,
  bulkRequirePrimarySource: true,
  alternativesMinSimilarityScore: 0.6,
  extractionEvidenceRequired: true,
  qualityGateProfiles: null
})
const domainsText = ref('')
const configBusy = ref(false)
const configError = ref('')
const configSaved = ref(false)

const dossierFilters = ref({
  query: '',
  status: '',
  stale: ''
})
const dossierPage = ref({
  page: 0,
  size: 50,
  total: 0
})
const dossierItems = ref([])
const dossiersLoading = ref(false)
const dossiersError = ref('')
const dossiersMessage = ref('')
const dossierSort = reactive({ key: 'updatedAt', direction: 'desc' })
const versionSort = reactive({ key: 'version', direction: 'desc' })
const SORT_STORAGE_KEY = 'kb.sortState.v1'
const KB_EXPORT_STORAGE_KEY = 'kb.lastExport'
const KB_IMPORT_STORAGE_KEY = 'kb.lastImport'

const selectedIsins = ref(new Set())
const selectedIsin = ref('')
const dossierDetail = ref(null)
const dossierActionError = ref('')
const editMode = ref(false)
const dossierTableWrap = ref(null)
const showDossierScrollHint = ref(false)
const dossierForm = ref({
  displayName: '',
  contentMd: '',
  status: 'DRAFT',
  citationsText: '[]'
})

const extractionError = ref('')

const bulkIsinsText = ref('')
const bulkAutoApprove = ref(false)
const bulkApplyOverrides = ref(false)
const bulkBusy = ref(false)
const bulkError = ref('')
const bulkResult = ref(null)
const bulkSort = reactive({ key: 'isin', direction: 'asc' })

const alternativesIsin = ref('')
const alternativesAutoApprove = ref(false)
const alternativesBusy = ref(false)
const alternativesError = ref('')
const alternativesItems = ref([])
const alternativesSelected = ref(new Set())
const alternativesSort = reactive({ key: 'isin', direction: 'asc' })

const refreshForm = ref({
  limit: null,
  batchSize: null,
  dryRun: false,
  scopeText: ''
})
const refreshStats = ref({
  staleCount: 0,
  lastRun: null
})
const refreshBusy = ref(false)
const refreshError = ref('')
const refreshResult = ref(null)
const refreshSort = reactive({ key: 'isin', direction: 'asc' })

const llmActions = ref([])
const llmActionsLoading = ref(false)
const llmActionsError = ref('')
const llmActionsSort = reactive({ key: 'updatedAt', direction: 'desc' })
const llmActionsBusy = ref(new Set())
const llmActionResultsLoaded = ref(new Set())
const bulkActionId = ref('')
const alternativesActionId = ref('')
const refreshActionId = ref('')
const extractionActionId = ref('')
const bulkWarning = ref('')
const ACTIONS_POLL_INTERVAL_MS = 5000
let actionsPollHandle = null

const runsFilters = ref({
  isin: '',
  status: ''
})
const runsPage = ref({
  page: 0,
  size: 50,
  total: 0
})
const runsItems = ref([])
const runsLoading = ref(false)
const runsError = ref('')
const runsSort = reactive({ key: 'startedAt', direction: 'desc' })

const canApplyOverrides = computed(() => configForm.value.applyExtractionsToOverrides)
const selectedIsinsCount = computed(() => selectedIsins.value.size)
const hasDossierDetail = computed(() => Boolean(dossierDetail.value))
const isHintCompact = computed(() => dossierItems.value.length <= 3)
const allSelectedOnPage = computed(() =>
  dossierItems.value.length > 0 && dossierItems.value.every((item) => selectedIsins.value.has(item.isin))
)
const missingOnPageCount = computed(() => dossierItems.value.filter((item) => isMissing(item)).length)
const bulkIsins = computed(() => extractIsins(bulkIsinsText.value))
const selectedAlternativeCount = computed(() => alternativesSelected.value.size)
const alternativesIsinNormalized = computed(() => (alternativesIsin.value || '').trim().toUpperCase())

const dossierSorters = {
  isin: (item) => item.isin,
  status: (item) => item.latestDossierStatus,
  updatedAt: (item) => item.latestUpdatedAt
}
const versionSorters = {
  version: (item) => item.version,
  updatedAt: (item) => item.updatedAt
}
const alternativesSorters = {
  isin: (item) => item.isin,
  status: (item) => item.status
}
const bulkSorters = {
  isin: (item) => item.isin,
  status: (item) => item.status
}
const refreshSorters = {
  isin: (item) => item.isin,
  status: (item) => item.status
}
const llmActionSorters = {
  createdAt: (item) => item.createdAt,
  updatedAt: (item) => item.updatedAt,
  status: (item) => item.status
}
const runsSorters = {
  startedAt: (item) => item.startedAt,
  isin: (item) => item.isin,
  status: (item) => item.status
}

const dossierRows = computed(() => sortItems(dossierItems.value, dossierSort, dossierSorters))
const dossierVersionRows = computed(() =>
  sortItems(dossierDetail.value?.versions || [], versionSort, versionSorters)
)
const alternativesRows = computed(() => sortItems(alternativesItems.value, alternativesSort, alternativesSorters))
const bulkResultItems = computed(() =>
  sortItems(bulkResult.value?.items || [], bulkSort, bulkSorters)
)
const refreshResultItems = computed(() =>
  sortItems(refreshResult.value?.items || [], refreshSort, refreshSorters)
)
const llmActionRows = computed(() => sortItems(llmActions.value || [], llmActionsSort, llmActionSorters))
const runsRows = computed(() => sortItems(runsItems.value, runsSort, runsSorters))
const runningIsins = computed(() => {
  const active = new Set()
  for (const action of llmActions.value || []) {
    if (action.status !== 'RUNNING') continue
    for (const isin of action.isins || []) {
      if (isin) {
        active.add(isin)
      }
    }
  }
  return active
})
const bulkBlockedIsins = computed(() => bulkIsins.value.filter((isin) => runningIsins.value.has(isin)))
const bulkAllBusy = computed(
  () => bulkIsins.value.length > 0 && bulkBlockedIsins.value.length === bulkIsins.value.length
)
const bulkAction = computed(() => findActionById(bulkActionId.value))
const alternativesAction = computed(() => findActionById(alternativesActionId.value))
const refreshAction = computed(() => findActionById(refreshActionId.value))
const extractionAction = computed(() => findActionById(extractionActionId.value))
const alternativesRunning = computed(() => alternativesAction.value?.status === 'RUNNING')
const sortSnapshot = computed(() => ({
  dossier: { key: dossierSort.key, direction: dossierSort.direction },
  version: { key: versionSort.key, direction: versionSort.direction },
  bulk: { key: bulkSort.key, direction: bulkSort.direction },
  alternatives: { key: alternativesSort.key, direction: alternativesSort.direction },
  refresh: { key: refreshSort.key, direction: refreshSort.direction },
  actions: { key: llmActionsSort.key, direction: llmActionsSort.direction },
  runs: { key: runsSort.key, direction: runsSort.direction }
}))

const latestExtraction = computed(() => dossierDetail.value?.extractions?.[0] || null)
const dossierQualityGate = computed(() =>
  dossierDetail.value?.latestDossier?.quality_gate || dossierDetail.value?.latestDossier?.qualityGate || null
)
const dossierQualityReasons = computed(() => {
  if (!dossierQualityGate.value || dossierQualityGate.value.passed) return []
  return dossierQualityGate.value.reasons || []
})
const formattedExtraction = computed(() =>
  latestExtraction.value ? JSON.stringify(latestExtraction.value.extractedJson, null, 2) : ''
)
const extractionEvidenceGate = computed(() =>
  latestExtraction.value?.evidence_gate || latestExtraction.value?.evidenceGate || null
)
const extractionEvidenceMissing = computed(() => {
  if (!extractionEvidenceGate.value || extractionEvidenceGate.value.passed) return []
  return extractionEvidenceGate.value.missing_evidence || extractionEvidenceGate.value.missingEvidence || []
})
const extractionMissingFields = computed(() => {
  if (!latestExtraction.value) return []
  return (latestExtraction.value.missingFieldsJson || []).map((entry) => entry.field || entry)
})
const extractionWarnings = computed(() => {
  if (!latestExtraction.value) return []
  return (latestExtraction.value.warningsJson || []).map((entry) => entry.message || entry)
})
const formatFieldValue = (value) => {
  if (value === null || value === undefined) {
    return '-'
  }
  if (typeof value === 'string' && value.trim() === '') {
    return '-'
  }
  return value
}

const extractionBaseFields = computed(() => {
  const payload = latestExtraction.value?.extractedJson || {}
  const etf = payload.etf || {}
  const risk = payload.risk || {}
  const sri = risk.summary_risk_indicator || risk.summaryRiskIndicator || {}
  return [
    { label: 'Name', value: payload.name || '-' },
    { label: 'Instrument type', value: payload.instrument_type || payload.instrumentType || '-' },
    { label: 'Asset class', value: payload.asset_class || payload.assetClass || '-' },
    { label: 'Sub class', value: payload.sub_class || payload.subClass || '-' },
    { label: 'Layer', value: payload.layer ?? '-' },
    { label: 'Layer notes', value: payload.layer_notes || payload.layerNotes || '-' },
    { label: 'ETF ongoing charges pct', value: etf.ongoing_charges_pct ?? etf.ongoingChargesPct ?? '-' },
    { label: 'ETF benchmark', value: etf.benchmark_index || etf.benchmarkIndex || '-' },
    { label: 'SRI', value: sri.value ?? '-' }
  ]
})

const extractionValuationFields = computed(() => {
  const payload = latestExtraction.value?.extractedJson || {}
  const valuation = payload.valuation || {}
  return [
    {
      label: 'EBITDA',
      title: 'Earnings before interest, taxes, depreciation, and amortization',
      value: formatFieldValue(valuation.ebitda)
    },
    {
      label: 'EBITDA (ccy)',
      title: 'EBITDA reporting currency',
      value: formatFieldValue(valuation.ebitda_currency || valuation.ebitdaCurrency)
    },
    {
      label: 'EBITDA (EUR)',
      title: 'EBITDA normalized to EUR using FX rate',
      value: formatFieldValue(valuation.ebitda_eur ?? valuation.ebitdaEur)
    },
    {
      label: 'FX→EUR',
      title: 'FX rate used to convert EBITDA to EUR',
      value: formatFieldValue(valuation.fx_rate_to_eur ?? valuation.fxRateToEur)
    },
    {
      label: 'EV/EBITDA',
      title: 'Enterprise value divided by EBITDA',
      value: formatFieldValue(valuation.ev_to_ebitda ?? valuation.evToEbitda)
    },
    {
      label: 'Net rent',
      title: 'Net rental income (real estate)',
      value: formatFieldValue(valuation.net_rent ?? valuation.netRent)
    },
    {
      label: 'Net rent (ccy)',
      title: 'Net rent reporting currency',
      value: formatFieldValue(valuation.net_rent_currency ?? valuation.netRentCurrency)
    },
    {
      label: 'Net rent period',
      title: 'Net rent period end or fiscal year',
      value: formatFieldValue(valuation.net_rent_period_end ?? valuation.netRentPeriodEnd)
    },
    {
      label: 'Net rent period type',
      title: 'Net rent period type (e.g., TTM or FY)',
      value: formatFieldValue(valuation.net_rent_period_type ?? valuation.netRentPeriodType)
    },
    {
      label: 'NOI',
      title: 'Net operating income (real estate)',
      value: formatFieldValue(valuation.noi)
    },
    {
      label: 'NOI (ccy)',
      title: 'NOI reporting currency',
      value: formatFieldValue(valuation.noi_currency ?? valuation.noiCurrency)
    },
    {
      label: 'NOI period',
      title: 'NOI period end or fiscal year',
      value: formatFieldValue(valuation.noi_period_end ?? valuation.noiPeriodEnd)
    },
    {
      label: 'NOI period type',
      title: 'NOI period type (e.g., TTM or FY)',
      value: formatFieldValue(valuation.noi_period_type ?? valuation.noiPeriodType)
    },
    {
      label: 'AFFO',
      title: 'Adjusted funds from operations',
      value: formatFieldValue(valuation.affo)
    },
    {
      label: 'AFFO (ccy)',
      title: 'AFFO reporting currency',
      value: formatFieldValue(valuation.affo_currency ?? valuation.affoCurrency)
    },
    {
      label: 'AFFO period',
      title: 'AFFO period end or fiscal year',
      value: formatFieldValue(valuation.affo_period_end ?? valuation.affoPeriodEnd)
    },
    {
      label: 'AFFO period type',
      title: 'AFFO period type (e.g., TTM or FY)',
      value: formatFieldValue(valuation.affo_period_type ?? valuation.affoPeriodType)
    },
    {
      label: 'FFO',
      title: 'Funds from operations',
      value: formatFieldValue(valuation.ffo)
    },
    {
      label: 'FFO (ccy)',
      title: 'FFO reporting currency',
      value: formatFieldValue(valuation.ffo_currency ?? valuation.ffoCurrency)
    },
    {
      label: 'FFO period',
      title: 'FFO period end or fiscal year',
      value: formatFieldValue(valuation.ffo_period_end ?? valuation.ffoPeriodEnd)
    },
    {
      label: 'FFO period type',
      title: 'FFO period type (e.g., TTM or FY)',
      value: formatFieldValue(valuation.ffo_period_type ?? valuation.ffoPeriodType)
    },
    {
      label: 'FFO type',
      title: 'FFO variant (e.g., FFO I)',
      value: formatFieldValue(valuation.ffo_type ?? valuation.ffoType)
    },
    {
      label: 'P/E (LT)',
      title: 'Long-term P/E using smoothed EPS',
      value: formatFieldValue(valuation.pe_longterm ?? valuation.peLongterm)
    },
    {
      label: 'Earnings yield (LT)',
      title: 'Inverse of long-term P/E using smoothed EPS',
      value: formatFieldValue(valuation.earnings_yield_longterm ?? valuation.earningsYieldLongterm)
    },
    {
      label: 'P/E (current)',
      title: 'Current price-to-earnings ratio (TTM/forward as stated)',
      value: formatFieldValue(valuation.pe_current ?? valuation.peCurrent)
    },
    {
      label: 'P/E (current) as-of',
      title: 'As-of date for current P/E',
      value: formatFieldValue(valuation.pe_current_asof ?? valuation.peCurrentAsOf)
    },
    {
      label: 'P/B (current)',
      title: 'Current price-to-book ratio',
      value: formatFieldValue(valuation.pb_current ?? valuation.pbCurrent)
    },
    {
      label: 'P/B as-of',
      title: 'As-of date for current P/B',
      value: formatFieldValue(valuation.pb_current_asof ?? valuation.pbCurrentAsOf)
    },
    {
      label: 'P/E (TTM holdings)',
      title: 'Holdings-based P/E using trailing twelve months earnings',
      value: formatFieldValue(valuation.pe_ttm_holdings ?? valuation.peTtmHoldings)
    },
    {
      label: 'Earnings yield (TTM)',
      title: 'Holdings-based earnings yield using trailing twelve months earnings',
      value: formatFieldValue(valuation.earnings_yield_ttm_holdings ?? valuation.earningsYieldTtmHoldings)
    },
    {
      label: 'Coverage wt %',
      title: 'Percent of holdings weight with earnings data',
      value: formatFieldValue(valuation.holdings_coverage_weight_pct ?? valuation.holdingsCoverageWeightPct)
    },
    {
      label: 'Coverage count',
      title: 'Number of holdings with earnings data',
      value: formatFieldValue(valuation.holdings_coverage_count ?? valuation.holdingsCoverageCount)
    },
    {
      label: 'P/E method',
      title: 'Method used to calculate P/E',
      value: formatFieldValue(valuation.pe_method || valuation.peMethod)
    },
    {
      label: 'P/E horizon',
      title: 'Valuation horizon (e.g., long-term, TTM, forward)',
      value: formatFieldValue(valuation.pe_horizon || valuation.peHorizon)
    },
    {
      label: 'Neg earnings',
      title: 'Handling of negative earnings in P/E calculations',
      value: formatFieldValue(valuation.neg_earnings_handling || valuation.negEarningsHandling)
    }
  ]
})

const hasValuationData = computed(() => {
  const valuation = latestExtraction.value?.extractedJson?.valuation || {}
  return Object.values(valuation).some((value) => {
    if (value === null || value === undefined) {
      return false
    }
    if (typeof value === 'string') {
      return value.trim() !== ''
    }
    return true
  })
})

const dossierSources = computed(() => {
  const citations = dossierDetail.value?.latestDossier?.citations || []
  return Array.isArray(citations) ? citations : []
})

const canApproveDossier = computed(() => {
  const dossierId = dossierDetail.value?.latestDossier?.dossierId
  const status = dossierDetail.value?.latestDossier?.status
  return dossierId && status && status !== 'APPROVED' && status !== 'SUPERSEDED' && status !== 'REJECTED'
})
const canRejectDossier = computed(() => {
  const dossierId = dossierDetail.value?.latestDossier?.dossierId
  const status = dossierDetail.value?.latestDossier?.status
  return dossierId && status && status !== 'APPROVED' && status !== 'SUPERSEDED'
})

const isNewDossier = computed(() => !dossierDetail.value?.latestDossier?.dossierId)
const canApproveExtraction = computed(() => {
  const status = latestExtraction.value?.status
  return status === 'CREATED' || status === 'PENDING_REVIEW'
})
const canRejectExtraction = computed(() => {
  const status = latestExtraction.value?.status
  return status === 'CREATED' || status === 'PENDING_REVIEW'
})
const canApplyExtraction = computed(() => latestExtraction.value?.status === 'APPROVED')

const hasPrevDossierPage = computed(() => dossierPage.value.page > 0)
const hasNextDossierPage = computed(() => (dossierPage.value.page + 1) * dossierPage.value.size < dossierPage.value.total)
const hasPrevRunsPage = computed(() => runsPage.value.page > 0)
const hasNextRunsPage = computed(() => (runsPage.value.page + 1) * runsPage.value.size < runsPage.value.total)

watch(
  () => configForm.value.applyExtractionsToOverrides,
  (allowed) => {
    if (!allowed) {
      bulkApplyOverrides.value = false
    }
  }
)

watch(
  () => activeSection.value,
  (section) => {
    if (section === 'RUNS') {
      loadRuns()
    }
    if (section === 'REFRESH') {
      loadRefreshStats()
    }
    if (section === 'ACTIONS') {
      loadLlmActions()
    }
  }
)

watch(hasDossierDetail, async () => {
  await nextTick()
  updateDossierScrollHint()
})

watch(sortSnapshot, (next) => {
  persistSortState(next)
})

onMounted(async () => {
  hydrateSortState()
  hydrateBackupStatus()
  await loadConfig()
  if (!kbDisabled.value) {
    await loadDossiers()
    await loadRefreshStats()
    await loadLlmActions()
    startLlmActionsPolling()
    await nextTick()
    updateDossierScrollHint()
  }
  window.addEventListener('resize', updateDossierScrollHint)
  window.addEventListener('storage', handleStorageEvent)
})

onBeforeUnmount(() => {
  stopLlmActionsPolling()
  window.removeEventListener('resize', updateDossierScrollHint)
  window.removeEventListener('storage', handleStorageEvent)
})

function tabId(key) {
  return `kb-tab-${key.toLowerCase()}`
}

function panelId(key) {
  return `kb-panel-${key.toLowerCase()}`
}

function activateSection(key) {
  activeSection.value = key
}

function getTabElements() {
  return (tabRefs.value || []).filter((tab) => tab && typeof tab.focus === 'function')
}

function handleTabListKeydown(event) {
  const key = event.key
  if (!['ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp', 'Home', 'End'].includes(key)) return
  const tabs = getTabElements()
  if (!tabs.length) return
  const target =
    event.target && typeof event.target.closest === 'function' ? event.target.closest('[role="tab"]') : event.target
  let currentIndex = tabs.findIndex((tab) => tab === target)
  if (currentIndex === -1) {
    currentIndex = tabs.findIndex((tab) => tab?.dataset?.key === activeSection.value)
  }
  if (currentIndex === -1) return
  let nextIndex = currentIndex
  if (key === 'ArrowRight' || key === 'ArrowDown') {
    nextIndex = (currentIndex + 1) % tabs.length
  } else if (key === 'ArrowLeft' || key === 'ArrowUp') {
    nextIndex = (currentIndex - 1 + tabs.length) % tabs.length
  } else if (key === 'Home') {
    nextIndex = 0
  } else if (key === 'End') {
    nextIndex = tabs.length - 1
  }
  event.preventDefault()
  const nextTab = tabs[nextIndex]
  const nextKey = nextTab?.dataset?.key
  if (nextKey) {
    activateSection(nextKey)
  }
  nextTab?.focus()
}

function handleKbError(err, fallbackMessage) {
  if (err?.message && err.message.toLowerCase().includes('knowledge base is disabled')) {
    kbDisabled.value = true
    stopLlmActionsPolling()
    return true
  }
  error.value = err?.message || fallbackMessage
  return false
}

async function loadConfig() {
  configError.value = ''
  configSaved.value = false
  try {
    const result = await apiRequest('/kb/config')
    const normalized = normalizeConfig(result || {})
    configForm.value = { ...normalized }
    domainsText.value = (normalized.websearchAllowedDomains || []).join('\n')
    bulkAutoApprove.value = normalized.autoApprove
    bulkApplyOverrides.value = normalized.applyExtractionsToOverrides
    alternativesAutoApprove.value = normalized.autoApprove
  } catch (err) {
    if (!handleKbError(err, 'Failed to load KB config')) {
      configError.value = err?.message || 'Failed to load KB config'
    }
  }
}

async function saveConfig() {
  configBusy.value = true
  configError.value = ''
  configSaved.value = false
  try {
    const payload = toConfigPayload(configForm.value, domainsText.value)
    const result = await apiRequest('/kb/config', {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
    const normalized = normalizeConfig(result || {})
    configForm.value = { ...normalized }
    domainsText.value = (normalized.websearchAllowedDomains || []).join('\n')
    configSaved.value = true
  } catch (err) {
    configError.value = err?.message || 'Failed to save KB config'
  } finally {
    configBusy.value = false
  }
}

async function loadDossiers() {
  dossiersLoading.value = true
  dossiersError.value = ''
  dossiersMessage.value = ''
  const params = new URLSearchParams()
  if (dossierFilters.value.query) params.append('q', dossierFilters.value.query)
  if (dossierFilters.value.status) params.append('status', dossierFilters.value.status)
  if (dossierFilters.value.stale === 'true') params.append('stale', 'true')
  if (dossierFilters.value.stale === 'false') params.append('stale', 'false')
  params.append('page', String(dossierPage.value.page))
  params.append('size', String(dossierPage.value.size))
  try {
    const result = await apiRequest(`/kb/dossiers?${params.toString()}`)
    dossierItems.value = result.items || []
    dossierPage.value.total = result.total || 0
    syncSelectedDossier()
    await nextTick()
    updateDossierScrollHint()
  } catch (err) {
    if (!handleKbError(err, 'Failed to load dossiers')) {
      dossiersError.value = err?.message || 'Failed to load dossiers'
    }
  } finally {
    dossiersLoading.value = false
  }
}

async function loadDossierDetail(isin) {
  dossierActionError.value = ''
  extractionError.value = ''
  editMode.value = false
  try {
    const detail = await apiRequest(`/kb/dossiers/${encodeURIComponent(isin)}`)
    dossierDetail.value = detail
    const latest = detail.latestDossier
    dossierForm.value = {
      displayName: latest.displayName || '',
      contentMd: latest.contentMd || '',
      status: latest.status,
      citationsText: JSON.stringify(latest.citations || [], null, 2)
    }
  } catch (err) {
    dossierActionError.value = err?.message || 'Failed to load dossier detail'
  }
}

function syncSelectedDossier() {
  if (!selectedIsin.value) return
  const match = dossierItems.value.find((item) => item.isin === selectedIsin.value)
  if (!match) {
    selectedIsin.value = ''
    dossierDetail.value = null
  }
}

function openDossier(item) {
  selectedIsin.value = item.isin
  if (!item.hasDossier) {
    dossierActionError.value = ''
    extractionError.value = ''
    editMode.value = true
    dossierDetail.value = buildEmptyDossierDetail(item)
    return
  }
  loadDossierDetail(item.isin)
}

function closeDossier() {
  selectedIsin.value = ''
  dossierDetail.value = null
  editMode.value = false
}

async function saveDossier() {
  if (!dossierDetail.value) return
  dossierActionError.value = ''
  let citations
  try {
    citations = JSON.parse(dossierForm.value.citationsText || '[]')
  } catch (err) {
    dossierActionError.value = 'Citations JSON is invalid.'
    return
  }
  if (!Array.isArray(citations)) {
    dossierActionError.value = 'Citations must be a JSON array.'
    return
  }
  const payload = {
    displayName: dossierForm.value.displayName,
    contentMd: dossierForm.value.contentMd,
    status: dossierForm.value.status,
    citations
  }
  try {
    if (!dossierDetail.value.latestDossier.dossierId) {
      const createPayload = {
        ...payload,
        isin: dossierDetail.value.isin,
        origin: 'USER'
      }
      await apiRequest('/kb/dossiers', {
        method: 'POST',
        body: JSON.stringify(createPayload)
      })
    } else {
      await apiRequest(`/kb/dossiers/${dossierDetail.value.latestDossier.dossierId}`, {
        method: 'PUT',
        body: JSON.stringify(payload)
      })
    }
    editMode.value = false
    await loadDossierDetail(dossierDetail.value.isin)
    await loadDossiers()
  } catch (err) {
    dossierActionError.value = err?.message || 'Failed to save dossier'
  }
}

function toggleEditMode() {
  editMode.value = !editMode.value
}

async function approveDossier() {
  if (!dossierDetail.value) return
  dossierActionError.value = ''
  try {
    await apiRequest(`/kb/dossiers/${dossierDetail.value.latestDossier.dossierId}/approve`, { method: 'POST' })
    await loadDossierDetail(dossierDetail.value.isin)
    await loadDossiers()
  } catch (err) {
    dossierActionError.value = err?.message || 'Failed to approve dossier'
  }
}

async function rejectDossier() {
  if (!dossierDetail.value) return
  dossierActionError.value = ''
  try {
    await apiRequest(`/kb/dossiers/${dossierDetail.value.latestDossier.dossierId}/reject`, { method: 'POST' })
    await loadDossierDetail(dossierDetail.value.isin)
    await loadDossiers()
  } catch (err) {
    dossierActionError.value = err?.message || 'Failed to reject dossier'
  }
}

async function runExtraction() {
  if (!dossierDetail.value) return
  if (isIsinBusy(dossierDetail.value.isin)) {
    extractionError.value = 'LLM action already running for this ISIN.'
    return
  }
  extractionError.value = ''
  try {
    const result = await apiRequest(`/kb/dossiers/${dossierDetail.value.latestDossier.dossierId}/extract`, { method: 'POST' })
    extractionActionId.value = result.actionId
    await loadLlmActions(true)
  } catch (err) {
    extractionError.value = err?.message || 'Extraction failed'
  }
}

async function fillMissingData() {
  if (!dossierDetail.value) return
  if (isIsinBusy(dossierDetail.value.isin)) {
    extractionError.value = 'LLM action already running for this ISIN.'
    return
  }
  extractionError.value = ''
  try {
    const result = await apiRequest(`/kb/dossiers/${encodeURIComponent(dossierDetail.value.isin)}/missing-data`, {
      method: 'POST'
    })
    extractionActionId.value = result.actionId
    await loadLlmActions(true)
  } catch (err) {
    extractionError.value = err?.message || 'Missing data fill failed'
  }
}

async function approveExtraction() {
  if (!latestExtraction.value) return
  extractionError.value = ''
  try {
    await apiRequest(`/kb/extractions/${latestExtraction.value.extractionId}/approve`, { method: 'POST' })
    await loadDossierDetail(dossierDetail.value.isin)
    await loadDossiers()
  } catch (err) {
    extractionError.value = err?.message || 'Approve failed'
  }
}

async function rejectExtraction() {
  if (!latestExtraction.value) return
  extractionError.value = ''
  try {
    await apiRequest(`/kb/extractions/${latestExtraction.value.extractionId}/reject`, { method: 'POST' })
    await loadDossierDetail(dossierDetail.value.isin)
    await loadDossiers()
  } catch (err) {
    extractionError.value = err?.message || 'Reject failed'
  }
}

async function applyExtraction() {
  if (!latestExtraction.value) return
  extractionError.value = ''
  try {
    await apiRequest(`/kb/extractions/${latestExtraction.value.extractionId}/apply`, { method: 'POST' })
    await loadDossierDetail(dossierDetail.value.isin)
    await loadDossiers()
  } catch (err) {
    extractionError.value = err?.message || 'Apply failed'
  }
}

function isSelected(isin) {
  return selectedIsins.value.has(isin)
}

function toggleSelection(isin) {
  const next = new Set(selectedIsins.value)
  if (next.has(isin)) {
    next.delete(isin)
  } else {
    next.add(isin)
  }
  selectedIsins.value = next
}

function updateDossierScrollHint() {
  const el = dossierTableWrap.value
  if (!el) {
    showDossierScrollHint.value = false
    return
  }
  const maxScrollLeft = el.scrollWidth - el.clientWidth
  const canScroll = maxScrollLeft > 2
  showDossierScrollHint.value = canScroll && el.scrollLeft < maxScrollLeft - 2
}

function buildEmptyDossierDetail(item) {
  const displayName = item?.name || ''
  const draft = {
    dossierId: null,
    displayName,
    contentMd: '',
    status: 'DRAFT',
    citations: [],
    version: 0,
    origin: 'USER',
    authoredBy: 'USER',
    autoApproved: false
  }
  dossierForm.value = {
    displayName,
    contentMd: '',
    status: 'DRAFT',
    citationsText: '[]'
  }
  return {
    isin: item?.isin,
    displayName,
    latestDossier: draft,
    versions: [],
    extractions: [],
    lastRefreshRun: null
  }
}

function toggleSelectAllOnPage(event) {
  const checked = !!event?.target?.checked
  const next = new Set(selectedIsins.value)
  for (const item of dossierItems.value) {
    if (!item?.isin) continue
    if (checked) {
      next.add(item.isin)
    } else {
      next.delete(item.isin)
    }
  }
  selectedIsins.value = next
}

function selectMissingOnPage() {
  const next = new Set(selectedIsins.value)
  for (const item of dossierItems.value) {
    if (!item?.isin) continue
    if (isMissing(item)) {
      next.add(item.isin)
    }
  }
  selectedIsins.value = next
}

function clearSelection() {
  selectedIsins.value = new Set()
}

function useSelectedForBulk() {
  if (!selectedIsins.value.size) return
  bulkIsinsText.value = Array.from(selectedIsins.value).join('\n')
  activeSection.value = 'BULK'
}

async function deleteSelectedDossiers() {
  dossiersError.value = ''
  dossiersMessage.value = ''
  const isins = Array.from(selectedIsins.value)
  if (!isins.length) return
  const confirmed = window.confirm('Do you really want to delete selected ISINs from Knowledge Base?')
  if (!confirmed) return
  try {
    const result = await apiRequest('/kb/dossiers/delete', {
      method: 'POST',
      body: JSON.stringify({ isins })
    })
    if (isins.includes(selectedIsin.value)) {
      closeDossier()
    }
    selectedIsins.value = new Set()
    await loadDossiers()
    dossiersMessage.value = `Deleted dossiers=${result.dossiersDeleted}, extractions=${result.extractionsDeleted}.`
  } catch (err) {
    if (!handleKbError(err, 'Failed to delete dossiers')) {
      dossiersError.value = err?.message || 'Failed to delete dossiers'
    }
  }
}

function nextDossierPage() {
  if (!hasNextDossierPage.value) return
  dossierPage.value.page += 1
  loadDossiers()
}

function prevDossierPage() {
  if (!hasPrevDossierPage.value) return
  dossierPage.value.page = Math.max(0, dossierPage.value.page - 1)
  loadDossiers()
}

async function runBulkResearch() {
  bulkError.value = ''
  bulkWarning.value = ''
  bulkResult.value = null
  if (!bulkIsins.value.length) {
    bulkError.value = 'Provide at least one valid ISIN.'
    return
  }
  const blocked = bulkBlockedIsins.value
  const runnable = bulkIsins.value.filter((isin) => !blocked.includes(isin))
  if (!runnable.length) {
    bulkError.value = 'All provided ISINs already have a running LLM action.'
    return
  }
  if (blocked.length) {
    bulkWarning.value = `Skipping ${blocked.length} ISIN(s) already running: ${blocked.join(', ')}`
  }
  bulkBusy.value = true
  try {
    const result = await submitBulkResearch(runnable, bulkAutoApprove.value, bulkApplyOverrides.value)
    bulkActionId.value = result.actionId
    await loadLlmActions(true)
  } catch (err) {
    bulkError.value = err?.message || 'Bulk research failed'
  } finally {
    bulkBusy.value = false
  }
}

async function submitBulkResearch(isins, autoApprove, applyOverrides) {
  return apiRequest('/kb/dossiers/bulk-research', {
    method: 'POST',
    body: JSON.stringify({
      isins,
      autoApprove,
      applyToOverrides: applyOverrides
    })
  })
}

async function runAlternatives() {
  alternativesError.value = ''
  alternativesItems.value = []
  alternativesSelected.value = new Set()
  const normalizedIsin = alternativesIsinNormalized.value
  if (!normalizedIsin) {
    alternativesError.value = 'Base ISIN is required.'
    return
  }
  if (isIsinBusy(normalizedIsin)) {
    alternativesError.value = 'LLM action already running for this ISIN.'
    return
  }
  alternativesIsin.value = normalizedIsin
  alternativesBusy.value = true
  try {
    const result = await apiRequest(`/kb/alternatives/${encodeURIComponent(normalizedIsin)}`, {
      method: 'POST',
      body: JSON.stringify({ autoApprove: alternativesAutoApprove.value })
    })
    alternativesActionId.value = result.actionId
    await loadLlmActions(true)
  } catch (err) {
    alternativesError.value = err?.message || 'Alternatives search failed'
  } finally {
    alternativesBusy.value = false
  }
}

function alternativeSelectable(item) {
  return item?.status === 'FAILED'
}

function isAlternativeSelected(isin) {
  return alternativesSelected.value.has(isin)
}

function toggleAlternativeSelection(isin) {
  const item = alternativesItems.value.find((alt) => alt.isin === isin)
  if (!item || !alternativeSelectable(item)) return
  const next = new Set(alternativesSelected.value)
  if (next.has(isin)) {
    next.delete(isin)
  } else {
    next.add(isin)
  }
  alternativesSelected.value = next
}

function sendAlternativesToBulk() {
  if (!alternativesSelected.value.size) return
  bulkIsinsText.value = Array.from(alternativesSelected.value).join('\n')
  activeSection.value = 'BULK'
}

async function loadRefreshStats() {
  refreshError.value = ''
  try {
    const params = new URLSearchParams({
      status: 'APPROVED',
      stale: 'true',
      page: '0',
      size: '1'
    })
    const result = await apiRequest(`/kb/dossiers?${params.toString()}`)
    refreshStats.value.staleCount = result.total || 0
    const runsResult = await apiRequest('/kb/runs?page=0&size=50')
    refreshStats.value.lastRun = (runsResult.items || []).find((run) => run.action === 'REFRESH') || null
  } catch (err) {
    refreshError.value = err?.message || 'Failed to load refresh stats'
  }
}

function startLlmActionsPolling() {
  if (actionsPollHandle || kbDisabled.value) return
  actionsPollHandle = setInterval(() => {
    loadLlmActions(true)
  }, ACTIONS_POLL_INTERVAL_MS)
}

function stopLlmActionsPolling() {
  if (!actionsPollHandle) return
  clearInterval(actionsPollHandle)
  actionsPollHandle = null
}

async function loadLlmActions(silent = false) {
  if (kbDisabled.value) return
  if (!silent) {
    llmActionsLoading.value = true
  }
  llmActionsError.value = ''
  try {
    const result = await apiRequest('/kb/llm-actions')
    llmActions.value = Array.isArray(result) ? result : []
    await syncActionResults()
  } catch (err) {
    if (!handleKbError(err, 'Failed to load LLM actions')) {
      llmActionsError.value = err?.message || 'Failed to load LLM actions'
    }
  } finally {
    if (!silent) {
      llmActionsLoading.value = false
    }
  }
}

async function loadLlmActionDetail(actionId) {
  if (!actionId) return null
  return apiRequest(`/kb/llm-actions/${encodeURIComponent(actionId)}`)
}

async function syncActionResults() {
  await syncActionResult(bulkActionId.value, handleBulkActionResult)
  await syncActionResult(alternativesActionId.value, handleAlternativesActionResult)
  await syncActionResult(refreshActionId.value, handleRefreshActionResult)
  await syncActionResult(extractionActionId.value, handleExtractionActionResult)
}

async function syncActionResult(actionId, handler) {
  if (!actionId || llmActionResultsLoaded.value.has(actionId)) {
    return
  }
  const action = findActionById(actionId)
  if (!action || action.status === 'RUNNING') {
    return
  }
  try {
    const detail = await loadLlmActionDetail(actionId)
    if (detail) {
      await handler(detail)
      markActionResultLoaded(actionId)
    }
  } catch (err) {
    llmActionsError.value = err?.message || 'Failed to load LLM action detail'
  }
}

function markActionResultLoaded(actionId) {
  if (!actionId) return
  const next = new Set(llmActionResultsLoaded.value)
  next.add(actionId)
  llmActionResultsLoaded.value = next
}

async function handleBulkActionResult(detail) {
  bulkResult.value = detail.bulkResearchResult || null
  if (detail.status === 'FAILED') {
    bulkError.value = detail.message || 'Bulk research failed'
  } else if (detail.status === 'CANCELED') {
    bulkError.value = 'Bulk research canceled'
  } else {
    bulkError.value = ''
  }
  await loadDossiers()
}

async function handleAlternativesActionResult(detail) {
  const items = detail.alternativesResult?.alternatives || []
  alternativesItems.value = items
  if (detail.status === 'FAILED') {
    alternativesError.value = detail.message || 'Alternatives search failed'
  } else if (detail.status === 'CANCELED') {
    alternativesError.value = 'Alternatives search canceled'
  } else {
    alternativesError.value = ''
  }
  await loadDossiers()
}

async function handleRefreshActionResult(detail) {
  refreshResult.value = detail.refreshBatchResult || null
  if (detail.status === 'FAILED') {
    refreshError.value = detail.message || 'Refresh batch failed'
  } else if (detail.status === 'CANCELED') {
    refreshError.value = 'Refresh batch canceled'
  } else {
    refreshError.value = ''
  }
  await loadDossiers()
  await loadRefreshStats()
}

async function handleExtractionActionResult(detail) {
  const actionLabel = detail?.type === 'MISSING_DATA' ? 'Missing data fill' : 'Extraction'
  if (detail.status === 'FAILED') {
    extractionError.value = detail.message || `${actionLabel} failed`
  } else if (detail.status === 'CANCELED') {
    extractionError.value = `${actionLabel} canceled`
  } else {
    extractionError.value = ''
  }
  if (dossierDetail.value?.isin && detail.isins?.includes(dossierDetail.value.isin)) {
    await loadDossierDetail(dossierDetail.value.isin)
  }
  await loadDossiers()
}

function isActionBusy(actionId) {
  return llmActionsBusy.value.has(actionId)
}

function setActionBusy(actionId, busy) {
  const next = new Set(llmActionsBusy.value)
  if (busy) {
    next.add(actionId)
  } else {
    next.delete(actionId)
  }
  llmActionsBusy.value = next
}

async function cancelLlmAction(actionId) {
  if (!actionId) return
  setActionBusy(actionId, true)
  llmActionsError.value = ''
  try {
    await apiRequest(`/kb/llm-actions/${encodeURIComponent(actionId)}/cancel`, { method: 'POST' })
    await loadLlmActions(true)
  } catch (err) {
    llmActionsError.value = err?.message || 'Failed to cancel LLM action'
  } finally {
    setActionBusy(actionId, false)
  }
}

async function dismissLlmAction(actionId) {
  if (!actionId) return
  setActionBusy(actionId, true)
  llmActionsError.value = ''
  try {
    await apiRequest(`/kb/llm-actions/${encodeURIComponent(actionId)}`, { method: 'DELETE' })
    await loadLlmActions(true)
  } catch (err) {
    llmActionsError.value = err?.message || 'Failed to dismiss LLM action'
  } finally {
    setActionBusy(actionId, false)
  }
}

async function runRefreshBatch() {
  refreshError.value = ''
  refreshResult.value = null
  refreshBusy.value = true
  const scopeIsins = extractIsins(refreshForm.value.scopeText)
  const limit = refreshForm.value.limit && refreshForm.value.limit > 0 ? refreshForm.value.limit : null
  const batchSize = refreshForm.value.batchSize && refreshForm.value.batchSize > 0 ? refreshForm.value.batchSize : null
  const payload = {
    limit,
    batchSize,
    dryRun: !!refreshForm.value.dryRun,
    scope: scopeIsins.length ? { isins: scopeIsins } : null
  }
  try {
    const result = await apiRequest('/kb/refresh/batch', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
    refreshActionId.value = result.actionId
    await loadLlmActions(true)
  } catch (err) {
    refreshError.value = err?.message || 'Refresh batch failed'
  } finally {
    refreshBusy.value = false
  }
}

async function loadRuns() {
  runsLoading.value = true
  runsError.value = ''
  const params = new URLSearchParams()
  if (runsFilters.value.isin) params.append('isin', runsFilters.value.isin)
  if (runsFilters.value.status) params.append('status', runsFilters.value.status)
  params.append('page', String(runsPage.value.page))
  params.append('size', String(runsPage.value.size))
  try {
    const result = await apiRequest(`/kb/runs?${params.toString()}`)
    runsItems.value = result.items || []
    runsPage.value.total = result.total || 0
  } catch (err) {
    runsError.value = err?.message || 'Failed to load runs'
  } finally {
    runsLoading.value = false
  }
}

function nextRunsPage() {
  if (!hasNextRunsPage.value) return
  runsPage.value.page += 1
  loadRuns()
}

function prevRunsPage() {
  if (!hasPrevRunsPage.value) return
  runsPage.value.page = Math.max(0, runsPage.value.page - 1)
  loadRuns()
}

function normalizeConfig(raw) {
  const effort = ['low', 'medium', 'high'].includes(raw.websearch_reasoning_effort)
    ? raw.websearch_reasoning_effort
    : 'low'
  return {
    enabled: !!raw.enabled,
    refreshIntervalDays: raw.refresh_interval_days ?? 30,
    autoApprove: !!raw.auto_approve,
    applyExtractionsToOverrides: !!raw.apply_extractions_to_overrides,
    overwriteExistingOverrides: !!raw.overwrite_existing_overrides,
    batchSizeInstruments: raw.batch_size_instruments ?? 10,
    batchMaxInputChars: raw.batch_max_input_chars ?? 120000,
    maxParallelBulkBatches: raw.max_parallel_bulk_batches ?? 2,
    maxBatchesPerRun: raw.max_batches_per_run ?? 5,
    pollIntervalSeconds: raw.poll_interval_seconds ?? 300,
    maxInstrumentsPerRun: raw.max_instruments_per_run ?? 100,
    maxRetriesPerInstrument: raw.max_retries_per_instrument ?? 3,
    baseBackoffSeconds: raw.base_backoff_seconds ?? 2,
    maxBackoffSeconds: raw.max_backoff_seconds ?? 30,
    dossierMaxChars: raw.dossier_max_chars ?? 15000,
    kbRefreshMinDaysBetweenRunsPerInstrument: raw.kb_refresh_min_days_between_runs_per_instrument ?? 7,
    runTimeoutMinutes: raw.run_timeout_minutes ?? 30,
    websearchReasoningEffort: effort,
    websearchAllowedDomains: raw.websearch_allowed_domains || [],
    bulkMinCitations: raw.bulk_min_citations ?? 2,
    bulkRequirePrimarySource: raw.bulk_require_primary_source ?? true,
    alternativesMinSimilarityScore: raw.alternatives_min_similarity_score ?? 0.6,
    extractionEvidenceRequired: raw.extraction_evidence_required ?? true,
    qualityGateProfiles: raw.quality_gate_profiles || null
  }
}

function toConfigPayload(form, domainsRaw) {
  return {
    enabled: form.enabled,
    refresh_interval_days: form.refreshIntervalDays,
    auto_approve: form.autoApprove,
    apply_extractions_to_overrides: form.applyExtractionsToOverrides,
    overwrite_existing_overrides: form.overwriteExistingOverrides,
    batch_size_instruments: form.batchSizeInstruments,
    batch_max_input_chars: form.batchMaxInputChars,
    max_parallel_bulk_batches: form.maxParallelBulkBatches,
    max_batches_per_run: form.maxBatchesPerRun,
    poll_interval_seconds: form.pollIntervalSeconds,
    max_instruments_per_run: form.maxInstrumentsPerRun,
    max_retries_per_instrument: form.maxRetriesPerInstrument,
    base_backoff_seconds: form.baseBackoffSeconds,
    max_backoff_seconds: form.maxBackoffSeconds,
    dossier_max_chars: form.dossierMaxChars,
    kb_refresh_min_days_between_runs_per_instrument: form.kbRefreshMinDaysBetweenRunsPerInstrument,
    run_timeout_minutes: form.runTimeoutMinutes,
    websearch_reasoning_effort: form.websearchReasoningEffort,
    websearch_allowed_domains: parseDomains(domainsRaw),
    bulk_min_citations: form.bulkMinCitations,
    bulk_require_primary_source: form.bulkRequirePrimarySource,
    alternatives_min_similarity_score: form.alternativesMinSimilarityScore,
    extraction_evidence_required: form.extractionEvidenceRequired,
    quality_gate_profiles: form.qualityGateProfiles
  }
}

function parseDomains(raw) {
  if (!raw) return []
  return raw
    .split(/[\n,]/g)
    .map((entry) => entry.trim())
    .filter((entry) => entry)
}

function extractIsins(value) {
  const text = (value || '').toUpperCase()
  const matches = text.match(/[A-Z]{2}[A-Z0-9]{9}[0-9]/g) || []
  const unique = []
  const seen = new Set()
  for (const match of matches) {
    if (seen.has(match)) continue
    seen.add(match)
    unique.push(match)
  }
  return unique
}

function findActionById(actionId) {
  if (!actionId) return null
  return (llmActions.value || []).find((action) => action.actionId === actionId) || null
}

function isIsinBusy(isin) {
  if (!isin) return false
  const normalized = String(isin).trim().toUpperCase()
  if (!normalized) return false
  return runningIsins.value.has(normalized)
}

function formatIsinList(isins) {
  if (!Array.isArray(isins) || !isins.length) return '-'
  return isins.join(', ')
}

function formatActionType(type) {
  if (!type) return '-'
  switch (type) {
    case 'RESEARCH':
      return 'Research'
    case 'ALTERNATIVES':
      return 'Alternatives'
    case 'EXTRACTION':
      return 'Extraction'
    case 'REFRESH':
      return 'Refresh'
    case 'MISSING_DATA':
      return 'Missing data'
    default:
      return type
  }
}

function formatActionTrigger(trigger) {
  if (!trigger) return '-'
  if (trigger === 'AUTO') return 'Auto'
  if (trigger === 'USER') return 'User'
  return trigger
}

function formatActionStatus(status) {
  if (!status) return 'unknown'
  return status.toLowerCase().replace(/_/g, ' ')
}

function canUseStorage() {
  if (typeof window === 'undefined') return false
  if (import.meta.env?.MODE === 'test') {
    return window.__ENABLE_TEST_STORAGE__ === true
  }
  try {
    return !!window.localStorage
  } catch (err) {
    return false
  }
}

function readSortState() {
  if (!canUseStorage()) return null
  try {
    const raw = window.localStorage.getItem(SORT_STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw)
  } catch (err) {
    return null
  }
}

function persistSortState(state) {
  if (!canUseStorage()) return
  try {
    window.localStorage.setItem(SORT_STORAGE_KEY, JSON.stringify(state))
  } catch (err) {
    // Ignore storage failures.
  }
}

function applySortState(target, allowedKeys, stored) {
  if (!target || !stored || typeof stored !== 'object') return
  const key = stored.key
  const direction = stored.direction
  if (!allowedKeys.includes(key)) return
  if (direction !== 'asc' && direction !== 'desc') return
  target.key = key
  target.direction = direction
}

function hydrateSortState() {
  const stored = readSortState()
  if (!stored) return
  applySortState(dossierSort, Object.keys(dossierSorters), stored.dossier)
  applySortState(versionSort, Object.keys(versionSorters), stored.version)
  applySortState(bulkSort, Object.keys(bulkSorters), stored.bulk)
  applySortState(alternativesSort, Object.keys(alternativesSorters), stored.alternatives)
  applySortState(refreshSort, Object.keys(refreshSorters), stored.refresh)
  applySortState(llmActionsSort, Object.keys(llmActionSorters), stored.actions)
  applySortState(runsSort, Object.keys(runsSorters), stored.runs)
}

function readBackupTimestamp(key) {
  if (!canUseStorage()) return null
  try {
    return window.localStorage.getItem(key)
  } catch (err) {
    return null
  }
}

function hydrateBackupStatus() {
  lastKbExportAt.value = readBackupTimestamp(KB_EXPORT_STORAGE_KEY)
  lastKbImportAt.value = readBackupTimestamp(KB_IMPORT_STORAGE_KEY)
}

function handleStorageEvent(event) {
  if (!event || !event.key) return
  if (event.key === KB_EXPORT_STORAGE_KEY || event.key === KB_IMPORT_STORAGE_KEY) {
    hydrateBackupStatus()
  }
}

function toggleSort(sortState, key, defaultDirection = 'asc') {
  if (!sortState) return
  if (sortState.key === key) {
    sortState.direction = sortState.direction === 'asc' ? 'desc' : 'asc'
    return
  }
  sortState.key = key
  sortState.direction = defaultDirection
}

function ariaSort(sortState, key) {
  if (!sortState || sortState.key !== key) return 'none'
  return sortState.direction === 'asc' ? 'ascending' : 'descending'
}

function sortIndicator(sortState, key) {
  if (!sortState || sortState.key !== key) return ''
  return sortState.direction === 'asc' ? '^' : 'v'
}

function sortButtonLabel(label, sortState, key) {
  if (!sortState || sortState.key !== key) return `Sort by ${label}`
  return `Sort by ${label} (${sortState.direction === 'asc' ? 'ascending' : 'descending'})`
}

function sortItems(items, sortState, accessors) {
  if (!Array.isArray(items)) return []
  const accessor = accessors?.[sortState?.key]
  if (!accessor) return [...items]
  const direction = sortState.direction === 'desc' ? -1 : 1
  return [...items].sort((a, b) => compareValues(accessor(a), accessor(b), direction))
}

function compareValues(left, right, direction) {
  if (left == null && right == null) return 0
  if (left == null) return 1
  if (right == null) return -1
  const leftVal = toComparable(left)
  const rightVal = toComparable(right)
  if (leftVal < rightVal) return -1 * direction
  if (leftVal > rightVal) return 1 * direction
  return 0
}

function toComparable(value) {
  if (value == null) return null
  if (typeof value === 'number') return value
  if (value instanceof Date) return value.getTime()
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return ''
    const dateValue = Date.parse(trimmed)
    if (!Number.isNaN(dateValue) && /[T:-]/.test(trimmed)) {
      return dateValue
    }
    const numeric = Number(trimmed)
    if (!Number.isNaN(numeric)) {
      return numeric
    }
    return trimmed.toLowerCase()
  }
  return value
}

function shouldUseDetails(value, limit = 80) {
  if (!value) return false
  return value.trim().length > limit
}

function summarizeText(value, limit = 80) {
  if (!value) return '-'
  const compact = value.trim().replace(/\s+/g, ' ')
  if (compact.length <= limit) return compact
  return `${compact.slice(0, limit - 3)}...`
}

function isMissing(item) {
  return !item.hasApprovedDossier || !item.hasApprovedExtraction
}

function formatBackupDate(value) {
  if (!value) return 'Never'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return 'Never'
  return parsed.toLocaleString()
}

function formatDate(value) {
  return new Date(value).toLocaleString()
}

function statusBadgeClass(status) {
  if (!status) return 'neutral'
  if (['APPROVED', 'APPLIED', 'SUCCEEDED', 'DONE', 'GENERATED', 'EXISTS'].includes(status)) return 'ok'
  if (['FAILED', 'REJECTED', 'FAILED_TIMEOUT', 'CANCELED'].includes(status)) return 'warn'
  return 'neutral'
}

function gateBadgeClass(passed) {
  if (passed === true) return 'ok'
  if (passed === false) return 'warn'
  return 'neutral'
}

function extractionBadgeClass(freshness) {
  if (freshness === 'CURRENT') return 'ok'
  if (freshness === 'OUTDATED') return 'warn'
  return 'neutral'
}

function formatExtractionFreshness(freshness) {
  if (freshness === 'CURRENT') return 'Current'
  if (freshness === 'OUTDATED') return 'Outdated'
  return 'None'
}

function formatManualApproval(approval) {
  if (!approval) return '-'
  const parts = []
  if (approval.dossier) parts.push('Dossier')
  if (approval.extraction) parts.push('Extraction')
  return parts.length ? parts.join(' + ') : '-'
}

function formatManualApprovals(items) {
  if (!Array.isArray(items) || items.length === 0) return '-'
  const formatted = items
    .map((item) => {
      if (!item) return ''
      const label = formatManualApproval(item.approval)
      if (!item.isin) return label
      if (label === '-') return ''
      return `${item.isin} (${label})`
    })
    .filter(Boolean)
  return formatted.length ? formatted.join(', ') : '-'
}
</script>

<style scoped>
.kb-main-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
  margin-top: 1rem;
}

.kb-backup-status {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid #ead9c2;
  border-radius: 10px;
  background: #fff7eb;
  margin-top: 0.75rem;
}

.kb-backup-item {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.kb-backup-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #6b5f4b;
}

.kb-backup-value {
  font-weight: 600;
}

.kb-main-tabs .tab-active {
  background: #14131a;
  color: #f6f4f0;
  border-color: #14131a;
}

.sort-button {
  background: none;
  border: none;
  padding: 0;
  font: inherit;
  color: inherit;
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  cursor: pointer;
}

.sort-button:focus-visible {
  outline: 2px solid #14131a;
  outline-offset: 2px;
}

.sort-indicator {
  font-size: 0.75rem;
  opacity: 0.7;
}

.kb-filter-form {
  max-width: none;
}

.kb-split {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 1.5rem;
}

.kb-split-stacked {
  grid-template-columns: 1fr;
}

.kb-pane {
  min-width: 0;
}

.kb-inline-hint {
  margin: 0 0 0.75rem;
  position: sticky;
  top: 0;
  z-index: 2;
  background: #fff7eb;
  padding: 0.25rem 0.5rem;
  border-bottom: 1px solid #ead9c2;
  border-radius: 8px 8px 0 0;
}

.kb-inline-hint-compact {
  padding: 0.2rem 0.4rem;
  font-size: 0.85rem;
  margin-bottom: 0.5rem;
}

.kb-table-wrap {
  position: relative;
}

.kb-table-wrap-scrollable {
  padding-right: 1.1rem;
}

.kb-table-wrap-scrollable::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 28px;
  height: 100%;
  pointer-events: none;
  background: linear-gradient(to left, rgba(255, 255, 255, 0.95), rgba(255, 255, 255, 0));
}

.kb-dossier-table {
  min-width: 1000px;
}

.kb-dossier-table-scrollable th:last-child,
.kb-dossier-table-scrollable td:last-child {
  padding-right: 2.25rem;
}

.kb-isin {
  font-weight: 600;
  white-space: nowrap;
  flex-shrink: 0;
}

.kb-isin-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  flex-wrap: nowrap;
}

.kb-open-inline {
  border: 1px solid #f0b15c;
  background: transparent;
  color: #c06a00;
  border-radius: 999px;
  font-size: 0.75rem;
  padding: 0.2rem 0.55rem;
  cursor: pointer;
  flex-shrink: 0;
}

.kb-open-icon {
  display: none;
  font-weight: 700;
  line-height: 1;
}

.kb-open-inline:focus-visible {
  outline: 2px solid #14131a;
  outline-offset: 2px;
}

@media (min-width: 1101px) {
  .kb-open-inline {
    display: none;
  }
}

@media (max-width: 1100px) {
  .kb-actions-col {
    display: none;
  }
}

@media (max-width: 480px) {
  .kb-open-text {
    display: none;
  }

  .kb-open-icon {
    display: inline;
  }

  .kb-open-inline {
    padding: 0.2rem 0.4rem;
    min-width: 28px;
  }
}

.kb-select-col {
  width: 42px;
}

.kb-detail {
  background: #faf8f4;
  border: 1px solid #e6e3dc;
  border-radius: 14px;
  padding: 1rem;
}

.kb-detail-empty {
  border: 1px dashed #e6e3dc;
  border-radius: 14px;
  padding: 1rem;
}

.kb-detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 1rem;
}

.kb-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-bottom: 1rem;
}

.kb-citations {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 0.4rem;
}

.kb-citations a {
  text-decoration: underline;
}

.kb-details summary {
  cursor: pointer;
  font-weight: 600;
}

.kb-details p {
  margin: 0.35rem 0 0;
}

.kb-dl {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 0.35rem 1rem;
  margin: 0;
}

.kb-dl dt {
  font-weight: 600;
}

.kb-dl dd {
  margin: 0;
}

.kb-dl abbr[title] {
  text-decoration: underline dotted;
  cursor: help;
}

.kb-valuation {
  margin-top: 0.6rem;
}

.kb-settings {
  max-width: none;
}

.kb-settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 1rem;
}

.kb-refresh-stats {
  margin-bottom: 1rem;
}

@media (max-width: 1100px) {
  .kb-split {
    grid-template-columns: 1fr;
  }
}
</style>
