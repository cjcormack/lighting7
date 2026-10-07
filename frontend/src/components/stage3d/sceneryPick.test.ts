import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../api/stageElementApi'
import { buildElement } from './scene/builders'
import { elementAnchorBox, pickedScenery, pressPicks } from './sceneryPick'
import { DRAG_PX_THRESHOLD } from './dragThreshold'

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const moon = element({ uuid: 'moon', name: 'Moon', layer: 'SET', positionX: 1, positionY: 4, positionZ: 3, params: { shape: 'DISC', flies: true, states: { trimM: 7 } } })
const tabs = element({ uuid: 'tabs', name: 'Tabs', kind: 'DRAPE', layer: 'VENUE', widthM: 8, depthM: 0.1, heightM: 5, params: { role: 'TABS', operation: 'DRAW' } })
const sofa = element({ uuid: 'sofa', name: 'Sofa', layer: 'SET' })
const leg = element({ uuid: 'leg', name: 'Leg', kind: 'DRAPE', layer: 'VENUE', params: { role: 'LEG', operation: 'DEAD' } })
const proscenium = element({ uuid: 'pros', name: 'Proscenium', kind: 'PROSCENIUM', layer: 'VENUE' })
const room = element({ uuid: 'room', name: 'Hall', kind: 'ROOM', layer: 'VENUE' })
const byUuid = new Map([moon, tabs, sofa, leg, proscenium, room].map((e) => [e.uuid, e]))

describe('a press on the canvas (scenery-programmer plan D11)', () => {
  it('picks only on a primary-button click that barely moved', () => {
    const at = { x: 100, y: 100 }
    expect(pressPicks({ button: 0, down: at, up: at })).toBe(true)
    expect(pressPicks({ button: 0, down: at, up: { x: 100 + DRAG_PX_THRESHOLD, y: 100 } })).toBe(true)
    expect(pressPicks({ button: 2, down: at, up: at })).toBe(false)
  })

  it('never opens the popover on a pan — an orbit drag, a section pan, a finger drag', () => {
    const down = { x: 200, y: 200 }
    // An orbit drag released over the piece it started on.
    expect(pressPicks({ button: 0, down, up: { x: 260, y: 230 } })).toBe(false)
    // Just past the threshold, either axis.
    expect(pressPicks({ button: 0, down, up: { x: 200 + DRAG_PX_THRESHOLD + 1, y: 200 } })).toBe(false)
    expect(pressPicks({ button: 0, down, up: { x: 200, y: 200 - DRAG_PX_THRESHOLD - 1 } })).toBe(false)
    // A release whose press went down off the canvas.
    expect(pressPicks({ button: 0, down: null, up: down })).toBe(false)
  })
})

describe('which element a press picks', () => {
  it('takes the nearest surface the ray met, whatever order the cast found them in', () => {
    expect(pickedScenery([{ elementUuid: 'tabs', distance: 9 }, { elementUuid: 'moon', distance: 6 }], byUuid)).toBe('moon')
    expect(pickedScenery([{ elementUuid: 'moon', distance: 12 }, { elementUuid: 'tabs', distance: 9 }], byUuid)).toBe('tabs')
  })

  it('opens drawn, flown and Set-layer pieces, and nothing for the fixed venue', () => {
    expect(pickedScenery([{ elementUuid: 'tabs', distance: 1 }], byUuid)).toBe('tabs')
    expect(pickedScenery([{ elementUuid: 'moon', distance: 1 }], byUuid)).toBe('moon')
    expect(pickedScenery([{ elementUuid: 'sofa', distance: 1 }], byUuid)).toBe('sofa')
    expect(pickedScenery([{ elementUuid: 'leg', distance: 1 }], byUuid)).toBeNull()
    expect(pickedScenery([{ elementUuid: 'pros', distance: 1 }], byUuid)).toBeNull()
    expect(pickedScenery([{ elementUuid: 'room', distance: 1 }], byUuid)).toBeNull()
  })

  it('lets a fixed wall in front hide the piece behind it, as it hides it from the eye', () => {
    expect(pickedScenery([{ elementUuid: 'moon', distance: 8 }, { elementUuid: 'pros', distance: 5 }], byUuid)).toBeNull()
  })

  it('ignores a surface of an element the scene no longer draws, and answers nothing for no hit', () => {
    expect(pickedScenery([{ elementUuid: 'gone', distance: 1 }, { elementUuid: 'sofa', distance: 4 }], byUuid)).toBe('sofa')
    expect(pickedScenery([], byUuid)).toBeNull()
  })
})

describe("the popover's anchor box", () => {
  it('is the box round what is drawn, in three.js space, and flies with a flown piece', () => {
    const { centre, half } = elementAnchorBox(moon, buildElement(moon))
    // Lighting (1, 4, trim 7 + half its height) → three (x, z, −y).
    expect(centre.x).toBeCloseTo(1, 1)
    expect(centre.z).toBeCloseTo(-4, 1)
    expect(centre.y).toBeGreaterThan(7)
    expect(centre.y).toBeLessThan(8)
    expect(half.y).toBeCloseTo(0.5, 1)
    const flownIn = { ...moon, params: { ...moon.params, states: { trimM: 3 } } }
    expect(elementAnchorBox(flownIn, buildElement(flownIn)).centre.y).toBeCloseTo(centre.y - 4, 5)
  })

  it('falls back to its own box when nothing of it is drawn', () => {
    const hidden = { ...moon, params: { ...moon.params, states: { trimM: 7, visible: false } } }
    const box = elementAnchorBox(hidden, null)
    expect(box.centre).toEqual({ x: 1, y: 7.5, z: -4 })
    expect(box.half.y).toBe(0.5)
  })
})
