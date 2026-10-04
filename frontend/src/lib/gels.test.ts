import { describe, expect, it } from 'vitest'
import gelsJson from '../../../src/main/resources/gels.json'
import { EMPTY_GELS, findGel, indexGels, searchGels, type Gel } from './gels'

/**
 * The gel library is the desk's (`GET /gels`, fixture optics plan D7), served from the same resource
 * this reads: `data/gels.ts` is gone, and the client only indexes what it is given.
 */
const gels = indexGels(gelsJson as Gel[])

describe('the served gel library', () => {
  it('indexes the desk resource by code, with its brands in library order', () => {
    expect(gels.all).toHaveLength(40)
    expect(gels.brands).toEqual(['Lee', 'Rosco'])
    expect(findGel(gels, 'L201')?.color).toBe('#9bbede')
    expect(findGel(gels, 'R25')).toMatchObject({ name: 'Orange Red', brand: 'Rosco', estimate: true })
    expect(findGel(gels, 'L-HT115')?.name).toBe('Peacock Blue')
  })

  it('answers null for no code and for one the library does not hold', () => {
    expect(findGel(gels, null)).toBeNull()
    expect(findGel(gels, '')).toBeNull()
    expect(findGel(gels, 'R999')).toBeNull()
  })

  it('searches by code or name within a brand', () => {
    expect(searchGels(gels, 'ct blue', 'All').map((g) => g.code)).toEqual(['L201', 'L202', 'L203'])
    expect(searchGels(gels, 'pale gold', 'Rosco').map((g) => g.code)).toEqual(['R08'])
    expect(searchGels(gels, '', 'Lee').every((g) => g.brand === 'Lee')).toBe(true)
  })

  it('is empty, not absent, while the library has not arrived', () => {
    expect(indexGels(undefined)).toBe(EMPTY_GELS)
    expect(searchGels(EMPTY_GELS, 'blue', 'All')).toEqual([])
    expect(findGel(EMPTY_GELS, 'L201')).toBeNull()
  })
})
