// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateSummary } from '@/api/templatesApi'
import { parseTemplateIntent, serializeTemplateIntent, type TemplateIntent } from '@/lib/templateIntent'
import { PositionControl } from './PositionControl'
import { seedValues, templateRowsFromValues, valueChanges } from './templateRows'

/**
 * A palette's position, centre-relative (venue-visit plan D3), and P3's promise about one stored
 * in degrees: it reads on the new scale, and only a change here writes it centre-relative — so an
 * old palette opened and saved for its name keeps its `deg:` row as it was.
 */

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})
afterEach(() => vi.unstubAllGlobals())

const OLD: TemplateSummary = {
  id: 1,
  uuid: 'u',
  name: 'Downstage',
  family: 'POSITION',
  kind: 'value',
  isGeneric: true,
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'position', value: 'deg:270,135' }],
} as unknown as TemplateSummary

describe('PositionControl', () => {
  it('shows a stored degree palette on the centre scale, and says it is still in degrees', () => {
    const values = seedValues(OLD)
    render(<PositionControl value={values.position} onChange={() => {}} />)
    expect(screen.getByRole('spinbutton', { name: 'Pan' })).toHaveValue(0)
    expect(screen.getByRole('spinbutton', { name: 'Tilt' })).toHaveValue(0)
    expect(screen.getByText(/Saved in degrees/)).toBeInTheDocument()
  })

  it('leaves an unedited degree palette exactly as stored', () => {
    const values = seedValues(OLD)
    expect(valueChanges(values, values)).toEqual({})
    expect(templateRowsFromValues('POSITION', values).map((r) => r.value)).toEqual(['deg:270,135'])
  })

  it('writes centre-relative once the position is moved', () => {
    const onChange = vi.fn<(intent: TemplateIntent | null) => void>()
    render(<PositionControl value={parseTemplateIntent('deg:270,135') ?? undefined} onChange={onChange} />)
    const pan = screen.getByRole('spinbutton', { name: 'Pan' })
    fireEvent.change(pan, { target: { value: '-30' } })
    const intent = onChange.mock.calls.at(-1)?.[0]
    expect(intent).toEqual({ kind: 'position', pan: -30, tilt: 0, unit: 'centre' })
    expect(serializeTemplateIntent(intent!)).toBe('pos:-30,0')
  })
})
