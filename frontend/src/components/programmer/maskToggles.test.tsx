// @vitest-environment jsdom
import { useState } from 'react'
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  MaskPicker,
  maskIsEmpty,
  maskToWire,
  maskValueKey,
  useMaskDraft,
  type MaskValue,
} from './maskPicker'
import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * *All attributes* (venue-visit plan S10): a switch, on for every attribute, that shows the four
 * family checkboxes only while it is off. Off starts with all four ticked; the switch moves only
 * when pressed; nothing ticked is a state a host refuses to act on.
 */
const allSwitch = () => screen.getByRole('switch', { name: 'All attributes' })
const box = (name: string) => screen.getByRole('checkbox', { name }) as HTMLInputElement
const families = () => screen.queryAllByRole('checkbox').map((b) => (b as HTMLInputElement).checked)

function Harness({ initial = null as MaskValue, onChange = (_: MaskValue) => {}, counts = undefined as Partial<Record<AttributeFamily, number>> | undefined }) {
  const [value, setValue] = useState<MaskValue>(initial)
  return (
    <MaskPicker
      value={value}
      counts={counts}
      onChange={(next) => {
        onChange(next)
        setValue(next)
      }}
    />
  )
}

describe('the All attributes switch', () => {
  it('is on for null, with no family checkboxes', () => {
    render(<Harness />)
    expect(allSwitch()).toHaveAttribute('aria-checked', 'true')
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(screen.getByText('All attributes.')).toBeTruthy()
  })

  it('switched off, shows the four ticked — not yet a narrowing', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    fireEvent.click(allSwitch())
    expect(onChange).toHaveBeenLastCalledWith(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])
    expect(allSwitch()).toHaveAttribute('aria-checked', 'false')
    expect(families()).toEqual([true, true, true, true])
    expect(screen.getByText('All four attributes — untick to narrow.')).toBeTruthy()
  })

  it('narrows by unticking, in declaration order', () => {
    const onChange = vi.fn()
    render(<Harness initial={['INTENSITY', 'POSITION', 'COLOUR', 'BEAM']} onChange={onChange} />)
    fireEvent.click(box('Position'))
    fireEvent.click(box('Beam'))
    expect(onChange).toHaveBeenLastCalledWith(['INTENSITY', 'COLOUR'])
    expect(screen.getByText('Only Intensity and Colour.')).toBeTruthy()
  })

  it('stays off when the last family is unticked, and says why nothing can be done', () => {
    const onChange = vi.fn()
    render(<Harness initial={['COLOUR']} onChange={onChange} />)
    fireEvent.click(box('Colour'))
    expect(onChange).toHaveBeenLastCalledWith([])
    expect(allSwitch()).toHaveAttribute('aria-checked', 'false')
    expect(families()).toEqual([false, false, false, false])
    expect(screen.getByText('Pick at least one attribute.')).toBeTruthy()
  })

  it('stays off when all four are ticked back', () => {
    render(<Harness initial={['INTENSITY', 'POSITION', 'COLOUR']} />)
    fireEvent.click(box('Beam'))
    expect(allSwitch()).toHaveAttribute('aria-checked', 'false')
    expect(families()).toEqual([true, true, true, true])
  })

  it('switched on again drops the ticks', () => {
    const onChange = vi.fn()
    render(<Harness initial={['COLOUR']} onChange={onChange} />)
    fireEvent.click(allSwitch())
    expect(onChange).toHaveBeenLastCalledWith(null)
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
  })

  it('names what the programmer holds while switched on, since the counts are hidden', () => {
    render(<Harness counts={{ INTENSITY: 3, COLOUR: 4 }} />)
    expect(screen.getByText('All attributes. The programmer holds Intensity (3) and Colour (4).')).toBeTruthy()
  })

  it("adds the host's hint to the sentence", () => {
    render(<MaskPicker value={null} onChange={() => {}} hint="Scenery goes back whole." />)
    expect(screen.getByText('All attributes. Scenery goes back whole.')).toBeTruthy()
  })
})

describe('the wire fold', () => {
  it('sends no mask for every attribute, switched on or all four ticked', () => {
    expect(maskToWire(null)).toBeUndefined()
    expect(maskToWire(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])).toBeUndefined()
    expect(maskToWire(['BEAM', 'COLOUR'])).toEqual(['COLOUR', 'BEAM'])
  })

  it('marks nothing ticked as empty, and keys it apart from every attribute', () => {
    expect(maskIsEmpty([])).toBe(true)
    expect(maskIsEmpty(null)).toBe(false)
    expect(maskValueKey([])).not.toBe(maskValueKey(null))
    expect(maskValueKey(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])).toBe(maskValueKey(null))
  })
})

describe('useMaskDraft', () => {
  function setup(initial: AttributeFamily[] | null) {
    const write = vi.fn()
    const hook = renderHook(({ stored }) => useMaskDraft(stored, write), { initialProps: { stored: initial } })
    return { write, hook }
  }

  it('keeps the switch off over all four without writing', () => {
    const { write, hook } = setup(null)
    act(() => hook.result.current[1](['INTENSITY', 'POSITION', 'COLOUR', 'BEAM']))
    expect(write).not.toHaveBeenCalled()
    expect(hook.result.current[0]).toEqual(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])
  })

  it('writes a narrowing, and keeps the draft while the echo is in flight and after it lands', () => {
    const { write, hook } = setup(null)
    act(() => hook.result.current[1](['COLOUR']))
    expect(write).toHaveBeenCalledWith(['COLOUR'])
    expect(hook.result.current[0]).toEqual(['COLOUR'])
    hook.rerender({ stored: ['COLOUR'] })
    expect(hook.result.current[0]).toEqual(['COLOUR'])
  })

  it('never writes nothing ticked', () => {
    const { write, hook } = setup(['COLOUR'])
    act(() => hook.result.current[1]([]))
    expect(write).not.toHaveBeenCalled()
    expect(hook.result.current[0]).toEqual([])
  })

  it('writes a change made before the last write echoes back, and keeps it when the echoes land', () => {
    const { write, hook } = setup(null)
    act(() => hook.result.current[1](['INTENSITY', 'POSITION', 'COLOUR', 'BEAM']))
    act(() => hook.result.current[1](['INTENSITY', 'POSITION', 'BEAM']))
    // Colour ticked straight back, before the first write's echo: all four again, which the stored
    // mask (still none) already says — but the newest write does not, so it is written.
    act(() => hook.result.current[1](['INTENSITY', 'POSITION', 'COLOUR', 'BEAM']))
    expect(write.mock.calls).toEqual([[['INTENSITY', 'POSITION', 'BEAM']], [null]])
    hook.rerender({ stored: ['INTENSITY', 'POSITION', 'BEAM'] })
    expect(hook.result.current[0]).toEqual(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])
    hook.rerender({ stored: null })
    expect(hook.result.current[0]).toEqual(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])
  })

  it('follows a write from elsewhere', () => {
    const { hook } = setup(['COLOUR'])
    hook.rerender({ stored: null })
    expect(hook.result.current[0]).toBeNull()
  })
})
