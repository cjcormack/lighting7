// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BindingForm, type SubjectChoices } from './BindingForm'
import { EMPTY_RECORD_OPTIONS } from './recordOptions'
import { CATALOGUE } from './surfaceFixtures'
import type { SurfaceBinding } from '@/store/surfaces'

/** The form over a sample of the catalogue the desk serves (surface-setup plan D1, D12). */

afterEach(cleanup)

const choices: SubjectChoices = {
  groups: [{ key: 'front-wash', label: 'Front wash' }],
  fixtures: [{ key: 'hex-1', label: 'Hex 1' }],
  records: { ...EMPTY_RECORD_OPTIONS, STACK: [{ uuid: 'stack-1', label: 'Act 1', detail: null }] },
}
const layers = [
  { id: 'layer-a', name: 'A' },
  { id: 'layer-b', name: 'B' },
]

function renderForm(props: Partial<Parameters<typeof BindingForm>[0]> = {}) {
  const onChange = vi.fn()
  const onLayerChange = vi.fn()
  render(
    <BindingForm
      catalogue={CATALOGUE}
      input="BUTTON"
      binding={null}
      entryId={null}
      subjects={choices}
      attributesFor={() => [{ name: 'dimmer', displayName: 'Dimmer', type: 'slider', category: 'dimmer', continuous: true }]}
      layers={layers}
      layer={null}
      onLayerChange={onLayerChange}
      onChange={onChange}
      {...props}
    />,
  )
  return { onChange, onLayerChange }
}

function pick(label: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Does' }))
  fireEvent.click(screen.getByRole('option', { name: label }))
}

describe('BindingForm', () => {
  it('offers a button the functions a button reaches, grouped, and finds one by name', () => {
    renderForm()
    fireEvent.click(screen.getByRole('button', { name: 'Does' }))
    expect(screen.getByRole('option', { name: 'GO' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Level' })).toBeNull()
    fireEvent.change(screen.getByLabelText('Find a function'), { target: { value: 'tap' } })
    expect(screen.getByRole('option', { name: 'Tap' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'GO' })).toBeNull()
  })

  it('binds a GO to the first stack, whole, as soon as it is picked', () => {
    const { onChange } = renderForm()
    pick('GO')
    expect(onChange).toHaveBeenCalledWith({ does: { type: 'go' }, on: { type: 'record', kind: 'STACK', uuid: 'stack-1' } }, undefined)
  })

  it('holds a function whose subject it cannot fill, and says so', () => {
    const { onChange } = renderForm()
    pick('Look')
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByText('Choose what it acts on to bind it.')).toBeInTheDocument()
  })

  it('sets a button\'s How from the function\'s own list', () => {
    const blackout: SurfaceBinding = { does: { type: 'blackout' } }
    const { onChange } = renderForm({ binding: blackout, entryId: 'blackout' })
    fireEvent.click(screen.getByRole('button', { name: 'On' }))
    expect(onChange).toHaveBeenCalledWith({ does: { type: 'blackout' }, how: 'ON' }, undefined)
  })

  it('gives a continuous part Pick up or Jump, and a motor fader a sentence', () => {
    const level: SurfaceBinding = { does: { type: 'level', attribute: 'dimmer' }, on: { type: 'group', key: 'front-wash' } }
    const { onChange } = renderForm({ input: 'CONTINUOUS', binding: level, entryId: 'level' })
    fireEvent.click(screen.getByRole('button', { name: 'Jump' }))
    expect(onChange).toHaveBeenCalledWith(level, 'IMMEDIATE')
    cleanup()
    renderForm({ input: 'CONTINUOUS', binding: level, entryId: 'level', motor: true })
    expect(screen.getByText(/The motor follows/)).toBeInTheDocument()
  })

  it('moves a binding between layers with the layer chips, and names where it writes', () => {
    const go: SurfaceBinding = { does: { type: 'go' }, on: { type: 'record', kind: 'STACK', uuid: 'stack-1' } }
    const { onLayerChange } = renderForm({ binding: go, entryId: 'go' })
    expect(screen.getByRole('button', { name: 'Both layers' }).getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'B only' }))
    expect(onLayerChange).toHaveBeenCalledWith('layer-b')
    expect(screen.getByText('Writes to Playback')).toBeInTheDocument()
  })

  it('takes a binding off when Nothing is picked', () => {
    const go: SurfaceBinding = { does: { type: 'go' }, on: { type: 'record', kind: 'STACK', uuid: 'stack-1' } }
    const { onChange } = renderForm({ binding: go, entryId: 'go' })
    pick('Nothing')
    expect(onChange).toHaveBeenCalledWith(null)
  })
})
