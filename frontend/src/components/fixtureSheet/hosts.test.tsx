// @vitest-environment jsdom
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ElementType, ReactNode } from 'react'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

type SheetProps = {
  host: string
  aim?: ReactNode
  focus?: ReactNode
  group?: { name: string }
  fixture?: { key: string }
  onOpenMember?: unknown
  titleComponent?: ElementType<{ children?: ReactNode }>
}
const sheets = vi.hoisted(() => ({ props: [] as SheetProps[] }))
vi.mock('./FixtureSheet', () => ({
  FixtureSheet: (props: SheetProps) => {
    sheets.props.push(props)
    const Title = props.titleComponent ?? 'div'
    return (
      <div data-testid="sheet" data-host={props.host}>
        <Title>{props.fixture?.key ?? props.group?.name}</Title>
        <div data-sheet-body />
        {props.aim}
        <div data-fx-tray />
      </div>
    )
  },
}))

import { chan, groupSummary, makeFixture, sliderProp } from '@/test/fixtureFactories'

const MOVER = makeFixture('spot-3', [
  sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540 }),
  sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT', degMin: 0, degMax: 270 }),
])

vi.mock('../../store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../store/fixtures')>()),
  useFixtureListQuery: () => ({ data: [MOVER] }),
}))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtureByKey: new Map([['spot-3', MOVER]]), typeByKey: new Map() }),
}))
vi.mock('@/store/patches', () => ({ useVisiblePatchListQuery: () => ({ data: [{ id: 7, key: 'spot-3' }] }) }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('../fixtures/FixtureParkButton', () => ({ FixtureParkButton: () => null }))
vi.mock('@/hooks/useLanternIndex', () => ({ useLanternIndex: () => new Map() }))
vi.mock('../stage3d/StageAimControls', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../stage3d/StageAimControls')>()),
  StageAimControls: () => <div data-testid="aim-body" />,
}))
vi.mock('../stage3d/StageFocusPanel', () => ({ StageFocusPanel: () => null }))
vi.mock('../../store/groups', () => ({
  useGroupListQuery: () => ({ data: [groupSummary('front', 2)] }),
  useGroupQuery: () => ({ data: { members: [] } }),
}))
vi.mock('../fx/FxBadge', () => ({ FxBadge: () => null }))
vi.mock('../fixtures/LocateButton', () => ({ LocateButton: () => <button type="button">Locate</button> }))

import { FixtureDetailModal } from '../groups/FixtureDetailModal'
import { StageFixtureControlPanel, StageFixtureControls } from '../stage3d/StageFixtureControlPanel'
import { GroupDetailModal } from '../fixtures/GroupDetailModal'
import { GroupCard } from '../groups/GroupCard'
import { resetEditorSurfaceMedia } from '../editor/EditorSurface'
import { AllFixturesView } from '../../routes/Fixtures'
import { PhoneSheet, SAFE_BOTTOM_CLASS, settleHeight } from './PhoneSheet'
import { BuskFixtureSheet } from '../busking/BuskFixtureSheet'

/** The two questions `useEditorForm` asks: narrow (an upright phone) and short (one held landscape). */
function stubForm({ narrow, short }: { narrow: boolean; short: boolean }) {
  resetEditorSurfaceMedia()
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('max-width') ? narrow : query.includes('max-height') ? short : false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
}

beforeEach(() => {
  sheets.props = []
})
afterEach(() => {
  vi.unstubAllGlobals()
  resetEditorSurfaceMedia()
})

/** The two hosts session 2 moves onto the sheet (§3.3, §4): neither draws an Edit toggle any more. */
describe('fixture sheet hosts', () => {
  it('the pop-up mounts the sheet as a popup, with no Edit toggle', () => {
    sheets.props = []
    render(<FixtureDetailModal fixtureKey="spot-3" onClose={() => {}} />)
    expect(screen.getByTestId('sheet')).toBeTruthy()
    expect(sheets.props.at(-1)?.host).toBe('popup')
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('the Stage panel mounts it as stage, with Aim moved into the sheet rather than pinned under it', () => {
    sheets.props = []
    render(<StageFixtureControlPanel patchKey="spot-3" projectId={1} canAim onClose={() => {}} />)
    expect(sheets.props.at(-1)?.host).toBe('stage')
    expect(sheets.props.at(-1)?.aim).toBeTruthy()
    // The aim body is inside the sheet (its Position row's popover), not a block of the panel's.
    expect(screen.getByTestId('sheet').contains(screen.getByTestId('aim-body'))).toBe(true)
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('offers no Aim off the live project', () => {
    sheets.props = []
    render(<StageFixtureControlPanel patchKey="spot-3" projectId={1} canAim={false} onClose={() => {}} />)
    expect(sheets.props.at(-1)?.aim).toBeUndefined()
  })
})

/** Session 4's two group hosts: the group sheet is the fixture sheet's body on a group (D1). */
describe('group sheet hosts', () => {
  it('the group sheet mounts the sheet on the group as a popup, its members opening their own', () => {
    sheets.props = []
    render(<GroupDetailModal groupName="front" onClose={() => {}} />)
    expect(screen.getByTestId('sheet')).toBeTruthy()
    expect(sheets.props.at(-1)).toMatchObject({ host: 'popup', group: { name: 'front' } })
    expect(typeof sheets.props.at(-1)?.onOpenMember).toBe('function')
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('a group card mounts the card host, with no Edit toggle', () => {
    sheets.props = []
    render(<GroupCard group={groupSummary('front', 2)} onFixtureClick={() => {}} onOpenSheet={() => {}} />)
    expect(sheets.props.at(-1)).toMatchObject({ host: 'card', group: { name: 'front' } })
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('a group card’s corner opens the group sheet', () => {
    const onOpenSheet = vi.fn()
    render(<GroupCard group={groupSummary('front', 2)} onFixtureClick={() => {}} onOpenSheet={onOpenSheet} />)
    fireEvent.click(screen.getByRole('button', { name: 'Open front’s sheet' }))
    expect(onOpenSheet).toHaveBeenCalledWith('front')
  })
})

/** Session 6: the cards page keeps its card host and gains the corner that opens the pop-up. */
describe('the cards page', () => {
  it('a card’s corner button opens the fixture’s pop-up', () => {
    render(<AllFixturesView fixtureList={[MOVER]} filteredFixtures={[MOVER]} />)
    expect(sheets.props.map((p) => p.host)).toEqual(['card'])
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    // Last in the card's header actions, where the pencil was.
    const corner = screen.getByRole('button', { name: `Open ${MOVER.name}’s sheet` })
    expect(corner.parentElement?.lastElementChild).toBe(corner)
    fireEvent.click(corner)
    expect(sheets.props.at(-1)).toMatchObject({ host: 'popup', fixture: { key: 'spot-3' } })
    expect(screen.getByRole('dialog')).toBeTruthy()
  })
})

/** Session 6, D14: the Stage view's fixture sheet on a phone. */
describe('the Stage view on a phone', () => {
  it('opens the phone host on a fixture tap below sm, and no docked stage panel', () => {
    stubForm({ narrow: true, short: false })
    const { rerender } = render(<StageFixtureControls patchKey={null} projectId={1} canAim onClose={() => {}} />)
    expect(screen.queryByTestId('sheet')).toBeNull()
    // The tap selects the fixture; the sheet opens on it.
    rerender(<StageFixtureControls patchKey="spot-3" projectId={1} canAim onClose={() => {}} />)
    expect(document.querySelector('[data-phone-sheet]')?.getAttribute('data-phone-sheet')).toBe('bottom-sheet')
    expect(sheets.props.at(-1)).toMatchObject({ host: 'phone', fixture: { key: 'spot-3' } })
    expect(sheets.props.some((p) => p.host === 'stage')).toBe(false)
    expect(screen.queryByRole('button', { name: 'Close fixture controls' })).toBeNull()
    // The phone keeps the Stage's two extras: the Focus tab and Aim…
    expect(sheets.props.at(-1)?.focus).toBeTruthy()
    expect(sheets.props.at(-1)?.aim).toBeTruthy()
  })

  it('docks the 380px stage panel on a desk, and draws no phone sheet', () => {
    stubForm({ narrow: false, short: false })
    render(<StageFixtureControls patchKey="spot-3" projectId={1} canAim onClose={() => {}} />)
    expect(sheets.props.at(-1)?.host).toBe('stage')
    expect(document.querySelector('[data-phone-sheet]')).toBeNull()
    expect(screen.getByRole('button', { name: 'Close fixture controls' })).toBeTruthy()
  })

  it('gives a landscape phone the right-hand side form, with no grabber', () => {
    // 844×390: wider than sm, and short — short beats narrow.
    stubForm({ narrow: false, short: true })
    render(<StageFixtureControls patchKey="spot-3" projectId={1} canAim onClose={() => {}} />)
    const content = document.querySelector('[data-phone-sheet]') as HTMLElement
    expect(content.getAttribute('data-phone-sheet')).toBe('side-sheet')
    expect(sheets.props.at(-1)?.host).toBe('phone')
    expect(document.querySelector('[data-sheet-grabber]')).toBeNull()
    expect(content.getAttribute('style')).toContain('380px')
  })

  it('is not modal: a press on the stage behind is the stage’s, and the sheet stays open', () => {
    stubForm({ narrow: true, short: false })
    const onClose = vi.fn()
    render(<StageFixtureControls patchKey="spot-3" projectId={1} canAim onClose={onClose} />)
    fireEvent.pointerDown(document.body)
    expect(onClose).not.toHaveBeenCalled()
    expect(document.querySelector('[data-phone-sheet]')).not.toBeNull()
    expect(document.querySelector('[data-slot="sheet-overlay"]')).toBeNull()
  })
})

/** The phone form's three heights and its grabber (§4, "The phone"). */
describe('PhoneSheet', () => {
  function draw(onClose = vi.fn()) {
    render(
      <PhoneSheet open onClose={onClose} form="bottom-sheet" modal={false} description="A fixture">
        <h2>Spot 3</h2>
        <div data-sheet-body>rows</div>
        <div data-fx-tray>tray</div>
      </PhoneSheet>,
    )
    return {
      content: document.querySelector('[data-phone-sheet]') as HTMLElement,
      grabber: document.querySelector('[data-sheet-grabber]') as HTMLButtonElement,
      onClose,
    }
  }

  it('opens at half, and a tap on the grabber steps it full, to the peek, and back to half', () => {
    const { content, grabber } = draw()
    expect(content.dataset.height).toBe('half')
    fireEvent.click(grabber)
    expect(content.dataset.height).toBe('full')
    fireEvent.click(grabber)
    expect(content.dataset.height).toBe('peek')
    // At the peek the properties fold away: the header and the tray are what is left.
    expect(content.className).toContain('[&_[data-sheet-body]]:hidden')
    fireEvent.click(grabber)
    expect(content.dataset.height).toBe('half')
    expect(content.className).not.toContain('[&_[data-sheet-body]]:hidden')
  })

  it('steps with the arrow keys, and stops at either end', () => {
    const { content, grabber } = draw()
    fireEvent.keyDown(grabber, { key: 'ArrowUp' })
    fireEvent.keyDown(grabber, { key: 'ArrowUp' })
    expect(content.dataset.height).toBe('full')
    fireEvent.keyDown(grabber, { key: 'ArrowDown' })
    fireEvent.keyDown(grabber, { key: 'ArrowDown' })
    fireEvent.keyDown(grabber, { key: 'ArrowDown' })
    expect(content.dataset.height).toBe('peek')
  })

  it('follows a drag on the grabber and settles on the nearest height, the drag’s click swallowed', () => {
    const { content, grabber } = draw()
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(844)
    vi.spyOn(content, 'getBoundingClientRect').mockReturnValue({ height: 422 } as DOMRect)
    const body = content.querySelector('[data-sheet-body]') as HTMLElement
    vi.spyOn(body, 'getBoundingClientRect').mockReturnValue({ height: 322 } as DOMRect)
    fireEvent.pointerDown(grabber, { pointerId: 1, clientY: 422, button: 0, pointerType: 'touch' })
    fireEvent.pointerMove(grabber, { pointerId: 1, clientY: 122 })
    expect(content.dataset.height).toBe('drag')
    expect(content.style.height).toBe('722px')
    fireEvent.pointerUp(grabber, { pointerId: 1, clientY: 122 })
    fireEvent.click(grabber)
    expect(content.dataset.height).toBe('full')
  })

  it('lets go when dragged well below the peek', () => {
    const { grabber, content, onClose } = draw()
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(844)
    vi.spyOn(content, 'getBoundingClientRect').mockReturnValue({ height: 422 } as DOMRect)
    vi.spyOn(content.querySelector('[data-sheet-body]') as HTMLElement, 'getBoundingClientRect').mockReturnValue({
      height: 322,
    } as DOMRect)
    fireEvent.pointerDown(grabber, { pointerId: 1, clientY: 400, button: 0, pointerType: 'touch' })
    fireEvent.pointerMove(grabber, { pointerId: 1, clientY: 800 })
    fireEvent.pointerUp(grabber, { pointerId: 1, clientY: 800 })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('settles where a quick flick let go, though its last move and release land in one render', () => {
    const { content, grabber } = draw()
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(844)
    vi.spyOn(content, 'getBoundingClientRect').mockReturnValue({ height: 422 } as DOMRect)
    vi.spyOn(content.querySelector('[data-sheet-body]') as HTMLElement, 'getBoundingClientRect').mockReturnValue({
      height: 322,
    } as DOMRect)
    act(() => {
      fireEvent.pointerDown(grabber, { pointerId: 1, clientY: 422, button: 0, pointerType: 'touch' })
      fireEvent.pointerMove(grabber, { pointerId: 1, clientY: 122 })
      fireEvent.pointerUp(grabber, { pointerId: 1, clientY: 122 })
    })
    expect(content.dataset.height).toBe('full')
  })

  it('does not swallow the next tap after a cancelled drag', () => {
    const { content, grabber } = draw()
    fireEvent.pointerDown(grabber, { pointerId: 1, clientY: 400, button: 0, pointerType: 'touch' })
    fireEvent.pointerMove(grabber, { pointerId: 1, clientY: 300 })
    fireEvent.pointerCancel(grabber, { pointerId: 1 })
    expect(content.dataset.height).toBe('half')
    // A cancel is followed by no click; the next tap is a tap.
    fireEvent.pointerDown(grabber, { pointerId: 2, clientY: 400, button: 0, pointerType: 'touch' })
    fireEvent.pointerUp(grabber, { pointerId: 2, clientY: 400 })
    fireEvent.click(grabber)
    expect(content.dataset.height).toBe('full')
  })

  it('shows the properties again when a sheet left at the peek turns to the side form', () => {
    const sheet = (form: 'bottom-sheet' | 'side-sheet') => (
      <PhoneSheet open onClose={() => {}} form={form} modal={false} description="A fixture">
        <h2>Spot 3</h2>
        <div data-sheet-body>rows</div>
      </PhoneSheet>
    )
    const { rerender } = render(sheet('bottom-sheet'))
    const grabber = document.querySelector('[data-sheet-grabber]') as HTMLElement
    fireEvent.keyDown(grabber, { key: 'ArrowDown' })
    expect((document.querySelector('[data-phone-sheet]') as HTMLElement).className).toContain('[&_[data-sheet-body]]:hidden')
    rerender(sheet('side-sheet'))
    const content = document.querySelector('[data-phone-sheet]') as HTMLElement
    expect(content.dataset.phoneSheet).toBe('side-sheet')
    expect(content.className).not.toContain('[&_[data-sheet-body]]:hidden')
  })

  it('settles on the nearest of the three, or lets go', () => {
    const snaps = { peek: 100, half: 422, full: 742 }
    expect(settleHeight(150, snaps)).toBe('peek')
    expect(settleHeight(300, snaps)).toBe('half')
    expect(settleHeight(700, snaps)).toBe('full')
    expect(settleHeight(40, snaps)).toBe('peek')
    expect(settleHeight(30, snaps)).toBeNull()
  })

  it('keeps the tray above the home indicator, and gives back the keyboard’s bite', () => {
    const viewport = Object.assign(new EventTarget(), { height: 844, offsetTop: 0 })
    vi.stubGlobal('visualViewport', viewport)
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(844)
    const { content } = draw()
    // The safe-area inset is the content's own bottom padding, under the tray at the column's foot.
    expect(content.className).toContain(SAFE_BOTTOM_CLASS)
    expect(content.style.bottom).toBe('0px')
    // The keyboard comes up over 300px: the sheet rises by it and gives the inset strip back.
    act(() => {
      viewport.height = 544
      viewport.dispatchEvent(new Event('resize'))
    })
    expect(content.style.bottom).toBe('300px')
    expect(content.className).not.toContain(SAFE_BOTTOM_CLASS)
    expect(content.style.maxHeight).toBe('calc(88svh - 300px)')
  })
})

/** The busk view's *Fixture sheet…* on a phone is the same phone sheet, modal (session 6). */
describe('the busk fixture sheet', () => {
  it('is the phone host in the bottom sheet on an upright phone, and modal', () => {
    stubForm({ narrow: true, short: false })
    render(<BuskFixtureSheet target={{ type: 'fixture', key: 'spot-3' }} onClose={() => {}} />)
    const content = document.querySelector('[data-phone-sheet]') as HTMLElement
    expect(content.dataset.phoneSheet).toBe('bottom-sheet')
    expect(content.dataset.buskFixtureSheet).toBe('bottom')
    expect(document.querySelector('[data-sheet-grabber]')).not.toBeNull()
    expect(sheets.props.at(-1)).toMatchObject({ host: 'phone', fixture: { key: 'spot-3' } })
    // Modal, as a sheet opened from a menu: it draws the shaded overlay the Stage's does not.
    expect(document.querySelector('[data-slot="sheet-overlay"]')).not.toBeNull()
  })

  it('is the side form held landscape, and the 512px pop-up form on a desk', () => {
    stubForm({ narrow: false, short: true })
    const { unmount } = render(<BuskFixtureSheet target={{ type: 'fixture', key: 'spot-3' }} onClose={() => {}} />)
    expect((document.querySelector('[data-phone-sheet]') as HTMLElement).dataset.buskFixtureSheet).toBe('side')
    expect(sheets.props.at(-1)?.host).toBe('phone')
    unmount()

    stubForm({ narrow: false, short: false })
    render(<BuskFixtureSheet target={{ type: 'fixture', key: 'spot-3' }} onClose={() => {}} />)
    expect(document.querySelector('[data-phone-sheet]')).toBeNull()
    expect(document.querySelector('[data-busk-fixture-sheet]')?.getAttribute('data-busk-fixture-sheet')).toBe('side')
    expect(sheets.props.at(-1)?.host).toBe('popup')
  })
})
