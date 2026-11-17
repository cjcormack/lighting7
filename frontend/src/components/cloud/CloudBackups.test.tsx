// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BackupSettingsChange, BackupStatus, CloudState } from '@/store/cloud'

const mocks = vi.hoisted(() => ({
  status: undefined as BackupStatus | undefined,
  save: vi.fn((_body: BackupSettingsChange) => Promise.resolve({})),
  run: vi.fn(() => Promise.resolve({})),
  pollingInterval: undefined as number | undefined,
}))

vi.mock('@/store/cloud', () => ({
  cloudApi: { endpoints: { backupStatus: { useQueryState: () => ({ data: mocks.status }) } } },
  useBackupStatusQuery: (_arg: unknown, options: { pollingInterval?: number }) => {
    mocks.pollingInterval = options.pollingInterval
    return { data: mocks.status, error: undefined }
  },
  useSetBackupSettingsMutation: () => [
    (body: BackupSettingsChange) => ({ unwrap: () => mocks.save(body) }),
    { isLoading: false, error: undefined, reset: () => {} },
  ],
  useRunBackupMutation: () => [() => ({ unwrap: () => mocks.run() }), { isLoading: false, error: undefined, reset: () => {} }],
}))

import { BACKUP_IDLE_POLL_MS, BACKUP_POLL_MS, CloudBackups, REMOTE_OFF_REASON, formatSize } from './CloudBackups'

const adopted: CloudState = {
  configured: true,
  enabled: true,
  adopted: { installId: 'inst-1', orgName: 'Test Org', adoptedAt: '2026-11-01T00:00:00.000Z', fingerprint: 'SHA256:x' },
}

function status(over: Partial<BackupStatus> = {}): BackupStatus {
  return { enabled: false, dailyAt: '04:00', ...over }
}

describe('CloudBackups', () => {
  afterEach(() => {
    cleanup()
    mocks.status = undefined
    mocks.save.mockClear()
    mocks.run.mockClear()
  })

  it('turns backups on', () => {
    mocks.status = status()
    render(<CloudBackups state={adopted} />)
    expect(screen.getByText('No backup made yet.')).toBeTruthy()
    fireEvent.click(screen.getByRole('switch', { name: 'Back up to Lighting7 Cloud' }))
    expect(mocks.save).toHaveBeenCalledWith({ enabled: true })
  })

  it('offers both schedules once on, nightly by default, and backing up on quit', () => {
    mocks.status = status({ enabled: true })
    render(<CloudBackups state={adopted} />)
    expect(screen.getByRole('switch', { name: 'Every night' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByLabelText('Each night at')).toBeTruthy()
    expect(screen.getByRole('switch', { name: 'When the desk quits' }).getAttribute('aria-checked')).toBe('false')
    fireEvent.click(screen.getByRole('switch', { name: 'When the desk quits' }))
    expect(mocks.save).toHaveBeenCalledWith({ onQuit: true })
    fireEvent.click(screen.getByRole('switch', { name: 'Every night' }))
    expect(mocks.save).toHaveBeenCalledWith({ nightly: false })
  })

  it('hides the time with nightly off, and says when nothing backs up on its own', () => {
    mocks.status = status({ enabled: true, nightly: false, onQuit: true })
    render(<CloudBackups state={adopted} />)
    expect(screen.queryByLabelText('Each night at')).toBeNull()
    expect(screen.queryByTestId('cloud-backup-no-schedule')).toBeNull()
    cleanup()

    mocks.status = status({ enabled: true, nightly: false, onQuit: false })
    render(<CloudBackups state={adopted} />)
    expect(screen.getByTestId('cloud-backup-no-schedule')).toBeTruthy()
  })

  it('names a backup made as the desk quit', () => {
    mocks.status = status({ lastOkAt: '2026-11-15T23:00:00.000Z', lastKind: 'QUIT' })
    render(<CloudBackups state={adopted} />)
    expect(screen.getByTestId('cloud-backup-last').textContent).toContain('As the desk quit')
  })

  it('sets the time once the field is left', () => {
    mocks.status = status({ enabled: true, nextAt: '2026-11-17T04:00:00.000Z' })
    render(<CloudBackups state={adopted} />)
    const field = screen.getByLabelText('Each night at')
    fireEvent.change(field, { target: { value: '03:30' } })
    expect(mocks.save).not.toHaveBeenCalled()
    fireEvent.blur(field)
    expect(mocks.save).toHaveBeenCalledWith({ dailyAt: '03:30' })
  })

  it('backs up now, and polls slowly while idle and quickly while a backup runs', () => {
    mocks.status = status()
    render(<CloudBackups state={adopted} />)
    expect(mocks.pollingInterval).toBe(BACKUP_IDLE_POLL_MS)
    fireEvent.click(screen.getByRole('button', { name: 'Back up now' }))
    expect(mocks.run).toHaveBeenCalledTimes(1)
    cleanup()

    mocks.status = status({ running: { kind: 'MANUAL', phase: 'UPLOADING', startedAt: '2026-11-16T10:00:00.000Z', parts: 3, partsSent: 1 } })
    render(<CloudBackups state={adopted} />)
    expect(mocks.pollingInterval).toBe(BACKUP_POLL_MS)
    expect(screen.getByTestId('cloud-backup-running').textContent).toBe('Uploading, part 2 of 3…')
    expect((screen.getByRole('button', { name: 'Back up now' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('shows the last backup and a failure after it', () => {
    mocks.status = status({
      lastOkAt: '2026-11-15T04:00:00.000Z',
      lastSizeBytes: 3 * 1024 * 1024,
      lastKind: 'SCHEDULED',
      lastFailedAt: '2026-11-16T04:00:00.000Z',
      lastCode: 'BACKUP_QUOTA_EXCEEDED',
      lastMessage: 'The backup would take the org past its backup storage.',
    })
    render(<CloudBackups state={adopted} />)
    const last = screen.getByTestId('cloud-backup-last').textContent ?? ''
    expect(last).toContain('3.0 MB')
    expect(last).toContain('Nightly')
    expect(last).toContain('past its backup storage')
  })

  it('reads but cannot change the settings over remote access', () => {
    mocks.status = status({ enabled: true })
    render(<CloudBackups state={{ ...adopted, remote: true }} />)
    expect((screen.getByRole('switch', { name: 'Back up to Lighting7 Cloud' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: 'Back up now' }) as HTMLButtonElement).disabled).toBe(false)
    expect(screen.queryByText(REMOTE_OFF_REASON)).toBeNull()
  })

  it('cannot back up now over remote access while the switch is off, and says why', () => {
    mocks.status = status()
    render(<CloudBackups state={{ ...adopted, remote: true }} />)
    expect((screen.getByRole('button', { name: 'Back up now' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText(REMOTE_OFF_REASON)).toBeTruthy()
    cleanup()

    render(<CloudBackups state={adopted} />)
    expect((screen.getByRole('button', { name: 'Back up now' }) as HTMLButtonElement).disabled).toBe(false)
  })

  it('words sizes', () => {
    expect(formatSize(512)).toBe('1 KB')
    expect(formatSize(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatSize(3 * 1024 * 1024 * 1024)).toBe('3.0 GB')
  })
})
