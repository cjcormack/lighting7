// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

/**
 * Row A's *+ Look* (programmer-toolbar plan D11, D12): the one picker on its Looks, pressed whole —
 * no targets and no mask — and, below 600px of the row, one **+** whose picker has *Include a cue or
 * Look…* at its top. The press, the ring and staying open are the picker's (`ApplyPicker.test.tsx`).
 */
type PickerProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  anchorRef?: { current: HTMLElement | null }
  library: string
  pair: { targets: unknown[]; families: unknown }
  lead?: ReactNode
}
const picker = vi.hoisted(() => vi.fn())
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: PickerProps) => {
    picker(props)
    return props.open ? <div data-testid="picker">{props.lead}</div> : null
  },
}))

const { AddLookAndInclude } = await import('./AddLook')
const lastPicker = () => picker.mock.lastCall![0] as PickerProps

afterEach(() => {
  cleanup()
  picker.mockClear()
})

function bar(onInclude = vi.fn()) {
  render(<AddLookAndInclude projectId={3} onInclude={onInclude} lookWordClass="look-word" includeWordClass="include-word" />)
  return onInclude
}

describe('+ Look', () => {
  it('opens the Looks under itself, pressed whole — no targets, no mask', () => {
    bar()
    expect(picker).not.toHaveBeenCalled()
    const look = screen.getByRole('button', { name: 'Add a Look' })
    fireEvent.click(look)
    expect(lastPicker()).toMatchObject({ open: true, library: 'looks', pair: { targets: [], families: null } })
    // The pair's box, drawn at every width, so a row crossing 600 keeps the picker's anchor.
    expect(lastPicker().anchorRef?.current).toBe(look.parentElement)
    expect(lastPicker().lead).toBeNull()
    expect(look).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(look)
    expect(lastPicker().open).toBe(false)
  })

  it('sits beside Include, each wording itself at its own rung', () => {
    const onInclude = bar()
    expect(screen.getByRole('button', { name: 'Add a Look' }).querySelector('.look-word')?.textContent).toBe('Look')
    const include = screen.getByRole('button', { name: 'Include…' })
    expect(include.querySelector('.include-word')?.textContent).toBe('Include…')
    fireEvent.click(include)
    expect(onInclude).toHaveBeenCalled()
    expect(picker).not.toHaveBeenCalled()
  })

  it('is one + below 600px of the row, its picker led by Include a cue or Look…', () => {
    const onInclude = bar()
    // The container query is the stylesheet's: the + shows below 600, the pair above it.
    expect(screen.getByRole('button', { name: 'Add a Look' }).className).toContain('@[600px]:inline-flex')
    expect(screen.getByRole('button', { name: 'Include…' }).className).toContain('@[600px]:inline-flex')
    const plus = screen.getByRole('button', { name: 'Include, or add a Look' })
    expect(plus.className).toContain('@[600px]:hidden')
    fireEvent.click(plus)
    expect(lastPicker()).toMatchObject({ open: true, library: 'looks' })
    expect(lastPicker().anchorRef?.current).toBe(plus.parentElement)
    const lead = screen.getByRole('button', { name: /Include a cue or Look…/ })
    fireEvent.click(lead)
    expect(onInclude).toHaveBeenCalled()
    expect(lastPicker().open).toBe(false)
  })

  it('closes when the picker asks', () => {
    bar()
    fireEvent.click(screen.getByRole('button', { name: 'Add a Look' }))
    act(() => lastPicker().onOpenChange(false))
    expect(screen.queryByTestId('picker')).toBeNull()
    expect(screen.getByRole('button', { name: 'Add a Look' })).toHaveAttribute('aria-expanded', 'false')
  })
})
