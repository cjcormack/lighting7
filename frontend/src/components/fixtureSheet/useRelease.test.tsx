// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { TargetCleared } from '@/api/programmerWsApi'

const api = vi.hoisted(() => ({ clearTarget: vi.fn() }))
vi.mock('@/api/lightingApi', () => ({ lightingApi: { programmer: { clearTarget: api.clearTarget } } }))
const removeFx = vi.hoisted(() => vi.fn(() => ({ unwrap: () => Promise.resolve() })))
vi.mock('@/store/fixtureFx', () => ({ useRemoveFxMutation: () => [removeFx] }))
vi.mock('@/store/programmer', () => ({ useProgrammerRevision: () => 0 }))
vi.mock('@/lib/programmerFade', () => ({ getProgrammerFadeMs: () => 2000 }))
const toast = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }))
vi.mock('sonner', () => ({ toast }))

import { useReleaseTargets } from './useRelease'

const selectionKey = '[{"key":"par-1","type":"fixture"},{"key":"par-2","type":"fixture"}]'
const chase = { effectId: 9, effectType: 'Chase', targetKey: selectionKey, isGroupTarget: false, propertyName: 'dimmer' }
const cleared = (key: string): TargetCleared => ({ targetType: 'fixture', targetKey: key, values: 1, effects: 0, partial: [chase] })

afterEach(() => vi.clearAllMocks())

describe('Release over a selection (effects plan D3)', () => {
  it('stops a selection effect every item of which it released, and counts it', async () => {
    api.clearTarget.mockImplementation((_t: string, key: string) => Promise.resolve(cleared(key)))
    const targets = [
      { type: 'fixture' as const, key: 'par-1', name: 'Par 1' },
      { type: 'fixture' as const, key: 'par-2', name: 'Par 2' },
    ]
    const { result } = renderHook(() => useReleaseTargets(targets))
    await result.current()
    expect(removeFx).toHaveBeenCalledTimes(1)
    expect(removeFx).toHaveBeenCalledWith({ id: 9, fixtureKey: selectionKey })
    expect(toast.success.mock.calls[0][0]).toMatch(/1 effect/)
    expect(toast.warning).not.toHaveBeenCalled()
  })

  it('leaves one running that names something not released, and says so', async () => {
    api.clearTarget.mockImplementation((_t: string, key: string) => Promise.resolve(cleared(key)))
    const { result } = renderHook(() => useReleaseTargets([{ type: 'fixture', key: 'par-1', name: 'Par 1' }]))
    await result.current()
    expect(removeFx).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledTimes(1)
  })
})
