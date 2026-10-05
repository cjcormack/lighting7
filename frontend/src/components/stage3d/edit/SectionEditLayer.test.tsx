// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import type { FixturePatch } from '../../../api/patchApi'
import type { RiggingDto } from '../../../api/riggingApi'
import type { StageRegionDto } from '../../../api/stageRegionApi'
import type { DrawnPoint } from '../../../hooks/useProjectedPatches'
import type { Selection } from '../../stage/stageEditing'
import { DEFAULT_VIEW_FLAGS } from '../useStageView'
import { createSectionViewStore, type SectionControls } from './sectionView'
import type { SnapGrid } from './useSnapGrid'

// The layer reads the rig's projected points through this hook; the test hands them in.
let points: DrawnPoint[] = []
vi.mock('../../../hooks/useProjectedPatches', () => ({
  useProjectedPatches: () => ({ points, extraPoints: [], extent: { hMin: 0, hMax: 0, vMin: 0, vMax: 0 }, dims: {} }),
}))

import { SectionEditLayer } from './SectionEditLayer'

/**
 * Editing on a section (stage-view plan session 5): the SVG plot's gestures over the 3D scene. jsdom
 * lays nothing out, so the layer's box is at 0,0 with no size — a pointer's position is its offset
 * from the canvas's centre — and the section is centred on 0,0 at 100 pixels a metre: a client
 * pixel (x, y) is the point (x / 100, y / 100) on the section.
 */

const SNAP: SnapGrid = {
  step: 0.25,
  setStep: () => {},
  snapOn: true,
  setSnapOn: () => {},
  active: true,
  activeRef: { current: true },
  snapValue: (v) => Math.round(v / 0.25) * 0.25,
}

function point(key: string, world: { x: number; y: number; z: number }, extra: Partial<FixturePatch> = {}): DrawnPoint {
  return {
    patch: { id: key.length, key, riggingUuid: null, stageX: world.x, stageY: world.y, stageZ: world.z, ...extra } as FixturePatch,
    world,
    // Plan: h = X, v = −Y.
    screen: { h: world.x, v: -world.y },
    leftPct: 0,
    topPct: 0,
  }
}

const bar = { uuid: 'lx1', name: 'LX1', positionX: 0, positionY: 4, positionZ: 5, yawDeg: 0, pitchDeg: 0, rollDeg: 0, lengthM: 6 } as RiggingDto
const deck = { id: 3, uuid: 'deck', name: 'Deck', centerX: 0, centerY: 2, centerZ: 0.5, widthM: 4, depthM: 2, heightM: 0.5, yawDeg: 0 } as StageRegionDto

const controls: SectionControls = { panBy: vi.fn(), zoomAt: vi.fn(), fit: vi.fn() }

function draw(over: Partial<React.ComponentProps<typeof SectionEditLayer>> = {}) {
  const viewStore = createSectionViewStore()
  viewStore.set({ h: 0, v: 0, zoom: 100, width: 800, height: 600 })
  const props = {
    projectId: 1,
    camera: 'plan' as const,
    viewStore,
    controls: () => controls,
    selection: null as Selection,
    view: DEFAULT_VIEW_FLAGS,
    snap: SNAP,
    riggings: [bar],
    regions: [deck],
    elements: [],
    placing: false,
    placementDefault: { x: 0, y: 4, z: 0 },
    onSelectionChange: vi.fn(),
    onMarqueeSelect: vi.fn(),
    onPlacementClick: vi.fn(),
    onPatchPlacementChange: vi.fn(),
    onRegionPositionChange: vi.fn(),
    onRiggingPositionChange: vi.fn(),
    onElementPositionChange: vi.fn(),
    ...over,
  }
  const view = render(<SectionEditLayer {...props} />)
  const svg = view.container.querySelector('svg[class*="touch-none"]') as SVGSVGElement
  return { ...props, svg, container: view.container, unmount: view.unmount }
}

/** A callback's calls: every one `draw` hands the layer is a `vi.fn()`. */
const calls = (fn: unknown) => (fn as Mock).mock.calls

const at = (h: number, v: number) => ({ clientX: h * 100, clientY: v * 100, pointerId: 1, button: 0 })

/** A press on the layer, a drag across the window to (h, v) in steps, and the release there. */
function drag(target: Element, from: [number, number], to: [number, number], mods: { shiftKey?: boolean; metaKey?: boolean } = {}) {
  fireEvent.pointerDown(target, { ...at(...from), ...mods })
  for (let i = 1; i <= 4; i++) {
    const h = from[0] + ((to[0] - from[0]) * i) / 4
    const v = from[1] + ((to[1] - from[1]) * i) / 4
    // Marquee and pan moves are the layer's own (pointer capture); a body's drag is on the window.
    fireEvent.pointerMove(target, { ...at(h, v), ...mods })
    fireEvent.pointerMove(window, { ...at(h, v), ...mods })
  }
  fireEvent.pointerUp(target, { ...at(...to), ...mods })
  fireEvent.pointerUp(window, { ...at(...to), ...mods })
}

beforeEach(() => {
  points = [point('par-1', { x: 0, y: 0, z: 0 }), point('par-2', { x: 1, y: -0.5, z: 0 }), point('par-3', { x: 3, y: 2, z: 0 })]
  vi.clearAllMocks()
})
afterEach(cleanup)

describe('SectionEditLayer — the plot’s gestures on a section', () => {
  it('a ⇧-drag on empty space is a marquee: the fixtures inside it join the selection', () => {
    const { svg, onMarqueeSelect, onSelectionChange } = draw()
    drag(svg, [-0.5, -0.5], [1.5, 1])
    expect(onMarqueeSelect).not.toHaveBeenCalled()
    expect(controls.panBy).toHaveBeenCalled()

    drag(svg, [-0.5, -0.5], [1.5, 1], { shiftKey: true })
    expect(onMarqueeSelect).toHaveBeenCalledWith(
      [
        { kind: 'patch', patchKey: 'par-1' },
        { kind: 'patch', patchKey: 'par-2' },
      ],
      'add',
    )
    // ⌘ toggles a click, but a marquee only ever adds — the plot's rule.
    drag(svg, [-0.5, -0.5], [1.5, 1], { metaKey: true })
    expect(onMarqueeSelect).toHaveBeenLastCalledWith(expect.any(Array), 'add')
    expect(onSelectionChange).not.toHaveBeenCalled()
  })

  it('a click selects what is under it, with ⇧ adding; a click on nothing clears', () => {
    const { svg, onSelectionChange } = draw()
    fireEvent.pointerDown(svg, at(3, -2))
    fireEvent.pointerUp(window, at(3, -2))
    expect(onSelectionChange).toHaveBeenLastCalledWith({ kind: 'patch', patchKey: 'par-3' }, 'replace')
    fireEvent.pointerDown(svg, { ...at(2, -4), shiftKey: true })
    fireEvent.pointerUp(window, { ...at(2, -4), shiftKey: true })
    expect(onSelectionChange).toHaveBeenLastCalledWith({ kind: 'rigging', uuid: 'lx1' }, 'add')
    fireEvent.pointerDown(svg, at(6, 3))
    fireEvent.pointerUp(svg, at(6, 3))
    expect(onSelectionChange).toHaveBeenLastCalledWith(null)
  })

  it('a selected free fixture drags on the grid, and lines up with another object on the way', () => {
    const { svg, onPatchPlacementChange } = draw({
      selection: { kind: 'patch', patchKey: 'par-3' },
      selectedKeys: new Set(['patch:par-3']),
    })
    drag(svg, [3, -2], [3.41, -2.13])
    expect(onPatchPlacementChange).toHaveBeenLastCalledWith(
      points[2]!.patch,
      { riggingUuid: null, stageX: 3.5, stageY: 2.25, stageZ: 0 },
      true,
    )
    // Within a few pixels of par-1's row, it snaps to the row rather than the grid.
    drag(svg, [3, -2], [2.08, 0.03])
    const [, next] = calls(onPatchPlacementChange).at(-1)!
    expect(next.stageX).toBe(2)
    expect(next.stageY).toBeCloseTo(0, 9)
  })

  it('a free fixture dropped on a bar is hung on it, and the bar lights as the drop target', () => {
    const { svg, container, onPatchPlacementChange } = draw({
      selection: { kind: 'patch', patchKey: 'par-3' },
      selectedKeys: new Set(['patch:par-3']),
    })
    fireEvent.pointerDown(svg, at(3, -2))
    fireEvent.pointerMove(window, at(2, -3))
    fireEvent.pointerMove(window, at(1, -3.96))
    expect(container.querySelector('[data-drop-target="lx1"]')).not.toBeNull()
    fireEvent.pointerUp(window, at(1, -3.96))
    const [, next, settled] = calls(onPatchPlacementChange).at(-1)!
    expect(settled).toBe(true)
    expect(next.riggingUuid).toBe('lx1')
    expect(container.querySelector('[data-drop-target]')).toBeNull()
  })

  it('a fixture on a bar slides along it, snapped in the bar’s own metres and stopped at its ends', () => {
    const hung = point('hung', { x: 0, y: 4, z: 4.7 }, { riggingUuid: 'lx1', stageX: 0, stageY: 0, stageZ: -0.3 })
    points = [hung]
    const { svg, onPatchPlacementChange } = draw({
      selection: { kind: 'patch', patchKey: 'hung' },
      selectedKeys: new Set(['patch:hung']),
    })
    drag(svg, [0, -4], [1.1, -3.5])
    expect(onPatchPlacementChange).toHaveBeenLastCalledWith(
      hung.patch,
      { riggingUuid: 'lx1', stageX: 1, stageY: 0, stageZ: -0.3 },
      true,
    )
    drag(svg, [0, -4], [9, -4])
    expect(calls(onPatchPlacementChange).at(-1)![1].stageX).toBe(3)
  })

  it('a drag cut short — the layer unmounting, or a second pointer — still settles where it got to', () => {
    const selected = { selection: { kind: 'patch', patchKey: 'par-3' } as Selection, selectedKeys: new Set(['patch:par-3']) }
    const first = draw(selected)
    fireEvent.pointerDown(first.svg, at(3, -2))
    // The first move past the threshold promotes the press; the next one drags.
    fireEvent.pointerMove(window, at(3.3, -2))
    fireEvent.pointerMove(window, at(3.6, -2))
    first.unmount()
    expect(first.onPatchPlacementChange).toHaveBeenLastCalledWith(
      points[2]!.patch,
      { riggingUuid: null, stageX: 3.5, stageY: 2, stageZ: 0 },
      true,
    )

    const second = draw(selected)
    fireEvent.pointerDown(second.svg, at(3, -2))
    fireEvent.pointerMove(window, at(3.3, -2))
    fireEvent.pointerMove(window, at(3.6, -2))
    // A second finger starts a drag of its own before the first lifts: the first settles first.
    fireEvent.pointerDown(second.svg, { ...at(3, -2), pointerId: 2 })
    fireEvent.pointerMove(window, { ...at(3.1, -2.4), pointerId: 2 })
    const settles = calls(second.onPatchPlacementChange).filter(([, , settled]) => settled)
    expect(settles).toHaveLength(1)
  })

  it('an armed placement lands where the click is, snapped, taking the axis the section cannot see', () => {
    const { svg, onPlacementClick, onSelectionChange } = draw({ placing: true })
    fireEvent.pointerDown(svg, at(1.12, -0.37))
    fireEvent.pointerUp(svg, at(1.12, -0.37))
    expect(onPlacementClick).toHaveBeenCalledWith({ x: 1, y: 0.25, z: 0 })
    // Placing takes every press, even one on a fixture.
    fireEvent.pointerDown(svg, at(0.02, -0.05))
    fireEvent.pointerUp(svg, at(0.02, -0.05))
    expect(onPlacementClick).toHaveBeenLastCalledWith({ x: 0, y: 0, z: 0 })
    expect(onSelectionChange).not.toHaveBeenCalled()
  })

  it('in Front a selected region has a top and a floor handle, and the top raises the deck and keeps the floor', () => {
    const { container, onRegionPositionChange } = draw({
      camera: 'front',
      selection: { kind: 'region', uuid: 'deck' },
      selectedKeys: new Set(['region:deck']),
    })
    const handles = [...container.querySelectorAll('rect')].filter((r) => (r as SVGRectElement).style.cursor === 'ns-resize')
    expect(handles).toHaveLength(2)
    // Front: v = −Z. The top handle is at the deck, Z 0.5.
    fireEvent.pointerDown(handles[0]!, at(0, -0.5))
    fireEvent.pointerMove(window, at(0.4, -1.02))
    fireEvent.pointerUp(window, at(0.4, -1.02))
    expect(onRegionPositionChange).toHaveBeenLastCalledWith(
      deck,
      { centerX: 0, centerY: 2, centerZ: 1, yawDeg: 0, heightM: 1 },
      true,
    )
  })

  it('in Plan a selected region has corner and turn handles, and none for its height', () => {
    const { container } = draw({ selection: { kind: 'region', uuid: 'deck' }, selectedKeys: new Set(['region:deck']) })
    const rects = [...container.querySelectorAll('rect')] as SVGRectElement[]
    expect(rects.filter((r) => r.style.cursor === 'nwse-resize')).toHaveLength(4)
    expect(rects.filter((r) => r.style.cursor === 'ns-resize')).toHaveLength(0)
  })

  it('pans from an object that is not selected, and selects it on a click', () => {
    const { svg, onSelectionChange, onRegionPositionChange } = draw()
    drag(svg, [-1.5, -2], [-1, -2])
    expect(controls.panBy).toHaveBeenCalled()
    expect(onRegionPositionChange).not.toHaveBeenCalled()
    expect(onSelectionChange).not.toHaveBeenCalled()
    fireEvent.pointerDown(svg, at(-1.5, -2))
    fireEvent.pointerUp(window, at(-1.5, -2))
    expect(onSelectionChange).toHaveBeenLastCalledWith({ kind: 'region', uuid: 'deck' }, 'replace')
  })

  it('pans and zooms the section camera, never itself', () => {
    const { svg } = draw()
    fireEvent.pointerDown(svg, at(6, 3))
    fireEvent.pointerMove(svg, at(6.5, 3))
    fireEvent.pointerUp(svg, at(6.5, 3))
    expect(controls.panBy).toHaveBeenCalledWith(50, 0)
    act(() => {
      svg.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, clientX: 10, clientY: 20, bubbles: true, cancelable: true }))
    })
    expect(controls.zoomAt).toHaveBeenCalledWith(1.15, 10, 20)
  })

  it('draws nothing until the section camera has said where it is', () => {
    const viewStore = createSectionViewStore()
    const { container } = render(
      <SectionEditLayer
        projectId={1}
        camera="plan"
        viewStore={viewStore}
        controls={() => controls}
        selection={null}
        view={DEFAULT_VIEW_FLAGS}
        snap={SNAP}
        riggings={[]}
        regions={[]}
        elements={[]}
        placing={false}
        placementDefault={{ x: 0, y: 0, z: 0 }}
        onSelectionChange={() => {}}
      />,
    )
    expect(container.querySelector('svg')).toBeNull()
    act(() => viewStore.set({ h: 0, v: 0, zoom: 50, width: 400, height: 300 }))
    expect(container.querySelector('[data-section-edit="plan"]')).not.toBeNull()
  })
})
