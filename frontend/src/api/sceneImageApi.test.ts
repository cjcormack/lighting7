import { describe, expect, it } from 'vitest'
import { aspectFit, paintOf, ratioLabel, sceneImageTypeOf, sceneImageUrl, withPaintSide } from './sceneImageApi'

const A = 'a'.repeat(64)
const B = 'b'.repeat(64)

describe('sceneImageApi (scrim plan session 1)', () => {
  it('names an image and its derived copies on the desk', () => {
    expect(sceneImageUrl(3, A)).toBe(`/api/rest/projects/3/scene-images/${A}`)
    expect(sceneImageUrl(3, A, 'display')).toBe(`/api/rest/projects/3/scene-images/${A}?variant=display`)
    expect(sceneImageUrl(3, A, 'mask')).toBe(`/api/rest/projects/3/scene-images/${A}?variant=mask`)
  })

  it('reads a params document’s paint, keeping only well-formed hashes', () => {
    expect(paintOf({})).toEqual({})
    expect(paintOf({ paint: { front: A, back: B } })).toEqual({ front: A, back: B })
    expect(paintOf({ paint: { front: 'not-a-hash', back: B } })).toEqual({ back: B })
    expect(paintOf({ paint: 'oops' })).toEqual({})
  })

  it('sets and takes off one side, and an empty paint is absent', () => {
    expect(withPaintSide({}, 'front', A)).toEqual({ front: A })
    expect(withPaintSide({ front: A }, 'back', B)).toEqual({ front: A, back: B })
    expect(withPaintSide({ front: A, back: B }, 'front', null)).toEqual({ back: B })
    expect(withPaintSide({ front: A }, 'front', null)).toBeNull()
  })

  it('compares an image’s aspect with its cloth’s, within 2 %', () => {
    expect(aspectFit({ width: 2000, height: 1000 }, 12, 6)).toMatchObject({ matches: true, heightForImage: 6 })
    // 1.5 % off still matches; 3 % off does not, and the matching height is offered.
    expect(aspectFit({ width: 2000, height: 1000 }, 12, 6.09)!.matches).toBe(true)
    const off = aspectFit({ width: 1600, height: 900 }, 12, 6)!
    expect(off.matches).toBe(false)
    expect(off.heightForImage).toBe(6.75)
    expect(aspectFit({ width: 2000, height: 1000 }, null, 6)).toBeNull()
    expect(aspectFit({ width: 2000, height: 1000 }, 12, 0)).toBeNull()
  })

  it('writes an aspect as a short ratio', () => {
    expect(ratioLabel(2)).toBe('2 : 1')
    expect(ratioLabel(16 / 9)).toBe('16 : 9')
    expect(ratioLabel(1.5)).toBe('3 : 2')
    expect(ratioLabel(2048 / 1365)).toBe('3 : 2')
    expect(ratioLabel(2.37)).toBe('2.37 : 1')
  })

  it('takes a PNG or a JPEG by type, or by name where the type is blank', () => {
    expect(sceneImageTypeOf({ type: 'image/png', name: 'x' })).toBe('image/png')
    expect(sceneImageTypeOf({ type: 'image/jpeg', name: 'x' })).toBe('image/jpeg')
    expect(sceneImageTypeOf({ type: '', name: 'Forest.JPG' })).toBe('image/jpeg')
    expect(sceneImageTypeOf({ type: 'image/webp', name: 'x.webp' })).toBeNull()
  })
})
