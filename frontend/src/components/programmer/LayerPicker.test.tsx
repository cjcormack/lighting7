// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The add-layer picker's preselected targets — the cue editor's Values selection
 * (show-cue-editing plan §4.1): with targets given, choosing a source skips the target step and the
 * layer lands on them; with none it asks.
 */

vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({
    data: [{ id: 4, uuid: 'u4', name: 'Warm', families: ['INTENSITY'], rowCount: 2, effectCount: 0, targetCount: 2, hasDeferredEffects: false }],
  }),
}))
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: [] }) }))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtures', () => ({ useFixtureListQuery: () => ({ data: [] }) }))
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterLiveQuery: () => ({ data: [] }) }))
vi.mock('@/components/cues/CueTargetPicker', () => ({ CueTargetPicker: () => <div data-testid="target-step" /> }))

const { LayerPicker } = await import('./LayerPicker')

afterEach(cleanup)

describe('LayerPicker', () => {
  it('lands on the preselected targets, skipping the target step', () => {
    const onConfirm = vi.fn()
    const targets = [
      { type: 'group' as const, key: 'Front' },
      { type: 'fixture' as const, key: 'orbit-2' },
    ]
    render(<LayerPicker projectId={1} kind="look" preselectedTargets={targets} onConfirm={onConfirm} onCancel={() => {}} />)
    fireEvent.click(screen.getByText('Warm'))
    expect(screen.queryByTestId('target-step')).toBeNull()
    expect(screen.getByText('On Front, orbit-2.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Add layer' }))
    expect(onConfirm).toHaveBeenCalledWith(expect.objectContaining({ lookId: 4, targets }))
  })

  it('asks where the layer lands when nothing is preselected', () => {
    render(<LayerPicker projectId={1} kind="look" onConfirm={() => {}} onCancel={() => {}} />)
    fireEvent.click(screen.getByText('Warm'))
    expect(screen.getByTestId('target-step')).toBeInTheDocument()
  })
})
