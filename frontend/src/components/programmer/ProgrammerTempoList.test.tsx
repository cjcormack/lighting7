// @vitest-environment jsdom
import { cleanup, fireEvent, render, renderHook, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProgrammerTempo } from '@/api/programmerWsApi'
import type { SpeedMasterLiveState } from '@/api/speedMastersWsApi'

/** The programmer's Tempo rows (fx-speed plan D8, D11): one per held master, its tempo typed in place, ✕ releasing it. */
const store = {
  tempo: { projectId: 6, masters: [] } as ProgrammerTempo,
  connected: true,
}
const BANK: SpeedMasterLiveState[] = [
  { uuid: 'm1', index: 1, name: 'Main', bpm: 120, isRunning: true, source: 'MANUAL' },
  { uuid: 'm2', index: 2, name: 'Chase', bpm: 128, isRunning: true, source: 'MANUAL' },
  { uuid: 'm3', index: 3, name: '', bpm: 90, isRunning: true, source: 'MANUAL' },
]
const programmerSetTempo = vi.fn()
const programmerClearTempo = vi.fn()

vi.mock('@/store/programmer', () => ({
  useProgrammerTempo: () => store.tempo,
  programmerSetTempo: (...a: unknown[]) => programmerSetTempo(...a),
  programmerClearTempo: (...a: unknown[]) => programmerClearTempo(...a),
}))
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterLiveQuery: () => ({ data: BANK }) }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => store.connected }))

import { ProgrammerTempoList, useHeldTempoCount } from './ProgrammerTempoList'

beforeEach(() => {
  store.tempo = { projectId: 6, masters: [] }
  store.connected = true
  programmerSetTempo.mockClear()
  programmerClearTempo.mockClear()
})
afterEach(cleanup)

const rowOf = (uuid: string) => document.querySelector(`[data-tempo-master="${uuid}"]`) as HTMLElement

describe('ProgrammerTempoList', () => {
  it('draws nothing while no tempo is held', () => {
    const { container } = render(<ProgrammerTempoList projectId={6} />)
    expect(container.innerHTML).toBe('')
  })

  it('draws a row per held master, named from the bank, in the order first held', () => {
    store.tempo = {
      projectId: 6,
      masters: [
        { speedMasterUuid: 'm2', bpm: 132 },
        { speedMasterUuid: 'm3', bpm: 96.25 },
        { speedMasterUuid: 'gone', bpm: 100 },
      ],
    }
    render(<ProgrammerTempoList projectId={6} />)
    const list = screen.getByRole('region', { name: 'Held tempo' })
    expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      expect.stringContaining('M2 · Chase'),
      expect.stringContaining('M3'),
      expect.stringContaining('a master'),
    ])
    expect(within(rowOf('m2')).getByRole('button', { name: 'M2 · Chase tempo 132' })).toBeTruthy()
    expect(within(rowOf('m3')).getByRole('button', { name: 'M3 tempo 96.3' })).toBeTruthy()
  })

  it("leaves out another project's holds", () => {
    store.tempo = { projectId: 9, masters: [{ speedMasterUuid: 'm2', bpm: 132 }] }
    const { container } = render(<ProgrammerTempoList projectId={6} />)
    expect(container.innerHTML).toBe('')
  })

  it('sends setTempo for a typed tempo on Enter', () => {
    store.tempo = { projectId: 6, masters: [{ speedMasterUuid: 'm2', bpm: 132 }] }
    render(<ProgrammerTempoList projectId={6} />)
    fireEvent.click(screen.getByRole('button', { name: 'M2 · Chase tempo 132' }))
    const field = screen.getByLabelText('M2 · Chase tempo')
    fireEvent.change(field, { target: { value: '140' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(programmerSetTempo).toHaveBeenCalledWith('m2', 140)
  })

  it('sends nothing when the tempo is opened and left as it was', () => {
    store.tempo = { projectId: 6, masters: [{ speedMasterUuid: 'm2', bpm: 109.0909 }] }
    render(<ProgrammerTempoList projectId={6} />)
    fireEvent.click(screen.getByRole('button', { name: 'M2 · Chase tempo 109.1' }))
    fireEvent.blur(screen.getByLabelText('M2 · Chase tempo'))
    expect(programmerSetTempo).not.toHaveBeenCalled()
  })

  it('sends clearTempo with the master for ✕', () => {
    store.tempo = { projectId: 6, masters: [{ speedMasterUuid: 'm2', bpm: 132 }] }
    render(<ProgrammerTempoList projectId={6} />)
    fireEvent.click(screen.getByRole('button', { name: 'Release M2 · Chase' }))
    expect(programmerClearTempo).toHaveBeenCalledWith('m2')
  })

  it('marks a staged hold, and a staged release as releasing with nothing to release', () => {
    store.tempo = {
      projectId: 6,
      masters: [
        { speedMasterUuid: 'm2', bpm: 132, staged: true },
        { speedMasterUuid: 'm3', bpm: 90, staged: true, released: true },
      ],
    }
    render(<ProgrammerTempoList projectId={6} />)
    expect(rowOf('m2').dataset.staged).toBe('true')
    expect(within(rowOf('m2')).getByText('staged')).toBeTruthy()
    expect(within(rowOf('m2')).getByRole('button', { name: 'Release M2 · Chase' })).toBeTruthy()
    expect(rowOf('m3').dataset.releasing).toBe('true')
    expect(within(rowOf('m3')).getByText('releasing')).toBeTruthy()
    expect(within(rowOf('m3')).queryByRole('button', { name: 'Release M3' })).toBeNull()
  })

  it('is read-only while the desk is offline', () => {
    store.connected = false
    store.tempo = { projectId: 6, masters: [{ speedMasterUuid: 'm2', bpm: 132 }] }
    render(<ProgrammerTempoList projectId={6} />)
    expect(screen.getByRole('button', { name: 'Release M2 · Chase' })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: 'M2 · Chase tempo 132' })).toHaveProperty('disabled', true)
  })
})

describe('useHeldTempoCount', () => {
  it('counts the holds, not a release still to land', () => {
    store.tempo = {
      projectId: 6,
      masters: [
        { speedMasterUuid: 'm2', bpm: 132 },
        { speedMasterUuid: 'm3', bpm: 90, staged: true, released: true },
      ],
    }
    expect(renderHook(() => useHeldTempoCount(6)).result.current).toBe(1)
  })
})
