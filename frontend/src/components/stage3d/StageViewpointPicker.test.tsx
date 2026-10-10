// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { StageViewpointDto } from '../../api/stageViewpointApi'
import type { SeatViewpointRef, StageViewpoint } from '../../lib/stageViewpoint'
import { StageViewpointPicker } from './StageViewpointPicker'
import type { SeatScrimHint } from './seatScrimHint'

/** A saved seat view, as `GET stage-viewpoints` answers one. */
function seatRow(uuid: string, name: string, seatId: string): StageViewpointDto {
  return {
    id: 1,
    uuid,
    name,
    kind: 'SEAT',
    seatElementUuid: 'stalls',
    seatId,
    eyeX: null,
    eyeY: null,
    eyeZ: null,
    targetX: null,
    targetY: null,
    targetZ: null,
    fovDeg: null,
    sortOrder: 0,
  }
}

function hint(text: string, others = 0): SeatScrimHint {
  const reading = { uuid: 'gauze', name: 'Forest gauze', open: 0.12, ratio: 0.24 }
  return { reading, others: Array.from({ length: others }, () => reading), text, detail: text }
}

function open(props: Partial<Parameters<typeof StageViewpointPicker>[0]> = {}) {
  render(
    <StageViewpointPicker
      viewpoint={'eye' as StageViewpoint}
      camera="eye"
      saved={[seatRow('far', 'Far side', 'F18'), seatRow('centre', 'Centre', 'F9')]}
      landable={() => true}
      onPick={vi.fn()}
      onFrame={vi.fn()}
      canFrame={false}
      onSave={vi.fn()}
      canSave={false}
      {...props}
    />,
  )
  // Radix's DropdownMenuTrigger opens on `pointerdown`, not `click`.
  fireEvent.pointerDown(screen.getByRole('button', { name: /^Viewpoint:/ }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
}

describe('StageViewpointPicker’s seat hint (scrim plan D13)', () => {
  it('says so under a seat that sees a gauze as near-solid, and says nothing under one that does not', async () => {
    const text = 'Forest gauze reads near-solid from F18 (open 12 %)'
    open({ seatHints: new Map([['far', hint(text, 1)]]) })
    const far = (await screen.findByText('Far side')).closest('[role="menuitem"]') as HTMLElement
    expect(within(far).getByText(`${text} · 1 more`)).toBeTruthy()
    expect(far.getAttribute('title')).toBe(text)
    const centre = screen.getByText('Centre').closest('[role="menuitem"]') as HTMLElement
    expect(centre.querySelector('[data-seat-scrim-hint]')).toBeNull()
  })

  it('lists the seat the window sits in unsaved, with its hint', async () => {
    const ref = 'seat:5f3c1a2e-0b4d-4c6e-9a8b-7d6e5f4c3b2a:A1' as SeatViewpointRef
    const onPick = vi.fn()
    open({ viewpoint: ref, seatHints: new Map([[ref, hint('Forest gauze reads near-solid from A1 (open 3 %)')]]), onPick })
    const item = (await screen.findAllByText('Row A, seat 1')).map((el) => el.closest('[role="menuitem"]')).find((el) => el != null) as HTMLElement
    expect(within(item).getByText('unsaved')).toBeTruthy()
    expect(within(item).getByText('Forest gauze reads near-solid from A1 (open 3 %)')).toBeTruthy()
    fireEvent.click(item)
    expect(onPick).toHaveBeenCalledWith(ref)
  })

  it('draws no hint for a seat whose seat is gone', async () => {
    open({ landable: (row) => row.uuid !== 'far', seatHints: new Map([['far', hint('Forest gauze reads near-solid from F18 (open 12 %)')]]) })
    const far = (await screen.findByText('Far side')).closest('[role="menuitem"]') as HTMLElement
    expect(far.querySelector('[data-seat-scrim-hint]')).toBeNull()
    expect(within(far).getByText('seat gone')).toBeTruthy()
  })
})
