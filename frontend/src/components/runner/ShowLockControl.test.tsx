// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { ShowLockControl } from './ShowLockControl'

/**
 * One lock control, in one place, on both views that have a lock. It used to be drawn by the Prompt
 * Book's own toolbar and by nothing on Show at all, so the same control sat in a different position
 * depending on which of the two you were looking at.
 */

function draw(over: Partial<React.ComponentProps<typeof ShowLockControl>> = {}) {
  const onToggle = vi.fn()
  const onStayUnlocked = vi.fn()
  const utils = render(
    <ShowLockControl
      locked
      onToggle={onToggle}
      countdownSecondsLeft={null}
      onStayUnlocked={onStayUnlocked}
      {...over}
    />,
  )
  return { ...utils, onToggle, onStayUnlocked }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('ShowLockControl', () => {
  it('offers to unlock while locked', () => {
    const { onToggle } = draw()
    fireEvent.click(screen.getByRole('button', { name: 'Unlock for editing' }))
    expect(onToggle).toHaveBeenCalledTimes(1)
  })

  it('offers to lock while unlocked', () => {
    const { onToggle } = draw({ locked: false })
    fireEvent.click(screen.getByRole('button', { name: 'Lock the show' }))
    expect(onToggle).toHaveBeenCalledTimes(1)
  })

  it('shouts while a running show is unlocked', () => {
    // Believing you are locked when you are not is how a show gets edited by accident, so this is
    // the one state the chrome is loud about.
    draw({ locked: false })
    expect(screen.getByRole('button', { name: 'Lock the show' }).className).toContain('animate-pulse')
  })

  it('stays quiet while locked', () => {
    draw()
    expect(screen.getByRole('button', { name: 'Unlock for editing' }).className).not.toContain(
      'animate-pulse',
    )
  })

  it('shows an inert control where the backend will not accept edits', () => {
    // Carried over from the Prompt Book: the disabled control is the only thing saying *why*
    // nothing can be changed, so it is shown rather than hidden.
    draw({ disabled: true })
    const button = screen.getByRole('button', { name: 'Unlock for editing' }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(button.className).not.toContain('animate-pulse')
  })

  it('counts down before re-locking, with a way to refuse', () => {
    const { onStayUnlocked } = draw({ locked: false, countdownSecondsLeft: 7 })
    expect(screen.getByText(/Re-locking in 7s/)).toBeTruthy()

    fireEvent.click(screen.getByText('Stay unlocked'))
    expect(onStayUnlocked).toHaveBeenCalledTimes(1)
  })

  it('shows no countdown when none is running', () => {
    draw()
    expect(screen.queryByText(/Re-locking/)).toBeNull()
  })
})
