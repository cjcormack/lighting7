// @vitest-environment jsdom
import { StrictMode } from "react"
import { Provider } from "react-redux"
import { act, cleanup, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { BootStatus } from "@/api/bootStatusWsApi"
import {
  bootStatusWs,
  installBootStatusFetch,
  installRelativeUrlRequest,
} from "@/test/backendMock"

// lightingApi opens a real WebSocket at import time and jsdom has none, so mock
// it. Capturing its `bootStatus.subscribe` callback (via bootStatusWs) also lets
// us drive the WS bridge (invalidateTags -> refetch) that bootStatus.ts wires up
// at import.
vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

import { BootGate } from "./BootGate"
import { restApi } from "./store/restApi"
import { store } from "./store"

// Poll interval in BootGate.tsx; a wait longer than this proves polling stopped.
const POLL_MS = 400

const FX_COMPILE: BootStatus = {
  phase: "FX_COMPILE",
  message: "Compiling effects (12/28)…",
  percent: 45,
  ready: false,
  error: null,
}
const READY: BootStatus = {
  phase: "READY",
  message: "Ready",
  percent: 100,
  ready: true,
  error: null,
}
const FAILED: BootStatus = {
  phase: "FAILED",
  message: "",
  percent: 0,
  ready: false,
  error: "FX compile failed: syntax error",
}

const CHILD_TEXT = "APP CONTENT"

// The status the (mocked) backend currently reports; mutate it between polls.
let currentStatus: BootStatus
let fetchMock: ReturnType<typeof installBootStatusFetch>

beforeEach(() => {
  currentStatus = { ...FX_COMPILE }
  installRelativeUrlRequest()
  fetchMock = installBootStatusFetch(() => currentStatus)
})

// The global cleanup (src/test/setup.ts) would run AFTER this hook — vitest unwinds
// afterEach in reverse registration order — and this suite must unmount before it resets
// the store and pulls the fetch mock, or the still-mounted gate refetches into nothing.
// cleanup() is idempotent, so calling it here just fixes the ordering.
afterEach(() => {
  cleanup()
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
})

// Rendered in StrictMode (as production does) so the tests stay honest about
// double-invoked effects; assertions are on observable DOM, never render counts.
function renderGate() {
  return render(
    <StrictMode>
      <Provider store={store}>
        <BootGate>
          <div>{CHILD_TEXT}</div>
        </BootGate>
      </Provider>
    </StrictMode>,
  )
}

function progressBar(container: HTMLElement) {
  return container.querySelector<HTMLElement>(".bg-primary")
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

describe("BootGate", () => {
  it("shows the connecting overlay and gates children before the first response", () => {
    // Assert synchronously, before the (1ms-delayed) first fetch resolves.
    const { container } = renderGate()

    expect(screen.getByText(/starting up/i)).toBeInTheDocument()
    expect(
      screen.getByText(/connecting to the lighting controller/i),
    ).toBeInTheDocument()
    expect(container.querySelector(".animate-spin")).toBeInTheDocument()
    expect(progressBar(container)).toHaveStyle({ width: "0%" })
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("reflects a non-ready phase: percent, message and humanised phase, still gated", async () => {
    currentStatus = { ...FX_COMPILE }
    const { container } = renderGate()

    // "FX_COMPILE" is humanised to "FX COMPILE" (underscores -> spaces).
    await screen.findByText("FX COMPILE")
    expect(progressBar(container)).toHaveStyle({ width: "45%" })
    expect(screen.getByText("Compiling effects (12/28)…")).toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("falls back to 0% and default copy when the status fields are nullish", async () => {
    // Data present, but percent/message absent -> the `?? 0` / `?? fallback`.
    currentStatus = {
      phase: "STARTING",
      ready: false,
      error: null,
    } as unknown as BootStatus
    const { container } = renderGate()

    await screen.findByText("STARTING")
    expect(progressBar(container)).toHaveStyle({ width: "0%" })
    expect(
      screen.getByText(/connecting to the lighting controller/i),
    ).toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("shows a destructive alert with the error on a FAILED boot, and stops polling", async () => {
    currentStatus = { ...FAILED }
    const { container } = renderGate()

    const alert = await screen.findByRole("alert")
    expect(screen.getByText("Startup failed")).toBeInTheDocument()
    expect(alert).toHaveTextContent("Show initialisation failed")
    expect(alert).toHaveTextContent("FX compile failed: syntax error")
    // Failure state swaps the spinner for a warning icon.
    expect(container.querySelector(".animate-spin")).not.toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()

    // Settled (failed) -> polling stopped: no further fetches over time.
    const calls = fetchMock.mock.calls.length
    await wait(POLL_MS + 200)
    expect(fetchMock.mock.calls.length).toBe(calls)
  })

  it("shows the fallback error copy when a FAILED boot has no error string", async () => {
    currentStatus = { ...FAILED, error: null }
    renderGate()

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("The lighting show failed to start.")
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("renders children and no overlay once ready", async () => {
    currentStatus = { ...READY }
    renderGate()

    await screen.findByText(CHILD_TEXT)
    expect(screen.queryByText(/starting up/i)).not.toBeInTheDocument()
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })

  it("replaces the overlay with children when a later poll returns ready", async () => {
    currentStatus = { ...FX_COMPILE }
    renderGate()

    await screen.findByText("FX COMPILE")
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()

    // The next poll picks up readiness and the gate hands off to the app.
    currentStatus = { ...READY }
    await screen.findByText(CHILD_TEXT)
    expect(screen.queryByText(/starting up/i)).not.toBeInTheDocument()
  })

  it("stops polling once ready (no fetches after settling)", async () => {
    currentStatus = { ...READY }
    renderGate()

    await screen.findByText(CHILD_TEXT)
    const calls = fetchMock.mock.calls.length
    await wait(POLL_MS + 200)
    expect(fetchMock.mock.calls.length).toBe(calls)
  })

  it("re-shows the overlay when a WS bootProgressState re-enters warm-up after ready", async () => {
    currentStatus = { ...READY }
    renderGate()
    await screen.findByText(CHILD_TEXT)

    // A pushed frame invalidates the cached status; the now-non-ready refetch
    // flips `ready` back to false and the gate re-appears. Drives the WS bridge
    // + component together via the captured lightingApi.bootStatus callback.
    expect(bootStatusWs.callback).toBeTypeOf("function")
    currentStatus = {
      phase: "SHOW_INIT",
      message: "Re-initialising show…",
      percent: 10,
      ready: false,
      error: null,
    }
    await act(async () => {
      bootStatusWs.callback!()
    })

    await screen.findByText("Re-initialising show…")
    expect(screen.getByText("SHOW INIT")).toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })
})
