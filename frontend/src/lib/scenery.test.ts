import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../api/stageElementApi'
import { parseSceneryFrame, type LiveScenery } from '../api/sceneryApi'
import { beamReach, elementColliders, type BeamHit } from '../components/stage3d/scene/beamReach'
import { buildElement } from '../components/stage3d/scene/builders'
import { pleatShape } from '../components/stage3d/scene/pleat'
import { elementBaseZ } from '../components/stage3d/scene/sceneParts'
import {
  describeCueChange,
  describeSceneryState,
  easeSceneryT,
  previewScenery,
  sceneryAt,
  sceneryChoices,
  sceneryElements,
  sceneryKeysOf,
  sceneryLandsAt,
  sceneryForSource,
  isSceneryPickable,
  movesWithEntries,
  plansScenery,
  type SceneryOverlayCache,
} from './scenery'
import { parseElementScenery } from '../api/stageElementApi'

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
      const out: BeamHit = { t: 0, nx: 0, ny: 0, nz: 0, skin: 0, collider: null }
      // A beam straight upstage through the middle of the opening, a metre up.
      return beamReach(0, 1, 5, 0, 0, -1, colliders, 40, out) ? out.t : null
    }
    expect(reach(live({ tabs: { state: { open: 1 } } }))).toBeNull()
    expect(reach(live({ tabs: { state: { open: 0 } } }))).toBeCloseTo(5.4 - pleatShape(tabs).amplitudeM, 9)
  })

  it('offers each kind its states, and names a change the way the card reads it', () => {
    expect(sceneryChoices(tabs).map((c) => c.label)).toEqual(['Closed', 'Half open', 'Drawn', 'Shown', 'Hidden'])
    expect(sceneryChoices(moon).map((c) => c.id)).toEqual(['trim:in', 'trim:out', 'visible:true', 'visible:false'])
    expect(describeSceneryState(tabs, { open: 0.3 })).toBe('open 30%')
    expect(describeCueChange(moon, { trimM: 7 }, 3000)).toBe('trim · out · 3 s')
    expect(describeCueChange(tabs, { open: 0 }, null)).toBe('closed · with the cue')
  })

  it("starts a Next GO preview's moves when it arrives", () => {
    const scenery = previewScenery([{ elementUuid: 'tabs', state: { open: 1 }, from: { open: 0 }, durationMs: 4000 }], 2, 500)
    expect(scenery.entries.tabs).toMatchObject({ startedAtMs: 500, durationMs: 4000 })
  })
})

describe('Blind on stage: the staged scenery, per vis source (scenery-programmer plan D12)', () => {
  const live: LiveScenery = parseSceneryFrame(
    {
      projectId: 1,
      elements: [{ elementUuid: 'moon', state: { trimM: 7 }, from: { trimM: 7 }, elapsedMs: 0, durationMs: 0, source: { kind: 'base' } }],
      staged: [{ elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 7 }, elapsedMs: 0, durationMs: 4000 }],
    },
    1000,
  )

  it('Output draws live; Output + Programmer and Programmer draw the staged move', () => {
    expect(sceneryForSource(live, 'output').entries.moon.state.trimM).toBe(7)
    expect(sceneryForSource(live, 'outputProgrammer').entries.moon.state.trimM).toBe(3)
    expect(sceneryForSource(live, 'programmer').entries.moon.state.trimM).toBe(3)
    expect(sceneryForSource(live, 'programmer').entries.moon.durationMs).toBe(4000)
  })

  it('keeps what nothing stages, and hands back the frame itself when nothing is staged', () => {
    const both = parseSceneryFrame(
      {
        projectId: 1,
        elements: [
          { elementUuid: 'moon', state: { trimM: 7 }, from: { trimM: 7 } },
          { elementUuid: 'tabs', state: { open: 0 }, from: { open: 0 } },
        ],
        staged: [{ elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 7 } }],
      },
      0,
    )
    expect(sceneryForSource(both, 'programmer').entries.tabs.state.open).toBe(0)
    const unstaged = parseSceneryFrame({ projectId: 1, elements: [{ elementUuid: 'moon', state: { trimM: 7 }, from: { trimM: 7 } }] }, 0)
    for (const source of ['output', 'outputProgrammer', 'programmer'] as const) {
      expect(sceneryForSource(unstaged, source)).toBe(unstaged)
    }
    expect(sceneryForSource(live, 'output')).toBe(live)
  })
})

describe('which pieces a click opens (D11)', () => {
  it('a drawn drape, a flown piece and the Set layer; never a room, the seats or the fixed venue', () => {
    expect(isSceneryPickable(tabs)).toBe(true)
    expect(isSceneryPickable(element({ kind: 'OBJECT', layer: 'VENUE', params: { flies: true } }))).toBe(true)
    expect(isSceneryPickable(element({ kind: 'DRAPE', layer: 'VENUE', params: { operation: 'FLY' } }))).toBe(true)
    expect(isSceneryPickable(element({ kind: 'FLAT', layer: 'SET' }))).toBe(true)
    expect(isSceneryPickable(element({ kind: 'DRAPE', layer: 'VENUE', params: { operation: 'DEAD' } }))).toBe(false)
    expect(isSceneryPickable(element({ kind: 'PROSCENIUM', layer: 'VENUE' }))).toBe(false)
    expect(isSceneryPickable(element({ kind: 'ROOM', layer: 'SET' }))).toBe(false)
    expect(isSceneryPickable(element({ kind: 'SEATING', layer: 'SET' }))).toBe(false)
  })
})

describe("Moves with: the element's read as the list says it (D11)", () => {
  const moon = element({ uuid: 'moon', name: 'Moon', positionZ: 3, params: { flies: true, states: { trimM: 7 } } })
  const read = parseElementScenery({
    cues: [
      { stackId: 1, cueId: 12, label: '2', state: { trimM: 3 }, transitionMs: 4000 },
      { stackId: 1, cueId: 14, label: 'Blackout', state: { visible: false }, transitionMs: 0 },
    ],
    sets: [{ stackId: 1, name: 'Main', state: { trimM: 7 } }],
    looks: [{ lookId: 5, name: 'Night', state: { trimM: 3, visible: true } }],
    bogus: true,
  })

  it('names each owner and what it does, cues first with their clocks', () => {
    expect(movesWithEntries(moon, read, () => 'Main').map((e) => [e.kind, e.id, e.owner, e.what])).toEqual([
      ['cue', 12, 'Q2', 'trim · in · 4 s'],
      ['cue', 14, 'Blackout', 'hidden · snap'],
      ['set', 1, "Main's set", 'trim · out'],
      ['look', 5, 'Night', 'trim · in · shown'],
    ])
  })

  it("names a cue's stack only where the cues span more than one", () => {
    const twoStacks = { ...read, cues: [...read.cues, { stackId: 2, cueId: 20, label: '1', state: { trimM: 7 }, transitionMs: null }] }
    const names = new Map([[1, 'Act 1'], [2, 'Act 2']])
    expect(movesWithEntries(moon, twoStacks, (id) => names.get(id)).filter((e) => e.kind === 'cue').map((e) => e.owner)).toEqual([
      'Act 1 · Q2',
      'Act 1 · Blackout',
      'Act 2 · Q1',
    ])
  })

  it('parses only well-formed entries off the wire', () => {
    const parsed = parseElementScenery({ cues: [{ cueId: 1 }, null, { stackId: 1, cueId: 2, label: 'x', state: { open: 'no' } }], sets: 'x' })
    expect(parsed.cues).toEqual([{ stackId: 1, cueId: 2, label: 'x', state: {}, transitionMs: null }])
    expect(parsed.sets).toEqual([])
    expect(parsed.looks).toEqual([])
  })
})

describe('the Positions plan draws the scenery that moves the light (D16)', () => {
  it('every drape and the Set layer, not the room, the proscenium or the seats', () => {
    expect(plansScenery(element({ kind: 'DRAPE', layer: 'VENUE' }))).toBe(true)
    expect(plansScenery(element({ kind: 'OBJECT', layer: 'SET' }))).toBe(true)
    expect(plansScenery(element({ kind: 'ROOM', layer: 'VENUE' }))).toBe(false)
    expect(plansScenery(element({ kind: 'PROSCENIUM', layer: 'VENUE' }))).toBe(false)
    expect(plansScenery(element({ kind: 'SEATING', layer: 'VENUE' }))).toBe(false)
  })
})
