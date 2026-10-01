import { describe, expect, it } from 'vitest'
import library from '../../../src/main/resources/lanterns/library.json'
import {
  effectiveLantern,
  focusFeatures,
  indexLanterns,
  LANTERN_FAMILY_KIND,
  lanternFieldDeg,
  lanternFieldLabel,
  lanternListLabel,
  lanternsByFamily,
  softnessForFocus,
  zoomFor,
  type Lantern,
} from './lanterns'

// The desk's own resource, from the backend's tree: what `GET /lanterns` answers.
const LANTERNS = indexLanterns(library as Lantern[])
const byId = (id: string) => LANTERNS.byId.get(id)!

describe('the lantern library, as the desk ships it', () => {
  it("maps every family the desk ships to the kind the desk derives from it", () => {
    for (const l of LANTERNS.all) expect(LANTERN_FAMILY_KIND[l.family]).toBeDefined()
    // `LanternFamily.kind` in lighting7: a PC is a fresnel to anything that asks only for a kind.
    expect(LANTERN_FAMILY_KIND.PC).toBe('FRESNEL')
    expect(LANTERN_FAMILY_KIND.CYC).toBe('WASH')
  })

  it("answers the named lantern, else the kind's default, else nothing", () => {
    expect(effectiveLantern(LANTERNS, 'rama-175', 'PROFILE')?.id).toBe('rama-175')
    expect(effectiveLantern(LANTERNS, null, 'PROFILE')?.id).toBe('s4-19')
    // An id this desk's library does not hold is drawn as if it named none.
    expect(effectiveLantern(LANTERNS, 'from-a-newer-desk', 'PAR')?.id).toBe('par64-cp62')
    expect(effectiveLantern(LANTERNS, null, 'MOVING_HEAD')).toBeNull()
  })

  it('groups by family in the resource order', () => {
    const families = lanternsByFamily(LANTERNS).map(([f]) => f)
    expect(families[0]).toBe('PROFILE')
    expect(new Set(families).size).toBe(families.length)
  })
})

describe('focus', () => {
  it('offers what the lantern can take', () => {
    expect(focusFeatures(byId('s4-19'))).toMatchObject({ blades: 'shutters', iris: true, zoom: null, oval: false, softness: true })
    expect(focusFeatures(byId('cantata-f'))).toMatchObject({ blades: 'barnDoors', iris: false, softness: true })
    expect(focusFeatures(byId('cantata-f')).zoom).toEqual({ minDeg: 9, maxDeg: 51 })
    expect(focusFeatures(byId('par64-cp62'))).toMatchObject({ blades: null, oval: true, softness: false })
    expect(focusFeatures(null).blades).toBeNull()
  })

  it('clamps a zoom into the range, and has none on a fixed lantern', () => {
    expect(zoomFor(byId('s4-zoom-25-50'), 30)).toBe(30)
    expect(zoomFor(byId('s4-zoom-25-50'), 10)).toBe(25)
    expect(zoomFor(byId('s4-zoom-25-50'), null)).toBeNull()
    expect(zoomFor(byId('s4-19'), 30)).toBeNull()
    expect(lanternFieldDeg(byId('s4-zoom-25-50'), null)).toBe(35)
    expect(lanternFieldDeg(byId('s4-19'), 30)).toBe(19)
  })

  it("keeps a fresnel softer than a profile at either end of the knob", () => {
    const profileSoft = softnessForFocus(byId('s4-19'), 1)!
    const fresnelSharp = softnessForFocus(byId('cantata-f'), 0)!
    expect(softnessForFocus(byId('s4-19'), 0)).toBeLessThan(0.1)
    expect(fresnelSharp).toBeGreaterThan(softnessForFocus(byId('s4-19'), 0)!)
    expect(profileSoft).toBeGreaterThan(fresnelSharp)
    // No knob on a PAR, and none set is the lantern's own edge.
    expect(softnessForFocus(byId('par64-cp62'), 0.5)).toBeNull()
    expect(softnessForFocus(byId('s4-19'), null)).toBeNull()
  })

  it('summarises a field', () => {
    expect(lanternFieldLabel(byId('s4-19'))).toBe('19°')
    expect(lanternFieldLabel(byId('s4-zoom-25-50'))).toBe('zoom 25–50°')
    expect(lanternFieldLabel(byId('par64-cp62'))).toBe('oval 44×21°')
  })
})

describe('a fixture\'s lanterns in a phrase', () => {
  it('names one, counts a pair of one type, and joins a mixed pair', () => {
    expect(lanternListLabel(['Source Four 19°'])).toBe('Source Four 19°')
    expect(lanternListLabel(['Source Four 19°', 'Source Four 19°'])).toBe('Source Four 19° ×2')
    expect(lanternListLabel(['Source Four 19°', 'Strand Cantata F'])).toBe('Source Four 19° + Strand Cantata F')
    expect(lanternListLabel([])).toBe('')
  })
})
