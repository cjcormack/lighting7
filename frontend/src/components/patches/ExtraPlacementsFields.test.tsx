// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import type { PatchPlacementInput } from '@/api/patchApi'
import type { PlacementGeometry } from '@/lib/extraPlacements'

vi.mock('@/store/riggings', () => ({
  useRiggingListQuery: () => ({ data: [{ uuid: 'rig-lx1', name: 'LX1', sortOrder: 0 }] }),
}))

import { ExtraPlacementsFields } from './ExtraPlacementsFields'
import libraryJson from '../../../../src/main/resources/lanterns/library.json'
import { indexLanterns, type Lantern } from '@/lib/lanterns'

afterEach(cleanup)

const primary: PlacementGeometry = {
  riggingUuid: 'rig-lx1',
  stageX: -3,
  stageY: 0,
  stageZ: -0.4,
  baseYawDeg: 10,
  basePitchDeg: 45,
}

const lanterns = indexLanterns(libraryJson as Lantern[])

function Harness({
  initial,
  onValue,
  lantern,
}: {
  initial: PatchPlacementInput[]
  onValue: (v: PatchPlacementInput[]) => void
  lantern?: React.ComponentProps<typeof ExtraPlacementsFields>['lantern']
}) {
  const [value, setValue] = useState(initial)
  return (
    <ExtraPlacementsFields
      projectId={1}
      primary={primary}
      lantern={lantern}
      value={value}
      onChange={(next) => {
        setValue(next)
        onValue(next)
      }}
    />
  )
}

describe('ExtraPlacementsFields', () => {
  it('adds a lantern mirrored across the centre line on the same rigging', () => {
    const onValue = vi.fn()
    render(<Harness initial={[]} onValue={onValue} />)
    expect(screen.getByText(/another lantern on this same circuit/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: /Lantern/ }))
    expect(onValue).toHaveBeenLastCalledWith([
      {
        label: null,
        riggingUuid: 'rig-lx1',
        stageX: 3,
        stageY: 0,
        stageZ: -0.4,
        baseYawDeg: -10,
        basePitchDeg: 45,
        baseRollDeg: null,
      },
    ])
  })

  it('edits a label and removes a lantern, keeping ids unique per lantern', () => {
    const onValue = vi.fn()
    const stored: PatchPlacementInput = {
      uuid: 'sr',
      label: 'SR',
      riggingUuid: null,
      stageX: 1,
      stageY: 2,
      stageZ: null,
      baseYawDeg: null,
      basePitchDeg: null,
    }
    const { container } = render(
      <Harness initial={[stored, { ...stored, uuid: 'mid', label: 'Mid' }]} onValue={onValue} />,
    )
    const ids = [...container.querySelectorAll('[id]')].map((el) => el.id)
    expect(new Set(ids).size).toBe(ids.length)

    fireEvent.change(screen.getByDisplayValue('Mid'), { target: { value: 'CS' } })
    expect(onValue).toHaveBeenLastCalledWith([stored, { ...stored, uuid: 'mid', label: 'CS' }])

    fireEvent.click(screen.getByRole('button', { name: 'Remove SR' }))
    expect(onValue).toHaveBeenLastCalledWith([{ ...stored, uuid: 'mid', label: 'CS' }])
  })

  it('keeps a row its own fields when an unsaved lantern before it is removed', () => {
    render(<Harness initial={[]} onValue={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Lantern/ }))
    fireEvent.click(screen.getByRole('button', { name: /Lantern/ }))
    const second = document.getElementById('extra-placement-1-label') as HTMLInputElement
    fireEvent.change(second, { target: { value: 'SR' } })

    fireEvent.click(screen.getByRole('button', { name: 'Remove lantern 1' }))
    // The same DOM node survives: an index key would have handed row 0's node the SR row and
    // unmounted this one, dropping focus or a half-typed value with it.
    expect(second.isConnected).toBe(true)
    expect(second.value).toBe('SR')
  })

  it('draws a divider between lanterns but not above the first', () => {
    const stored: PatchPlacementInput = {
      uuid: 'a',
      label: 'SR',
      riggingUuid: null,
      stageX: 1,
      stageY: 0,
      stageZ: null,
      baseYawDeg: null,
      basePitchDeg: null,
    }
    render(<Harness initial={[stored, { ...stored, uuid: 'b', label: 'CS' }]} onValue={vi.fn()} />)
    const rowOf = (value: string) => screen.getByDisplayValue(value).closest('.space-y-3') as HTMLElement
    expect(rowOf('SR').className).not.toContain('border-t')
    expect(rowOf('CS').className).toContain('border-t')
  })

  it('stops offering another lantern at the cap', () => {
    const many = Array.from({ length: 16 }, (_, i): PatchPlacementInput => ({
      uuid: `u-${i}`,
      label: null,
      riggingUuid: null,
      stageX: i,
      stageY: 0,
      stageZ: null,
      baseYawDeg: null,
      basePitchDeg: null,
    }))
    render(<Harness initial={many} onValue={vi.fn()} />)
    expect((screen.getByRole('button', { name: /Lantern/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('ExtraPlacementsFields — the sides of a variable-length run', () => {
  function SegmentHarness({ initial, onValue }: { initial: PatchPlacementInput[]; onValue: (v: PatchPlacementInput[]) => void }) {
    const [value, setValue] = useState(initial)
    return (
      <ExtraPlacementsFields
        projectId={1}
        primary={primary}
        value={value}
        onChange={(next) => {
          setValue(next)
          onValue(next)
        }}
        segmentLength={{ fixtureM: 10 }}
      />
    )
  }

  it("names them as sides, and gives each a length that falls back to the fixture's", () => {
    const onValue = vi.fn()
    render(<SegmentHarness initial={[]} onValue={onValue} />)
    expect(screen.getByText('Other sides of this run')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Side/ }))

    const length = screen.getByLabelText('Length') as HTMLInputElement
    expect(length.value).toBe('')
    expect(length.placeholder).toBe("10 m (fixture's)")

    fireEvent.change(length, { target: { value: '6.5' } })
    expect(onValue.mock.lastCall![0][0].lengthM).toBe(6.5)
    fireEvent.change(length, { target: { value: '' } })
    expect(onValue.mock.lastCall![0][0].lengthM).toBeNull()

    fireEvent.change(length, { target: { value: '250' } })
    expect(screen.getByText(/Between 0.01 and 100 m/)).toBeTruthy()
  })

  it('offers no length for a fixed-length fixture', () => {
    render(<Harness initial={[]} onValue={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Lantern/ }))
    expect(screen.queryByLabelText('Length')).toBeNull()
  })
})

describe('ExtraPlacementsFields — a lantern of its own (stage-view plan session 7)', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  const placed: PatchPlacementInput = {
    label: 'SR',
    riggingUuid: 'rig-lx1',
    stageX: 3,
    stageY: 0,
    stageZ: -0.4,
    baseYawDeg: -10,
    basePitchDeg: 45,
  }
  const lantern = { lanterns, kind: 'PROFILE' as const, fixtureLantern: lanterns.byId.get('s4-19')! }

  it('draws a lantern picker and a focus per entry only for a type hung with a lantern', () => {
    render(<Harness initial={[placed]} onValue={() => {}} />)
    expect(screen.queryByRole('button', { name: 'Focus SR' })).toBeNull()
    cleanup()
    render(<Harness initial={[placed]} onValue={() => {}} lantern={lantern} />)
    expect(screen.getByRole('button', { name: 'Focus SR' })).toBeTruthy()
    // Naming none, the entry is the fixture's own lantern.
    expect(screen.getByText(/Same as the fixture/)).toBeTruthy()
  })

  it('focuses the entry on its own, keeping everything else it holds', () => {
    const onValue = vi.fn()
    render(<Harness initial={[placed]} onValue={onValue} lantern={lantern} />)
    fireEvent.click(screen.getByRole('button', { name: 'Focus SR' }))
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Bottom shutters depth' }), { key: 'ArrowRight' })
    const [next] = onValue.mock.lastCall![0] as PatchPlacementInput[]
    expect(next).toMatchObject({ label: 'SR', stageX: 3, basePitchDeg: 45 })
    expect(next.shutters).toEqual([
      { depth: 0, angleDeg: 0 },
      { depth: 0.01, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
    ])
  })
})
