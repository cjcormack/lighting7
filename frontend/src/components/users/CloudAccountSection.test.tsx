// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  cloud: { data: undefined } as { data: unknown },
  invite: vi.fn(),
  cancel: vi.fn(),
  unlink: vi.fn(),
}))

vi.mock('@/store/cloud', () => ({ useCloudStateQuery: () => mocks.cloud }))
vi.mock('@/store/cloudLinks', () => ({
  CLOUD_LINK_VIA_LABELS: { ADOPTION: 'linked when this desk was adopted', INVITE: 'linked by invite', PROVED: 'linked by signing in once' },
  useInviteCloudAccountMutation: () => [(args: unknown) => ({ unwrap: () => mocks.invite(args) }), { isLoading: false }],
  useCancelCloudInviteMutation: () => [(args: unknown) => ({ unwrap: () => mocks.cancel(args) }), { isLoading: false }],
  useUnlinkCloudAccountMutation: () => [(args: unknown) => ({ unwrap: () => mocks.unlink(args) }), { isLoading: false }],
}))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { CloudAccountCell, CloudAccountSection } from './CloudAccountSection'
import type { DeskUser } from '@/store/users'

const base: DeskUser = {
  id: 3,
  uuid: 'u-3',
  username: 'sam',
  displayName: 'Sam',
  role: 'OPERATOR',
  disabled: false,
  createdAt: new Date(0).toISOString(),
}
const link = { email: 'sam@example.com', linkedAt: new Date(0).toISOString(), linkedVia: 'PROVED' as const }
const invite = {
  inviteId: 'inv-1',
  email: 'sam@example.com',
  createdAt: new Date(0).toISOString(),
  expiresAt: new Date(7 * 86_400_000).toISOString(),
}
const adopted = (connection = 'ONLINE') => ({
  data: { configured: true, enabled: true, connection, adopted: { installId: 'i', orgName: 'The Venue', adoptedAt: '', fingerprint: '' } },
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  mocks.cloud = { data: undefined }
})

describe('CloudAccountCell', () => {
  it('names the linked account, an invite, or nothing', () => {
    const { rerender } = render(<CloudAccountCell user={{ ...base, cloudLink: link }} />)
    expect(screen.getByText('sam@example.com')).toBeInTheDocument()
    rerender(<CloudAccountCell user={{ ...base, cloudInvite: invite }} />)
    expect(screen.getByText('Invited')).toBeInTheDocument()
    rerender(<CloudAccountCell user={base} />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })
})

describe('CloudAccountSection', () => {
  it('draws nothing on a desk that is not adopted', () => {
    const { container } = render(<CloudAccountSection user={base} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('invites an email, saying the organisation approves it first', async () => {
    mocks.cloud = adopted()
    mocks.invite.mockResolvedValue(invite)
    render(<CloudAccountSection user={base} />)
    fireEvent.click(screen.getByRole('button', { name: 'Invite a cloud account…' }))
    expect(screen.getByText(/owner or admin of The Venue approves the invite/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Their Lighting7 Cloud email'), { target: { value: ' sam@example.com ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send invite' }))
    await vi.waitFor(() => expect(mocks.invite).toHaveBeenCalledWith({ userId: 3, email: 'sam@example.com' }))
  })

  it('shows the desk refusing an invite inline', async () => {
    mocks.cloud = adopted()
    mocks.invite.mockRejectedValue({ status: 409, data: { error: 'Sam already has an invite waiting: cancel it first.', code: 'CLOUD_INVITE_PENDING' } })
    render(<CloudAccountSection user={base} />)
    fireEvent.click(screen.getByRole('button', { name: 'Invite a cloud account…' }))
    fireEvent.change(screen.getByLabelText('Their Lighting7 Cloud email'), { target: { value: 'sam@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send invite' }))
    expect(await screen.findByText(/already has an invite waiting/)).toBeInTheDocument()
  })

  it('offers no invite while the desk is offline to the cloud', () => {
    mocks.cloud = adopted('RETRYING')
    render(<CloudAccountSection user={base} />)
    expect(screen.getByRole('button', { name: 'Invite a cloud account…' })).toBeDisabled()
    expect(screen.getByText(/isn't online to the cloud/)).toBeInTheDocument()
  })

  it('cancels a waiting invite', async () => {
    mocks.cloud = adopted()
    mocks.cancel.mockResolvedValue(undefined)
    render(<CloudAccountSection user={{ ...base, cloudInvite: invite }} />)
    expect(screen.getByText(/Waiting for an owner or admin of The Venue/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel invite' }))
    await vi.waitFor(() => expect(mocks.cancel).toHaveBeenCalledWith({ userId: 3 }))
  })

  it('unlinks a linked account after a confirm', async () => {
    mocks.cloud = adopted()
    mocks.unlink.mockResolvedValue(undefined)
    render(<CloudAccountSection user={{ ...base, cloudLink: link }} />)
    expect(screen.getByText(/linked by signing in once/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Unlink' }))
    expect(mocks.unlink).not.toHaveBeenCalled()
    fireEvent.click(await screen.findByRole('button', { name: 'Unlink' }))
    await vi.waitFor(() => expect(mocks.unlink).toHaveBeenCalledWith({ userId: 3 }))
  })
})
