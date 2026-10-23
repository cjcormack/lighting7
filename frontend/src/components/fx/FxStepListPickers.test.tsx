// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { TemplateSummary } from '@/api/templatesApi'
import type { SettingPropertyDescriptor } from '@/store/fixtures'

const wire = vi.hoisted(() => ({ templates: [] as TemplateSummary[] }))
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: wire.templates, isLoading: false }) }))
vi.mock('react-router', () => ({ useParams: () => ({ projectId: '1' }) }))

import { FxPaletteListPicker, FxSlotListPicker } from './FxStepListPickers'

const palette = (name: string, family: TemplateSummary['family'], propertyName: string, value: string) =>
  ({
    id: 1,
    uuid: `uuid-${name}`,
    name,
    notes: null,
    fadeDurationMs: null,
    family,
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    rows: [{ targetType: 'deferred', targetKey: '', propertyName, value }],
    effect: null,
  }) as unknown as TemplateSummary

const GOBO = {
  type: 'setting',
  name: 'gobo',
  displayName: 'Gobo',
  category: 'gobo',
  options: [
    { name: 'OPEN', level: 0, displayName: 'Open' },
    { name: 'GOBO_3', level: 30, displayName: 'Gobo 3' },
    { name: 'GOBO_5', level: 50, displayName: 'Gobo 5' },
  ],
} as unknown as SettingPropertyDescriptor

afterEach(cleanup)

describe('FxPaletteListPicker', () => {
  it('draws the steps in order, by name, and moves, removes and adds by reference', () => {
    wire.templates = [
      palette('Centre', 'POSITION', 'position', 'pos:0,0'),
      palette('Downstage', 'POSITION', 'position', 'pos:-64,32'),
      palette('Red', 'COLOUR', 'rgbColour', '#FF0000'),
    ]
    const onChange = vi.fn()
    render(
      <FxPaletteListPicker label="Palettes" value="pal:uuid-Centre,pal:uuid-Downstage" propertyName="position" readOnly={false} onChange={onChange} />,
    )
    const steps = within(screen.getByRole('list', { name: 'Palettes' })).getAllByRole('listitem')
    expect(steps.map((s) => s.getAttribute('data-step'))).toEqual(['Centre', 'Downstage'])

    fireEvent.click(screen.getByRole('button', { name: 'Move Downstage earlier' }))
    expect(onChange).toHaveBeenLastCalledWith('pal:uuid-Downstage,pal:uuid-Centre')
    fireEvent.click(screen.getByRole('button', { name: 'Remove Centre' }))
    expect(onChange).toHaveBeenLastCalledWith('pal:uuid-Downstage')
  })

  it('names a reference it cannot find as missing, and asks for two or more', () => {
    wire.templates = [palette('Centre', 'POSITION', 'position', 'pos:0,0')]
    render(<FxPaletteListPicker label="Palettes" value="pal:uuid-gone" readOnly={false} onChange={() => {}} />)
    expect(screen.getByText('Missing palette')).toBeTruthy()
    expect(screen.getByText(/Two palettes or more/)).toBeTruthy()
  })
})

describe('FxPaletteListPicker on an effect palette', () => {
  it('offers only the palette’s own family, even before a step is chosen', () => {
    wire.templates = [palette('Red', 'COLOUR', 'rgbColour', '#FF0000'), palette('Blue', 'COLOUR', 'rgbColour', '#0000FF')]
    render(<FxPaletteListPicker label="Palettes" value="" family="POSITION" readOnly={false} onChange={() => {}} />)
    expect(screen.getByText('No palettes to step')).toBeTruthy()
    cleanup()
    render(<FxPaletteListPicker label="Palettes" value="" family="COLOUR" readOnly={false} onChange={() => {}} />)
    expect(screen.getByText('Add a palette…')).toBeTruthy()
  })
})

describe('FxSlotListPicker', () => {
  it('reads stored names on the head’s setting, dashing one the head lacks', () => {
    render(<FxSlotListPicker label="Slots" value="GOBO_3,Gobo 9" setting={GOBO} readOnly={false} onChange={() => {}} />)
    const steps = within(screen.getByRole('list', { name: 'Slots' })).getAllByRole('listitem')
    expect(steps.map((s) => s.getAttribute('data-step'))).toEqual(['Gobo 3', 'Gobo 9'])
    expect(steps[1].className).toContain('border-dashed')
    expect(screen.getByText(/not on this head/)).toBeTruthy()
  })

  it('takes typed names where there is no setting to offer — an effect palette’s', () => {
    const onChange = vi.fn()
    render(<FxSlotListPicker label="Slots" value="Gobo 3" setting={undefined} readOnly={false} onChange={onChange} />)
    const input = screen.getByRole('textbox', { name: 'Add to Slots' })
    fireEvent.change(input, { target: { value: 'Gobo 5' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('Gobo 3,Gobo 5')
  })

  it('on a palette, takes typed names beside the rig’s options and says a missing one is on no head', () => {
    const onChange = vi.fn()
    render(<FxSlotListPicker label="Slots" value="GOBO_3,Gobo 9" setting={GOBO} fromRig readOnly={false} onChange={onChange} />)
    expect(screen.getByRole('combobox', { name: 'Add to Slots' })).toBeTruthy()
    const input = screen.getByRole('textbox', { name: 'Type a slot for Slots' })
    fireEvent.change(input, { target: { value: 'Gobo 7' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('GOBO_3,Gobo 9,Gobo 7')
    expect(screen.getByText(/on no patched head/)).toBeTruthy()
  })
})
