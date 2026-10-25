// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, renderHook } from '@testing-library/react'
import type { PressPair } from '@/hooks/usePress'

/**
 * *+ Effect* (apply-model plan §4.6, session 6): the picker's effects arm over the window's pair —
 * the bare effect press, one Local effect over the selection under its mask (`FxPicker.test.tsx`
 * pins the request) — disabled in Output, on a focused palette layer and with nothing selected.
 */
const FOUR: PressPair = {
  targets: [1, 2, 3, 4].map((n) => ({ type: 'fixture' as const, key: `hex-${n}` })),
  families: ['INTENSITY'],
}
const wire = vi.hoisted(() => ({
  pair: null as PressPair | null,
  scope: { kind: 'local' } as { kind: string } | null,
  store: null as object | null,
}))
vi.mock('@/components/fx/selectionFxTarget', () => ({ useWindowFxPair: () => wire.pair }))
vi.mock('@/hooks/usePress', () => ({ useWindowPaletteFit: () => ({ families: null, emitters: null }) }))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => wire.scope }))
vi.mock('./LookRowStore', () => ({ useLookRowStore: () => wire.store }))
const picker = vi.hoisted(() => vi.fn())
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: unknown) => {
    picker(props)
    return null
  },
}))

const { ProgrammerAddFxSheet, useProgrammerAddFx } = await import('./ProgrammerAddFx')

afterEach(() => {
  cleanup()
  picker.mockClear()
  wire.scope = { kind: 'local' }
  wire.store = null
})

describe('+ Effect', () => {
  it('opens the effects arm over a four-head selection, on its masked family', () => {
    wire.pair = FOUR
    const { result } = renderHook(() => useProgrammerAddFx())
    expect(result.current.disabled).toBe(false)
    render(<ProgrammerAddFxSheet projectId={3} open offer={result.current} onClose={() => {}} />)
    expect(picker).toHaveBeenCalledWith(expect.objectContaining({ projectId: 3, library: 'fx', pair: FOUR, family: 'INTENSITY' }))
  })

  it('is disabled with nothing selected, in Output, and on a focused palette layer', () => {
    wire.pair = { targets: [], families: null }
    expect(renderHook(() => useProgrammerAddFx()).result.current.disabled).toBe(true)
    wire.pair = FOUR
    wire.scope = { kind: 'output' }
    expect(renderHook(() => useProgrammerAddFx()).result.current.disabled).toBe(true)
    wire.scope = { kind: 'layer' }
    expect(renderHook(() => useProgrammerAddFx()).result.current.disabled).toBe(true)
    wire.store = { lookName: 'Warm wash' }
    expect(renderHook(() => useProgrammerAddFx()).result.current.reason).toContain('Warm wash')
  })
})
