// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { Object3D, OrthographicCamera } from 'three'
import {
  StageLabelStore,
  labelPriority,
  overlapsAny,
  toStageLabelMode,
  wantsLabel,
  type StageLabelKind,
} from './stageLabels'

describe('label mode', () => {
  it('reads the boolean a desk stored before the label layer', () => {
    expect(toStageLabelMode(true)).toBe('positions')
    expect(toStageLabelMode(false)).toBe('none')
    expect(toStageLabelMode('all')).toBe('all')
    expect(toStageLabelMode('bogus')).toBe('positions')
    expect(toStageLabelMode(undefined)).toBe('positions')
  })

  it('shows positions always, fixtures only when emphasised, until All', () => {
    expect(wantsLabel('positions', 'position', false)).toBe(true)
    expect(wantsLabel('positions', 'fixture', false)).toBe(false)
    expect(wantsLabel('positions', 'fixture', true)).toBe(true)
    expect(wantsLabel('all', 'fixture', false)).toBe(true)
    // None is nothing — not even the selected fixture.
    expect(wantsLabel('none', 'position', false)).toBe(false)
    expect(wantsLabel('none', 'fixture', true)).toBe(false)
  })

  it('ranks the hovered or selected label first, then positions, then fixtures', () => {
    expect(labelPriority('fixture', true)).toBeGreaterThan(labelPriority('position', false))
    expect(labelPriority('position', false)).toBeGreaterThan(labelPriority('fixture', false))
  })
})

describe('overlapsAny', () => {
  it('counts the gap between two labels as a collision', () => {
    const placed = [{ x: 0, y: 0, w: 40, h: 16 }]
    expect(overlapsAny({ x: 41, y: 0, w: 40, h: 16 }, placed)).toBe(true)
    expect(overlapsAny({ x: 43, y: 0, w: 40, h: 16 }, placed)).toBe(false)
    expect(overlapsAny({ x: 0, y: 18, w: 40, h: 16 }, placed)).toBe(false)
  })

  it('reads only the first `count` rects — the store reuses its array across frames', () => {
    const placed = [{ x: 0, y: 0, w: 40, h: 16 }]
    expect(overlapsAny({ x: 0, y: 0, w: 10, h: 10 }, placed, 0)).toBe(false)
  })
})

/** A camera looking straight down -Z at a 100 × 100 px screen spanning x, y ∈ [-50, 50]. */
function camera(): OrthographicCamera {
  const cam = new OrthographicCamera(-50, 50, 50, -50, 0.1, 100)
  cam.position.set(0, 0, 10)
  cam.updateMatrixWorld()
  return cam
}

function anchorAt(x: number, y: number): Object3D {
  const o = new Object3D()
  o.position.set(x, y, 0)
  return o
}

function add(store: StageLabelStore, kind: StageLabelKind, text: string, x: number, y: number, emphasised = false) {
  const e = store.add(kind, text, emphasised)
  e.anchor = anchorAt(x, y)
  // jsdom lays nothing out; give every label the same measured box.
  e.w = 30
  e.h = 12
  return e
}

describe('StageLabelStore.layout', () => {
  it('declutters: of two labels on one spot, the higher rank is the one shown', () => {
    const store = new StageLabelStore()
    store.setMode('all')
    const fixture = add(store, 'fixture', 'Par 1', 0, 0)
    const rig = add(store, 'position', 'LX1', 0, 0)
    store.layout(camera(), 100, 100)
    expect(rig.shown).toBe(true)
    expect(fixture.shown).toBe(false)
  })

  it('shows both when they are apart', () => {
    const store = new StageLabelStore()
    store.setMode('all')
    const a = add(store, 'fixture', 'Par 1', -30, 0)
    const b = add(store, 'fixture', 'Par 2', 30, 0)
    store.layout(camera(), 100, 100)
    expect(a.shown && b.shown).toBe(true)
    // Centred on the anchor: x = -30 is 20px from the left, less half the 30px box.
    expect(a.el.style.transform).toBe('translate(5px, 44px)')
  })

  it('hides a fixture under Positions until it is emphasised, and restyles it in place', () => {
    const store = new StageLabelStore()
    const par = add(store, 'fixture', 'Par 1', 0, 0)
    store.layout(camera(), 100, 100)
    expect(par.shown).toBe(false)
    const el = par.el
    store.update(par, 'fixture', 'Par 1', true)
    par.w = 30
    par.h = 12
    store.layout(camera(), 100, 100)
    expect(par.shown).toBe(true)
    expect(par.el).toBe(el)
  })

  it('hides a label whose anchor is off screen or behind the camera', () => {
    const store = new StageLabelStore()
    const off = add(store, 'position', 'Far', 500, 0)
    const behind = add(store, 'position', 'Behind', 0, 0)
    behind.anchor!.position.z = 50
    store.layout(camera(), 100, 100)
    expect(off.shown).toBe(false)
    expect(behind.shown).toBe(false)
  })

  it('shows nothing under None, and moves the elements into a container set later', () => {
    const store = new StageLabelStore()
    const rig = add(store, 'position', 'LX1', 0, 0)
    store.setMode('none')
    store.layout(camera(), 100, 100)
    expect(rig.shown).toBe(false)
    const host = document.createElement('div')
    store.setContainer(host)
    expect(host.contains(rig.el)).toBe(true)
    store.remove(rig)
    expect(host.contains(rig.el)).toBe(false)
    expect(store.size).toBe(0)
  })

  it('asks the canvas for a frame when a label changes', () => {
    const store = new StageLabelStore()
    let frames = 0
    store.invalidate = () => {
      frames++
    }
    const e = store.add('fixture', 'Par 1', false)
    store.update(e, 'fixture', 'Par 1', false)
    expect(frames).toBe(1)
    store.update(e, 'fixture', 'Par 1', true)
    store.setMode('all')
    expect(frames).toBe(3)
  })
})
