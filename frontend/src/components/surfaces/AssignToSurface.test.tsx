// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ControlSurfaceType, IdentifyCapture, IdentifyWait, SurfaceSlot } from '@/store/surfaces'
import { CATALOGUE, FIXTURE_PROFILE, MAP_FRAME } from './surfaceFixtures'

const writes: unknown[] = []
const toasts = vi.hoisted(() => ({ error: vi.fn(), success: vi.fn(), message: vi.fn(), warning: vi.fn() }))
const identify = vi.hoisted(() => ({
  wait: { waiting: false, endsAt: null, ended: null } as IdentifyWait,
  begin: vi.fn(),
  cancel: vi.fn(),
  onCapture: null as ((capture: IdentifyCapture) => void) | null,
}))
const remote = vi.hoisted(() => ({ on: false }))
const rig = vi.hoisted(() => ({ types: null as ControlSurfaceType[] | null, attached: null as string[] | null }))
// The desk's answers for the slots these tests touch, by `controlId|layer`.
const PREVIEW_LINES: Record<string, string[]> = {
  'btn-3|': ['Replaces GO · Act 1.'],
  'btn-3|layer-a': ['Beats GO · Act 1 on Layer A; every other layer keeps it.'],
}

vi.mock('sonner', () => ({ toast: toasts }))
vi.mock('@/lib/remoteMode', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/remoteMode')>()),
  isRemoteMode: () => remote.on,
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 7 } }) }))
vi.mock('@/store/surfaces', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/store/surfaces')>()
  return {
    ...actual,
    useControlSurfaceTypeListQuery: () => ({ data: rig.types ?? [FIXTURE_PROFILE] }),
    useSurfaceFunctionsQuery: () => ({ data: CATALOGUE }),
    useSurfaceMapsQuery: () => ({ data: { [FIXTURE_PROFILE.typeKey]: MAP_FRAME } }),
    useSurfaceMap: () => MAP_FRAME,
    useSurfaceDevices: () =>
      (rig.attached ?? [FIXTURE_PROFILE.typeKey]).map((typeKey) => ({ displayKey: `${typeKey}-1`, typeKey, isMatched: true })),
    useSurfaceSlotPreviewQuery: (args: { slot: SurfaceSlot } | symbol) => {
      if (typeof args === 'symbol') return { data: undefined }
      const { slot } = args
      const profile = (rig.types ?? [FIXTURE_PROFILE]).find((t) => t.typeKey === slot.deviceTypeKey)!
      const control = profile.controls.find((c) => c.controlId === slot.controlId)!
      const bank = profile.banks.find((b) => b.id === slot.layer)
      const place = `${control.label}${slot.part === 'PUSH' ? ' push' : ''}${bank == null ? '' : ` · ${bank.name}`}`
      return { data: { place, replaces: PREVIEW_LINES[`${slot.controlId}|${slot.layer ?? ''}`] ?? [] } }
    },
    useSurfaceControls: () => ({}),
    useIdentify: (onCapture: (capture: IdentifyCapture) => void) => {
      identify.onCapture = onCapture
      return { wait: identify.wait, begin: identify.begin, cancel: identify.cancel }
    },
    useWriteSurfaceSlotMutation: () => [
      (args: unknown) => {
        writes.push(args)
        return { unwrap: () => Promise.resolve({}) }
      },
      { isLoading: false },
    ],
  }
})
vi.mock('./useBindingChoices', async () => {
  const { EMPTY_RECORD_OPTIONS } = await import('./recordOptions')
  return { useBindingChoices: () => ({ subjects: { groups: [], fixtures: [], records: EMPTY_RECORD_OPTIONS }, attributesFor: () => [] }) }
})

import {
  AssignToSurfaceButton,
  AssignToSurfaceHost,
  AssignToSurfaceSheet,
  assignTargetNow,
  closeAssignToSurface,
  openAssignToSurface,
  type AssignTarget,
} from './AssignToSurface'

/**
 * *Assign to surface…* (surface-setup plan session 6): the sheet waits for a touch, or a click on
 * the picture in remote mode, offers what fits the record and the part, says what it replaces, and
 * assigns through the slot write.
 */

const STACK: AssignTarget = { name: 'Act 1', subjects: [{ type: 'record', kind: 'STACK', uuid: 's-1' }], projectId: 7 }
const PALETTE: AssignTarget = { name: 'Warm', subjects: [{ type: 'record', kind: 'PALETTE', uuid: 'p-1' }], projectId: 7 }

const OTHER: ControlSurfaceType = { ...FIXTURE_PROFILE, typeKey: 'other-surface', product: 'Other', banks: [] }

function touch(controlId: string, part: 'MAIN' | 'PUSH' = 'MAIN', deviceTypeKey = FIXTURE_PROFILE.typeKey) {
  act(() => identify.onCapture?.({ sessionId: 's', deviceTypeKey, displayKey: 'xtc-1', controlId, part }))
}

function sheet(target: AssignTarget = STACK, onClose = vi.fn()) {
  render(<AssignToSurfaceSheet projectId={7} target={target} onClose={onClose} />)
  return onClose
}

beforeEach(() => {
  writes.length = 0
  identify.wait = { waiting: false, endsAt: null, ended: null }
  identify.begin.mockClear()
  identify.cancel.mockClear()
  remote.on = false
  rig.types = null
  rig.attached = null
})
afterEach(() => {
  cleanup()
  closeAssignToSurface()
})

describe('Assign to surface…', () => {
  it('opens waiting for a touch on any device', () => {
    sheet()
    expect(identify.begin).toHaveBeenCalledWith(null)
  })

  it('takes the touched control, offers GO on the stack, and assigns it through the slot write', async () => {
    const onClose = sheet()
    touch('btn-5')
    const slot = screen.getByTestId('assign-slot')
    expect(within(slot).getByText('Button 5')).toBeInTheDocument()
    expect(within(slot).getByRole('button', { name: 'Does' })).toHaveTextContent('GO')
    expect(screen.getByTestId('assign-replaces')).toHaveTextContent('Nothing is bound there yet.')
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }))
    expect(writes).toEqual([
      {
        projectId: 7,
        slot: { deviceTypeKey: FIXTURE_PROFILE.typeKey, controlId: 'btn-5', part: 'MAIN', layer: null },
        binding: { does: { type: 'go' }, on: { type: 'record', kind: 'STACK', uuid: 's-1' } },
        takeoverPolicy: null,
      },
    ])
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(toasts.success).toHaveBeenCalledWith('Assigned Act 1 to Button 5')
  })

  it('names what it replaces, and assigns on the layer chosen', () => {
    sheet()
    touch('btn-3')
    expect(screen.getByTestId('assign-replaces')).toHaveTextContent('Replaces GO · Act 1.')
    fireEvent.click(within(screen.getByRole('group', { name: 'Layer' })).getByRole('button', { name: 'A only' }))
    expect(screen.getByTestId('assign-replaces')).toHaveTextContent('Beats GO · Act 1 on Layer A')
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }))
    expect(writes).toMatchObject([{ slot: { controlId: 'btn-3', layer: 'layer-a' } }])
  })

  it('drops a layer the surface touched next does not have', () => {
    rig.types = [FIXTURE_PROFILE, OTHER]
    rig.attached = [FIXTURE_PROFILE.typeKey, OTHER.typeKey]
    sheet()
    touch('btn-3')
    fireEvent.click(within(screen.getByRole('group', { name: 'Layer' })).getByRole('button', { name: 'A only' }))
    touch('btn-5', 'MAIN', OTHER.typeKey)
    expect(screen.queryByRole('group', { name: 'Layer' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }))
    expect(writes).toMatchObject([{ slot: { deviceTypeKey: OTHER.typeKey, controlId: 'btn-5', layer: null } }])
  })

  it('drops the slot when its surface is no longer offered, rather than writing it on another', () => {
    rig.types = [FIXTURE_PROFILE, OTHER]
    rig.attached = [FIXTURE_PROFILE.typeKey, OTHER.typeKey]
    const { rerender } = render(<AssignToSurfaceSheet projectId={7} target={STACK} onClose={vi.fn()} />)
    touch('btn-5', 'MAIN', OTHER.typeKey)
    expect(screen.getByTestId('assign-slot')).toBeInTheDocument()
    rig.types = [FIXTURE_PROFILE]
    rig.attached = [FIXTURE_PROFILE.typeKey]
    rerender(<AssignToSurfaceSheet projectId={7} target={STACK} onClose={vi.fn()} />)
    expect(screen.queryByTestId('assign-slot')).toBeNull()
    expect(screen.getByRole('button', { name: 'Assign' })).toBeDisabled()
  })

  it('offers a fader what fits the record, and says so when nothing does', () => {
    sheet()
    touch('fader-1')
    expect(within(screen.getByTestId('assign-slot')).getByRole('button', { name: 'Does' })).toHaveTextContent('FX amount')
    cleanup()
    sheet(PALETTE)
    touch('fader-1')
    expect(screen.getByTestId('assign-slot')).toHaveTextContent('Nothing Warm can do fits this fader.')
    expect(screen.getByRole('button', { name: 'Assign' })).toBeDisabled()
  })

  it('offers an encoder\'s push its own functions', () => {
    sheet(PALETTE)
    touch('enc-9', 'PUSH')
    const slot = screen.getByTestId('assign-slot')
    expect(within(slot).getByText('Encoder 9 push')).toBeInTheDocument()
    expect(within(slot).getByRole('button', { name: 'Does' })).toHaveTextContent('Palette')
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }))
    expect(writes).toMatchObject([
      { slot: { controlId: 'enc-9', part: 'PUSH' }, binding: { does: { type: 'palette', palette: 'p-1' }, on: { type: 'selection' } } },
    ])
  })

  it('cancels the wait, and says when the wait timed out', () => {
    identify.wait = { waiting: true, endsAt: Date.now() + 30_000, ended: null }
    sheet()
    const banner = screen.getByRole('status')
    expect(banner).toHaveTextContent('Touch a control on the desk')
    fireEvent.click(within(banner).getByRole('button', { name: 'Cancel' }))
    expect(identify.cancel).toHaveBeenCalled()
    cleanup()
    identify.wait = { waiting: false, endsAt: null, ended: 'timeout' }
    sheet()
    expect(screen.getByText('Nothing was touched.')).toBeInTheDocument()
  })

  it('in remote mode, waits for nothing and takes a click on the picture', () => {
    remote.on = true
    sheet()
    expect(identify.begin).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /Touch a control/ })).toBeNull()
    fireEvent.click(screen.getByTitle('Button 5'))
    expect(within(screen.getByTestId('assign-slot')).getByText('Button 5')).toBeInTheDocument()
  })

  it('assigns nothing for a record of another show', () => {
    sheet({ ...STACK, projectId: 3 })
    expect(screen.getByText(/Surfaces are bound in the current show/)).toBeInTheDocument()
    expect(identify.begin).not.toHaveBeenCalled()
  })

  it('is opened by a door and closed by its Cancel, from the layout', () => {
    render(
      <>
        <AssignToSurfaceButton target={STACK} />
        <AssignToSurfaceHost />
      </>,
    )
    expect(screen.queryByTestId('assign-to-surface')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Assign to surface…' }))
    expect(assignTargetNow()).toBe(STACK)
    expect(screen.getByTestId('assign-to-surface')).toBeInTheDocument()
    fireEvent.click(within(screen.getByTestId('assign-to-surface')).getByRole('button', { name: 'Cancel' }))
    expect(assignTargetNow()).toBeNull()
    expect(screen.queryByTestId('assign-to-surface')).toBeNull()
  })

  it('opens fresh each time, waiting again and holding no slot from before', () => {
    render(<AssignToSurfaceHost />)
    act(() => openAssignToSurface(STACK))
    touch('btn-5')
    expect(screen.getByTestId('assign-slot')).toBeInTheDocument()
    act(() => openAssignToSurface(STACK))
    expect(screen.queryByTestId('assign-slot')).toBeNull()
    expect(identify.begin).toHaveBeenCalledTimes(2)
  })
})
