// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CloudState, RestoreStatus } from '@/store/cloud'

const mocks = vi.hoisted(() => ({
  status: undefined as RestoreStatus | undefined,
  confirm: vi.fn((_body: { name: string }) => Promise.resolve({})),
  decline: vi.fn(() => Promise.resolve({})),
}))

vi.mock('@/store/cloud', () => ({
  cloudApi: { endpoints: { restoreStatus: { useQueryState: () => ({ data: mocks.status }) } } },
  useRestoreStatusQuery: () => ({ data: mocks.status, error: undefined }),
  useConfirmRestoreMutation: () => [
    (body: { name: string }) => ({ unwrap: () => mocks.confirm(body) }),
    { isLoading: false, error: undefined, reset: () => {} },
  ],
  useDeclineRestoreMutation: () => [() => ({ unwrap: () => mocks.decline() }), { isLoading: false, error: undefined, reset: () => {} }],
}))

import { CloudRestore, RESTORE_LOCAL_ONLY_REASON, hasRestore } from './CloudRestore'

const restore = { replacesInstallId: 'inst-old', replacesName: 'Main desk', backupId: '01JD0000000000000000000000' }

function state(over: Partial<CloudState> = {}): CloudState {
  return {
    configured: true,
    enabled: true,
    adopted: { installId: 'inst-new', orgName: 'Test Org', adoptedAt: '2026-11-01T00:00:00.000Z', fingerprint: 'SHA256:x', restore },
    ...over,
  }
}

const backup = {
  backupId: restore.backupId,
  installName: 'Main desk',
  createdAt: '2026-11-15T04:00:00.000Z',
  sizeBytes: 3 * 1024 * 1024,
  deskVersion: '1.4.0',
  tooNew: false,
}

describe('CloudRestore', () => {
  afterEach(() => {
    cleanup()
    mocks.status = undefined
    mocks.confirm.mockClear()
    mocks.decline.mockClear()
  })

  it('confirms only once the old name is typed', () => {
    mocks.status = { pending: restore, backup, confirmName: 'Main desk', phase: 'IDLE' }
    render(<CloudRestore state={state()} />)
    expect(screen.getByRole('heading').textContent).toContain('Replace “Main desk” and restore its backup')
    const go = screen.getByRole('button', { name: 'Replace and restore' }) as HTMLButtonElement
    expect(go.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('Confirm name'), { target: { value: 'Main desk' } })
    expect(go.disabled).toBe(false)
    fireEvent.click(go)
    expect(mocks.confirm).toHaveBeenCalledWith({ name: 'Main desk' })
  })

  it('declines', () => {
    mocks.status = { pending: restore, backup, confirmName: 'Main desk', phase: 'IDLE' }
    render(<CloudRestore state={state()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Decline' }))
    expect(mocks.decline).toHaveBeenCalled()
  })

  it('offers no confirm over remote access', () => {
    mocks.status = { pending: restore, backup, confirmName: 'Main desk', phase: 'IDLE' }
    render(<CloudRestore state={state({ remote: true })} />)
    expect(screen.getByText(RESTORE_LOCAL_ONLY_REASON)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Replace and restore' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Decline' })).toBeTruthy()
  })

  it('refuses a backup from a newer desk', () => {
    mocks.status = { pending: restore, backup: { ...backup, tooNew: true }, confirmName: 'Main desk', phase: 'IDLE' }
    render(<CloudRestore state={state()} />)
    fireEvent.change(screen.getByLabelText('Confirm name'), { target: { value: 'Main desk' } })
    expect((screen.getByRole('button', { name: 'Replace and restore' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText(/made by a newer desk/)).toBeTruthy()
  })

  it('says why the last confirm stopped', () => {
    mocks.status = {
      pending: restore,
      backup,
      confirmName: 'Main desk',
      phase: 'IDLE',
      lastError: { code: 'BACKUP_CORRUPT', message: 'The backup is damaged, so nothing was restored.' },
    }
    render(<CloudRestore state={state()} />)
    expect(screen.getByText('The backup is damaged, so nothing was restored.')).toBeTruthy()
  })

  it('names a replacement alone and a backup alone', () => {
    mocks.status = { pending: { replacesInstallId: 'inst-old', replacesName: 'Main desk' }, confirmName: 'Main desk', phase: 'IDLE' }
    const { unmount } = render(
      <CloudRestore state={state({ adopted: { ...state().adopted!, restore: { replacesInstallId: 'inst-old', replacesName: 'Main desk' } } })} />,
    )
    expect(screen.getByRole('button', { name: 'Replace' })).toBeTruthy()
    unmount()
    mocks.status = { pending: { backupId: restore.backupId }, backup, confirmName: 'Main desk', phase: 'IDLE' }
    render(<CloudRestore state={state({ adopted: { ...state().adopted!, restore: { backupId: restore.backupId } } })} />)
    expect(screen.getByRole('button', { name: 'Restore' })).toBeTruthy()
  })

  it('asks for the restart once the backup is staged, and can still drop it', () => {
    const ready = state({ restartToRestore: true, adopted: { ...state().adopted!, restore: null } })
    expect(hasRestore(ready)).toBe(true)
    render(<CloudRestore state={ready} />)
    expect(screen.getByText('Restart the desk to finish the restore')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Don’t restore' }))
    expect(mocks.decline).toHaveBeenCalled()
  })

  it('has nothing to show on an adopted desk with no restore and nothing stopped', () => {
    const none = state({ adopted: { ...state().adopted!, restore: null } })
    mocks.status = { phase: 'IDLE' }
    const { container } = render(<CloudRestore state={none} />)
    expect(container.textContent).toBe('')
    expect(hasRestore(state({ adopted: null }))).toBe(false)
  })

  it('still says why a confirm stopped once the cloud cleared the restore', () => {
    mocks.status = {
      phase: 'IDLE',
      lastError: { code: 'REPLACEMENT_EXPIRED', message: 'The replacement expired: it waited longer than 7 days.' },
    }
    render(<CloudRestore state={state({ adopted: { ...state().adopted!, restore: null } })} />)
    expect(screen.getByText('The restore stopped')).toBeTruthy()
    expect(screen.getByText('The replacement expired: it waited longer than 7 days.')).toBeTruthy()
  })
})
