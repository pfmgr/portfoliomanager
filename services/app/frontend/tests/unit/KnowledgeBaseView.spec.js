import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import KnowledgeBaseView from '../../src/views/KnowledgeBaseView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => Promise.resolve().then(() => Promise.resolve())

function mockKnowledgeBaseApi({
  config = { enabled: true },
  dossiers = [],
  dossierDetails = {},
  runs = [],
  actions = [],
  actionDetails = {}
} = {}) {
  apiRequest.mockImplementation((path, options = {}) => {
    if (path === '/kb/config') {
      return Promise.resolve(config)
    }
    if (path.startsWith('/kb/dossiers?')) {
      return Promise.resolve({ items: dossiers, total: dossiers.length })
    }
    if (path.startsWith('/kb/dossiers/') && path.includes('/')) {
      const isin = decodeURIComponent(path.split('/').pop())
      return Promise.resolve(dossierDetails[isin] || dossierDetails.default || {})
    }
    if (path.startsWith('/kb/runs')) {
      return Promise.resolve({ items: runs, total: runs.length })
    }
    if (path === '/kb/llm-actions') {
      return Promise.resolve(actions)
    }
    if (path.startsWith('/kb/llm-actions/') && path.endsWith('/cancel')) {
      const actionId = decodeURIComponent(path.split('/')[3])
      const detail = actionDetails[actionId] || actions.find((action) => action.actionId === actionId) || {}
      return Promise.resolve({ ...detail, actionId, status: 'CANCELED' })
    }
    if (path.startsWith('/kb/llm-actions/')) {
      const actionId = decodeURIComponent(path.split('/').pop())
      return Promise.resolve(actionDetails[actionId] || actions.find((action) => action.actionId === actionId) || {})
    }
    return Promise.resolve({})
  })
}

describe('KnowledgeBaseView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('hydrates persisted sort state from storage', async () => {
    const store = new Map()
    const storage = {
      getItem: vi.fn((key) => (store.has(key) ? store.get(key) : null)),
      setItem: vi.fn((key, value) => {
        store.set(key, value)
      }),
      removeItem: vi.fn((key) => {
        store.delete(key)
      }),
      clear: vi.fn(() => {
        store.clear()
      })
    }
    const originalLocalStorage = window.localStorage
    Object.defineProperty(window, 'localStorage', {
      value: storage,
      configurable: true
    })
    window.__ENABLE_TEST_STORAGE__ = true

    let wrapper
    try {
      storage.setItem(
        'kb.sortState.v1',
        JSON.stringify({
          dossier: { key: 'isin', direction: 'asc' }
        })
      )

      apiRequest.mockImplementation((path) => {
        if (path.startsWith('/kb/config')) {
          return Promise.resolve({ enabled: true })
        }
        if (path.startsWith('/kb/dossiers')) {
          return Promise.resolve({ items: [], total: 0 })
        }
        if (path.startsWith('/kb/runs')) {
          return Promise.resolve({ items: [], total: 0 })
        }
        if (path.startsWith('/kb/llm-actions')) {
          return Promise.resolve([])
        }
        return Promise.resolve({})
      })

      wrapper = mount(KnowledgeBaseView)
      await flushPromises()

      const isinSortButton = wrapper.find('table.kb-dossier-table th[aria-sort="ascending"] button.sort-button')
      expect(isinSortButton.exists()).toBe(true)
      expect(isinSortButton.text()).toContain('ISIN')
    } finally {
      if (wrapper) {
        wrapper.unmount()
      }
      delete window.__ENABLE_TEST_STORAGE__
      Object.defineProperty(window, 'localStorage', {
        value: originalLocalStorage,
        configurable: true
      })
    }
  })

  it('renders empty state when no instruments exist', async () => {
    apiRequest.mockImplementation((path) => {
      if (path.startsWith('/kb/config')) {
        return Promise.resolve({ enabled: true })
      }
      if (path.startsWith('/kb/dossiers')) {
        return Promise.resolve({ items: [], total: 0 })
      }
      if (path.startsWith('/kb/runs')) {
        return Promise.resolve({ items: [], total: 0 })
      }
      if (path.startsWith('/kb/llm-actions')) {
        return Promise.resolve([])
      }
      return Promise.resolve({})
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Knowledge Base')
    expect(wrapper.text()).toContain('No dossiers found.')
    wrapper.unmount()
  }, 10000)

  it('hides dossier filters by default and toggles them without reloading data', async () => {
    const dossierCalls = []
    apiRequest.mockImplementation((path) => {
      if (path.startsWith('/kb/config')) {
        return Promise.resolve({ enabled: true })
      }
      if (path.startsWith('/kb/dossiers')) {
        dossierCalls.push(path)
        return Promise.resolve({ items: [], total: 0 })
      }
      if (path.startsWith('/kb/runs')) {
        return Promise.resolve({ items: [], total: 0 })
      }
      if (path.startsWith('/kb/llm-actions')) {
        return Promise.resolve([])
      }
      return Promise.resolve({})
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()

    const toggleButton = wrapper.find('button[aria-controls="kb-dossier-filters"]')
    const filterPanel = wrapper.find('#kb-dossier-filters')
    expect(toggleButton.exists()).toBe(true)
    expect(toggleButton.text()).toBe('Show Filter')
    expect(wrapper.text()).toContain('No filters applied')
    expect(toggleButton.attributes('aria-expanded')).toBe('false')
    expect(filterPanel.attributes('style') || '').toContain('display: none')
    const initialCallCount = dossierCalls.length
    expect(initialCallCount).toBeGreaterThan(0)

    await toggleButton.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(toggleButton.text()).toBe('Hide Filter')
    expect(toggleButton.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('#kb-dossier-filters').attributes('style') || '').not.toContain('display: none')
    expect(dossierCalls).toHaveLength(initialCallCount)

    const searchInput = wrapper.find('input[placeholder="ISIN or name"]')
    await searchInput.setValue('alpha')
    await toggleButton.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(toggleButton.text()).toBe('Show Filter')
    expect(wrapper.find('#kb-dossier-filters').attributes('style') || '').toContain('display: none')
    expect(wrapper.find('input[placeholder="ISIN or name"]').element.value).toBe('alpha')
    expect(wrapper.text()).toContain('No filters applied')
    expect(dossierCalls).toHaveLength(initialCallCount)

    wrapper.unmount()
  })

  it('loads dossiers with server-side sorting and resets page for sort and filters', async () => {
    const dossierCalls = []
    apiRequest.mockImplementation((path) => {
      if (path.startsWith('/kb/config')) {
        return Promise.resolve({ enabled: true })
      }
      if (path.startsWith('/kb/dossiers')) {
        dossierCalls.push(path)
        return Promise.resolve({
          items: [
            {
              isin: 'DE0000000001',
              name: 'Alpha',
              hasDossier: true,
              latestDossierStatus: 'DRAFT',
              latestUpdatedAt: '2025-01-01T00:00:00',
              latestDossierVersion: 1,
              approvalStatus: 'NOT_APPROVED',
              latestExtractionStatus: 'NONE',
              blacklistScope: 'NONE',
              blacklistPendingChange: false,
              hasApprovedDossier: false,
              hasApprovedExtraction: false,
              stale: false,
              extractionFreshness: 'NONE'
            }
          ],
          total: 120
        })
      }
      if (path.startsWith('/kb/runs')) {
        return Promise.resolve({ items: [], total: 0 })
      }
      if (path.startsWith('/kb/llm-actions')) {
        return Promise.resolve([])
      }
      return Promise.resolve({})
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()

    expect(dossierCalls[0]).toContain('sortBy=updatedAt')
    expect(dossierCalls[0]).toContain('sortDirection=desc')
    expect(dossierCalls[0]).toContain('page=0')

    const filterToggle = wrapper.find('button[aria-controls="kb-dossier-filters"]')
    await filterToggle.trigger('click')
    await flushPromises()
    await flushPromises()

    const getNextButton = () => wrapper.findAll('button').find((button) => button.text() === 'Next')

    const nextButton = getNextButton()
    expect(nextButton).toBeTruthy()
    await nextButton.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(dossierCalls.at(-1)).toContain('page=1')

    const isinSortButton = wrapper.findAll('button.sort-button').find((button) => button.text().includes('ISIN'))
    expect(isinSortButton).toBeTruthy()
    await isinSortButton.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(dossierCalls.at(-1)).toContain('sortBy=isin')
    expect(dossierCalls.at(-1)).toContain('sortDirection=asc')
    expect(dossierCalls.at(-1)).toContain('page=0')

    await getNextButton().trigger('click')
    await flushPromises()
    expect(dossierCalls.at(-1)).toContain('page=1')

    const searchInput = wrapper.find('input[placeholder="ISIN or name"]')
    await searchInput.setValue('alpha')
    const filterForm = wrapper.find('form.kb-filter-form')
    await filterForm.trigger('submit')
    await flushPromises()
    expect(dossierCalls.at(-1)).toContain('q=alpha')
    expect(dossierCalls.at(-1)).toContain('page=0')
    expect(wrapper.text()).toContain('1 filter active: Search')

    const selects = wrapper.findAll('form.kb-filter-form select')
    await selects[1].setValue('APPROVED')
    await selects[2].setValue('PENDING_REVIEW')
    await selects[3].setValue('CURRENT')
    await selects[4].setValue('ALL_PROPOSALS')
    await filterForm.trigger('submit')
    await flushPromises()
    expect(dossierCalls.at(-1)).toContain('approvalStatus=APPROVED')
    expect(dossierCalls.at(-1)).toContain('extractionStatus=PENDING_REVIEW')
    expect(dossierCalls.at(-1)).toContain('freshnessStatus=CURRENT')
    expect(dossierCalls.at(-1)).toContain('blacklistStatus=ALL_PROPOSALS')
    expect(wrapper.text()).toContain('5 filters active: Search, Approval, Extraction, Freshness, Proposal exclusions')

    wrapper.unmount()
  })

  it('renders canonical workflow status details, retry timing, and review evidence notes', async () => {
    mockKnowledgeBaseApi({
      dossiers: [],
      runs: [],
      actions: [
        {
          actionId: 'action-review',
          type: 'EXTRACTION',
          status: 'REVIEW_REQUIRED',
          trigger: 'USER',
          isins: ['DE0000000001'],
          createdAt: '2026-07-12T10:00:00Z',
          updatedAt: '2026-07-12T10:03:00Z',
          message: 'Review required',
          currentStep: 'SOURCE_VERIFICATION',
          attempts: 2,
          nextRetryAt: null,
          errorCode: 'INSUFFICIENT_EVIDENCE',
          errorReference: 'ref-123',
          childTotal: null,
          childCompleted: null,
          childFailed: null,
          childCanceled: null
        },
        {
          actionId: 'action-retry',
          type: 'REFRESH',
          status: 'WAITING_RETRY',
          trigger: 'USER',
          isins: [],
          createdAt: '2026-07-12T09:00:00Z',
          updatedAt: '2026-07-12T09:03:00Z',
          message: 'Retry scheduled',
          currentStep: 'PERSISTING_RESULT',
          attempts: 3,
          nextRetryAt: '2026-07-12T10:30:00Z',
          errorCode: 'TIMEOUT',
          errorReference: 'req-42',
          childTotal: 4,
          childCompleted: 2,
          childFailed: 1,
          childCanceled: 0
        }
      ],
      actionDetails: {
        'action-review': {
          actionId: 'action-review',
          type: 'EXTRACTION',
          status: 'REVIEW_REQUIRED',
          message: 'Review required',
          currentStep: 'SOURCE_VERIFICATION',
          attempts: 2,
          errorCode: 'INSUFFICIENT_EVIDENCE',
          errorReference: 'ref-123',
          extractionResult: {
            evidenceGate: {
              passed: false,
              missing_evidence: ['Prospectus URL']
            },
            warningsJson: [{ message: 'Evidence warning' }]
          }
        }
      }
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    const actionsTab = wrapper.findAll('button').find((button) => button.text() === 'LLM actions')
    await actionsTab.trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Review required')
    expect(wrapper.text()).toContain('Waiting retry')
    expect(wrapper.text()).toContain('Source Verification')
    expect(wrapper.text()).toContain('Attempt 2')
    expect(wrapper.text()).toContain('Retry')
    expect(wrapper.text()).toContain('2/4 done')
    expect(wrapper.text()).toContain('1 failed')
    expect(wrapper.text()).toContain('Evidence missing: Prospectus URL')
    expect(wrapper.text()).toContain('Evidence warning')
    expect(wrapper.find('[role="status"][aria-live="polite"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('resumes polling after remount without leaving duplicate intervals behind', async () => {
    vi.useFakeTimers()
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval')
    const clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval')

    mockKnowledgeBaseApi({
      actions: [
        {
          actionId: 'action-live',
          type: 'REFRESH',
          status: 'RUNNING',
          trigger: 'USER',
          isins: [],
          createdAt: '2026-07-12T10:00:00Z',
          updatedAt: '2026-07-12T10:00:00Z',
          message: 'Running',
          currentStep: 'PROVIDER_OR_DOMAIN_CALL',
          attempts: 1,
          nextRetryAt: null,
          childTotal: null,
          childCompleted: null,
          childFailed: null,
          childCanceled: null
        }
      ]
    })

    const firstWrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(setIntervalSpy).toHaveBeenCalledTimes(1)
    const callsAfterFirstMount = apiRequest.mock.calls.filter(([path]) => path === '/kb/llm-actions').length
    expect(callsAfterFirstMount).toBeGreaterThan(0)

    firstWrapper.unmount()
    expect(clearIntervalSpy).toHaveBeenCalled()

    const callsAfterFirstUnmount = apiRequest.mock.calls.filter(([path]) => path === '/kb/llm-actions').length
    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()
    expect(apiRequest.mock.calls.filter(([path]) => path === '/kb/llm-actions')).toHaveLength(callsAfterFirstUnmount)

    const secondWrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(setIntervalSpy).toHaveBeenCalledTimes(2)
    expect(apiRequest.mock.calls.filter(([path]) => path === '/kb/llm-actions').length).toBeGreaterThan(callsAfterFirstUnmount)

    secondWrapper.unmount()
    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
  })

  it('stops polling when the server transitions an action to a terminal state', async () => {
    vi.useFakeTimers()

    let llmActionCalls = 0
    mockKnowledgeBaseApi({
      actions: []
    })
    apiRequest.mockImplementation((path) => {
      if (path === '/kb/config') return Promise.resolve({ enabled: true })
      if (path.startsWith('/kb/dossiers')) return Promise.resolve({ items: [], total: 0 })
      if (path.startsWith('/kb/runs')) return Promise.resolve({ items: [], total: 0 })
      if (path === '/kb/llm-actions') {
        llmActionCalls += 1
        if (llmActionCalls === 1) {
          return Promise.resolve([
            {
              actionId: 'action-poll',
              type: 'REFRESH',
              status: 'RUNNING',
              trigger: 'USER',
              isins: [],
              createdAt: '2026-07-12T10:00:00Z',
              updatedAt: '2026-07-12T10:00:00Z',
              message: 'Running',
              currentStep: 'PROVIDER_OR_DOMAIN_CALL',
              attempts: 1,
              childTotal: null,
              childCompleted: null,
              childFailed: null,
              childCanceled: null
            }
          ])
        }
        return Promise.resolve([
          {
            actionId: 'action-poll',
            type: 'REFRESH',
            status: 'COMPLETED',
            trigger: 'USER',
            isins: [],
            createdAt: '2026-07-12T10:00:00Z',
            updatedAt: '2026-07-12T10:05:00Z',
            message: 'Completed',
            currentStep: 'PERSISTING_RESULT',
            attempts: 1,
            childTotal: null,
            childCompleted: null,
            childFailed: null,
            childCanceled: null
          }
        ])
      }
      if (path === '/kb/llm-actions/action-poll') {
        return Promise.resolve({
          actionId: 'action-poll',
          type: 'REFRESH',
          status: 'COMPLETED',
          message: 'Completed',
          currentStep: 'PERSISTING_RESULT',
          attempts: 1
        })
      }
      return Promise.resolve({})
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(llmActionCalls).toBe(2)

    await vi.advanceTimersByTimeAsync(15000)
    await flushPromises()
    expect(llmActionCalls).toBe(2)

    wrapper.unmount()
  })

  it('disables approve/apply when the server evidence gate fails and keeps accessible status semantics', async () => {
    mockKnowledgeBaseApi({
      dossiers: [
        {
          isin: 'DE0000000001',
          name: 'Alpha',
          hasDossier: true,
          latestDossierStatus: 'APPROVED',
          latestUpdatedAt: '2026-07-12T10:00:00Z',
          latestDossierVersion: 1,
          approvalStatus: 'APPROVED',
          latestExtractionStatus: 'PENDING_REVIEW',
          blacklistScope: 'NONE',
          blacklistPendingChange: false,
          hasApprovedDossier: true,
          hasApprovedExtraction: false,
          stale: false,
          extractionFreshness: 'CURRENT'
        }
      ],
      dossierDetails: {
        DE0000000001: {
          isin: 'DE0000000001',
          displayName: 'Alpha',
          blacklist: {
            requestedScope: 'NONE',
            effectiveScope: 'NONE',
            pendingChange: false
          },
          latestDossier: {
            dossierId: 123,
            status: 'APPROVED',
            version: 1,
            origin: 'USER',
            authoredBy: 'USER',
            autoApproved: false,
            contentMd: 'Alpha content',
            citations: [],
            quality_gate: { passed: true }
          },
          versions: [],
          extractions: [
            {
              extractionId: 44,
              dossierId: 123,
              model: 'gpt-4o-mini',
              extractedJson: {},
              missingFieldsJson: [{ field: 'fund_size' }],
              warningsJson: [{ message: 'Check evidence' }],
              status: 'PENDING_REVIEW',
              error: null,
              createdAt: '2026-07-12T10:00:00Z',
              approvedBy: null,
              approvedAt: null,
              appliedBy: null,
              appliedAt: null,
              autoApproved: false,
              evidenceGate: {
                passed: false,
                missing_evidence: ['Prospectus URL']
              }
            }
          ],
          lastRefreshRun: null
        }
      },
      actions: [
        {
          actionId: 'action-review',
          type: 'EXTRACTION',
          status: 'REVIEW_REQUIRED',
          trigger: 'USER',
          isins: ['DE0000000001'],
          createdAt: '2026-07-12T10:00:00Z',
          updatedAt: '2026-07-12T10:05:00Z',
          message: 'Review required',
          currentStep: 'SOURCE_VERIFICATION',
          attempts: 1,
          errorCode: 'INSUFFICIENT_EVIDENCE',
          errorReference: 'ref-77',
          childTotal: null,
          childCompleted: null,
          childFailed: null,
          childCanceled: null
        }
      ],
      actionDetails: {
        'action-review': {
          actionId: 'action-review',
          type: 'EXTRACTION',
          status: 'REVIEW_REQUIRED',
          message: 'Review required',
          currentStep: 'SOURCE_VERIFICATION',
          attempts: 1,
          errorCode: 'INSUFFICIENT_EVIDENCE',
          errorReference: 'ref-77',
          extractionResult: {
            evidenceGate: {
              passed: false,
              missing_evidence: ['Prospectus URL']
            },
            warningsJson: [{ message: 'Check evidence' }]
          }
        }
      }
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(typeof wrapper.vm.loadDossierDetail).toBe('function')
    await wrapper.vm.loadDossierDetail('DE0000000001')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Review required')
    expect(wrapper.text()).toContain('Evidence gate:')
    expect(wrapper.text()).toContain('FAIL')
    expect(wrapper.text()).toContain('Review notes')
    expect(wrapper.text()).toContain('Evidence missing: Prospectus URL')
    expect(wrapper.text()).toContain('Check evidence')

    const approveButton = wrapper.findAll('button').find((button) => button.text() === 'Approve extraction')
    const applyButton = wrapper.findAll('button').find((button) => button.text() === 'Apply to overrides')
    expect(approveButton).toBeTruthy()
    expect(applyButton).toBeTruthy()
    expect(approveButton.attributes('disabled')).toBeDefined()
    expect(applyButton.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="status"][aria-live="polite"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows cancel for active actions and dismiss for terminal ones', async () => {
    mockKnowledgeBaseApi({
      actions: [
        {
          actionId: 'action-queued',
          type: 'REFRESH',
          status: 'QUEUED',
          trigger: 'USER',
          isins: [],
          createdAt: '2026-07-12T10:00:00Z',
          updatedAt: '2026-07-12T10:00:00Z',
          message: 'Queued',
          currentStep: 'PROVIDER_OR_DOMAIN_CALL',
          attempts: 0,
          childTotal: null,
          childCompleted: null,
          childFailed: null,
          childCanceled: null
        },
        {
          actionId: 'action-running',
          type: 'REFRESH',
          status: 'RUNNING',
          trigger: 'USER',
          isins: [],
          createdAt: '2026-07-12T10:01:00Z',
          updatedAt: '2026-07-12T10:01:00Z',
          message: 'Running',
          currentStep: 'PROVIDER_OR_DOMAIN_CALL',
          attempts: 1,
          childTotal: null,
          childCompleted: null,
          childFailed: null,
          childCanceled: null
        },
        {
          actionId: 'action-retry',
          type: 'REFRESH',
          status: 'WAITING_RETRY',
          trigger: 'USER',
          isins: [],
          createdAt: '2026-07-12T10:02:00Z',
          updatedAt: '2026-07-12T10:02:00Z',
          message: 'Retry later',
          currentStep: 'PERSISTING_RESULT',
          attempts: 2,
          nextRetryAt: '2026-07-12T10:30:00Z',
          childTotal: null,
          childCompleted: null,
          childFailed: null,
          childCanceled: null
        },
        {
          actionId: 'action-done',
          type: 'REFRESH',
          status: 'COMPLETED',
          trigger: 'USER',
          isins: [],
          createdAt: '2026-07-12T10:03:00Z',
          updatedAt: '2026-07-12T10:03:00Z',
          message: 'Done',
          currentStep: 'PERSISTING_RESULT',
          attempts: 1,
          childTotal: null,
          childCompleted: null,
          childFailed: null,
          childCanceled: null
        }
      ]
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    const actionsTab = wrapper.findAll('button').find((button) => button.text() === 'LLM actions')
    await actionsTab.trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()

    const actionsPanel = wrapper.find('#kb-panel-actions')
    const rows = actionsPanel.findAll('tbody tr')
    const rowByText = (text) => rows.find((row) => row.text().includes(text))

    expect(rowByText('Queued').text()).toContain('Cancel')
    expect(rowByText('Running').text()).toContain('Cancel')
    expect(rowByText('Waiting retry').text()).toContain('Cancel')
    expect(rowByText('Completed').text()).toContain('Dismiss')
    expect(rowByText('Completed').text()).not.toContain('Cancel')

    wrapper.unmount()
  })

  it('moves cancel focus and announces action errors with alert semantics', async () => {
    apiRequest.mockImplementation((path, options = {}) => {
      if (path === '/kb/config') return Promise.resolve({ enabled: true })
      if (path.startsWith('/kb/dossiers')) return Promise.resolve({ items: [], total: 0 })
      if (path.startsWith('/kb/runs')) return Promise.resolve({ items: [], total: 0 })
      if (path === '/kb/llm-actions') {
        return Promise.resolve([
          {
            actionId: 'action-running',
            type: 'REFRESH',
            status: 'RUNNING',
            trigger: 'USER',
            isins: [],
            createdAt: '2026-07-12T10:00:00Z',
            updatedAt: '2026-07-12T10:00:00Z',
            message: 'Running',
            currentStep: 'PROVIDER_OR_DOMAIN_CALL',
            attempts: 1,
            childTotal: null,
            childCompleted: null,
            childFailed: null,
            childCanceled: null
          },
          {
            actionId: 'action-done',
            type: 'REFRESH',
            status: 'COMPLETED',
            trigger: 'USER',
            isins: [],
            createdAt: '2026-07-12T10:03:00Z',
            updatedAt: '2026-07-12T10:03:00Z',
            message: 'Done',
            currentStep: 'PERSISTING_RESULT',
            attempts: 1,
            childTotal: null,
            childCompleted: null,
            childFailed: null,
            childCanceled: null
          }
        ])
      }
      if (path === '/kb/llm-actions/action-running/cancel' && options.method === 'POST') {
        return Promise.reject(new Error('Cancel failed'))
      }
      if (path === '/kb/llm-actions/action-done' && options.method === 'DELETE') {
        return Promise.reject(new Error('Dismiss failed'))
      }
      if (path.startsWith('/kb/llm-actions/')) {
        return Promise.resolve({
          actionId: decodeURIComponent(path.split('/').pop()),
          status: 'COMPLETED'
        })
      }
      return Promise.resolve({})
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    const actionsTab = wrapper.findAll('button').find((button) => button.text() === 'LLM actions')
    await actionsTab.trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()

    const actionsPanel = wrapper.find('#kb-panel-actions')
    const actionButtons = () => actionsPanel.findAll('button')
    const focusSpy = vi.spyOn(HTMLElement.prototype, 'focus')

    await wrapper.vm.promptCancelLlmAction('action-running')
    await flushPromises()
    await flushPromises()

    const confirmButton = actionButtons().find((button) => button.text().includes('Confirm'))
    expect(wrapper.text()).toContain('Cancel confirmation opened.')
    expect(focusSpy).toHaveBeenCalled()
    expect(focusSpy.mock.instances.at(-1)).toBe(confirmButton.element)

    focusSpy.mockClear()

    await wrapper.vm.dismissCancelLlmAction('action-running')
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Cancel confirmation dismissed.')
    const restoredCancelButton = actionButtons().find((button) => button.text().includes('Cancel'))
    expect(focusSpy).toHaveBeenCalled()
    expect(focusSpy.mock.instances.at(-1)).toBe(restoredCancelButton.element)
    focusSpy.mockClear()

    await wrapper.vm.promptCancelLlmAction('action-running')
    await flushPromises()
    await flushPromises()

    await wrapper.vm.confirmCancelLlmAction('action-running')
    await flushPromises()
    await flushPromises()

    const errorAlert = wrapper.find('#kb-panel-actions [role="alert"]')
    expect(errorAlert.exists()).toBe(true)
    expect(errorAlert.text()).toContain('Cancel failed')

    await wrapper.vm.dismissLlmAction('action-done')
    await flushPromises()
    await flushPromises()

    const dismissErrorAlert = wrapper.find('#kb-panel-actions [role="alert"]')
    expect(dismissErrorAlert.text()).toContain('Dismiss failed')
    expect(wrapper.find('#kb-panel-actions [role="status"][aria-live="polite"]').attributes('aria-live')).toBe('polite')

    focusSpy.mockRestore()
    wrapper.unmount()
  })

  it('announces load errors as alerts and exposes the actions table wrapper', async () => {
    apiRequest.mockImplementation((path) => {
      if (path === '/kb/config') return Promise.resolve({ enabled: true })
      if (path.startsWith('/kb/dossiers')) return Promise.resolve({ items: [], total: 0 })
      if (path.startsWith('/kb/runs')) return Promise.resolve({ items: [], total: 0 })
      if (path === '/kb/llm-actions') return Promise.reject(new Error('Load failed'))
      return Promise.resolve({})
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await flushPromises()

    const actionsTab = wrapper.findAll('button').find((button) => button.text() === 'LLM actions')
    await actionsTab.trigger('click')
    await flushPromises()
    await flushPromises()

    const errorAlert = wrapper.find('#kb-panel-actions [role="alert"]')
    expect(errorAlert.exists()).toBe(true)
    expect(errorAlert.text()).toContain('Load failed')

    const wrapperRegion = wrapper.find('.kb-llm-actions-table-wrap')
    expect(wrapperRegion.attributes('role')).toBe('region')
    expect(wrapperRegion.attributes('tabindex')).toBe('0')
    expect(wrapperRegion.attributes('aria-label')).toBe('LLM actions table')
    expect(wrapperRegion.attributes('aria-describedby')).toBe('kb-llm-actions-table-hint')
    expect(wrapper.find('#kb-llm-actions-table-hint').text()).toContain('Left/Right arrows')

    Object.defineProperty(wrapperRegion.element, 'scrollLeft', { value: 0, writable: true, configurable: true })
    Object.defineProperty(wrapperRegion.element, 'scrollWidth', { value: 2000, configurable: true })
    Object.defineProperty(wrapperRegion.element, 'clientWidth', { value: 500, configurable: true })
    await wrapperRegion.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapperRegion.element.scrollLeft).toBeGreaterThan(0)

    wrapper.unmount()
  })
})
