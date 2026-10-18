// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.resetModules()
})

async function load() {
  const { markDeskQuit } = await import('./deskQuit')
  const { DeskQuitScreen } = await import('./DeskQuitScreen')
  return { markDeskQuit, DeskQuitScreen }
}

describe('DeskQuitScreen', () => {
  it('draws nothing until the desk has quit, then says so with no cool-down', async () => {
    const { markDeskQuit, DeskQuitScreen } = await load()
    const { container } = render(<DeskQuitScreen />)
    expect(container.innerHTML).toBe('')
    act(() => markDeskQuit())
    expect(screen.getByRole('alert').textContent).toContain('The desk has quit')
    expect(screen.queryByTestId('quit-screen-cooling')).toBeNull()
  })

  it('counts a cool-down out, then says the rig can be switched off', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000)
    const { markDeskQuit, DeskQuitScreen } = await load()
    render(<DeskQuitScreen />)
    act(() => markDeskQuit(1_000_000 + 65_000))
    expect(screen.getByTestId('quit-screen-cooling').textContent).toContain('another 1:05')
    act(() => vi.advanceTimersByTime(60_000))
    expect(screen.getByTestId('quit-screen-cooling').textContent).toContain('another 0:05')
    act(() => vi.advanceTimersByTime(6_000))
    expect(screen.getByTestId('quit-screen-cooling').textContent).toContain('the rig can be switched off')
  })
})
