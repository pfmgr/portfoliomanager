import { describe, it, expect, beforeEach } from 'vitest'
import router from '../../src/router'

describe('router auth guard', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('redirects to login when unauthenticated', async () => {
    await router.push('/rulesets')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('redirects to rulesets when already authenticated', async () => {
    sessionStorage.setItem('jwt', 'token')
    await router.push('/rulesets')
    await router.push('/login')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/rulesets')
  })
})
