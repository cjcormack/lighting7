// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { textPositionNear } from './textCaret'

function span(layer: HTMLElement, text: string, rect: [number, number, number, number], rotate?: string) {
  const el = document.createElement('span')
  el.setAttribute('role', 'presentation')
  el.textContent = text
  if (rotate) el.style.setProperty('--rotate', rotate)
  const [left, top, right, bottom] = rect
  el.getBoundingClientRect = () => ({ left, top, right, bottom, x: left, y: top, width: right - left, height: bottom - top, toJSON: () => ({}) }) as DOMRect
  layer.append(el)
  return el
}

describe('textPositionNear', () => {
  it('passes over a rotated watermark whose box covers the press', () => {
    const layer = document.createElement('div')
    span(layer, 'PERUSAL COPY', [0, 0, 600, 800], '-45deg')
    const line = span(layer, 'Enter the Fairy', [100, 200, 250, 212])
    expect(textPositionNear(layer, 40, 205)).toEqual({ node: line.firstChild, offset: 0 })
  })

  it('takes an unrotated span marked 0deg', () => {
    const layer = document.createElement('div')
    const line = span(layer, 'Exit', [100, 200, 140, 212], '0deg')
    expect(textPositionNear(layer, 200, 205)).toEqual({ node: line.firstChild, offset: 4 })
  })
})
