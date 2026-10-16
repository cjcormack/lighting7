// @vitest-environment jsdom
import { Provider } from "react-redux"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { installRelativeUrlRequest } from "@/test/backendMock"
import type { AuthStatus } from "@/store/auth"

vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

import { OnboardingGate } from "./OnboardingGate"
import { onboardingImportsStore, onboardingStepStore } from "./onboardingModel"
import { restApi } from "@/store/restApi"
import { store } from "@/store"

const CHILD_TEXT = "APP CONTENT"

const ADMIN: AuthStatus = {
  setupRequired: false,
  authenticated: true,
  user: { uuid: "u-1", username: "chris", displayName: "Chris", role: "ADMIN" },
}
const OPERATOR: AuthStatus = {
  ...ADMIN,
  user: { uuid: "u-2", username: "op", displayName: "Op", role: "OPERATOR" },
}

const SHOW_REPO = {
  fullName: "me/panto",
  name: "panto",
  owner: "me",
  private: true,
  defaultBranch: "main",
  htmlUrl: "https://github.com/me/panto",
  cloneUrl: "https://github.com/me/panto.git",
  description: null,
  pushPermission: true,
  lightingProject: true,
  projectName: "Panto",
  projectDescription: null,
  projectUuid: "p-1",
  projectKind: "SHOW",
}
const VENUE_REPO = {
  ...SHOW_REPO,
  fullName: "me/hall",
  name: "hall",
  htmlUrl: "https://github.com/me/hall",
  cloneUrl: "https://github.com/me/hall.git",
  projectName: "Village Hall",
  projectUuid: "v-1",
  projectKind: "VENUE",
}
const IMPORTED: Record<string, { projectId: number; name: string }> = {
  [SHOW_REPO.cloneUrl]: { projectId: 7, name: "Panto" },
  [VENUE_REPO.cloneUrl]: { projectId: 8, name: "Village Hall" },
}

/** An error answer: `installBackend` sends [body] with [status]. */
class Fail {
  constructor(
    readonly status: number,
    readonly body: unknown,
  ) {}
}
const failWith = (status: number, body: unknown) => new Fail(status, body)
const asMockFailure = (v: unknown) => (v instanceof Fail ? v : null)

interface Call {
  method: string
  url: string
  body: unknown
}

/**
 * A fetch that answers by URL substring — first match in insertion order, so a longer path is
 * listed before its prefix — and records every request with its parsed body.
 */
function installBackend(routes: Record<string, (body: unknown) => unknown>) {
  const calls: Call[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init)
    const text = await request.clone().text()
    const body = text ? JSON.parse(text) : undefined
    calls.push({ method: request.method, url: request.url, body })
    const match = Object.keys(routes).find((k) => request.url.includes(k))
    const answer = match ? routes[match](body) : {}
    const failure = asMockFailure(answer)
    return new Response(JSON.stringify(failure ? failure.body : answer), {
      status: failure ? failure.status : 200,
      headers: { "Content-Type": "application/json" },
    })
  })
  vi.stubGlobal("fetch", fetchMock)
  return calls
}

function backend(
  auth: AuthStatus,
  onboardingPending: boolean,
  { projectNames = ["Default"], finished = false }: { projectNames?: string[]; finished?: boolean } = {},
) {
  return installBackend({
    "auth/status": () => auth,
    "install/onboarding/finish": (body) =>
      finished
        ? failWith(409, { error: "Onboarding is already finished", code: "ONBOARDING_DONE" })
        : {
            currentProjectId: (body as { openProjectId?: number }).openProjectId ?? 1,
            removedPlaceholder: null,
            onboardingPending: false,
          },
    "install/telemetry": () => ({
      enabled: false,
      release: "lighting7@test",
      environment: "development",
      tracesSampleRate: 0,
      replaysSessionSampleRate: 0,
      replaysOnErrorSampleRate: 0,
    }),
    install: () => ({ uuid: "i-1", friendlyName: "Desk", createdAt: "2026-10-14 00:00:00.000Z", onboardingPending }),
    "oauth/github/identity": () => ({ connected: true, oauthConfigured: true, login: "me" }),
    "oauth/github/repositories": () => [SHOW_REPO, VENUE_REPO],
    "cloud-sync/configs": () => ({}),
    "cloud-sync/import": (body) => {
      const imported = IMPORTED[(body as { repoUrl: string }).repoUrl]
      return { ...imported, projectUuid: "u" }
    },
    "projects/current": () => ({ id: 1, name: "Default", description: null, isCurrent: true }),
    "projects/1": (body) => ({ id: 1, name: (body as { name: string }).name, description: null, isCurrent: true }),
    "rest/projects": () => projectNames.map((name, i) => ({ id: i + 1, name, description: null, isCurrent: i === 0 })),
  })
}

// The gate lazy-loads the screen, and the first test to reach it would otherwise pay for Vitest
// transforming the screen's whole module graph inside a 1 s `findBy`.
beforeAll(async () => {
  await import("./OnboardingScreen")
})

beforeEach(() => {
  installRelativeUrlRequest()
  window.sessionStorage.clear()
  onboardingStepStore.reset()
  onboardingImportsStore.reset()
})

afterEach(() => {
  cleanup()
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
})

function renderGate() {
  return render(
    <Provider store={store}>
      <OnboardingGate>
        <div>{CHILD_TEXT}</div>
      </OnboardingGate>
    </Provider>,
  )
}

describe("OnboardingGate", () => {
  it("shows the app to an admin once onboarding is done", async () => {
    backend(ADMIN, false)
    renderGate()
    await screen.findByText(CHILD_TEXT)
  })

  it("shows an operator the app without asking the install", async () => {
    const calls = backend(OPERATOR, true)
    renderGate()
    await screen.findByText(CHILD_TEXT)
    expect(calls.some((c) => c.url.endsWith("/install"))).toBe(false)
  })

  it("asks a new desk's admin about diagnostics before the app", async () => {
    backend(ADMIN, true)
    renderGate()
    await screen.findByRole("radiogroup", { name: "Diagnostics" })
    expect(screen.getByText("Send diagnostics?")).toBeInTheDocument()
    expect(screen.queryByText(CHILD_TEXT)).not.toBeInTheDocument()
  })

  it("writes the diagnostics choice and moves on to projects", async () => {
    const calls = backend(ADMIN, true)
    renderGate()
    fireEvent.click(await screen.findByRole("radio", { name: /^Send diagnostics/ }))
    fireEvent.click(screen.getByRole("button", { name: "Continue" }))
    await screen.findByText("Your projects")
    const put = calls.find((c) => c.method === "PUT" && c.url.includes("install/telemetry"))
    expect(put?.body).toEqual({ enabled: true })
  })

  it("asks rather than assuming the build's default", async () => {
    const calls = backend(ADMIN, true)
    renderGate()
    const continueButton = await screen.findByRole("button", { name: "Continue" })
    expect(screen.getAllByRole("radio").every((r) => r.getAttribute("aria-checked") === "false")).toBe(true)
    expect(continueButton).toBeDisabled()

    // "Don't send" is what the desk already holds, and is written all the same.
    fireEvent.click(screen.getByRole("radio", { name: /^Don't send/ }))
    fireEvent.click(continueButton)
    await screen.findByText("Your projects")
    const put = calls.find((c) => c.method === "PUT" && c.url.includes("install/telemetry"))
    expect(put?.body).toEqual({ enabled: false })
  })

  it("imports venues before shows and opens the first show on finish", async () => {
    onboardingStepStore.set("projects")
    const calls = backend(ADMIN, true)
    renderGate()

    fireEvent.click(await screen.findByLabelText(/Panto/))
    fireEvent.click(screen.getByLabelText(/Village Hall/))
    fireEvent.click(screen.getByRole("button", { name: "Import 2" }))
    await waitFor(() => expect(screen.getAllByText("Imported")).toHaveLength(2))

    const imports = calls.filter((c) => c.url.includes("cloud-sync/import"))
    expect(imports.map((c) => (c.body as { repoUrl: string }).repoUrl)).toEqual([
      VENUE_REPO.cloneUrl,
      SHOW_REPO.cloneUrl,
    ])
    expect(imports[0].body).toEqual({ repoUrl: VENUE_REPO.cloneUrl, branch: "main", projectName: "Village Hall" })

    fireEvent.click(screen.getByRole("button", { name: "Finish" }))
    await waitFor(() =>
      expect(calls.find((c) => c.url.includes("install/onboarding/finish"))?.body).toEqual({ openProjectId: 7 }),
    )
  })

  it("names the blank project the desk booted on and keeps it", async () => {
    onboardingStepStore.set("projects")
    const calls = backend(ADMIN, true)
    renderGate()

    const name = await screen.findByLabelText("Or start with a blank project")
    await waitFor(() => expect(name).toHaveValue("Default"))
    fireEvent.change(name, { target: { value: "Spring Gala" } })
    fireEvent.click(screen.getByRole("button", { name: "Start with a blank project" }))

    await waitFor(() =>
      expect(calls.find((c) => c.url.includes("install/onboarding/finish"))?.body).toEqual({}),
    )
    const rename = calls.find((c) => c.method === "PUT" && c.url.includes("projects/1"))
    expect(rename?.body).toEqual({ name: "Spring Gala" })
    const renameAt = calls.indexOf(rename!)
    const finishAt = calls.findIndex((c) => c.url.includes("install/onboarding/finish"))
    expect(renameAt).toBeLessThan(finishAt)
  })

  it("numbers a repo named like a project already on the desk, and keeps its import over Back", async () => {
    onboardingStepStore.set("projects")
    const calls = backend(ADMIN, true, { projectNames: ["Default", "Village Hall"] })
    renderGate()

    fireEvent.click(await screen.findByLabelText(/Village Hall/))
    fireEvent.click(screen.getByRole("button", { name: "Import 1" }))
    await screen.findByText("Imported")
    const imported = calls.find((c) => c.url.includes("cloud-sync/import"))
    expect((imported?.body as { projectName: string }).projectName).toBe("Village Hall (2)")

    fireEvent.click(screen.getByRole("button", { name: "Back" }))
    fireEvent.click(await screen.findByRole("radio", { name: /^Don't send/ }))
    fireEvent.click(screen.getByRole("button", { name: "Continue" }))
    await screen.findByLabelText("Open first")
    expect(screen.getByRole("button", { name: "Finish" })).toBeEnabled()
  })

  it("takes a finish another window already made as done", async () => {
    onboardingStepStore.set("projects")
    const calls = backend(ADMIN, true, { finished: true })
    renderGate()

    const start = await screen.findByRole("button", { name: "Start with a blank project" })
    await waitFor(() => expect(start).toBeEnabled())
    fireEvent.click(start)
    await waitFor(() =>
      expect(calls.filter((c) => c.url.endsWith("/install") && c.method === "GET").length).toBeGreaterThan(1),
    )
    expect(screen.queryByText("Onboarding is already finished")).not.toBeInTheDocument()
  })
})
