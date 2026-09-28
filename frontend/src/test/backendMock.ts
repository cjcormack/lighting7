import { vi } from "vitest"
import type { BootStatus } from "@/api/bootStatusWsApi"

// Shared test helpers for driving the boot-status RTK Query endpoint under
// vitest's jsdom environment. Not a test file (no `.test` suffix), so it isn't
// collected as a suite.

// RTK Query builds `new Request(joinUrls(baseUrl, path))` from restApi's
// relative baseUrl ('/api/rest'). Under jsdom the global Request is still
// undici's, which rejects root-relative URLs ("Failed to parse URL"). Resolve
// them against a dummy origin, preserving the real Request otherwise (headers,
// method, and — crucially — the abort signal). Call inside beforeEach; a
// matching vi.unstubAllGlobals() in afterEach restores the original.
export function installRelativeUrlRequest(): void {
  const RealRequest = globalThis.Request
  class BaseAwareRequest extends RealRequest {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      if (typeof input === "string" && input.startsWith("/")) {
        input = "http://localhost" + input
      }
      super(input, init)
    }
  }
  vi.stubGlobal("Request", BaseAwareRequest)
}

// A fetch mock answering GET /api/rest/status with whatever `getStatus()`
// currently returns. It resolves on a 1ms timer and honours the abort signal
// (RTK carries it on the Request object it passes as fetch's sole argument) —
// an instantly-resolving mock races RTK Query's abort handling under React
// StrictMode and spins into a refetch loop. Returns the vi.fn for call-count
// assertions.
export function installBootStatusFetch(getStatus: () => BootStatus) {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const signal = init?.signal ?? (input instanceof Request ? input.signal : undefined)
    return new Promise<Response>((resolve, reject) => {
      const onAbort = () => {
        clearTimeout(timer)
        reject(new DOMException("Aborted", "AbortError"))
      }
      const timer = setTimeout(() => {
        signal?.removeEventListener("abort", onAbort)
        resolve(
          new Response(JSON.stringify(getStatus()), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        )
      }, 1)
      if (signal) {
        if (signal.aborted) onAbort()
        else signal.addEventListener("abort", onAbort)
      }
    })
  })
  vi.stubGlobal("fetch", fetchMock)
  return fetchMock
}

// A non-2xx response, for testing failure paths. Wrap a route's value in
// `failWith(...)` instead of giving it a plain body.
const MOCK_STATUS = Symbol("mockStatus")
interface MockFailure {
  [MOCK_STATUS]: number
  body: unknown
}

export function failWith(status: number, body: unknown = { message: "mock failure" }): unknown {
  return { [MOCK_STATUS]: status, body } satisfies MockFailure
}

function asFailure(v: unknown): MockFailure | null {
  return typeof v === "object" && v !== null && MOCK_STATUS in v ? (v as MockFailure) : null
}

// A fetch mock that routes requests to canned JSON by URL substring, resolving
// on a 1ms timer and honouring the abort signal (see installBootStatusFetch for
// why). `routes` maps a URL substring → the JSON body to return; unmatched URLs
// get `{}`. Wrap a value in `failWith(status, body)` to return an error instead.
// Returns the vi.fn so tests can assert on `.mock.calls`.
export function installRecordingFetch(routes: Record<string, unknown> = {}) {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = input instanceof Request ? input.url : String(input)
    const signal = init?.signal ?? (input instanceof Request ? input.signal : undefined)
    const match = Object.keys(routes).find((k) => url.includes(k))
    const matched = match ? routes[match] : {}
    const failure = asFailure(matched)
    const body = failure ? failure.body : matched
    const status = failure ? failure[MOCK_STATUS] : 200
    return new Promise<Response>((resolve, reject) => {
      const onAbort = () => {
        clearTimeout(timer)
        reject(new DOMException("Aborted", "AbortError"))
      }
      const timer = setTimeout(() => {
        signal?.removeEventListener("abort", onAbort)
        resolve(
          new Response(JSON.stringify(body), {
            status,
            headers: { "Content-Type": "application/json" },
          }),
        )
      }, 1)
      if (signal) {
        if (signal.aborted) onAbort()
        else signal.addEventListener("abort", onAbort)
      }
    })
  })
  vi.stubGlobal("fetch", fetchMock)
  return fetchMock
}

// lightingApi opens a real WebSocket at import time, which jsdom lacks. These
// two exports replace it and capture the bootStatus WS-bridge callback that
// store/bootStatus.ts subscribes at import, so a test can fire a synthetic
// notification. Used from a `vi.mock("@/api/lightingApi", ...)` factory; vitest
// isolates modules per test file, so `bootStatusWs` is a fresh holder per file.
export const bootStatusWs: { callback: null | (() => void) } = { callback: null }

// Program-state WS bridge callback captured from store/cueStacks.ts, so a test
// can fire a synthetic `showChanged` notification.
export const programStateWs: { callback: null | ((e: unknown) => void) } = { callback: null }

// Run-state WS bridge callback captured from store/cueStacks.ts, so a test can fire a synthetic
// `cueRunStateChanged` frame — the desk (or another browser) moving the show.
export const cueRunStateWs: { callback: null | ((e: unknown) => void) } = { callback: null }

// Patch-list WS bridge callback captured from store/patches.ts, so a test can
// fire a synthetic `patchListChanged` and assert on invalidation batching.
export const patchesWs: { callback: null | (() => void) } = { callback: null }

// Unauthenticated-socket (close code 4401) bridge callback captured from
// store/auth.ts, so a test can fire a synthetic session rejection.
export const authWs: { callback: null | (() => void) } = { callback: null }

// Own-account-changed bridge callback captured from store/auth.ts. Separate from `authWs`
// because the two mean opposite things — a revoked session vs. a still-valid session whose
// account was edited elsewhere — and a test needs to fire one without the other.
export const ownAccountWs: { callback: null | (() => void) } = { callback: null }

// User-list bridge callback captured from store/users.ts, so a test can fire a synthetic
// `userListChanged`.
export const usersWs: { callback: null | (() => void) } = { callback: null }

// Install-row bridge callback captured from store/installs.ts.
export const installWs: { callback: null | (() => void) } = { callback: null }

// Update-state bridge callback captured from store/updates.ts. Payload-carrying, unlike every
// other machine-scoped bridge above — the frame *is* the download progress, so a test needs to
// hand it a real event rather than a bare notification.
export const updatesWs: { callback: null | ((e: unknown) => void) } = { callback: null }

/**
 * Programmer bridge, captured from `store/programmer.ts`.
 *
 * Unlike every holder above this one is **stateful**, because the programmer's client is: consumers
 * read `getState()` / `layers()` synchronously and then wait to be told it changed. A bare callback
 * holder would let a test fire a notification carrying nothing the component could read.
 *
 * `push` sets the state and notifies, which is the shape a real frame has — `programmer.layerState`
 * and `provenanceState` both mutate the snapshot before calling subscribers.
 */
export const programmerWs: {
  callbacks: ((state: unknown) => void)[]
  state: {
    blind: boolean
    entries: Map<string, unknown>
    channels: unknown[]
    provenance: Map<string, unknown>
    lastIncluded: unknown
    layers: unknown[]
    applied: unknown[]
  }
  push: (patch: Partial<typeof programmerWs.state>) => void
  reset: () => void
} = {
  callbacks: [],
  state: {
    blind: false,
    entries: new Map(),
    channels: [],
    provenance: new Map(),
    lastIncluded: null,
    layers: [],
    applied: [],
  },
  push(patch) {
    programmerWs.state = { ...programmerWs.state, ...patch }
    for (const fn of programmerWs.callbacks) fn(programmerWs.state)
  },
  reset() {
    programmerWs.callbacks = []
    programmerWs.state = {
      blind: false,
      entries: new Map(),
      channels: [],
      provenance: new Map(),
      lastIncluded: null,
      layers: [],
      applied: [],
    }
  },
}

// WebSocket readyState bridge, captured from store/status.ts. Unlike the holders above this
// keeps a *list*: two subscribers exist in that module — the module-level reconnect resync and
// the `status` query's cache-entry stream — and a single-callback holder would silently drop
// whichever registered first. `fire` drives all of them, as the real StatusApi does.
export const statusWs: {
  callbacks: ((status: number) => void)[]
  fire: (status: number) => void
} = {
  callbacks: [],
  fire: (status) => {
    for (const fn of [...statusWs.callbacks]) fn(status)
  },
}

const noopSub = () => ({ unsubscribe: () => {} })

/**
 * `selection.state` frames, so a test can drive `store/selection.ts`'s cache entry: `fire` delivers
 * a snapshot to the entry's subscriber and remembers it as the connect snapshot `getState` seeds.
 */
export const selectionWs: {
  callback: null | ((snapshot: unknown) => void)
  last: unknown
  fire: (snapshot: unknown) => void
} = {
  callback: null,
  last: null,
  fire: (snapshot) => {
    selectionWs.last = snapshot
    selectionWs.callback?.(snapshot)
  },
}

/**
 * `busk.pageState` frames, so a test can move the **desk's** showing page: `fire` delivers a page id
 * to the entry's subscriber and remembers it as the connect snapshot `getState` seeds. `sent` is
 * every `busk.setPage` the code under test made, and `landed` is what `setPage` answers — a test
 * sets it false to stand for a socket that is down.
 *
 * `last` starts **`undefined`**, not `null`, because the real `createBuskPageWsApi` distinguishes
 * the two: `undefined` is "no frame yet" and `null` is the desk saying it is pointed at no page, and
 * its `subscribe` replays on `!== undefined` — so it *does* hand a late subscriber an explicit
 * `null`. A mock that could not tell them apart would silently not replay that one frame.
 */
export const buskPageWs: {
  callback: null | ((pageId: number | null) => void)
  last: number | null | undefined
  landed: boolean
  sent: number[]
  fire: (pageId: number | null) => void
  reset: () => void
} = {
  callback: null,
  last: undefined,
  landed: true,
  sent: [],
  fire: (pageId) => {
    buskPageWs.last = pageId
    buskPageWs.callback?.(pageId)
  },
  reset: () => {
    buskPageWs.callback = null
    buskPageWs.last = undefined
    buskPageWs.landed = true
    buskPageWs.sent = []
  },
}

/**
 * `windows.state` frames and the rebroadcast commands, so a test can drive `store/windows.ts`'s
 * cache entry and the bridge hook. `announced` is every announce the code under test sent.
 */
export const windowsWs: {
  callback: null | ((windows: unknown[]) => void)
  commandCallback: null | ((command: unknown) => void)
  last: unknown[] | null
  announced: unknown[]
  sent: unknown[]
  fire: (windows: unknown[]) => void
  command: (command: unknown) => void
  reset: () => void
} = {
  callback: null,
  commandCallback: null,
  last: null,
  announced: [],
  sent: [],
  fire: (windows) => {
    windowsWs.last = windows
    windowsWs.callback?.(windows)
  },
  command: (command) => {
    windowsWs.commandCallback?.(command)
  },
  reset: () => {
    windowsWs.callback = null
    windowsWs.commandCallback = null
    windowsWs.last = null
    windowsWs.announced = []
    windowsWs.sent = []
  },
}

/**
 * The desk's hand: the `hand.state` stream a test fires, and the two writes it records.
 *
 * Spelled out rather than left to the fallback Proxy for `windows`' reason — `store/hand.ts` seeds
 * its cache entry from `getState()` — and for one more: the whole point of the guarded drop is
 * *which argument* it carries, so `dropped` keeps every call rather than counting them.
 */
export const handWs: {
  callback: null | ((held: unknown) => void)
  last: unknown
  pickedUp: unknown[]
  dropped: (string | undefined)[]
  /** What `pickUp` answers — `sendGesture`'s "did this leave the browser". Set false for a dead socket. */
  pickUpReaches: boolean
  fire: (held: unknown) => void
  reset: () => void
} = {
  callback: null,
  last: null,
  pickedUp: [],
  dropped: [],
  pickUpReaches: true,
  fire: (held) => {
    handWs.last = held
    handWs.callback?.(held)
  },
  reset: () => {
    handWs.callback = null
    handWs.last = null
    handWs.pickedUp = []
    handWs.dropped = []
    handWs.pickUpReaches = true
  },
}

/** `busk.layoutChanged` frames, so a test can fire one at `store/busk.ts`'s bridge. */
export const buskWs: { callback: null | ((pageIds: number[]) => void) } = { callback: null }

/** `busk.rigChanged` frames — payload-free — so a test can fire one at the rig bridge. */
export const buskRigWs: { callback: null | (() => void) } = { callback: null }

/**
 * The template library's two frames: `templateListChanged` (payload-free, invalidates) and
 * `templatePressed` (keyed, patched in place). Spelled out rather than left to the fallback Proxy
 * because a test has to *fire* the pressed one — the Proxy can only make subscribing safe.
 */
export const templatesWs: {
  changed: null | (() => void)
  pressed: null | ((event: { templateId: number; lastPressedAt: string }) => void)
} = { changed: null, pressed: null }

export function lightingApiMock() {
  const namespaces: Record<string, unknown> = {
      bootStatus: {
        subscribe: (fn: () => void) => {
          bootStatusWs.callback = fn
          return {
            unsubscribe: () => {
              bootStatusWs.callback = null
            },
          }
        },
      },
      patches: {
        subscribe: (fn: () => void) => {
          patchesWs.callback = fn
          return {
            unsubscribe: () => {
              patchesWs.callback = null
            },
          }
        },
      },
      auth: {
        subscribeUnauthenticated: (fn: () => void) => {
          authWs.callback = fn
          return {
            unsubscribe: () => {
              authWs.callback = null
            },
          }
        },
        subscribeOwnAccountChanged: (fn: () => void) => {
          ownAccountWs.callback = fn
          return {
            unsubscribe: () => {
              ownAccountWs.callback = null
            },
          }
        },
      },
      busk: {
        subscribe: (fn: (pageIds: number[]) => void) => {
          buskWs.callback = fn
          return {
            unsubscribe: () => {
              buskWs.callback = null
            },
          }
        },
        subscribeRigChanged: (fn: () => void) => {
          buskRigWs.callback = fn
          return {
            unsubscribe: () => {
              buskRigWs.callback = null
            },
          }
        },
      },
      templates: {
        subscribe: (fn: () => void) => {
          templatesWs.changed = fn
          return {
            unsubscribe: () => {
              templatesWs.changed = null
            },
          }
        },
        subscribePressed: (fn: (event: { templateId: number; lastPressedAt: string }) => void) => {
          templatesWs.pressed = fn
          return {
            unsubscribe: () => {
              templatesWs.pressed = null
            },
          }
        },
      },
      users: {
        subscribe: (fn: () => void) => {
          usersWs.callback = fn
          return {
            unsubscribe: () => {
              usersWs.callback = null
            },
          }
        },
      },
      install: {
        subscribe: (fn: () => void) => {
          installWs.callback = fn
          return {
            unsubscribe: () => {
              installWs.callback = null
            },
          }
        },
      },
      updates: {
        subscribe: (fn: (e: unknown) => void) => {
          updatesWs.callback = fn
          return {
            unsubscribe: () => {
              updatesWs.callback = null
            },
          }
        },
      },
      // store/status.ts calls `get()` in its queryFn and the auth endpoints call
      // `reconnect(true)` from onQueryStarted. The fallback Proxy below returns a
      // subscriber factory for both — callable, but it would make `get()` answer with
      // a Subscription. Spell the namespace out instead of relying on that.
      status: {
        subscribe: (fn: (status: number) => void) => {
          statusWs.callbacks.push(fn)
          return {
            unsubscribe: () => {
              statusWs.callbacks = statusWs.callbacks.filter((cb) => cb !== fn)
            },
          }
        },
        get: () => 3, // Status.CLOSED — no socket exists under the mock
        reconnect: () => {},
      },
      // Spelled out rather than left to the fallback Proxy for the reason `status` is: the
      // programmer's consumers *read* before they subscribe, and the Proxy would hand `getState()`
      // back a Subscription.
      programmer: {
        getState: () => programmerWs.state,
        layers: () => programmerWs.state.layers,
        applied: () => programmerWs.state.applied,
        isBlind: () => programmerWs.state.blind,
        entryCount: () => programmerWs.state.entries.size,
        lastIncluded: () => programmerWs.state.lastIncluded,
        getKeyState: (targetKey: string, propertyName: string) => ({
          entry: programmerWs.state.entries.get(`${targetKey}|${propertyName}`),
          provenance: programmerWs.state.provenance.get(`${targetKey}|${propertyName}`),
        }),
        subscribe: (fn: (state: unknown) => void) => {
          programmerWs.callbacks.push(fn)
          return {
            unsubscribe: () => {
              programmerWs.callbacks = programmerWs.callbacks.filter((cb) => cb !== fn)
            },
          }
        },
        subscribeToKey: noopSub,
        subscribeToErrors: noopSub,
        // The writers have to be spelled out too, and this is the cost of not being the Proxy any
        // more: it answered every unknown member, so a surface that merely *called* `programmerSet`
        // used to be safe under the mock. Without these, rendering one throws
        // "programmer.set is not a function" — in a test about something else entirely.
        set: () => {},
        setColour: () => {},
        setPosition: () => {},
        clearEntry: () => {},
        clearAll: () => {},
        setBlind: () => {},
        requestState: () => {},
        addLayer: () => {},
        removeLayer: () => {},
        moveLayer: () => {},
        patchLayer: () => {},
      },
      // Spelled out for the reason `status` is: `store/selection.ts`'s `queryFn` seeds its cache
      // entry from `getState()`, and the fallback Proxy would hand it back a Subscription — which
      // then reaches `useBuskingSelection` as a list of targets and is not iterable.
      selection: {
        getState: () => selectionWs.last,
        subscribe: (fn: (snapshot: unknown) => void) => {
          selectionWs.callback = fn
          if (selectionWs.last != null) fn(selectionWs.last)
          return {
            unsubscribe: () => {
              selectionWs.callback = null
            },
          }
        },
        set: () => {},
        toggle: () => {},
        clear: () => {},
        subselect: () => {},
      },
      // Spelled out for the `selection` reason above: `store/busk.ts`'s `buskShowingPage` query
      // seeds its cache entry from `getState()`, and the fallback Proxy would hand it back a
      // Subscription — not a `number | null` — which `BuskingView`'s `activePage` would then
      // compare a page id against and never match, masking the gap rather than surfacing it as a
      // wrong type. `setPage` returns `true` (a landed gesture), matching `sendGesture`'s shape.
      buskPage: {
        getState: () => buskPageWs.last,
        subscribe: (fn: (pageId: number | null) => void) => {
          buskPageWs.callback = fn
          if (buskPageWs.last !== undefined) fn(buskPageWs.last)
          return {
            unsubscribe: () => {
              buskPageWs.callback = null
            },
          }
        },
        setPage: (pageId: number) => {
          buskPageWs.sent.push(pageId)
          return buskPageWs.landed
        },
      },
      hand: {
        getState: () => handWs.last,
        subscribe: (fn: (held: unknown) => void) => {
          handWs.callback = fn
          if (handWs.last != null) fn(handWs.last)
          return {
            unsubscribe: () => {
              handWs.callback = null
            },
          }
        },
        pickUp: (kind: string, id: number) => {
          handWs.pickedUp.push({ kind, id })
          return handWs.pickUpReaches
        },
        drop: (uuid?: string) => {
          handWs.dropped.push(uuid)
        },
      },
      // Spelled out for the `selection` reason: `store/windows.ts` seeds its entry from
      // `getState()`, and the bridge hook reads the announce back off `announced`.
      windows: {
        getState: () => windowsWs.last,
        subscribe: (fn: (windows: unknown[]) => void) => {
          windowsWs.callback = fn
          if (windowsWs.last != null) fn(windowsWs.last)
          return {
            unsubscribe: () => {
              windowsWs.callback = null
            },
          }
        },
        subscribeCommands: (fn: (command: unknown) => void) => {
          windowsWs.commandCallback = fn
          return {
            unsubscribe: () => {
              windowsWs.commandCallback = null
            },
          }
        },
        announce: (payload: unknown) => {
          windowsWs.announced.push(payload)
        },
        lastAnnounce: () => windowsWs.announced[windowsWs.announced.length - 1] ?? null,
        show: (targetId: string, view: string) => {
          windowsWs.sent.push({ type: 'windows.show', targetId, view })
        },
        rename: (targetId: string, name: string) => {
          windowsWs.sent.push({ type: 'windows.rename', targetId, name })
        },
        fullscreen: (targetId: string, on: boolean) => {
          windowsWs.sent.push({ type: 'windows.fullscreen', targetId, on })
        },
        viewOptions: (targetId: string, view: string, options: Record<string, string>) => {
          windowsWs.sent.push({ type: 'windows.viewOptions', targetId, view, options })
        },
        follow: (targetId: string, on: boolean) => {
          windowsWs.sent.push({ type: 'windows.follow', targetId, on })
        },
      },
      cueStacks: {
        subscribe: noopSub,
        subscribeToProgramState: (fn: (e: unknown) => void) => {
          programStateWs.callback = fn
          return {
            unsubscribe: () => {
              programStateWs.callback = null
            },
          }
        },
        subscribeToRunState: (fn: (e: unknown) => void) => {
          cueRunStateWs.callback = fn
          return {
            unsubscribe: () => {
              cueRunStateWs.callback = null
            },
          }
        },
      },
  }

  // Store slices self-register a WS subscription at module load, so merely importing one has to
  // be safe. Rather than enumerate every namespace and every `subscribeToX` variant, fall back to
  // a no-op subscriber for anything the test didn't ask to observe — a new slice then can't break
  // unrelated tests just by existing.
  const anySubscriber = new Proxy({} as Record<string, unknown>, {
    get: () => noopSub,
  })

  return {
    lightingApi: new Proxy(namespaces, {
      get: (target, prop: string) => target[prop] ?? anySubscriber,
    }),
  }
}
