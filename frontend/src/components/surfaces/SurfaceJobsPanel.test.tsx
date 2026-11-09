// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ControlSurfaceType, SurfaceMapFrame } from '@/store/surfaces'
import { CATALOGUE, FIXTURE_PROFILE, JOB_KINDS, MAP_FRAME } from './surfaceFixtures'

/**
 * Editor B (surface-setup plan D11, D12): a job per region, from the kinds the desk says fit, its
 * items reordered with up and down, every change one job write.
 */

const puts = vi.hoisted(() => [] as unknown[])
const deletes = vi.hoisted(() => [] as unknown[])

vi.mock('@/store/surfaces', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/store/surfaces')>()
  return {
    ...actual,
    usePutSurfaceJobMutation: () => [(arg: unknown) => (puts.push(arg), { unwrap: () => Promise.resolve({}) }), {}],
    useDeleteSurfaceJobMutation: () => [(arg: unknown) => (deletes.push(arg), { unwrap: () => Promise.resolve() }), {}],
  }
})
vi.mock('@/store/groups', () => ({
  useGroupListQuery: () => ({ data: [{ name: 'front-wash', memberKeys: [] }, { name: 'back-wash', memberKeys: [] }] }),
}))
vi.mock('@/store/fixtures', () => ({ useVisibleFixtureListQuery: () => ({ data: [] }) }))
vi.mock('@/components/surfaces/recordOptions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/surfaces/recordOptions')>()
  return { ...actual, useRecordOptions: () => actual.EMPTY_RECORD_OPTIONS }
})
vi.mock('@/api/lightingApi', () => {
  const noSubscription = { unsubscribe: () => {} }
  return { lightingApi: new Proxy({}, { get: () => new Proxy({}, { get: () => () => noSubscription }) }) }
})

import { SurfaceJobsPanel, itemOf, itemOptions } from './SurfaceJobsPanel'

const slot = (region: string, layer: string | null = null) => ({ deviceTypeKey: FIXTURE_PROFILE.typeKey, region, layer })

function renderPanel(frame: SurfaceMapFrame = MAP_FRAME, onFocus = vi.fn(), profile: ControlSurfaceType = FIXTURE_PROFILE) {
  render(
    <SurfaceJobsPanel
      projectId={1}
      profile={profile}
      frame={frame}
      catalogue={CATALOGUE}
      kinds={JOB_KINDS}
      connected
      focused={null}
      onFocus={onFocus}
    />,
  )
}

function region(id: string): HTMLElement {
  return document.querySelector(`[data-region="${id}"]`) as HTMLElement
}

beforeEach(() => {
  puts.length = 0
  deletes.length = 0
})
afterEach(cleanup)

describe('SurfaceJobsPanel', () => {
  it('offers each region the kinds that fit it, and shows the job it holds', () => {
    renderPanel()
    const strips = within(region('strips')).getByRole('combobox', { name: 'Strips 1–8 job' }) as HTMLSelectElement
    expect(strips.value).toBe('groupStrips')
    expect([...strips.options].map((o) => o.textContent)).toEqual([
      'Nothing: bind each control yourself',
      'Groups as channel strips',
      'Fixtures as channel strips',
      'The selection, one head per strip',
      'Cue stacks as playbacks',
      'Speed masters',
    ])
    const master = within(region('master')).getByRole('combobox') as HTMLSelectElement
    expect(master.value).toBe('')
    expect([...master.options].map((o) => o.textContent)).toContain('Grand master and blackout')
    expect(within(region('encoders')).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      expect.stringContaining('Saturation'),
      expect.stringContaining('Tempo · M2 · Chase'),
    ])
  })

  it('reorders items with up and down, as one write of the job', () => {
    renderPanel()
    fireEvent.click(within(region('encoders')).getByRole('button', { name: 'Move Tempo · M2 · Chase up' }))
    expect(puts).toHaveLength(1)
    const put = puts[0] as { slot: unknown; kind: string; items: unknown[] }
    expect(put.slot).toEqual(slot('encoders'))
    expect(put.kind).toBe('wheels')
    expect(put.items.map((item) => (item as { does: { type: string } }).does.type)).toEqual(['tempo', 'level'])
    expect(within(region('encoders')).getByRole('button', { name: 'Move Saturation up' })).toBeDisabled()
  })

  it('adds an item from the list its kind offers, and takes one off', () => {
    renderPanel({ ...MAP_FRAME, jobs: [{ id: 1, region: 'strips', kind: 'groupStrips', items: [] }] })
    const add = within(region('strips')).getByRole('combobox', { name: 'Add an item' }) as HTMLSelectElement
    expect([...add.options].map((o) => o.value)).toEqual(['', 'group:front-wash', 'group:back-wash'])
    fireEvent.change(add, { target: { value: 'group:back-wash' } })
    expect((puts[0] as { items: unknown[] }).items).toEqual([{ type: 'group', key: 'back-wash' }])
    cleanup()

    // A full region offers nothing more; an item comes off as one write.
    renderPanel()
    expect(within(region('strips')).queryByRole('combobox', { name: 'Add an item' })).toBeNull()
    expect(within(region('strips')).getByText('Every slot has an item.')).toBeInTheDocument()
    fireEvent.click(within(region('strips')).getByRole('button', { name: 'Take Front wash off' }))
    expect((puts[1] as { items: unknown[] }).items).toEqual([null])
  })

  it('leaves a gap where an item comes off, and Add fills the first gap', () => {
    const strips = FIXTURE_PROFILE.jobRegions![0]
    const wide = { ...FIXTURE_PROFILE, jobRegions: [{ ...strips, slots: [strips.slots[0], strips.slots[0], strips.slots[0]] }] }
    const group = (key: string, label: string) => ({ item: { type: 'group' as const, key }, label })
    renderPanel(
      { ...MAP_FRAME, jobs: [{ id: 1, region: 'strips', kind: 'groupStrips', items: [group('front-wash', 'Front wash'), group('back-wash', 'Back wash'), group('side-wash', 'Side wash')] }] },
      vi.fn(),
      wide,
    )
    fireEvent.click(within(region('strips')).getByRole('button', { name: 'Take Back wash off' }))
    expect((puts[0] as { items: unknown[] }).items).toEqual([{ type: 'group', key: 'front-wash' }, null, { type: 'group', key: 'side-wash' }])
    cleanup()

    renderPanel(
      { ...MAP_FRAME, jobs: [{ id: 1, region: 'strips', kind: 'groupStrips', items: [group('front-wash', 'Front wash'), null, group('side-wash', 'Side wash')] }] },
      vi.fn(),
      wide,
    )
    const empty = document.querySelector('[data-region="strips"] [data-empty]') as HTMLElement
    expect(empty.textContent).toContain('Empty')
    expect(within(empty).queryByRole('button')).toBeNull()
    expect(within(region('strips')).queryByText('Every slot has an item.')).toBeNull()
    fireEvent.change(within(region('strips')).getByRole('combobox', { name: 'Add an item' }), { target: { value: 'group:back-wash' } })
    expect((puts[1] as { items: unknown[] }).items).toEqual([
      { type: 'group', key: 'front-wash' },
      { type: 'group', key: 'back-wash' },
      { type: 'group', key: 'side-wash' },
    ])
  })

  it('holds a new function until Add, so its settings can be chosen first', () => {
    renderPanel({ ...MAP_FRAME, jobs: [{ id: 2, region: 'encoders', kind: 'wheels', items: [] }] })
    const encoders = region('encoders')
    fireEvent.click(within(encoders).getByRole('button', { name: 'Add a wheel…' }))
    expect(within(encoders).getByRole('button', { name: 'Add' })).toBeDisabled()
    fireEvent.click(within(encoders).getByRole('button', { name: 'New item does' }))
    fireEvent.click(screen.getByRole('option', { name: 'Fan' }))
    fireEvent.click(within(within(encoders).getByRole('group', { name: 'New item axis' })).getByRole('button', { name: 'Tilt' }))
    expect(puts).toHaveLength(0)
    fireEvent.click(within(encoders).getByRole('button', { name: 'Add' }))
    expect(puts).toHaveLength(1)
    const [item] = (puts[0] as { items: { does: unknown }[] }).items
    expect(item.does).toEqual({ type: 'fan', axis: 'TILT' })
    expect(within(encoders).queryByTestId('job-add-function')).toBeNull()
  })

  it('changes a region\'s job, keeping the items only where the new kind holds the same', () => {
    renderPanel()
    fireEvent.change(within(region('strips')).getByRole('combobox', { name: 'Strips 1–8 job' }), { target: { value: 'headStrips' } })
    expect(puts).toEqual([{ projectId: 1, slot: slot('strips'), kind: 'headStrips', items: [] }])
    fireEvent.change(within(region('master')).getByRole('combobox'), { target: { value: 'grandMaster' } })
    expect(puts[1]).toEqual({ projectId: 1, slot: slot('master'), kind: 'grandMaster', items: [] })
  })

  it('takes the job off when Nothing is picked', () => {
    renderPanel()
    fireEvent.change(within(region('strips')).getByRole('combobox', { name: 'Strips 1–8 job' }), { target: { value: '' } })
    expect(deletes).toEqual([{ projectId: 1, slot: slot('strips') }])
  })

  it('edits a layer\'s own jobs when its chip is pressed', () => {
    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: 'Layer A only' }))
    const strips = within(region('strips')).getByRole('combobox', { name: 'Strips 1–8 job' }) as HTMLSelectElement
    expect(strips.value).toBe('')
    fireEvent.change(strips, { target: { value: 'fixtureStrips' } })
    expect((puts[0] as { slot: unknown }).slot).toEqual(slot('strips', 'layer-a'))
  })

  it('says a job this desk cannot read is kept and does nothing', () => {
    renderPanel({ ...MAP_FRAME, jobs: [{ id: 9, region: 'strips', kind: 'fromTheFuture', readable: false, items: [] }] })
    expect(screen.getByText(/cannot read this job; it is kept and does nothing/)).toBeInTheDocument()
    expect((within(region('strips')).getByRole('combobox', { name: 'Strips 1–8 job' }) as HTMLSelectElement).value).toBe('fromTheFuture')
  })

  it('focuses the region it is used in', () => {
    const onFocus = vi.fn()
    renderPanel(MAP_FRAME, onFocus)
    fireEvent.pointerDown(region('master'))
    expect(onFocus).toHaveBeenCalledWith('master')
  })
})

describe('item options', () => {
  it('reads each option back as the item it stands for', () => {
    const options = itemOptions('GROUP', { groups: [{ key: 'front', label: 'Front' }], fixtures: [], records: { STACK: [], ENTRY: [], CUE: [], LOOK: [], PALETTE: [], PAD: [], BANK: [], PAGE: [], MASTER: [] } })
    expect(options).toEqual([{ value: 'group:front', label: 'Front' }])
    expect(itemOf('group:front')).toEqual({ type: 'group', key: 'front' })
    expect(itemOf('record:STACK:1234')).toEqual({ type: 'record', kind: 'STACK', uuid: '1234' })
    expect(itemOf('nonsense')).toBeNull()
  })
})
