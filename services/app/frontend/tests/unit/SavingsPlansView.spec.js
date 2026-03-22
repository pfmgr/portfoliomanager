import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import SavingsPlansView from '../../src/views/SavingsPlansView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn(),
  apiDownload: vi.fn(),
  apiUpload: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('SavingsPlansView', () => {
  it('creates a saving plan for an ISIN without an instrument when backend materializes it', async () => {
    let createCalls = 0
    apiRequest.mockImplementation((url, options = {}) => {
      if (url === '/sparplans' && !options.method) {
        createCalls += 1
        if (createCalls === 1) {
          return Promise.resolve([])
        }
        return Promise.resolve([
          {
            savingPlanId: 1,
            depotId: 1,
            depotCode: 'tr',
            depotName: 'Trade Republic',
            isin: 'LU0000001001',
            name: 'Synthetic ETF',
            amountEur: 25,
            frequency: 'monthly',
            dayOfMonth: null,
            active: true,
            lastChanged: '2026-03-22',
            layer: 3
          }
        ])
      }
      if (url === '/depots') {
        return Promise.resolve([{ depotId: 1, depotCode: 'tr', name: 'Trade Republic' }])
      }
      if (url === '/layer-targets') {
        return Promise.resolve({ layerNames: { 3: 'Themes' } })
      }
      if (url === '/sparplans' && options.method === 'POST') {
        const body = JSON.parse(options.body)
        expect(body).toMatchObject({ depotId: 1, isin: 'LU0000001001', amountEur: 25 })
        return Promise.resolve({ savingPlanId: 1 })
      }
      return Promise.resolve({})
    })

    const wrapper = mount(SavingsPlansView)
    await flushPromises()

    const form = wrapper.find('form')
    const inputs = form.findAll('input')
    await form.find('select').setValue('1')
    await inputs[0].setValue('LU0000001001')
    await inputs[1].setValue('Synthetic ETF')
    await inputs[2].setValue('25')

    await form.trigger('submit.prevent')
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Savings plan created.')
    expect(wrapper.text()).toContain('Synthetic ETF')
    expect(wrapper.text()).toContain('Themes')
  })

  it('shows the effective layer after backend reactivates a deleted instrument', async () => {
    let createCalls = 0
    apiRequest.mockImplementation((url, options = {}) => {
      if (url === '/sparplans' && !options.method) {
        createCalls += 1
        if (createCalls === 1) {
          return Promise.resolve([])
        }
        return Promise.resolve([
          {
            savingPlanId: 2,
            depotId: 1,
            depotCode: 'tr',
            depotName: 'Trade Republic',
            isin: 'LU0000001002',
            name: 'Reactivated ETF',
            amountEur: 35,
            frequency: 'monthly',
            dayOfMonth: null,
            active: true,
            lastChanged: '2026-03-22',
            layer: 4
          }
        ])
      }
      if (url === '/depots') {
        return Promise.resolve([{ depotId: 1, depotCode: 'tr', name: 'Trade Republic' }])
      }
      if (url === '/layer-targets') {
        return Promise.resolve({ layerNames: { 4: 'Individual Stocks' } })
      }
      if (url === '/sparplans' && options.method === 'POST') {
        return Promise.resolve({ savingPlanId: 2 })
      }
      return Promise.resolve({})
    })

    const wrapper = mount(SavingsPlansView)
    await flushPromises()

    const form = wrapper.find('form')
    const inputs = form.findAll('input')
    await form.find('select').setValue('1')
    await inputs[0].setValue('LU0000001002')
    await inputs[1].setValue('Reactivated ETF')
    await inputs[2].setValue('35')

    await form.trigger('submit.prevent')
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Reactivated ETF')
    expect(wrapper.text()).toContain('Individual Stocks')
  })
})
