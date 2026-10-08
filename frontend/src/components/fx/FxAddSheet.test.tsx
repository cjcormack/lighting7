// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { MutableRefObject } from 'react'
import { makeActiveEffect, makeFixture } from '@/test/fixtureFactories'

const wire = vi.hoisted(() => ({ active: [] as unknown[] }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveEffectsQuery: () => ({ data: wire.active }),
  useRemoveFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/groups', () => ({ useRemoveGroupFxMutation: () => [vi.fn()] }))
const order = vi.hoisted(() => [] as string[])
/** The picker stand-in starts effect 77 on a press, as a tap would. */
vi.mock('./FxPicker', () => ({
  FxPicker: ({ onCurrent, onEdit }: { onCurrent: (c: unknown) => void; onEdit: () => void }) => (
    <>
      <button type="button" onClick={() => onCurrent({ effectId: 77, effectType: 'Circle', propertyName: 'position' })}>
        start
      </button>
      <button type="button" onClick={onEdit}>
        edit
      </button>
    </>
  ),
}))
vi.mock('./FxEditor', () => ({
  FxEditor: ({ flushRef }: { flushRef?: MutableRefObject<(() => void) | null> }) => {
    if (flushRef) flushRef.current = () => order.push('flush')
    return null
  },
}))

import { FxAddSheet } from './FxAddSheet'

const TARGET = { type: 'fixture' as const, fixture: makeFixture('spot-3', []) }

afterEach(() => {
  cleanup()
  order.length = 0
  wire.active = []
})

/**
 * The programmer's layer scope absorbs the effect a session settled on (session 3 amendment): once,
 * however the sheet goes, by the id the create answered — not the refetched list — and only after
 * the editor has landed any held move.
 */
describe('FxAddSheet', () => {
  it('absorbs the auditioned effect on close, by the create’s id, before the list has refetched', () => {
    const onFinished = vi.fn((id: number) => order.push(`finish ${id}`))
    const onClose = vi.fn()
    render(<FxAddSheet target={TARGET} open onClose={onClose} onFinished={onFinished} />)
    fireEvent.click(screen.getByRole('button', { name: 'start' }))
    act(() => {
      fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    })
    expect(onFinished).toHaveBeenCalledWith(77)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('absorbs when its host unmounts it, once', () => {
    const onFinished = vi.fn()
    const { unmount } = render(<FxAddSheet target={TARGET} open onClose={() => {}} onFinished={onFinished} />)
    fireEvent.click(screen.getByRole('button', { name: 'start' }))
    unmount()
    expect(onFinished).toHaveBeenCalledTimes(1)
    expect(onFinished).toHaveBeenCalledWith(77)
  })

  it('lands the editor’s held move before handing the effect to the Look', () => {
    wire.active = [makeActiveEffect({ id: 77, effectType: 'Circle', targetKey: 'spot-3', propertyName: 'position' })]
    const onFinished = vi.fn((id: number) => order.push(`finish ${id}`))
    const { unmount } = render(<FxAddSheet target={TARGET} open onClose={() => {}} onFinished={onFinished} />)
    fireEvent.click(screen.getByRole('button', { name: 'start' }))
    fireEvent.click(screen.getByRole('button', { name: 'edit' }))
    unmount()
    expect(order).toEqual(['flush', 'finish 77'])
  })

  it('absorbs nothing when nothing was started', () => {
    const onFinished = vi.fn()
    const { unmount } = render(<FxAddSheet target={TARGET} open onClose={() => {}} onFinished={onFinished} />)
    unmount()
    expect(onFinished).not.toHaveBeenCalled()
  })
})
