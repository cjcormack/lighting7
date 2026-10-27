// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PatchGroupDetail } from '@/api/patchApi'

const detail = vi.hoisted(() => ({ current: null as PatchGroupDetail | null }))
const updateGroup = vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve() }))
vi.mock('@/store/patches', () => ({
  usePatchGroupDetailQuery: () => ({ data: detail.current }),
  useUpdatePatchGroupMutation: () => [updateGroup, { isLoading: false }],
  useDeletePatchGroupMutation: () => [vi.fn(), { isLoading: false }],
  useUpdatePatchMutation: () => [vi.fn()],
}))

import { EditGroupSheet } from './EditGroupSheet'

const MEMBERS = [{ patchId: 1, fixtureKey: 'hex-1', fixtureName: 'Hex 1', fixtureTypeKey: 'hex', sortOrder: 0 }]

function draw(group: Partial<PatchGroupDetail>, owner: 'SHOW' | 'VENUE' = 'SHOW') {
  detail.current = { id: 7, name: 'Front wash', members: MEMBERS, owner, ...group }
  return render(
    <EditGroupSheet open onOpenChange={() => {}} groupId={7} groupName="Front wash" projectId={1} patches={[]} owner={owner} />,
  )
}

afterEach(() => {
  cleanup()
  updateGroup.mockClear()
  detail.current = null
})

const swatches = () => within(screen.getByRole('group', { name: 'Colours' }))

describe("EditGroupSheet's colour (tables-colours plan D7, D9)", () => {
  it('writes a pick at once, alone, and None clears it', () => {
    draw({ colour: 'red' })
    expect(swatches().getByRole('button', { name: /Red/ })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(swatches().getByRole('button', { name: /Lime/ }))
    expect(updateGroup).toHaveBeenCalledWith({ projectId: 1, groupId: 7, colour: 'lime' })
    fireEvent.click(screen.getByRole('button', { name: 'None' }))
    expect(updateGroup).toHaveBeenLastCalledWith({ projectId: 1, groupId: 7, colour: null })
  })

  it('writes nothing for the colour it already draws — on a venue group that would pin it as the show\'s', () => {
    draw({ colour: 'cyan', colourOwner: 'VENUE' }, 'VENUE')
    fireEvent.click(swatches().getByRole('button', { name: /Cyan/ }))
    expect(updateGroup).not.toHaveBeenCalled()
  })

  it('draws a member renamed while it is open, and starts each opening from the stored group', () => {
    const { rerender } = draw({ colour: null })
    const sheet = (open: boolean) => (
      <EditGroupSheet open={open} onOpenChange={() => {}} groupId={7} groupName="Front wash" projectId={1} patches={[]} owner="SHOW" />
    )
    fireEvent.change(screen.getByLabelText('Group Name'), { target: { value: 'Front' } })
    detail.current = { ...detail.current!, members: [{ ...MEMBERS[0], fixtureName: 'Hex One' }] }
    rerender(sheet(true))
    expect(screen.getByText('Hex One')).toBeInTheDocument()
    expect(screen.getByLabelText('Group Name')).toHaveValue('Front')
    rerender(sheet(false))
    rerender(sheet(true))
    expect(screen.getByLabelText('Group Name')).toHaveValue('Front wash')
  })

  it('keeps an unsaved rename when the group comes back recoloured', () => {
    const { rerender } = draw({ colour: null })
    fireEvent.change(screen.getByLabelText('Group Name'), { target: { value: 'Front' } })
    detail.current = { ...detail.current!, colour: 'lime' }
    rerender(
      <EditGroupSheet open onOpenChange={() => {}} groupId={7} groupName="Front wash" projectId={1} patches={[]} owner="SHOW" />,
    )
    expect(screen.getByLabelText('Group Name')).toHaveValue('Front')
  })

  it("recolours a venue's group from a show, where the rest of it is locked, and Take venue's gives it back (D10)", () => {
    draw({ colour: 'cyan', colourOwner: 'SHOW' }, 'VENUE')
    expect(screen.getByLabelText('Group Name')).toBeDisabled()
    expect(screen.getByText('yours')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'None' })).toBeNull()
    fireEvent.click(swatches().getByRole('button', { name: /Pink/ }))
    expect(updateGroup).toHaveBeenCalledWith({ projectId: 1, groupId: 7, colour: 'pink' })
    fireEvent.click(screen.getByRole('button', { name: 'Take venue’s' }))
    expect(updateGroup).toHaveBeenLastCalledWith({ projectId: 1, groupId: 7, colour: null })
  })

  it("offers nothing to clear on a venue's group in its own colour", () => {
    draw({ colour: 'cyan', colourOwner: 'VENUE' }, 'VENUE')
    expect(screen.queryByText('yours')).toBeNull()
    expect(screen.queryByRole('button', { name: /Take venue/ })).toBeNull()
  })

  it('changes nothing when an operator looks at a venue', () => {
    detail.current = { id: 7, name: 'Front wash', members: MEMBERS, owner: 'SHOW', colour: 'red' }
    render(
      <EditGroupSheet open onOpenChange={() => {}} groupId={7} groupName="Front wash" projectId={1} patches={[]} readOnly />,
    )
    expect(swatches().getByRole('button', { name: /Lime/ })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'None' })).toBeNull()
  })
})

describe("EditGroupSheet's order on a venue group in a show (group-order plan D14)", () => {
  const TWO = [MEMBERS[0], { patchId: 2, fixtureKey: 'hex-2', fixtureName: 'Hex 2', fixtureTypeKey: 'hex', sortOrder: 1 }]

  it('locks the name and members and leaves the order to drag', () => {
    draw({ members: TWO, orderOwner: 'VENUE' }, 'VENUE')
    expect(screen.getByLabelText('Group Name')).toBeDisabled()
    expect(screen.getAllByRole('button', { name: /^Move / })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
    expect(screen.queryByText('Add Fixtures')).toBeNull()
    expect(screen.getByText(/Drag to reorder it for this show/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.queryByText('Your order')).toBeNull()
  })

  it("marks the show's order, and Take venue's sends a null order alone, named apart from the colour's", () => {
    draw({ members: TWO, colour: 'pink', colourOwner: 'SHOW', orderOwner: 'SHOW' }, 'VENUE')
    expect(screen.getByText('Your order')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Take venue’s order' }))
    expect(updateGroup).toHaveBeenCalledWith({ projectId: 1, groupId: 7, memberOrder: null })
    fireEvent.click(screen.getByRole('button', { name: 'Take venue’s' }))
    expect(updateGroup).toHaveBeenLastCalledWith({ projectId: 1, groupId: 7, colour: null })
  })

  it('lets an operator looking at a venue neither drag nor save the order', () => {
    detail.current = { id: 7, name: 'Front wash', members: TWO, owner: 'SHOW' }
    render(
      <EditGroupSheet open onOpenChange={() => {}} groupId={7} groupName="Front wash" projectId={1} patches={[]} readOnly />,
    )
    expect(screen.queryAllByRole('button', { name: /^Move / })).toHaveLength(0)
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
  })
})
