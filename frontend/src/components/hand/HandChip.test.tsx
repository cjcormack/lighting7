// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import type { ReactNode } from 'react'
import { handWs } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from '@/store/index'
import { restApi } from '@/store/restApi'
import { HandChip } from './HandChip'

/**
 * The hand's chip (multi-screen plan §3.5, D14).
 *
 * Three things it has to get right, and each is a bug that only shows on a desk: the ghost is built
 * from the **frame's own summary DTOs** and subscribes to nothing about the record; the × lets go of
 * whatever is there rather than of what it happens to be drawing; and Escape is the **last** rung of
 * the ladder — a press while a cell editor is open belongs to the editor, and answering that in the
 * bubble phase would always say "nothing open" because Radix has already closed it.
 */

const wrap = (ui: ReactNode) => render(<Provider store={store}>{ui}</Provider>)

/**
 * Mount the chip and wait until its cache entry has actually subscribed.
 *
 * The bridge is form 3 (`onCacheEntryAdded`), so the subscription is registered after
 * `cacheDataLoaded` resolves — a frame fired before that lands on nobody, and the `queryFn`'s seed
 * already ran with an empty hand. Every test here fires *after* this, so what it asserts is the
 * broadcast path rather than the seed.
 */
async function mountChip() {
  wrap(<HandChip />)
  await waitFor(() => expect(handWs.callback).not.toBeNull())
}

const templateHold = {
  kind: 'TEMPLATE',
  id: 3,
  uuid: 'tmpl-uuid-3',
  template: {
    id: 3,
    uuid: 'tmpl-uuid-3',
    name: 'Warm Amber',
    kind: 'value',
    family: 'COLOUR',
    isGeneric: true,
    rows: [{ propertyName: 'colour', value: '#ff9d4a' }],
    notes: null,
  },
  look: null,
  cue: null,
  pickedUpOn: null,
  holdId: 1,
  pickedUpAtMs: 0,
  expiresAtMs: 0,
}

beforeEach(() => {
  handWs.reset()
  store.dispatch(restApi.util.resetApiState())
})

/**
 * Push a `hand.state` frame, as the desk's broadcast does, and wait for the chip to settle.
 *
 * `updateCachedData` from inside `onCacheEntryAdded` is an ordinary dispatch, so the render that
 * follows it is a later task — asserting straight after the fire reads the frame before it.
 */
async function fire(item: unknown) {
  await act(async () => {
    handWs.fire(item)
  })
  // Waiting on **which hold** is drawn, not merely that one is: a frame that replaces one hold with
  // another of the same record changes nothing else on screen, so a presence check would be
  // satisfied by the *previous* render and every assertion after it would read the frame before.
  // `useDeskHandQuery`'s re-render lands a task after `act` flushes, which is what `waitFor` is for.
  const expected = item == null ? null : String((item as { holdId: number }).holdId)
  await waitFor(() =>
    expect(document.querySelector('[data-hand-chip]')?.getAttribute('data-hold-id') ?? null).toBe(
      expected,
    ),
  )
}

describe('HandChip', () => {
  it('draws nothing while the hand is empty', async () => {
    await mountChip()
    await fire(null)
    expect(document.querySelector('[data-hand-chip]')).toBeNull()
  })

  it('builds the ghost from the frame’s own summary, with no second fetch', async () => {
    await mountChip()
    await fire(templateHold)
    expect(await screen.findByText('Warm Amber')).toBeTruthy()
    // `describeTemplate`'s value arm, derived from the embedded DTO alone — the chip asked no
    // library for it, and the mock would have answered nothing if it had.
    expect(screen.getByText('Colour')).toBeTruthy()
  })

  it('lets go of whatever is there when × is pressed — a BARE drop, never a guarded one', async () => {
    await mountChip()
    await fire(templateHold)
    await act(async () => {
      screen.getByLabelText('Let go of Warm Amber').click()
    })
    expect(handWs.dropped).toEqual([undefined])
  })

  it('drops on Escape when nothing else claims the key', async () => {
    await mountChip()
    await fire(templateHold)
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    })
    expect(handWs.dropped).toEqual([undefined])
  })

  it('leaves Escape alone while a cell editor is open — the key is the editor’s', async () => {
    const editor = document.createElement('div')
    // The attribute `editorIsOpen` looks for — spelled out here exactly as
    // `useEscapeEditorSnapshot.test.tsx` spells it, because it is a DOM contract rather than an
    // export, and a test that imported a constant could not catch the two drifting apart.
    editor.setAttribute('data-cell-editor-surface', 'popover')
    document.body.appendChild(editor)
    try {
      await mountChip()
      await fire(templateHold)
      await act(async () => {
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      })
      expect(handWs.dropped).toEqual([])
    } finally {
      editor.remove()
    }
  })

  it('leaves Escape alone while a dialog is open anywhere on the page', async () => {
    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    document.body.appendChild(dialog)
    try {
      await mountChip()
      await fire(templateHold)
      await act(async () => {
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      })
      expect(handWs.dropped).toEqual([])
    } finally {
      dialog.remove()
    }
  })

  it('ignores an Escape another handler has already claimed', async () => {
    await mountChip()
    await fire(templateHold)
    await act(async () => {
      const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
      event.preventDefault()
      window.dispatchEvent(event)
    })
    expect(handWs.dropped).toEqual([])
  })

  it('remounts on a re-pick-up of the same record — the key is the hold, not the uuid', async () => {
    await mountChip()
    await fire(templateHold)
    const first = document.querySelector('[data-hand-chip]')
    // A *different hold* of the same record: same kind, same id, same uuid, new `holdId`. Nothing
    // else about the frame moves, so the key is the only thing that can make this a new element —
    // which is the whole reason `holdId` exists on the wire.
    await fire({ ...templateHold, holdId: 2 })
    const second = document.querySelector('[data-hand-chip]')
    expect(second).not.toBeNull()
    expect(second).not.toBe(first)
  })
})
