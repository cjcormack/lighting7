// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LookPreviewStrip } from './lookValueChips'

/** Chips 30px wide, `+N` 14px, the effects glyph 14px, and the line [line] px. */
function stubWidths(line: number) {
  const rect = (width: number) =>
    ({ left: 0, top: 0, right: width, bottom: 16, width, height: 16, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-preview-line')) return rect(line)
    if (this.hasAttribute('data-preview-chip')) return rect(30)
    if (this.hasAttribute('data-preview-more') || this.hasAttribute('data-preview-effects')) return rect(14)
    return rect(0)
  })
}

/** The chips drawn on the line itself, not the measuring copy. */
function drawnLine() {
  const line = document.querySelector('[data-preview-line]') as HTMLElement
  const measure = line.querySelector('[aria-hidden]') as HTMLElement
  return [...line.children].filter((child) => child !== measure)
}

afterEach(() => vi.restoreAllMocks())

describe('LookPreviewStrip', () => {
  const VALUES = ['10', '20', '30', '40', '50']

  it('draws only the chips that fit on one line, and +N for the rest', () => {
    stubWidths(100)
    render(<LookPreviewStrip preview={VALUES} fxCount={0} />)
    // 30 + 4 + 30 + 4 + "+3" 14 = 82 fits 100; a third chip would need 116.
    expect(drawnLine().map((el) => el.textContent)).toEqual(['10', '20', '+3'])
    expect(document.querySelector('[data-preview-line]')!.getAttribute('title')).toContain('10  20  30  40  50')
  })

  it('draws every chip, with no +N, when they all fit', () => {
    stubWidths(400)
    render(<LookPreviewStrip preview={VALUES} fxCount={0} />)
    expect(drawnLine().map((el) => el.textContent)).toEqual(VALUES)
  })

  it('keeps room for the effects glyph after the chips', () => {
    // 100 less the glyph and its gap is 82: two chips and "+3" exactly.
    stubWidths(100 + 18)
    render(<LookPreviewStrip preview={VALUES} fxTypes={['Pulse']} fxCount={1} />)
    const drawn = drawnLine()
    expect(drawn.slice(0, 3).map((el) => el.textContent)).toEqual(['10', '20', '+3'])
    expect(screen.getByLabelText('1 FX: Pulse')).toBeInTheDocument()
  })

  it('names the effects of a Look that holds nothing else', () => {
    render(<LookPreviewStrip preview={[]} fxTypes={['ColourCycle', 'Pulse']} fxCount={2} />)
    expect(screen.getByText('Colour Cycle, Pulse')).toBeInTheDocument()
    expect(screen.getByLabelText('FX only')).toBeInTheDocument()
  })

  it('draws nothing for a Look with no values and no effects', () => {
    const { container } = render(<LookPreviewStrip preview={[]} fxCount={0} />)
    expect(container).toBeEmptyDOMElement()
  })
})
