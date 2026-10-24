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

/**
 * The programmer's place is the press (apply-model plan §5): `usePress`, whose routes are recorded
 * here. `verdict.ok` false answers a refusal, which `errorToastMiddleware` would already have
 * toasted — so the hand must keep the record and say nothing of its own.
 */
const pressed: { route: string; args: unknown }[] = []
const verdict = { ok: true }
const toggleAction = { current: 'applied' }
function route(name: string) {
  return () => [
    (args: unknown) => {
      pressed.push({ route: name, args })
      return {
        unwrap: () =>
          verdict.ok
            ? Promise.resolve({ action: toggleAction.current, fxCount: 0, skippedFamilies: [] })
            : Promise.reject(new Error('refused')),
      }
    },
  ]
}
vi.mock('@/store/palettes', () => ({
  useTogglePaletteMutation: route('togglePalette'),
  useApplyPaletteMutation: route('applyPalette'),
}))
vi.mock('@/store/looks', () => ({
  useToggleLookMutation: route('toggleLook'),
  useApplyLookMutation: route('applyLook'),
  useAbsorbLookFxMutation: route('absorb'),
}))
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useParams: () => ({ projectId: '1' }),
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

/** The desk's pair, and this window's own copy for when it is unlinked. */
const selection = {
  following: true,
  targets: [] as { type: string; key: string }[],
  local: [] as { type: string; key: string }[],
}
vi.mock('@/store/selection', () => ({
  usePressFamilies: () => null,
  useSelectionPair: () => ({ targets: selection.following ? selection.targets : selection.local, families: null }),
}))

import { store } from '@/store/index'
import { restApi } from '@/store/restApi'
import type { Cue } from '@/api/cuesApi'
import { HandCueLayerStrip, HandProgrammerLayerStrip } from './HandLayerTargets'
import { PressCueContext } from '@/components/press/pressCue'
import { HandPlaceStrip } from './HandTarget'

/**
 * The hand's two **layer** places (multi-screen plan §3.5, D12).
 *
 * What each one has to get right: the strip is drawn only for a record the target can take, the
 * place is this window's own mutation with the window's selection as targets — the desk's while it
 * follows, its own when unlinked — the drop names the record placed, and the cue's inverse is the
 * layers array read *before* the place — not a re-derivation.
 */
const wrap = (ui: ReactNode) => render(<Provider store={store}>{ui}</Provider>)

const hold = (kind: 'PALETTE' | 'LOOK' | 'CUE', over: Record<string, unknown> = {}) => ({
  kind,
  id: 8,
  uuid: `${kind.toLowerCase()}-uuid-8`,
  palette: kind === 'PALETTE' ? { id: 8, name: 'Amber', kind: 'value', isGeneric: true, rows: [] } : null,
  look: kind === 'LOOK' ? { id: 8, name: 'Warm Wash', fxCount: 0, rowCount: 2 } : null,
  cue: kind === 'CUE' ? { id: 8, name: 'Verse', entries: [{ stackId: 1, stackName: 'Act I', entryId: 108, cueNumber: '3' }] } : null,
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
        paletteId: null,
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
    adHocFx: [],
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
  pressed.length = 0
  patches.length = 0
  selection.following = true
  selection.targets = []
  selection.local = []
  verdict.ok = true
  toggleAction.current = 'applied'
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
  it('offers itself for a Look and a palette, and not for a cue', async () => {
    await withHold(<HandProgrammerLayerStrip />, hold('LOOK'), true)
  })

  it('draws nothing for a cue — a layer applies a Look or a palette', async () => {
    await withHold(<HandProgrammerLayerStrip />, hold('CUE'), false)
  })

  it('presses the record onto the desk selection, then drops naming it', async () => {
    selection.targets = [{ type: 'fixture', key: 'par-1' }]
    await withHold(<HandProgrammerLayerStrip />, hold('PALETTE'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(handWs.dropped).toEqual(['palette-uuid-8']))
    expect(pressed).toEqual([
      {
        route: 'togglePalette',
        args: { projectId: 1, paletteId: 8, targets: [{ type: 'fixture', key: 'par-1' }], propertyMask: undefined, families: undefined },
      },
    ])
  })

  it('honours an unlinked window: its own selection, not the desk’s', async () => {
    selection.following = false
    selection.targets = [{ type: 'fixture', key: 'par-1' }]
    selection.local = [{ type: 'group', key: 'wash' }]
    await withHold(<HandProgrammerLayerStrip />, hold('LOOK'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(pressed).toHaveLength(1))
    expect(pressed[0]).toEqual({
      route: 'toggleLook',
      args: { projectId: 1, lookId: 8, targets: [{ type: 'group', key: 'wash' }], families: undefined },
    })
  })

  it('says so when the place took the record off heads it already covered', async () => {
    toggleAction.current = 'removed'
    selection.targets = [{ type: 'fixture', key: 'par-1' }]
    await withHold(<HandProgrammerLayerStrip />, hold('LOOK'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(toasts).toHaveLength(1))
    expect(toasts[0]![0]).toBe('“Warm Wash” was already on the selection — taken off it')
  })

  it('keeps the record held when the press was refused', async () => {
    // A refusal is already on screen (`errorToastMiddleware`), so the hand keeps the record and
    // says nothing of its own — dropping it would lose the record and toast success beside the error.
    verdict.ok = false
    await withHold(<HandProgrammerLayerStrip />, hold('LOOK'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(pressed).toHaveLength(1))
    expect(handWs.dropped).toEqual([])
    expect(toasts).toHaveLength(0)
  })

  it('offers no Undo — a press answers no layer id to address', async () => {
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
    await withHold(<HandCueLayerStrip projectId={1} cue={cue()} />, hold('PALETTE'), true)
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

  it('appends onto an unlinked window’s own selection', async () => {
    selection.following = false
    selection.local = [{ type: 'fixture', key: 'par-4' }]
    await withHold(<HandCueLayerStrip projectId={1} cue={cue()} />, hold('LOOK'), true)
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(patches).toHaveLength(1))
    const sent = patches[0] as { layers: { targets: unknown[] }[] }
    expect(sent.layers[1]!.targets).toEqual([{ type: 'fixture', key: 'par-4' }])
  })

  it('with a host — the cue editor’s Looks tab — presses into the cue on its targets, never its own PATCH', async () => {
    const pressLayer = vi.fn((_source: unknown, _targets: unknown, _families: unknown) =>
      Promise.resolve({ action: 'applied' as const, cue: {} as never }),
    )
    const pressCue = {
      projectId: 1,
      cueId: 5,
      cueLabel: 'Q12',
      readOnly: false,
      layered: new Set<string>(),
      effects: [],
      pressLayer,
      landValues: vi.fn(() => 0),
      pickFx: vi.fn(() => Promise.resolve(true)),
    }
    const host = { targets: [{ type: 'group' as const, key: 'Front' }] }
    await withHold(
      <PressCueContext.Provider value={pressCue}>
        <HandCueLayerStrip projectId={1} cue={cue()} host={host} />
      </PressCueContext.Provider>,
      hold('LOOK'),
      true,
    )
    await act(async () => {
      screen.getByRole('button', { name: /Place “/ }).click()
    })
    await waitFor(() => expect(pressLayer).toHaveBeenCalledTimes(1))
    expect(pressLayer).toHaveBeenCalledWith({ lookId: 8 }, [{ type: 'group', key: 'Front' }], null)
    expect(patches).toHaveLength(0)
    expect(pressed).toHaveLength(0)
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
