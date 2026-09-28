import { useSyncExternalStore } from "react"

export type Theme = "light" | "dark"

// Resolve the theme to use: an explicit stored choice wins, otherwise fall back
// to the OS preference. Kept in one place so the pre-mount application in
// main.tsx and the ThemeMenuItem control can't drift apart.
export function getInitialTheme(): Theme {
  if (typeof window === "undefined") return "light"

  // Wrapped because this one runs at module scope in main.tsx, before React mounts and so before
  // any error boundary exists: `localStorage` throws outright when site data is blocked or in
  // some embedded views, and a throw here is a blank page with nothing in it to explain itself.
  // Degrades to the OS preference, which is what a first-time visitor gets anyway.
  try {
    const stored = localStorage.getItem("theme")
    if (stored === "dark" || stored === "light") return stored
  } catch {
    // Storage unavailable — fall through to the OS preference.
  }

  return window.matchMedia("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light"
}

// Reflect the theme onto the document root — Tailwind's `dark:` variant keys off
// this class. Persisting a user's choice is the caller's job (see ThemeMenuItem).
export function applyThemeClass(theme: Theme): void {
  document.documentElement.classList.toggle("dark", theme === "dark")
}

/**
 * **The theme as one fact with any number of readers** — the user menu's `ThemeMenuItem` and the
 * ⌘K command (busk-chrome plan D10: an immersive window has no user menu, so theme is ⌘K's).
 * A module-level subscribable rather than a `useState` in the control, for the reason
 * `ThemeToggle.tsx`'s docblock gave for allowing exactly one control: two mount-time snapshots
 * drift, and the one you did not press goes on claiming the old theme. It is deliberately *not*
 * `lib/syncStore.ts`, which JSON-encodes: `theme` is stored as the bare string `dark`, which
 * [getInitialTheme] reads at module scope in `main.tsx` before React exists, and re-encoding it
 * would black out the pre-mount paint on every existing desk. Lazily seeded from
 * [getInitialTheme] on first read, so importing this module touches no storage.
 */
let current: Theme | null = null
const listeners = new Set<() => void>()

export function getTheme(): Theme {
  current ??= getInitialTheme()
  return current
}

/** Apply and persist a theme, and re-render every reader. A no-op when unchanged. */
export function setTheme(theme: Theme): void {
  if (getTheme() === theme) return
  current = theme
  applyThemeClass(theme)
  try {
    localStorage.setItem("theme", theme)
  } catch {
    // Quota exhausted or storage unavailable. The class is already on the document, so the theme
    // holds for this session; it just won't survive a reload.
  }
  for (const fn of [...listeners]) fn()
}

export function toggleTheme(): void {
  setTheme(getTheme() === "light" ? "dark" : "light")
}

export function subscribeTheme(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

/** A React reader, re-rendering on every change from any control. */
export function useTheme(): Theme {
  return useSyncExternalStore(subscribeTheme, getTheme, getTheme)
}

/** Test seam: forget the cached choice so the next read seeds from storage again. */
export function resetThemeStore(): void {
  current = null
  listeners.clear()
}
