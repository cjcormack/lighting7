// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ prove: vi.fn(), reportUnlinked: vi.fn() }))

vi.mock('@/lib/remoteMode', () => ({ reportUnlinked: mocks.reportUnlinked }))
vi.mock('@/store/cloudLinks', () => ({
  useProveCloudLinkMutation: () => [(args: unknown) => ({ unwrap: () => mocks.prove(args) }), { isLoading: false }],
}))

import { RemoteUnlinkedScreen } from './RemoteAuthScreens'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

const fill = (username: string, password: string) => {
  fireEvent.change(screen.getByLabelText('Desk username'), { target: { value: username } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
}

describe('RemoteUnlinkedScreen', () => {
  it('tells the shell, and proves the link with a trimmed username', async () => {
    mocks.prove.mockResolvedValue({ authenticated: true })
    render(<RemoteUnlinkedScreen email="sam@example.com" />)
    expect(mocks.reportUnlinked).toHaveBeenCalled()
    const submit = screen.getByRole('button', { name: 'Link and sign in' })
    expect(submit).toBeDisabled()
    fill(' sam ', 'secret')
    fireEvent.click(submit)
    await vi.waitFor(() => expect(mocks.prove).toHaveBeenCalledWith({ username: 'sam', password: 'secret' }))
  })

  it('shows a refusal and clears the password', async () => {
    mocks.prove.mockRejectedValue({ status: 401, data: { error: 'Incorrect username or password', code: 'unauthenticated' } })
    render(<RemoteUnlinkedScreen email={null} />)
    fill('sam', 'wrong')
    fireEvent.click(screen.getByRole('button', { name: 'Link and sign in' }))
    expect(await screen.findByText('Incorrect username or password')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })
})
