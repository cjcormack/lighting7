// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { BackupStatus } from '@/store/cloud'

const backup = vi.hoisted(() => ({
  data: undefined as BackupStatus | undefined,
  error: undefined as unknown,
  fulfilledTimeStamp: Number.MAX_SAFE_INTEGER as number | undefined,
  startedTimeStamp: undefined as number | undefined,
  quit: vi.fn((_body: { force: boolean; skipBackup?: boolean }) => Promise.resolve({ rigWasSafe: true })),
}))
vi.mock('@/store/cloud', () => ({
  cloudApi: { endpoints: { backupStatus: { useQueryState: () => ({ data: backup.data }) } } },
  useBackupStatusQuery: () => ({
    data: backup.data,
    error: backup.error,
    fulfilledTimeStamp: backup.fulfilledTimeStamp,
    startedTimeStamp: backup.startedTimeStamp,
    isFetching: false,
  }),
  useSetBackupSettingsMutation: () => [() => ({ unwrap: () => Promise.resolve({}) }), { isLoading: false }],
  useRunBackupMutation: () => [() => ({ unwrap: () => Promise.resolve({}) }), { isLoading: false }],
}))
vi.mock('@/store/rigSafety', () => ({
  useQuitDeskMutation: () => [(body: { force: boolean; skipBackup?: boolean }) => ({ unwrap: () => backup.quit(body) }), { isLoading: false }],
}))

afterEach(() => {
  cleanup()
  backup.data = undefined
  backup.error = undefined
  backup.fulfilledTimeStamp = Number.MAX_SAFE_INTEGER
  backup.startedTimeStamp = undefined
  backup.quit.mockClear()
  vi.useRealTimers()
  vi.resetModules()
})

async function load() {
  const { markDeskQuit } = await import('./deskQuit')
  const { DeskQuitScreen } = await import('./DeskQuitScreen')
  return { markDeskQuit, DeskQuitScreen }
}

describe('DeskQuitScreen', () => {
  it('draws nothing until the desk has quit, then says so with no cool-down', async () => {
    const { markDeskQuit, DeskQuitScreen } = await load()
    const { container } = render(<DeskQuitScreen />)
    expect(container.innerHTML).toBe('')
    act(() => markDeskQuit())
    expect(screen.getByRole('alert').textContent).toContain('The desk has quit')
    expect(screen.queryByTestId('quit-screen-cooling')).toBeNull()
  })

  it('counts a cool-down out, then says the rig can be switched off', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000)
    const { markDeskQuit, DeskQuitScreen } = await load()
    render(<DeskQuitScreen />)
    act(() => markDeskQuit(1_000_000 + 65_000))
    expect(screen.getByTestId('quit-screen-cooling').textContent).toContain('another 1:05')
    act(() => vi.advanceTimersByTime(60_000))
    expect(screen.getByTestId('quit-screen-cooling').textContent).toContain('another 0:05')
    act(() => vi.advanceTimersByTime(6_000))
    expect(screen.getByTestId('quit-screen-cooling').textContent).toContain('the rig can be switched off')
  })

  const start: BackupStatus = {
    enabled: true,
    dailyAt: '04:00',
    onQuit: true,
    lastOkAt: '2026-11-15T23:00:00.000Z',
    running: { kind: 'QUIT', phase: 'MAKING', startedAt: '2026-11-16T23:00:00.000Z' },
  }

  it('follows the backup a quitting desk makes, and quits without waiting on the press', async () => {
    const { markDeskQuit, DeskQuitScreen } = await load()
    render(<DeskQuitScreen />)
    backup.data = { ...start, running: { kind: 'QUIT', phase: 'UPLOADING', startedAt: start.running!.startedAt, parts: 2, partsSent: 0 } }
    act(() => markDeskQuit(null, start))
    expect(screen.getByRole('alert').textContent).toContain('Backing up before the desk quits')
    expect(screen.getByTestId('cloud-backup-running').textContent).toBe('Uploading, part 1 of 2…')
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Quit without waiting' })))
    expect(backup.quit).toHaveBeenCalledWith({ force: true, skipBackup: true })
  })

  let started = Number.MAX_SAFE_INTEGER - 1_000

  /** One poll ending with [error], begun at [at]. */
  function poll(rerender: (ui: React.ReactElement) => void, Screen: () => React.ReactNode, error?: unknown, at = ++started) {
    backup.error = error
    backup.startedTimeStamp = at
    act(() => rerender(<Screen />))
  }

  async function quitAndGo(status: BackupStatus, since: BackupStatus = start) {
    const { markDeskQuit, DeskQuitScreen } = await load()
    backup.data = status
    const { rerender } = render(<DeskQuitScreen />)
    act(() => markDeskQuit(null, since))
    poll(rerender, DeskQuitScreen, { status: 'FETCH_ERROR' })
    poll(rerender, DeskQuitScreen, { status: 'FETCH_ERROR' })
  }

  it('says the desk has quit once it stops answering, and whether it backed up', async () => {
    await quitAndGo({ ...start, running: null, lastOkAt: '2026-11-16T23:01:00.000Z', lastKind: 'QUIT' })
    expect(screen.getByRole('alert').textContent).toContain('The desk has quit')
    expect(screen.getByTestId('quit-screen-backup').textContent).toContain('backed up to Lighting7 Cloud')
    cleanup()

    await quitAndGo({ ...start, running: null, lastFailedAt: '2026-11-16T23:01:00.000Z', lastCode: 'CLOUD_UNREACHABLE', lastMessage: 'The cloud didn’t answer' })
    expect(screen.getByTestId('quit-screen-backup').textContent).toContain('failed: The cloud didn’t answer')
    cleanup()

    await quitAndGo(start)
    expect(screen.getByTestId('quit-screen-backup').textContent).toContain('before its backup finished')
  })

  it('counts only the quit backup made after one already running, which the quit waited for', async () => {
    const manual: BackupStatus = { ...start, running: { kind: 'MANUAL', phase: 'UPLOADING', startedAt: start.running!.startedAt } }
    await quitAndGo({ ...manual, running: null, lastOkAt: '2026-11-16T23:01:00.000Z', lastKind: 'MANUAL' }, manual)
    expect(screen.getByTestId('quit-screen-backup').textContent).toContain('before its backup finished')
    cleanup()

    await quitAndGo({ ...manual, running: null, lastOkAt: '2026-11-16T23:02:00.000Z', lastKind: 'QUIT' }, manual)
    expect(screen.getByTestId('quit-screen-backup').textContent).toContain('backed up to Lighting7 Cloud')
  })

  it('ignores a status read before the quit, which says nothing of its backup', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(2_000_000)
    backup.fulfilledTimeStamp = 1_999_000
    await quitAndGo({ ...start, running: null, lastOkAt: '2026-11-16T23:01:00.000Z', lastKind: 'QUIT' })
    expect(screen.getByTestId('quit-screen-backup').textContent).toContain('before its backup finished')
  })

  it('keeps backing up while the desk answers with an error', async () => {
    const { markDeskQuit, DeskQuitScreen } = await load()
    const { rerender } = render(<DeskQuitScreen />)
    act(() => markDeskQuit(null, start))
    poll(rerender, DeskQuitScreen, { status: 503 })
    poll(rerender, DeskQuitScreen, { status: 503 })
    expect(screen.getByRole('alert').textContent).toContain('Backing up before the desk quits')
    expect(screen.getByRole('button', { name: 'Quit without waiting' })).toBeTruthy()
  })

  it('decides the desk has gone only on failed reads in a row begun after the quit', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(2_000_000)
    const { markDeskQuit, DeskQuitScreen } = await load()
    const { rerender } = render(<DeskQuitScreen />)
    act(() => markDeskQuit(null, start))
    poll(rerender, DeskQuitScreen, { status: 'FETCH_ERROR' }, 1_999_000)
    poll(rerender, DeskQuitScreen, { status: 'FETCH_ERROR' })
    expect(screen.getByRole('alert').textContent).toContain('Backing up before the desk quits')
    poll(rerender, DeskQuitScreen, undefined)
    poll(rerender, DeskQuitScreen, { status: 'TIMEOUT_ERROR' })
    expect(screen.getByRole('alert').textContent).toContain('Backing up before the desk quits')
    poll(rerender, DeskQuitScreen, { status: 'FETCH_ERROR' })
    expect(screen.getByRole('alert').textContent).toContain('The desk has quit')
  })

  it('says it waits at most 15 minutes', async () => {
    const { markDeskQuit, DeskQuitScreen } = await load()
    render(<DeskQuitScreen />)
    act(() => markDeskQuit(null, start))
    expect(screen.getByRole('alert').textContent).toContain('15 minutes at most')
  })
})
