// @vitest-environment jsdom
import { act, cleanup, render } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

/** ⌘K's *Telemetry…* scrolls to the card every time it is chosen, the same address included. */

vi.mock('@/store/telemetry', () => ({
  useTelemetryQuery: () => ({ data: { enabled: false } }),
  useUpdateTelemetryMutation: () => [vi.fn(), { isLoading: false }],
}))

const { TelemetryCard } = await import('./TelemetryCard')

afterEach(cleanup)

describe('the telemetry anchor', () => {
  it('scrolls again when /install#telemetry is chosen while already there', async () => {
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    const router = createMemoryRouter([{ path: '/install', element: <TelemetryCard /> }], {
      initialEntries: ['/install'],
    })
    render(<RouterProvider router={router} />)
    expect(scroll).not.toHaveBeenCalled()
    await act(() => router.navigate('/install#telemetry'))
    expect(scroll).toHaveBeenCalledTimes(1)
    await act(() => router.navigate('/install#telemetry'))
    expect(scroll).toHaveBeenCalledTimes(2)
  })
})
