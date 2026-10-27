// @vitest-environment jsdom
import { fireEvent, render, renderHook, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupMember } from '@/api/groupsApi'
import type { PatchGroup, PatchGroupDetail } from '@/api/patchApi'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const wire = vi.hoisted(() => ({
  patchGroups: [] as PatchGroup[],
  detail: undefined as PatchGroupDetail | undefined,
  venue: { isVenue: false, canEdit: false },
  reorder: vi.fn(),
  takeVenues: vi.fn(),
}))

vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 7 } }) }))
vi.mock('@/store/patches', () => ({
  usePatchGroupListQuery: () => ({ data: wire.patchGroups }),
  usePatchGroupDetailQuery: (_arg: unknown, opts: { skip?: boolean }) => ({ data: opts.skip ? undefined : wire.detail }),
}))
vi.mock('@/store/venues', () => ({ useVenueMode: () => wire.venue }))
vi.mock('@/store/groups', () => ({
  useReorderGroupMembersMutation: () => [(arg: unknown) => ({ unwrap: () => Promise.resolve(wire.reorder(arg)) })],
  useTakeVenueGroupOrderMutation: () => [(arg: unknown) => ({ unwrap: () => Promise.resolve(wire.takeVenues(arg)) })],
}))
vi.mock('@/store/fixtures', () => ({ useFixtureListQuery: () => ({ data: [] }) }))
vi.mock('./useVisibleGroupMembers', () => ({ useVisibleGroupMembers: (m: unknown) => m }))
vi.mock('./CompactFixtureCard', () => ({
  CompactFixtureCard: ({ fixtureName }: { fixtureName: string }) => <span>{fixtureName}</span>,
  MultiElementCompactCard: () => null,
}))

import { GroupMembersSection } from './GroupMembersSection'
import { useGroupMemberReorder, VENUE_ADMIN_ORDER_REASON } from './useGroupMemberReorder'

function member(key: string, index: number): GroupMember {
  return { fixtureKey: key, fixtureName: key.toUpperCase(), index, normalizedPosition: 0, panOffset: 0, tiltOffset: 0, symmetricInvert: false, tags: [] }
}

const members = ['a', 'b', 'c', 'd'].map(member)
const steps = { NORMAL: [1, 2, 3, 4], CENTRE_OUT: [2, 1, 1, 2] }

function renderList(defaultOrder = 'NORMAL') {
  return render(
    <GroupMembersSection
      members={members}
      isLoading={false}
      onFixtureClick={() => {}}
      layout="list"
      groupName="wash"
      orderSteps={steps}
      defaultOrder={defaultOrder}
    />,
  )
}

function rows() {
  return within(screen.getByRole('list', { name: 'wash members in order' })).getAllByRole('listitem')
}

beforeEach(() => {
  wire.patchGroups = [{ id: 3, name: 'wash', memberCount: 4, owner: 'SHOW' }]
  wire.detail = {
    id: 3,
    name: 'wash',
    owner: 'SHOW',
    members: ['a', 'b', 'c', 'd'].map((k, i) => ({ patchId: 10 + i, fixtureKey: k, fixtureName: k, fixtureTypeKey: 'hex', sortOrder: i })),
  }
  wire.venue = { isVenue: false, canEdit: false }
  wire.reorder.mockReset()
  wire.takeVenues.mockReset()
})

/** The group as a venue's in a show, its order the show's when `yours`. */
function venueGroup(yours: boolean) {
  const orderOwner = yours ? 'SHOW' : 'VENUE'
  wire.patchGroups = [{ id: 3, name: 'wash', memberCount: 4, owner: 'VENUE', orderOwner }]
  wire.detail = { ...wire.detail!, owner: 'VENUE', orderOwner }
}

describe('the group sheet’s member list (venue-visit plan S7)', () => {
  it('numbers the members in the group’s order with each one’s step', () => {
    renderList()
    expect(rows().map((r) => r.textContent)).toEqual(['1Astep 1', '2Bstep 2', '3Cstep 3', '4Dstep 4'])
    expect(screen.getAllByRole('button', { name: /^Move / })).toHaveLength(4)
    expect(screen.getByText(/Drag to reorder/)).toBeTruthy()
  })

  it('reads its steps under the group’s own distribution', () => {
    renderList('CENTRE_OUT')
    expect(rows().map((r) => r.textContent)).toEqual(['1Astep 2', '2Bstep 1', '3Cstep 1', '4Dstep 2'])
  })

  it('reorders a venue’s group in a show, as the show’s alone (group-order plan D14)', () => {
    venueGroup(false)
    renderList()
    expect(screen.getAllByRole('button', { name: /^Move / })).toHaveLength(4)
    expect(screen.getByText(/this show’s alone/)).toBeTruthy()
    expect(screen.queryByText('Your order')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Take venue’s order' })).toBeNull()
  })

  it('marks the show’s order of a venue’s group, and Take venue’s gives it back', () => {
    venueGroup(true)
    renderList()
    expect(screen.getByText('Your order')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Take venue’s order' }))
    expect(wire.takeVenues).toHaveBeenCalledWith({ projectId: 7, groupId: 3 })
  })

  it('is read-only for an operator in a venue', () => {
    wire.venue = { isVenue: true, canEdit: false }
    renderList()
    expect(screen.queryAllByRole('button', { name: /^Move / })).toHaveLength(0)
    expect(screen.getByText(VENUE_ADMIN_ORDER_REASON, { exact: false })).toBeTruthy()
  })
})

describe('useGroupMemberReorder', () => {
  it('writes the keys as patch ids, with the keys for the optimistic move', () => {
    const { result } = renderHook(() => useGroupMemberReorder('wash'))
    if (result.current.kind !== 'editable') throw new Error(`expected editable, got ${result.current.kind}`)
    result.current.write(['d', 'a', 'b', 'c'])
    expect(wire.reorder).toHaveBeenCalledWith({ projectId: 7, groupId: 3, groupName: 'wash', memberOrder: [13, 10, 11, 12], keys: ['d', 'a', 'b', 'c'] })
  })

  it('writes a venue’s group in a show the same way, the desk making it the show’s order', () => {
    venueGroup(false)
    const { result } = renderHook(() => useGroupMemberReorder('wash'))
    if (result.current.kind !== 'editable') throw new Error(`expected editable, got ${result.current.kind}`)
    expect(result.current.venueOrder?.yours).toBe(false)
    result.current.write(['b', 'a'])
    expect(wire.reorder).toHaveBeenCalledWith({ projectId: 7, groupId: 3, groupName: 'wash', memberOrder: [11, 10], keys: ['b', 'a'] })
  })

  it('has no venue order on a show’s own group', () => {
    const { result } = renderHook(() => useGroupMemberReorder('wash'))
    if (result.current.kind !== 'editable') throw new Error('expected editable')
    expect(result.current.venueOrder).toBeNull()
  })

  it('sends nothing for a key the stored group does not hold', () => {
    const { result } = renderHook(() => useGroupMemberReorder('wash'))
    if (result.current.kind !== 'editable') throw new Error('expected editable')
    result.current.write(['x', 'a'])
    expect(wire.reorder).not.toHaveBeenCalled()
  })

  it('waits for the stored group', () => {
    wire.detail = undefined
    const { result } = renderHook(() => useGroupMemberReorder('wash'))
    expect(result.current.kind).toBe('pending')
  })
})
