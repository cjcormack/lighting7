// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import type { ReactNode } from 'react'
import { handWs } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const toasts: [string, Record<string, unknown>][] = []
vi.mock('sonner', () => ({
  toast: Object.assign((...a: unknown[]) => toasts.push(a as never), {
    success: (...a: unknown[]) => toasts.push(a as never),
    error: (...a: unknown[]) => toasts.push(a as never),
    warning: (...a: unknown[]) => toasts.push(a as never),
  }),
}))

const addedLayers: unknown[] = []
/**
 * What `sendGesture` decided. `programmerAddLayer` returns it, and returning it *explicitly* is the
 * point of this mock: the first version returned `Array.push`'s new length, which is truthy by
 * accident — so the happy path was exercised and the dropped-frame path could not be.
 */
const sendVerdict = { ok: true }
vi.mock('@/store/programmer', () => ({
  programmerAddLayer: (input: unknown) => {
    addedLayers.push(input)
    return sendVerdict.ok
  },
}))

const patches: unknown[] = []
vi.mock('@/store/cues', () => ({
  usePatchProjectCueMutation: () => [
    (arg: unknown) => {
      patches.push(arg)
      return { unwrap: () => Promise.resolve({ id: 5 }) }
    },
  ],
}))

const selection: { targets: { type: string; key: string }[] } = { targets: [] }
vi.mock('@/store/selection', () => ({ useDeskSelection: () => selection.targets }))

import { store } from '@/store/index'
import { restApi } from '@/store/restApi'
import type { Cue } from '@/api/cuesApi'
import { HandCueLayerStrip, HandProgrammerLayerStrip } from './HandLayerTargets'
import { HandPlaceStrip } from './HandTarget'

/**
 * The hand's two **layer** places (multi-screen plan §3.5, D12).
 *
 * What each one has to get right: the strip is drawn only for a record the target can take, the
 * place is this window's own mutation with the desk selection as targets, the drop names the record
 * placed, and the cue's inverse is the layers array read *before* the place — not a re-derivation.
 */
const wrap = (ui: ReactNode) => render(<Provider store={store}>{ui}</Provider>)

const hold = (kind: 'TEMPLATE' | 'LOOK' | 'CUE', over: Record<string, unknown> = {}) => ({
  kind,
  id: 8,
  uuid: `${kind.toLowerCase()}-uuid-8`,
  template: kind === 'TEMPLATE' ? { id: 8, name: 'Amber', kind: 'value', isGeneric: true, rows: [] } : null,
  look: kind === 'LOOK' ? { id: 8, name: 'Warm Wash', effectCount: 0, rowCount: 2 } : null,
  cue: kind === 'CUE' ? { id: 8, name: 'Verse', cueNumber: '3', cueStackId: 1, cueStackName: 'Act I' } : null,
  pickedUpOn: null,
  holdId: 1,
  pickedUpAtMs: 0,
  expiresAtMs: 0,
  ...over,
})

const cue = (): Cue =>
  ({
    id: 5,
    name: 'Verse 2',
    cueNumber: '12',
    cueStackId: 1,
    sortOrder: 2,
    cueType: 'STANDARD',
    layers: [
      {
        lookId: 3,
        templateId: null,
        sortOrder: 1,
        enabled: true,
        targets: [],
        propertyMask: null,
        blendMode: 'OVERRIDE',
        amount: 1,
        stomp: false,
        speedMasterUuid: null,
        rateSpeedMasterUuid: null,
        delayMs: null,
        intervalMs: null,
        randomWindowMs: null,
      },
    ],
    adHocEffects: [],
    propertyAssignments: [],
    triggers: [],
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: 0,
    fadeCurve: 'LINEAR',
    notes: null,
    stomp: false,
  }) as unknown as Cue

beforeEach(() => {
  handWs.reset()
  toasts.length = 0
  addedLayers.length = 0
  patches.length = 0
  selection.targets = []
  sendVerdict.ok = true
  store.dispatch(restApi.util.resetApiState())
})

async function fire(item: unknown) {
  await act(async () => {
    handWs.fire(item)
  })
  await waitFor(() =>
    expect(handWs.callback).not.toBeNull(),
  )
}

/** Mount, wait for the form-3 subscription, then push the frame and wait for the strip. */
async function withHold(ui: ReactNode, item: unknown, expectStrip: boolean) {
  wrap(ui)
  await waitFor(() => expect(handWs.callback).not.toBeNull())
  await fire(item)
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: /Place “/ }) != null).toBe(expectStrip),
  )
}

describe('the programmer’s layer stack', () => {
  it('offers itself for a Look and a template, and not for a cue', async () => {
    await withHold(<HandProgrammerLayerStrip />, hold('LOOK'), true)
  })

  it('draws nothing for a cue — a layer applies a Look or a template', async () => {
    await withHold(<HandProgrammerLayerStrip />, hold('CUE'), false)
  })

  it('adds the layer with the desk selection as targets, then drops naming the record', async () => {
    selection.targets = [{ type: 'fixture', key: 'par-1' }]
    await withHold(<HandProgrammerLayerStrip />, hold('TEMPLATE'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(handWs.dropped).toEqual(['template-uuid-8']))
    expect(addedLayers).toEqual([
      { templateId: 8, targets: [{ type: 'fixture', key: 'par-1' }] },
    ])
  })

  it('keeps the record held when the frame never left — a closed socket is not a place', async () => {
    // The blocker this file exists to guard. `sendGesture` refuses a closed socket, toasts "that
    // did not reach the rig", and returns false; `programmerAddLayer` passes that back. If `run`
    // answers `true` regardless — as it first did — the hand is dropped and a success toast lands
    // *beside* that error, on the one place with no Undo to recover through.
    sendVerdict.ok = false
    await withHold(<HandProgrammerLayerStrip />, hold('LOOK'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    // It was attempted...
    expect(addedLayers).toHaveLength(1)
    // ...and nothing followed: no drop, so the operator still has the record, and no toast of our
    // own, because `sendGesture` has already said the only true thing there is to say.
    expect(handWs.dropped).toEqual([])
    expect(toasts).toHaveLength(0)
  })

  it('offers no Undo — programmer.addLayer answers no id to address', async () => {
    await withHold(<HandProgrammerLayerStrip />, hold('LOOK'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(toasts).toHaveLength(1))
    expect(toasts[0]![1].action).toBeUndefined()
  })
})

describe('a cue’s stack', () => {
  it('appends a layer at the top of the stack and keeps what was there', async () => {
    await withHold(<HandCueLayerStrip projectId={1} cue={cue()} />, hold('LOOK'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(patches).toHaveLength(1))
    const sent = patches[0] as { cueId: number; layers: { lookId?: number; sortOrder?: number }[] }
    expect(sent.cueId).toBe(5)
    expect(sent.layers).toHaveLength(2)
    // The existing layer, untouched, then the new one above it — later wins within a cue.
    expect(sent.layers[0]!.lookId).toBe(3)
    expect(sent.layers[1]).toMatchObject({ lookId: 8, sortOrder: 2, enabled: true })
  })

  it('undoes by patching back the layers read BEFORE the place', async () => {
    await withHold(<HandCueLayerStrip projectId={1} cue={cue()} />, hold('TEMPLATE'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(toasts).toHaveLength(1))
    const action = toasts[0]![1].action as { onClick: () => void }
    await act(async () => {
      action.onClick()
    })
    await waitFor(() => expect(patches).toHaveLength(2))
    const undone = patches[1] as { layers: { lookId?: number | null }[] }
    expect(undone.layers).toHaveLength(1)
    expect(undone.layers[0]!.lookId).toBe(3)
  })

  it('draws nothing for a cue in the hand', async () => {
    await withHold(<HandCueLayerStrip projectId={1} cue={cue()} />, hold('CUE'), false)
  })
})

/**
 * The band's two borders, and why the distinction exists at all.
 *
 * The place was briefly *refused* on the busk page while *Edit layout* was on. The reason given was
 * that a dashed band which no drag can land on, sitting among dashed slots that drags do land on,
 * reads as a drop target — which is true, and is an argument about how it is drawn, not about
 * whether the gesture should exist. Withholding it removed the capability in exactly the case the
 * hand is for: a record picked up on *another* window, which the palette in this one cannot stand in
 * for. So it is offered either way and drawn solid where something else is dashed.
 */
describe('HandPlaceStrip’s border', () => {
  async function drawStrip(amongDropTargets: boolean) {
    wrap(
      <HandPlaceStrip
        target="bank"
        where="Colours"
        amongDropTargets={amongDropTargets}
        onPlace={() => {}}
      />,
    )
    await waitFor(() => expect(handWs.callback).not.toBeNull())
    await fire(hold('LOOK'))
    return screen.findByRole('button', { name: /Place “/ })
  }

  it('is dashed where nothing else is — the ordinary play-mode band', async () => {
    const strip = await drawStrip(false)
    expect(strip.className).toContain('border-dashed')
    expect(strip.className).not.toContain('border-solid')
  })

  it('is solid among drop targets, and still offers the place', async () => {
    const strip = await drawStrip(true)
    expect(strip.className).toContain('border-solid')
    expect(strip.className).not.toContain('border-dashed')
  })
})
