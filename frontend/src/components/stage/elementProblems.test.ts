import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../api/stageElementApi'
import { elementProblems, fileProblems, problemPath } from './elementProblems'
import { draftOf, elementUpdate, emptyNumbers, withKindParam, withState } from './elementDraft'

describe('elementProblems — the desk’s refusal, filed by field', () => {
  // Word for word what `validateStageElement` answers over REST, joined as the route joins them.
  const refusal = [
    'widthM must be greater than 0: every kind but SEATING has a size',
    "params.openings[1] runs past the flat's end (fromM + widthM = 3.2, widthM 2.4)",
    'params.openings[1].heightM must be between 0.05 and 100.0 metres',
    "params.states.open is a drawn drape's (a DRAPE with operation DRAW)",
    'params: railHeightM and railEdge go together — send both, or neither',
    "a SEATING element's size comes from its rows and seats: leave widthM, depthM and heightM out",
    "params: unknown field 'wobble' (known: openings, states)",
  ].join('; ')

  it('reads the field each problem leads with, or the first it names', () => {
    expect(elementProblems(refusal).map((p) => p.path)).toEqual([
      'widthM',
      'params.openings[1]',
      'params.openings[1].heightM',
      'params.states.open',
      'params.railHeightM',
      null,
      null,
    ])
    expect(problemPath('name must be 1–100 characters')).toBe('name')
    // Scrim plan session 1: a painted face and a fabric file under their own paths.
    expect(problemPath('params.paint.front names no stored image')).toBe('params.paint.front')
    expect(problemPath("params.paint.back must be an image's SHA-256: 64 hex characters")).toBe('params.paint.back')
    expect(problemPath('params.fabric must be one of CANVAS, MUSLIN, SHARKSTOOTH, BOBBINET')).toBe('params.fabric')
    expect(problemPath("params.paint is a drape's or a flat's (a DRAPE or a FLAT); this OBJECT takes none")).toBe('params.paint')
    expect(problemPath("params: unknown field 'x' — fabric is a drape's")).toBe('params.fabric')
    expect(problemPath('positionX must be between -500.0 and 500.0 metres')).toBe('positionX')
  })

  it('draws a problem beside its field without its path, and the rest at the top', () => {
    const shown = new Set(['widthM', 'params.openings[1]', 'params.openings[1].heightM'])
    const filed = fileProblems(elementProblems(refusal), shown)
    expect(filed.at('widthM')).toEqual(['Must be greater than 0: every kind but SEATING has a size'])
    expect(filed.at('params.openings[1]')).toEqual(["Runs past the flat's end (fromM + widthM = 3.2, widthM 2.4)"])
    expect(filed.at('params.openings[1].heightM')).toEqual(['Must be between 0.05 and 100.0 metres'])
    // Nothing is lost for want of a field: a path this form is not drawing goes to the top.
    expect(filed.general).toEqual([
      "params.states.open is a drawn drape's (a DRAPE with operation DRAW)",
      'params: railHeightM and railEdge go together — send both, or neither',
      "a SEATING element's size comes from its rows and seats: leave widthM, depthM and heightM out",
      "params: unknown field 'wobble' (known: openings, states)",
    ])
  })
})

describe('elementUpdate — what a save sends', () => {
  const flat: StageElementDto = {
    id: 7,
    uuid: 'f',
    name: 'Flat 1',
    kind: 'FLAT',
    layer: 'SET',
    positionX: 1,
    positionY: 7,
    positionZ: 0,
    yawDeg: 0,
    widthM: 2.4,
    depthM: 0.1,
    heightM: 2.4,
    finishColour: null,
    finishPattern: null,
    emissive: false,
    params: { openings: [{ kind: 'DOOR', fromM: 0.2, widthM: 0.9, heightM: 2.1 }] },
    hidden: false,
    sortOrder: 0,
  }

  it('sends nothing for an untouched form, whatever order params come back in', () => {
    const draft = draftOf({ ...flat, params: { openings: [{ widthM: 0.9, heightM: 2.1, kind: 'DOOR', fromM: 0.2 }] } })
    expect(elementUpdate(flat, draft)).toEqual({})
  })

  it('sends the fields that moved, and params whole where anything in them did', () => {
    const draft = draftOf(flat)
    draft.name = ' Door flat '
    draft.yawDeg = 15
    draft.finishColour = '#aabbcc'
    draft.params = withState(draft.params, 'visible', false)
    expect(elementUpdate(flat, draft)).toEqual({
      name: 'Door flat',
      yawDeg: 15,
      finishColour: '#aabbcc',
      params: { ...flat.params, states: { visible: false } },
    })
  })

  it('takes a state out again, and `states` with it when it is empty', () => {
    const draft = draftOf({ ...flat, params: { states: { visible: false } } })
    draft.params = withState(draft.params, 'visible', null)
    expect(draft.params).toEqual({})
  })
})

describe('empty numbers and stale states', () => {
  const drape = {
    id: 1, uuid: 'd', name: 'Tabs', kind: 'DRAPE', layer: 'VENUE', positionX: 0, positionY: 0.6, positionZ: 0,
    yawDeg: 0, widthM: 8, depthM: 0.3, heightM: 6, finishColour: null, finishPattern: null, emissive: false,
    params: { role: 'TABS', operation: 'DRAW', states: { open: 1, visible: false } }, hidden: false, sortOrder: 0,
  } as StageElementDto

  it('never writes an emptied field as 0, and names it', () => {
    const draft = draftOf(drape)
    draft.positionX = null
    draft.widthM = null
    expect(emptyNumbers('DRAPE', draft)).toEqual(['positionX', 'widthM'])
    expect(elementUpdate(drape, draft)).toEqual({})
    // A seating has no box: its sizes are not required.
    expect(emptyNumbers('SEATING', { ...draft, positionX: 1 })).toEqual([])
  })

  it('a drape that stops drawing loses its open, and one that flies keeps a trim only while it flies', () => {
    const dead = withKindParam('DRAPE', drape.params, 'operation', 'DEAD')
    expect(dead).toEqual({ role: 'TABS', operation: 'DEAD', states: { visible: false } })
    const flown = withKindParam('DRAPE', withState(withKindParam('DRAPE', drape.params, 'operation', 'FLY'), 'trimM', 6), 'role', 'BORDER')
    expect(flown.states).toEqual({ visible: false, trimM: 6 })
    expect(withKindParam('OBJECT', { flies: true, states: { trimM: 4 } }, 'flies', null)).toEqual({})
  })

  it('a piece that stops travelling loses its travelS, which the desk refuses on one that does not', () => {
    // A DRAW drape keeps it as it becomes a FLY drape: both travel.
    const drawn = { role: 'TABS', operation: 'DRAW', travelS: 4.5 }
    expect(withKindParam('DRAPE', drawn, 'operation', 'FLY')).toEqual({ role: 'TABS', operation: 'FLY', travelS: 4.5 })
    // DEAD does not travel: travelS goes with the travel (set_scene could set it; the form must drop it).
    expect(withKindParam('DRAPE', drawn, 'operation', 'DEAD')).toEqual({ role: 'TABS', operation: 'DEAD' })
    // An object keeps it while it flies, and loses it with its trim when it stops.
    expect(withKindParam('OBJECT', { flies: true, travelS: 3 }, 'shape', 'DISC')).toEqual({ flies: true, travelS: 3, shape: 'DISC' })
    expect(withKindParam('OBJECT', { flies: true, travelS: 3, states: { trimM: 4 } }, 'flies', false)).toEqual({ flies: false })
  })
})
