// @vitest-environment jsdom
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { PaletteSummary } from '@/api/palettesApi'

/**
 * One FX colour parameter as a host of the programmer's `ColourEditor` (venue-rig-fixes plan D18):
 * its typed R/G/B fields, a `pal:` value drawn as its palette and opened at the colour it resolves
 * to, the band picking a reference, and *Save as palette…* naming the palette it made.
 */

vi.mock('react-colorful', async () => (await import('@/test/colourPickerMock')).colourPickerMock())
vi.mock('sonner', () => ({ toast: { error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))

let palettes: PaletteSummary[] = []
const createPalette = vi.hoisted(() => vi.fn())
vi.mock('@/store/palettes', () => ({
  usePaletteListQuery: () => ({ data: palettes, isLoading: false }),
  useCreatePaletteMutation: () => [createPalette, { isLoading: false }],
}))

import { FxColourPicker } from './FxColourPicker'

function palette(over: Partial<PaletteSummary> = {}): PaletteSummary {
  return {
    id: 1,
    uuid: 'u-warm',
    name: 'Warm Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#ff9d4a;policy=extract' }],
    kind: 'value',
    requiredEmitters: [],
    fx: null,
    layerCount: 0,
    lastPressedAt: null,
    buskPageCount: 0,
    ...over,
  }
}

const WARM = palette()
const COOL = palette({ id: 2, uuid: 'u-cool', name: 'Cool Fill', rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#4a9dff;policy=extract' }] })

/** The FX editor's way: whatever comes out is stored and handed straight back in. */
function Controlled({ initial, onEmit, white = false }: { initial: string; onEmit: (value: string, release: boolean) => void; white?: boolean }) {
  const [value, setValue] = useState(initial)
  return (
    <FxColourPicker
      value={value}
      label="Colour"
      extendedChannels={white ? { white: true } : undefined}
      onChange={(next, release) => {
        setValue(next)
        onEmit(next, release)
      }}
    />
  )
}

function draw(initial: string, white = false) {
  const onEmit = vi.fn()
  render(
    <MemoryRouter initialEntries={['/7/fx']}>
      <Routes>
        <Route path="/:projectId/fx" element={<Controlled initial={initial} onEmit={onEmit} white={white} />} />
      </Routes>
    </MemoryRouter>,
  )
  return { onEmit }
}

const open = (name: RegExp | string) => fireEvent.click(screen.getByRole('button', { name }))
const dialog = () => within(screen.getByRole('dialog'))
const field = (name: string) => dialog().getByRole('spinbutton', { name }) as HTMLInputElement

beforeEach(() => {
  palettes = [WARM, COOL]
  createPalette.mockReset()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('FxColourPicker', () => {
  it('draws the R, G and B fields and commits a typed R, releasing it once the field is left', () => {
    const { onEmit } = draw('#102030', true)
    open(/#102030/)
    expect(field('R').value).toBe('16')
    expect(field('G').value).toBe('32')
    expect(field('B').value).toBe('48')
    expect(field('W value')).toBeInTheDocument()

    fireEvent.change(field('R'), { target: { value: '200' } })
    expect(onEmit).toHaveBeenLastCalledWith('#c82030', false)
    fireEvent.blur(field('R'))
    expect(onEmit).toHaveBeenLastCalledWith('#c82030', true)
  })

  it('draws a reference as its palette and opens at the colour it resolves to', () => {
    draw('pal:u-warm')
    expect(screen.getByRole('button', { name: /Warm Key/ })).toBeInTheDocument()
    open(/Warm Key/)
    expect(field('R').value).toBe('255')
    expect(field('G').value).toBe('157')
    expect(field('B').value).toBe('74')
    expect(document.querySelector('[data-colour-editor-reference]')).toHaveTextContent('Warm Key')
    expect(dialog().getByRole('button', { name: /^Warm Key/ }).className).toContain('border-primary')
    expect(dialog().getByRole('button', { name: /^Cool Fill/ }).className).not.toContain('border-primary')
  })

  it('picks another palette as a reference, at once', () => {
    const { onEmit } = draw('pal:u-warm')
    open(/Warm Key/)
    fireEvent.click(dialog().getByRole('button', { name: /^Cool Fill/ }))
    expect(onEmit).toHaveBeenCalledExactlyOnceWith('pal:u-cool', true)
    expect(document.querySelector('[data-colour-editor-reference]')).toHaveTextContent('Cool Fill')
    expect(field('B').value).toBe('255')
  })

  it('turns a reference into its literal once a channel is typed', () => {
    const { onEmit } = draw('pal:u-warm')
    open(/Warm Key/)
    fireEvent.change(field('B'), { target: { value: '0' } })
    expect(onEmit).toHaveBeenLastCalledWith('#ff9d00', false)
    expect(document.querySelector('[data-colour-editor-reference]')).toBeNull()
  })

  it('saves the colour as a palette and names it as the value', async () => {
    createPalette.mockReturnValue({ unwrap: () => Promise.resolve({ uuid: 'u-new' }) })
    const { onEmit } = draw('#102030', true)
    open(/#102030/)
    fireEvent.change(field('W value'), { target: { value: '60' } })
    fireEvent.click(dialog().getByRole('button', { name: /Save #102030 as palette/ }))
    fireEvent.change(dialog().getByPlaceholderText('Palette name'), { target: { value: 'Steel' } })
    await act(async () => {
      fireEvent.click(dialog().getByRole('button', { name: 'Save' }))
    })
    expect(createPalette).toHaveBeenCalledExactlyOnceWith({
      projectId: 7,
      name: 'Steel',
      rows: [
        { targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: expect.stringContaining('#102030') },
        { targetType: 'deferred', targetKey: '', attribute: 'white', value: expect.any(String) },
      ],
    })
    expect(onEmit).toHaveBeenLastCalledWith('pal:u-new', true)
  })

  it('stores a pure-white pick as #ffffff with W offered, never the white LED alone', () => {
    const { onEmit } = draw('#102030', true)
    open(/#102030/)
    expect(dialog().queryByText('White = use white LED')).toBeNull()
    expect(document.querySelector('[title^="Pure white"]')).toBeNull()
    const picker = dialog().getByRole('textbox', { name: 'Colour picker' })
    fireEvent.pointerDown(picker)
    fireEvent.change(picker, { target: { value: '#ffffff' } })
    expect(onEmit).toHaveBeenLastCalledWith('#ffffff', false)
    expect(field('W value').value).toBe('0')
  })

  it('lands a typed channel the popover’s close cuts short', () => {
    const { onEmit } = draw('#102030')
    open(/#102030/)
    fireEvent.change(field('G'), { target: { value: '0' } })
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(onEmit).toHaveBeenLastCalledWith('#100030', true)
  })
})
