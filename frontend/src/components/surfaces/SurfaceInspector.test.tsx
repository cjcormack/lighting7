// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CATALOGUE, FIXTURE_PROFILE, MAP_FRAME } from './surfaceFixtures'

const writes: unknown[] = []
const clears: unknown[] = []
const toasts = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), message: vi.fn(), success: vi.fn() }))
let failClear = false

vi.mock('sonner', () => ({ toast: toasts }))

vi.mock('@/store/surfaces', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/store/surfaces')>()
  return {
    ...actual,
    useWriteSurfaceSlotMutation: () => [
      (args: unknown) => {
        writes.push(args)
        return { unwrap: () => Promise.resolve({}) }
      },
      {},
    ],
    useClearSurfaceSlotMutation: () => [
      (args: unknown) => {
        clears.push(args)
        return { unwrap: () => (failClear ? Promise.reject({ data: {} }) : Promise.resolve()) }
      },
      {},
    ],
  }
})
vi.mock('./useBindingChoices', async () => {
  const { EMPTY_RECORD_OPTIONS } = await import('./recordOptions')
  return {
    useBindingChoices: () => ({
      subjects: { groups: [{ key: 'front-wash', label: 'Front wash' }], fixtures: [], records: EMPTY_RECORD_OPTIONS },
      attributesFor: () => [],
    }),
  }
})

import { SurfaceInspector } from './SurfaceInspector'

/** Editor A: one block per part, a job's controls offering to become their own, identify (D12, D14). */

const identify = { wait: { waiting: false, endsAt: null as number | null, ended: null as string | null }, begin: vi.fn(), cancel: vi.fn() }

function renderInspector(controlId: string, viewedLayer = 'layer-a', wait = identify.wait, onEditJob?: (region: string) => void) {
  render(
    <SurfaceInspector
      projectId={1}
      profile={FIXTURE_PROFILE}
      controlId={controlId}
      viewedLayer={viewedLayer}
      frame={MAP_FRAME}
      catalogue={CATALOGUE}
      state={undefined}
      pickup={undefined}
      connected
      identify={{ ...identify, wait }}
      onEditJob={onEditJob}
    />,
  )
}

beforeEach(() => {
  writes.length = 0
  clears.length = 0
  failClear = false
  toasts.error.mockClear()
})
afterEach(cleanup)

describe('SurfaceInspector', () => {
  it('draws one block per part — an encoder\'s turn and its push — each with what it shows', () => {
    renderInspector('enc-9')
    const turn = document.querySelector('[data-part="MAIN"]') as HTMLElement
    const push = document.querySelector('[data-part="PUSH"]') as HTMLElement
    expect(within(turn).getByText('Turn')).toBeInTheDocument()
    expect(within(turn).getByText(/The ring shows what Local holds/)).toBeInTheDocument()
    expect(within(push).getByText('Push')).toBeInTheDocument()
    expect(within(push).getByText('Writes to Local')).toBeInTheDocument()
  })

  it('says a control its region\'s job derives is the job\'s, and makes it its own on the viewed layer', () => {
    const onEditJob = vi.fn()
    renderInspector('fader-1', 'layer-a', identify.wait, onEditJob)
    expect(screen.getByText(/From Groups as channel strips · item 1/)).toBeInTheDocument()
    expect(screen.getByText(/In Strips 1–8 · slot 1/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Its job…' }))
    expect(onEditJob).toHaveBeenCalledWith('strips')
    fireEvent.click(screen.getByRole('button', { name: 'Make this control its own' }))
    expect(writes).toEqual([
      {
        projectId: 1,
        slot: { deviceTypeKey: FIXTURE_PROFILE.typeKey, controlId: 'fader-1', part: 'MAIN', layer: 'layer-a' },
        binding: { does: { type: 'level', attribute: 'dimmer' }, on: { type: 'group', key: 'front-wash' }, how: undefined },
        takeoverPolicy: undefined,
      },
    ])
  })

  it('says what a control bound by hand beats', () => {
    renderInspector('fader-1', 'layer-b')
    expect(screen.getByText('Bound by hand over Groups as channel strips · item 1.')).toBeInTheDocument()
  })

  it('edits the slot the row sits on, and moves it between layers by writing there first', async () => {
    renderInspector('btn-3')
    fireEvent.click(screen.getByRole('button', { name: 'B only' }))
    await vi.waitFor(() => expect(clears).toHaveLength(1))
    expect((writes[0] as { slot: { layer: string } }).slot.layer).toBe('layer-b')
    expect((clears[0] as { slot: { layer: string | null } }).slot.layer).toBeNull()
  })

  it('says Remove takes the row off both layers when that is where it is, and toasts a clear the desk refused', async () => {
    renderInspector('btn-3')
    failClear = true
    fireEvent.click(screen.getByRole('button', { name: 'Remove from both layers' }))
    expect((clears[0] as { slot: { layer: string | null } }).slot.layer).toBeNull()
    await vi.waitFor(() => expect(toasts.error).toHaveBeenCalledWith('The desk could not take the binding off'))
  })

  it('shows a dead binding\'s reason', () => {
    renderInspector('btn-4')
    expect(screen.getByText(/Dead: missing cue stack/)).toBeInTheDocument()
  })

  it('waits for a touch, and says so, with Cancel', () => {
    renderInspector('btn-5')
    fireEvent.click(screen.getByRole('button', { name: 'Touch a control on the desk' }))
    expect(identify.begin).toHaveBeenCalled()
    cleanup()
    renderInspector('btn-5', 'layer-a', { waiting: true, endsAt: Date.now() + 30_000, ended: null })
    expect(screen.getByRole('status').textContent).toMatch(/Touch a control on the desk… it won’t fire/)
    fireEvent.click(screen.getByRole('button', { name: /Cancel/ }))
    expect(identify.cancel).toHaveBeenCalled()
  })
})
