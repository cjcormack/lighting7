// @vitest-environment jsdom
import { StrictMode } from "react"
import { Provider } from "react-redux"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { installRecordingFetch, installRelativeUrlRequest } from "@/test/backendMock"
import type { AuthStatus } from "@/store/auth"

// lightingApi opens a real WebSocket at import time and jsdom has none, so mock it.
// store/auth.ts registers its 4401 bridge against the mock at import.
vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

import { AuthGate } from "./AuthGate"
import { restApi } from "./store/restApi"
import { store } from "./store"

const CHILD_TEXT = "APP CONTENT"

const SIGNED_IN: AuthStatus = {
  setupRequired: false,
  authenticated: true,
  user: { uuid: "u-1", username: "chris", displayName: "Chris", role: "ADMIN" },
}
const SIGNED_OUT: AuthStatus = { setupRequired: false, authenticated: false }
const NEEDS_SETUP: AuthStatus = { setupRequired: true, authenticated: false }

let fetchMock: ReturnType<typeof installRecordingFetch>

beforeEach(() => {
  installRelativeUrlRequest()
})

// The global cleanup (src/test/setup.ts) runs AFTER this hook — vitest unwinds
// afterEach in reverse registration order — and this suite must unmount before it
// resets the store and pulls the fetch mock, or the still-mounted gate refetches into
// nothing. cleanup() is idempotent, so calling it here just fixes the ordering.
afterEach(() => {
  cleanup()
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
})

// Rendered in StrictMode (as production does) so the tests stay honest about
// double-invoked effects; assertions are on observable DOM, never render counts.
function renderGate(bypass = false) {
  return render(
    <StrictMode>
      <Provider store={store}>
        <AuthGate bypass={bypass}>
          <div>{CHILD_TEXT}</div>
        </AuthGate>
      </Provider>
    </StrictMode>,
  )
}

describe("AuthGate", () => {
  it("gates children behind a spinner until the first status lands", () => {
    fetchMock = installRecordingFetch({ "auth/status": SIGNED_IN })
    // Assert synchronously, before the (1ms-delayed) first fetch resolves.
    const { container } = renderGate()

    expect(container.querySelector(".animate-spin")).toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("renders the app once the session is authenticated", async () => {
    fetchMock = installRecordingFetch({ "auth/status": SIGNED_IN })
    renderGate()

    await screen.findByText(CHILD_TEXT)
    expect(screen.queryByRole("button", { name: /sign in/i })).not.toBeInTheDocument()
  })

  it("shows the login screen when nobody is signed in", async () => {
    fetchMock = installRecordingFetch({ "auth/status": SIGNED_OUT })
    renderGate()

    await screen.findByRole("button", { name: /sign in/i })
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("shows the setup screen when the desk has no users yet", async () => {
    fetchMock = installRecordingFetch({ "auth/status": NEEDS_SETUP })
    renderGate()

    await screen.findByRole("button", { name: /create account/i })
    expect(screen.getByText(/set up this desk/i)).toBeInTheDocument()
    // Setup wins over `authenticated: false` — bootstrap-open needs an account,
    // not a login form nobody could satisfy.
    expect(screen.queryByRole("button", { name: /^sign in$/i })).not.toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("reports an unreachable backend rather than offering a dead login form", async () => {
    // A network-level failure: nothing cached, so there is no stale status to trust.
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))),
    )
    renderGate()

    await screen.findByText(/can't reach the desk/i)
    expect(screen.queryByRole("button", { name: /sign in/i })).not.toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("renders children immediately when bypassed, without asking who is signed in", async () => {
    fetchMock = installRecordingFetch({ "auth/status": SIGNED_OUT })
    renderGate(true)

    expect(screen.getByText(CHILD_TEXT)).toBeInTheDocument()
    // The public reset page must not depend on the auth endpoint answering at all.
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
