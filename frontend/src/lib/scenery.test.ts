import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../api/stageElementApi'
import { parseSceneryFrame, type LiveScenery } from '../api/sceneryApi'
import { beamReach, elementColliders, type BeamHit } from '../components/stage3d/scene/beamReach'
import { buildElement } from '../components/stage3d/scene/builders'
import { PLEAT_DEPTH_M } from '../components/stage3d/scene/pleat'
import { elementBaseZ } from '../components/stage3d/scene/sceneParts'
import {
  choiceOf,
  describeCueChange,
  describeSceneryState,
  easeSceneryT,
  previewScenery,
  sceneryAt,
  sceneryChoices,
  sceneryElements,
  sceneryKeysOf,
  sceneryLandsAt,
  type SceneryOverlayCache,
} from './scenery'

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const tabs = element({
  uuid: 'tabs', name: 'House tabs', kind: 'DRAPE', layer: 'VENUE', positionY: 0.4, widthM: 8, depthM: 0.1, heightM: 5,
  params: { role: 'TABS', operation: 'DRAW', states: { open: 1 } },
})
const moon = element({
  uuid: 'moon', name: 'Moon', positionZ: 3, widthM: 1, depthM: 0.05, heightM: 1,
  params: { shape: 'DISC', flies: true, states: { trimM: 7 } },
})

function live(entries: Record<string, { state: object; from?: object; startedAtMs?: number; durationMs?: number }>): LiveScenery {
  return {
    projectId: 1,
    entries: Object.fromEntries(
      Object.entries(entries).map(([uuid, e]) => [
        uuid,
        { elementUuid: uuid, state: e.state, from: e.from ?? e.state, startedAtMs: e.startedAtMs ?? 0, durationMs: e.durationMs ?? 0 },
      ]),
    ),
  }
}

describe('scenery, drawn (stage-view plan session 8)', () => {
  it('takes the states its kind takes — the desk\'s sceneryKeysOf', () => {
    expect(sceneryKeysOf(tabs)).toEqual(['visible', 'open'])
    expect(sceneryKeysOf(moon)).toEqual(['visible', 'trimM'])
    expect(sceneryKeysOf(element({ kind: 'FLAT' }))).toEqual(['visible'])
  })

  it('eases a move sine in-out, shows a piece at once and hides one at the end', () => {
    expect(easeSceneryT(0)).toBe(0)
    expect(easeSceneryT(0.5)).toBeCloseTo(0.5, 9)
    expect(easeSceneryT(1)).toBe(1)
    const move = { state: { open: 1, visible: false }, from: { open: 0, visible: true }, startedAtMs: 1000, durationMs: 4000 }
    const mid = sceneryAt(move, 3000)
    expect(mid.open).toBeCloseTo(0.5, 9)
    expect(mid.visible).toBe(true)
    expect(sceneryAt(move, 5000)).toEqual({ open: 1, visible: false })
    expect(sceneryAt({ ...move, state: { visible: true }, from: { visible: false } }, 1001).visible).toBe(true)
  })

  it('anchors a frame at receipt, so elapsed time is not replayed', () => {
    const frame = parseSceneryFrame(
      { type: 'scenery.state', projectId: 3, elements: [{ elementUuid: 'tabs', state: { open: 1 }, from: { open: 0 }, startedAt: 'x', elapsedMs: 1500, durationMs: 4000 }] },
      10_000,
    )
    expect(frame.projectId).toBe(3)
    expect(frame.entries.tabs.startedAtMs).toBe(8500)
    expect(sceneryLandsAt(frame)).toBe(12_500)
  })

  it('lays the live state over the element the builders read, and keeps unmoved elements the same objects', () => {
    const cache: SceneryOverlayCache = new WeakMap()
    const scenery = live({ moon: { state: { trimM: 3 } }, tabs: { state: { open: 1 } } })
    const [t, m] = sceneryElements([tabs, moon], scenery, 0, cache)
    expect(t).toBe(tabs) // the tabs' base is drawn open: nothing to overlay
    expect(elementBaseZ(m)).toBe(3) // the moon flew in
    const again = sceneryElements([tabs, moon], scenery, 50, cache)
    expect(again[1]).toBe(m) // same drawn state, same object: the build cache holds
  })

  it('stops a beam at closed tabs, and lets it through drawn ones', () => {
    const cache: SceneryOverlayCache = new WeakMap()
    const reach = (scenery: LiveScenery) => {
      const [drawn] = sceneryElements([tabs], scenery, 0, cache)
      const colliders = elementColliders(drawn, buildElement(drawn))
      const out: BeamHit = { t: 0, nx: 0, ny: 0, nz: 0, skin: 0 }
      // A beam straight upstage through the middle of the opening, a metre up.
      return beamReach(0, 1, 5, 0, 0, -1, colliders, 40, out) ? out.t : null
    }
    expect(reach(live({ tabs: { state: { open: 1 } } }))).toBeNull()
    expect(reach(live({ tabs: { state: { open: 0 } } }))).toBeCloseTo(5.4 - PLEAT_DEPTH_M / 2, 9)
  })

  it('offers each kind its states, and names a change the way the card reads it', () => {
    expect(sceneryChoices(tabs).map((c) => c.label)).toEqual(['Closed', 'Half open', 'Drawn', 'Shown', 'Hidden'])
    expect(sceneryChoices(moon).map((c) => c.id)).toEqual(['trim:in', 'trim:out', 'visible:true', 'visible:false'])
    expect(choiceOf(moon, { trimM: 7 })?.id).toBe('trim:out')
    expect(choiceOf(tabs, { open: 0.3 })).toBeNull()
    expect(describeSceneryState(tabs, { open: 0.3 })).toBe('open 30%')
    expect(describeCueChange(moon, { trimM: 7 }, 3000)).toBe('trim · out · 3 s')
    expect(describeCueChange(tabs, { open: 0 }, null)).toBe('closed · with the cue')
  })

  it("starts a Next GO preview's moves when it arrives", () => {
    const scenery = previewScenery([{ elementUuid: 'tabs', state: { open: 1 }, from: { open: 0 }, durationMs: 4000 }], 2, 500)
    expect(scenery.entries.tabs).toMatchObject({ startedAtMs: 500, durationMs: 4000 })
  })
})
