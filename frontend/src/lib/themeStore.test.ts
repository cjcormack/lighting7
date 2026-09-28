// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { getTheme, resetThemeStore, setTheme, toggleTheme, useTheme } from './theme'

/**
 * The theme as one store (busk-chrome plan D10 gave it a second control, the ⌘K command): every
 * reader moves on a change from any control, the choice is persisted as the **bare** string
 * `getInitialTheme` reads before React exists, and the `dark` class follows.
 */
beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }))
})

afterEach(() => {
  resetThemeStore()
  window.localStorage.clear()
  document.documentElement.classList.remove('dark')
  vi.unstubAllGlobals()
})

describe('the theme store', () => {
  it('seeds from storage, applies the class and persists the bare string', () => {
    window.localStorage.setItem('theme', 'dark')
    expect(getTheme()).toBe('dark')
    setTheme('light')
    expect(window.localStorage.getItem('theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    toggleTheme()
    expect(window.localStorage.getItem('theme')).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('moves two readers together — the menu row and the ⌘K command cannot disagree', () => {
    const a = renderHook(() => useTheme())
    const b = renderHook(() => useTheme())
    expect(a.result.current).toBe('light')
    act(() => toggleTheme())
    expect(a.result.current).toBe('dark')
    expect(b.result.current).toBe('dark')
  })
})
