import { afterEach, describe, expect, it, vi } from "vitest"
import { applyThemeClass, getInitialTheme } from "./theme"

// theme.ts reaches for browser globals (window, localStorage, document). This
// suite runs in the default node environment, so we stand up just enough of
// each to exercise the real logic without pulling in jsdom.

function fakeLocalStorage(initial: Record<string, string> = {}) {
  const store = new Map<string, string>(Object.entries(initial))
  return {
    getItem: (key: string) => (store.has(key) ? store.get(key)! : null),
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
  }
}

// Minimal DOMTokenList stand-in backed by a Set — enough for classList.toggle
// (both the one-arg flip and the two-arg force form) and contains().
function fakeClassList() {
  const classes = new Set<string>()
  return {
    toggle(token: string, force?: boolean): boolean {
      const shouldHave = force === undefined ? !classes.has(token) : force
      if (shouldHave) classes.add(token)
      else classes.delete(token)
      return shouldHave
    },
    contains: (token: string) => classes.has(token),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("getInitialTheme", () => {
  function stubEnv(opts: { stored?: string | null; prefersDark: boolean }) {
    const matchMedia = vi.fn((query: string) => ({
      matches: query.includes("dark") ? opts.prefersDark : !opts.prefersDark,
    }))
    vi.stubGlobal("window", { matchMedia })
    vi.stubGlobal(
      "localStorage",
      fakeLocalStorage(opts.stored != null ? { theme: opts.stored } : {}),
    )
    return matchMedia
  }

  it('returns the stored choice when it is "dark" (ignoring OS preference)', () => {
    stubEnv({ stored: "dark", prefersDark: false })
    expect(getInitialTheme()).toBe("dark")
  })

  it('returns the stored choice when it is "light" (ignoring OS preference)', () => {
    stubEnv({ stored: "light", prefersDark: true })
    expect(getInitialTheme()).toBe("light")
  })

  it("falls back to the OS preference (dark) when nothing is stored", () => {
    const matchMedia = stubEnv({ stored: null, prefersDark: true })
    expect(getInitialTheme()).toBe("dark")
    expect(matchMedia).toHaveBeenCalledWith("(prefers-color-scheme: dark)")
  })

  it("falls back to the OS preference (light) when nothing is stored", () => {
    stubEnv({ stored: null, prefersDark: false })
    expect(getInitialTheme()).toBe("light")
  })

  it("ignores an unrecognised stored value and uses the OS preference", () => {
    stubEnv({ stored: "banana", prefersDark: true })
    expect(getInitialTheme()).toBe("dark")
  })

  it('returns "light" when there is no window (no DOM available)', () => {
    // No window stub — the `typeof window === "undefined"` short-circuit wins
    // before localStorage/matchMedia are ever touched.
    expect(getInitialTheme()).toBe("light")
  })
})

describe("applyThemeClass", () => {
  function stubDocument() {
    const classList = fakeClassList()
    vi.stubGlobal("document", { documentElement: { classList } })
    return classList
  }

  it('adds the "dark" class for the dark theme', () => {
    const classList = stubDocument()
    applyThemeClass("dark")
    expect(classList.contains("dark")).toBe(true)
  })

  it('removes the "dark" class for the light theme', () => {
    const classList = stubDocument()
    classList.toggle("dark", true) // start out dark
    applyThemeClass("light")
    expect(classList.contains("dark")).toBe(false)
  })

  it("is idempotent when applied repeatedly", () => {
    const classList = stubDocument()
    applyThemeClass("dark")
    applyThemeClass("dark")
    expect(classList.contains("dark")).toBe(true)

    applyThemeClass("light")
    applyThemeClass("light")
    expect(classList.contains("dark")).toBe(false)
  })
})
