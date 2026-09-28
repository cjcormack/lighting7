// @vitest-environment jsdom
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Store-connected, so the store module is mocked — same shape as DeviceLoginPage.test.tsx,
// which also keeps the import graph away from lightingApi's real WebSocket.
//
// These tests are about *lifecycle*, not rendering: a live sign-in code is a way into the
// account, so what matters is that exactly one is ever minted per reveal and that it is
// cancelled on every path off the screen. The factory is hoisted above everything else in this
// file, so the mocks it returns can't close over helpers declared here.
const createDeviceLogin = vi.fn()
const cancelDeviceLogin = vi.fn()
let statusResult: { data?: { status: string; expiresAt: string } } = {}

vi.mock('@/store/auth', () => ({
  useCreateDeviceLoginMutation: () => [
    () => ({ unwrap: () => createDeviceLogin() }),
    { isLoading: false },
  ],
  // Records on the trigger itself, not inside `unwrap` — the cancels this file is about are
  // fired-and-forgotten from a teardown, which never unwraps.
  useCancelDeviceLoginMutation: () => [
    (args: unknown) => {
      cancelDeviceLogin(args)
      return { unwrap: () => Promise.resolve() }
    },
    { isLoading: false },
  ],
  useRevokeOtherSessionsMutation: () => [
    () => ({ unwrap: () => Promise.resolve() }),
    { isLoading: false },
  ],
  useDeviceLoginStatusQuery: () => statusResult,
}))

import { DeviceLoginSection } from './DeviceLoginSection'

const CODE = {
  id: 'code-1',
  url: 'http://desk.local:8413/device/a-code',
  alternateUrls: [],
  expiresAt: new Date(0).toISOString(),
  displayName: 'Ops Person',
}

beforeEach(() => {
  // `expiresAt` is read against Date.now() for the countdown; keep it in the future so the
  // pending branch renders the way it would on screen.
  createDeviceLogin.mockResolvedValue({ ...CODE, expiresAt: new Date(Date.now() + 120_000).toISOString() })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  statusResult = {}
})

describe('DeviceLoginSection', () => {
  it('mints exactly one code when it appears', async () => {
    render(<DeviceLoginSection active onDone={() => {}} />)

    expect(await screen.findByText(CODE.url)).toBeInTheDocument()
    expect(createDeviceLogin).toHaveBeenCalledTimes(1)
  })

  it('cancels the outstanding code on unmount', async () => {
    const { unmount } = render(<DeviceLoginSection active onDone={() => {}} />)
    await screen.findByText(CODE.url)

    // The property the whole component is built around: hiding the section, and the whole
    // authenticated tree going away behind the login screen, are both this.
    unmount()
    expect(cancelDeviceLogin).toHaveBeenCalledWith({ id: 'code-1' })
  })

  it('cancels when it goes inactive without unmounting', async () => {
    const { rerender } = render(<DeviceLoginSection active onDone={() => {}} />)
    await screen.findByText(CODE.url)

    // The parent sheet closing. Radix keeps its content mounted for the ~300 ms exit
    // animation, so a mount-scoped cancel alone would leave the code live through it.
    rerender(<DeviceLoginSection active={false} onDone={() => {}} />)
    expect(cancelDeviceLogin).toHaveBeenCalledWith({ id: 'code-1' })
  })

  it('cancels a code that arrives after it has gone', async () => {
    let resolveMint: (value: unknown) => void = () => {}
    createDeviceLogin.mockReturnValue(new Promise((resolve) => { resolveMint = resolve }))

    const { unmount } = render(<DeviceLoginSection active onDone={() => {}} />)
    unmount()

    // Nothing was in `liveCode` when the teardown ran, so the mint has to cancel itself.
    // Otherwise a code nobody has ever seen stays exchangeable for its full TTL.
    resolveMint({ ...CODE, expiresAt: new Date(Date.now() + 120_000).toISOString() })
    await vi.waitFor(() => expect(cancelDeviceLogin).toHaveBeenCalledWith({ id: 'code-1' }))
  })

  // The app runs in StrictMode, so development mounts every effect, tears it down and mounts
  // it again. Both assertions here failed against a first cut that passed every test above:
  // the QR arrived already cancelled, and a second code was left live behind it.
  describe('under StrictMode', () => {
    let mintCount = 0
    beforeEach(() => {
      mintCount = 0
      createDeviceLogin.mockImplementation(() =>
        Promise.resolve({ ...CODE, id: `code-${++mintCount}`, expiresAt: new Date(Date.now() + 120_000).toISOString() }),
      )
    })

    it('still shows a live code', async () => {
      render(
        <StrictMode>
          <DeviceLoginSection active onDone={() => {}} />
        </StrictMode>,
      )

      // A flag cleared by a teardown but never restored by the matching setup reads `false`
      // while the section is on screen, so every code cancels itself on arrival.
      expect(await screen.findByText(CODE.url)).toBeInTheDocument()
      await vi.waitFor(() =>
        expect(screen.queryByText(/no longer valid/)).not.toBeInTheDocument(),
      )
    })

    it('mints once, not once per effect invocation', async () => {
      render(
        <StrictMode>
          <DeviceLoginSection active onDone={() => {}} />
        </StrictMode>,
      )
      await screen.findByText(CODE.url)

      // Minting is a server-side create, and the backend retires the caller's previous code on
      // every mint. Two mints therefore race: resolved backwards, the section displays a QR the
      // server has already cancelled, and no client-side repair is possible because the client
      // cannot know which mint the server saw last. So the count itself is the contract.
      expect(mintCount).toBe(1)
      expect(createDeviceLogin).toHaveBeenCalledTimes(1)
    })
  })

  it('offers a retry when the mint itself fails', async () => {
    createDeviceLogin.mockRejectedValue({ status: 500, data: { error: 'Server exploded' } })
    render(<DeviceLoginSection active onDone={() => {}} />)

    // A failed mint leaves no code, so the EXPIRED/CANCELLED "Show a new code" branch can't
    // render — without a retry here the tab is an error message with nothing to press, and the
    // one-mint-per-mount guard means simply waiting never helps.
    const retry = await screen.findByRole('button', { name: /Try again/ })

    createDeviceLogin.mockResolvedValue({ ...CODE, expiresAt: new Date(Date.now() + 120_000).toISOString() })
    fireEvent.click(retry)
    expect(await screen.findByText(CODE.url)).toBeInTheDocument()
  })

  it('offers the undo once a device has taken the code', async () => {
    statusResult = { data: { status: 'USED', expiresAt: new Date(Date.now() + 120_000).toISOString() } }
    render(<DeviceLoginSection active onDone={() => {}} />)

    // No confirmation step exists, so detect-and-undo is the whole control.
    expect(await screen.findByText(/Signed in as Ops Person/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /sign out every other device/ }),
    ).toBeInTheDocument()
    // Settled: the code is spent, so there is nothing left to cancel on the way out.
    expect(screen.queryByText(CODE.url)).not.toBeInTheDocument()
  })
})
