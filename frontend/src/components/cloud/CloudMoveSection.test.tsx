// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CloudMovePlanRow, CloudMoveRow } from '@/store/cloudSync'

const mocks = vi.hoisted(() => ({
  plan: [] as unknown[],
  pushEnabled: true,
  move: vi.fn((_ids: number[]): Promise<unknown> => Promise.resolve([])),
  planCalls: [] as unknown[],
}))

vi.mock('@/store/cloudSync', async () => {
  const { useState } = await import('react')
  return {
    useCloudMovePlanQuery: (_arg: unknown, options: { skip?: boolean }) => {
      mocks.planCalls.push(options)
      return {
        data: options.skip ? undefined : { projects: mocks.plan, pushEnabled: mocks.pushEnabled },
        error: undefined,
        isLoading: false,
      }
    },
    useCloudMoveMutation: () => {
      const [error, setError] = useState<unknown>(undefined)
      const trigger = (ids: number[]) => ({
        unwrap: async () => {
          try {
            return await mocks.move(ids)
          } catch (e) {
            setError(e)
            throw e
          }
        },
      })
      return [trigger, { isLoading: false, error, reset: () => setError(undefined) }] as const
    },
  }
})

import { CloudMoveSection } from './CloudMoveSection'

const venue: CloudMovePlanRow = { projectId: 2, name: 'Commemoration Hall', kind: 'VENUE', repoUrl: 'https://github.com/c/hall.git', cloudHasRepo: false }
const show: CloudMovePlanRow = { projectId: 1, name: 'Panto', kind: 'SHOW', repoUrl: 'https://github.com/c/panto.git', cloudHasRepo: true, cloudRepoName: 'Panto' }

describe('CloudMoveSection', () => {
  beforeEach(() => {
    mocks.plan = []
    mocks.pushEnabled = true
    mocks.planCalls = []
    mocks.move.mockReset()
  })
  afterEach(cleanup)

  it('draws nothing when no project syncs with GitHub', () => {
    const { container } = render(<CloudMoveSection remote={false} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('lists the plan with its GitHub repos and what another desk moved, and moves them all', async () => {
    mocks.plan = [venue, show]
    const moved: CloudMoveRow[] = [
      { projectId: 2, name: 'Commemoration Hall', status: 'MOVED', repoName: 'Commemoration Hall', tags: 2 },
      { projectId: 1, name: 'Panto', status: 'MOVED_DIVERGED', repoName: 'Panto', existed: true },
    ]
    mocks.move.mockResolvedValue(moved)
    render(<CloudMoveSection remote={false} />)
    const plan = screen.getByTestId('cloud-move-plan')
    expect(within(plan).getByText('c/hall')).toBeTruthy()
    expect(within(plan).getByText('Venue')).toBeTruthy()
    expect(within(plan).getByText(/Already on the cloud as Panto/)).toBeTruthy()

    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Move 2 projects to the cloud' })))
    expect(mocks.move).toHaveBeenCalledWith([2, 1])
    const results = screen.getByTestId('cloud-move-results')
    expect(within(results).getByText('Moved to Commemoration Hall.')).toBeTruthy()
    expect(within(results).getByText(/its next sync merges the two/)).toBeTruthy()
  })

  it('offers a failed project again, and only it', async () => {
    mocks.plan = [show]
    mocks.move.mockResolvedValueOnce([
      { projectId: 1, name: 'Panto', status: 'FAILED', code: 'SYNC_QUOTA_EXCEEDED', message: 'Ask the org owner for more storage' },
    ])
    const { rerender } = render(<CloudMoveSection remote={false} />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Move to the cloud' })))
    expect(screen.getByText(/Not moved: Ask the org owner for more storage\. It still syncs with GitHub\./)).toBeTruthy()

    mocks.plan = [show, venue]
    rerender(<CloudMoveSection remote={false} />)
    expect(screen.queryByRole('button', { name: /Move .* to the cloud/ })).toBeNull()
    const plan = screen.getByTestId('cloud-move-plan')
    expect(within(plan).queryByText('Panto')).toBeNull()
    expect(within(plan).getByText('Commemoration Hall')).toBeTruthy()
    mocks.move.mockResolvedValueOnce([{ projectId: 1, name: 'Panto', status: 'MOVED', repoName: 'Panto' }])
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Try it again' })))
    expect(mocks.move).toHaveBeenLastCalledWith([1])
    expect(screen.getByText('Moved to Panto.')).toBeTruthy()
  })

  it('says a pull-only desk cannot move, and offers no move', () => {
    mocks.plan = [show]
    mocks.pushEnabled = false
    render(<CloudMoveSection remote={false} />)
    expect(screen.getByText(/This desk only pulls/)).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('moves nothing over remote access and never asks for the plan', () => {
    mocks.plan = [show]
    render(<CloudMoveSection remote />)
    expect(screen.getByText(/never over remote access/)).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
    expect(mocks.planCalls.every((o) => (o as { skip?: boolean }).skip === true)).toBe(true)
  })
})
