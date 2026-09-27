// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ControlState,
  ControlSurfaceBinding,
  ControlSurfaceType,
  SurfaceDeviceInfo,
} from '@/store/surfaces'

/**
 * The Surfaces tab's structural claims: the run-mode toolbar is **gone** (plan D1), a matched
 * device is drawn as a **picture** and an unmatched one as the explain-yourself card, the dead
 * count is a header badge, and `?binding=` still lands on the control it names.
 *
 * That last one is not decoration: the link is minted from the fixtures and groups pages, so it
 * is an in-app contract the rebuild had to carry across.
 */

let devices: SurfaceDeviceInfo[] = []
let bindings: ControlSurfaceBinding[] = []
let controls: Record<string, Record<string, ControlState>> = {}
let activeBanks: Record<string, string> = {}
const setBank = vi.fn()

vi.mock('@/store/surfaces', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/store/surfaces')>()
  return {
    ...actual,
    useSurfaceDevices: () => devices,
    useActiveBanks: () => activeBanks,
    useEncoderBanks: () => ({ xtc: { propertyName: 'colour' } }),
    useSurfaceControls: () => controls,
    usePickupStates: () => ({}),
    useControlSurfaceTypeListQuery: () => ({ data: [profile] }),
    useSurfaceBindingsQuery: () => ({ data: bindings }),
    useCreateSurfaceBindingMutation: () => [vi.fn(), {}],
    useUpdateSurfaceBindingMutation: () => [vi.fn(), {}],
    useExpandSurfaceBindingMutation: () => [vi.fn(), {}],
    useDeleteSurfaceBindingMutation: () => [vi.fn(), {}],
  }
})
vi.mock('@/store/selection', () => ({
  useDeskSelection: () => [],
  clearDeskSelection: vi.fn(),
}))
// The inspector's stage line resolves a bound property's descriptor before it can read a value,
// so both list queries are stubbed empty: no descriptor, no line, which is the arm every case in
// this file takes.
vi.mock('@/store/groups', () => ({
  useGroupListQuery: () => ({ data: [] }),
  useGroupPropertiesQuery: () => ({ data: [] }),
}))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: [] }),
  useVisibleFixtureListQuery: () => ({ data: [] }),
}))
// The inspector resolves a record binding's uuid to a name through these three; empty is the arm
// every case in this file takes, since none of them binds a record.
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: [] }) }))
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: [] }) }))
vi.mock('@/store/busk', () => ({ useBuskPagesQuery: () => ({ data: [] }) }))
// The narrow fallback and the inspector's two editing surfaces are stubbed: each drags in the
// binding-target picker and, behind it, the speed-master bank, and none of them is what this file
// is asserting about. `EditBindingSheet` lives in the matrix module, so the stub exports both.
vi.mock('@/components/surfaces/BindingMatrix', () => ({
  BindingMatrix: () => <div data-testid="binding-matrix" />,
  EditBindingSheet: () => null,
}))
vi.mock('@/components/surfaces/LearnModeOverlay', () => ({ LearnModeOverlay: () => null }))
// The library is stubbed for the reason above and one more: it reaches the fixture, group and cue
// stores, whose module bodies install WS bridges against the `lightingApi` stub below.
// `SurfaceLibrary.test.tsx` is where its rows are asserted.
let libraryPlacements: ReadonlyMap<string, string> = new Map()
vi.mock('@/components/surfaces/SurfaceLibrary', () => ({
  SurfaceLibrary: (props: { placements: ReadonlyMap<string, string> }) => {
    libraryPlacements = props.placements
    return <div data-testid="surface-library" />
  },
}))
vi.mock('@/components/CurrentProjectRedirect', () => ({ CurrentProjectRedirect: () => null }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
// `@/store/surfaces` is only partially mocked, so its module body still runs — and that body
// installs the bindings-changed bridge. The stub therefore has to be a whole surfaces API, not
// just the one method the page calls.
//
// Everything *else* falls through to a no-op subscriber, `backendMock`'s trick and for its reason:
// store slices self-register a WS subscription at module load, so merely importing one has to be
// safe. This page reaches a long way now — the inspector's stage-value line pulls in the fixture,
// group and channel-source modules — and enumerating each of their bridges would make an unrelated
// new slice able to break this file.
const noSubscription = { unsubscribe: () => {} }
const anyNamespace = new Proxy({} as Record<string, unknown>, {
  get: () => () => noSubscription,
})
const surfaces = {
  setBank: (...args: unknown[]) => setBank(...args),
  subscribeBindingsChanged: () => noSubscription,
  subscribeDevices: () => noSubscription,
  subscribeBanks: () => noSubscription,
  subscribePickup: () => noSubscription,
  subscribeControls: () => noSubscription,
  subscribeEncoderBanks: () => noSubscription,
  subscribeScaler: () => noSubscription,
  getDevices: () => null,
  getBanks: () => null,
  getScaler: () => null,
  getControls: () => null,
  getEncoderBanks: () => null,
}
vi.mock('@/api/lightingApi', () => ({
  lightingApi: new Proxy({ surfaces } as Record<string, unknown>, {
    get: (target, prop: string) => target[prop] ?? anyNamespace,
  }),
}))

const profile: ControlSurfaceType = {
  typeKey: 'xtc',
  vendor: 'Behringer',
  product: 'X-Touch Compact',
  portPattern: null,
  className: 'X',
  banks: [{ id: 'layer-a', name: 'A' }],
  strips: [{ id: 'strip-1', fader: 'fader-1', select: 'btn-25', encoder: null, flash: null }],
  layout: {
    regions: [
      {
        name: 'strips',
        columns: 1,
        cells: [
          { controlId: 'fader-1', col: 0, row: 0 },
          { controlId: 'btn-25', col: 0, row: 1 },
          { controlId: 'bank-layer-a', col: 0, row: 2 },
        ],
      },
    ],
  },
  controls: [
    {
      type: 'fader',
      controlId: 'fader-1',
      label: 'Fader 1',
      cc: 1,
      channel: 1,
      hasMotor: true,
      motorCc: 1,
      touchNote: null,
      touchCc: 101,
      resolution: 'SEVEN_BIT',
    },
    { type: 'button', controlId: 'btn-25', label: 'Button 25', note: 25, channel: 1, ledFeedback: 'ON_OFF' },
    {
      type: 'bankButton',
      controlId: 'bank-layer-a',
      label: 'A',
      note: 90,
      programChange: null,
      channel: 1,
      bankId: 'layer-a',
    },
  ],
}

const matched: SurfaceDeviceInfo = {
  displayKey: 'xtouch',
  displayName: 'X-Touch Compact',
  typeKey: 'xtc',
  isMatched: true,
  hasInputPort: true,
  hasOutputPort: true,
  activeBank: null,
}
const unmatched: SurfaceDeviceInfo = {
  displayKey: 'nano',
  displayName: 'nanoKONTROL2',
  typeKey: null,
  isMatched: false,
  hasInputPort: true,
  hasOutputPort: false,
  activeBank: null,
}

function binding(
  id: number,
  controlId: string,
  target: ControlSurfaceBinding['target'],
  health: ControlSurfaceBinding['health'] = { type: 'ok' },
): ControlSurfaceBinding {
  return {
    id,
    projectId: 1,
    deviceTypeKey: 'xtc',
    controlId,
    bank: null,
    target,
    targetType: target.type,
    takeoverPolicy: null,
    sortOrder: id,
    health,
  }
}

const { SurfacesContent } = await import('./Surfaces')

/**
 * The page joins the app's single `DndContext` with `useDndMonitor` rather than nesting one, so it
 * has to be rendered inside a context here as `Layout.tsx` mounts it in the app. A monitor with no
 * context throws, which is dnd-kit telling you the same thing.
 */
function renderPage(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <DndContext>
        <SurfacesContent projectId={1} />
      </DndContext>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  devices = [matched]
  bindings = []
  controls = {}
  activeBanks = {}
  setBank.mockClear()
  libraryPlacements = new Map()
})
afterEach(cleanup)

describe('Surfaces', () => {
  // D1: these two promise the rig will change *now*, and this is the page where the desk is
  // wired rather than run. The targets and the ShowBar's own toggles are untouched.
  it('has no Blackout or grand-master buttons', () => {
    renderPage()
    expect(screen.queryByText(/blackout/i)).toBeNull()
    expect(screen.queryByText(/^GM /i)).toBeNull()
  })

  it('draws the picture for a device with a layout', () => {
    renderPage()
    expect(screen.getByTestId('surface-panel')).toBeInTheDocument()
    expect(screen.getByTitle('Fader 1')).toBeInTheDocument()
  })

  // An unmatched device is never auto-selected — there is nothing to draw for it — but its chip
  // is a way in to the card that says why, which is the only place that explains itself.
  it('explains an unmatched device rather than drawing an empty panel', () => {
    devices = [matched, unmatched]
    renderPage()
    fireEvent.click(screen.getByText('nanoKONTROL2'))
    expect(screen.queryByTestId('surface-panel')).toBeNull()
    expect(screen.getByText(/didn’t match any registered/)).toBeInTheDocument()
  })

  it('counts every dead binding in the header', () => {
    bindings = [
      binding(1, 'fader-1', { type: 'blackout' }),
      binding(2, 'btn-25', { type: 'fireCue', cueId: 4 }, { type: 'missingCue', cueId: 4 }),
      binding(3, 'strip-1', { type: 'unknown', targetType: 'future', rawPayload: '{}' }, {
        type: 'unknownTarget',
        targetType: 'future',
      }),
    ]
    renderPage()
    expect(screen.getByText('2 dead bindings')).toBeInTheDocument()
  })

  it('opens the inspector on the control a ?binding= link names, and forces its bank', () => {
    bindings = [{ ...binding(7, 'fader-1', { type: 'blackout' }), bank: 'layer-a' }]
    renderPage('/?binding=7')
    // The inspector's own heading, not the panel's cell.
    expect(screen.getByRole('heading', { name: /Fader 1/ })).toBeInTheDocument()
    expect(setBank).toHaveBeenCalledWith('xtc', 'layer-a')
  })

  // A strip row's `controlId` is a strip id, which is in no profile's `controls` — so following
  // the link straight to it renders an empty inspector rather than an error. And this is the
  // common path, not a corner: a group on a strip is what mints the badge that links here.
  it('lands a strip binding’s link on the strip’s fader', () => {
    bindings = [binding(8, 'strip-1', { type: 'strip', target: { type: 'group', key: 'Movers' } })]
    renderPage('/?binding=8')
    expect(screen.getByRole('heading', { name: /Fader 1/ })).toBeInTheDocument()
  })

  // D9: one slot, two occupants. The picture is what both are about, so the library takes the
  // inspector's place rather than opening a second column beside it.
  it('swaps the inspector for the library in edit mode, and back again', () => {
    bindings = [{ ...binding(7, 'fader-1', { type: 'blackout' }), bank: 'layer-a' }]
    renderPage('/?binding=7')
    expect(screen.queryByTestId('surface-library')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Edit bindings' }))
    expect(screen.getByTestId('surface-library')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Fader 1/ })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByTestId('surface-library')).toBeNull()
    expect(screen.getByRole('heading', { name: /Fader 1/ })).toBeInTheDocument()
  })

  /**
   * One cross per *row*, not per control. A strip is one binding covering four controls, so a
   * cross on its fader would remove three other things the operator was not pointing at — it goes
   * on the strip's own backdrop instead, and only a control's own row is crossable on the control.
   */
  /**
   * A binding on a bank button can never fire — `route` switches the bank before it resolves one —
   * so the picture has to say so where the operator is looking rather than only in the inspector.
   * The label stays the bank's, because that is still what the press does.
   */
  it('draws a bank button holding a binding as dead, without letting it claim the target', () => {
    bindings = [binding(11, 'bank-layer-a', { type: 'blackout' })]
    renderPage()
    const cell = screen.getByTitle(/^A · bank layer-a/)
    expect(cell.className).toContain('border-destructive')
    expect(cell.textContent).toBe('A')
    expect(screen.getByTitle(/holds a binding that can never fire/)).toBeInTheDocument()
  })

  it('offers the remove cross on a control’s own row and on the strip, never on a derived control', () => {
    bindings = [
      binding(8, 'strip-1', { type: 'strip', target: { type: 'group', key: 'Movers' } }),
      binding(9, 'fader-1', { type: 'blackout' }),
    ]
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Edit bindings' }))
    // fader-1 has its own row, so it is crossable; btn-25 only resolves through strip-1.
    expect(screen.getByLabelText('Remove the binding on Fader 1')).toBeInTheDocument()
    expect(screen.queryByLabelText('Remove the binding on Button 25')).toBeNull()
    expect(screen.getByLabelText('Remove the strip-1 binding')).toBeInTheDocument()
  })

  /**
   * A bank-agnostic strip row drives the picture on *every* bank — `resolveControl` falls back to
   * it — so a cross that only saw the exact-bank row would leave a visibly bound strip with no way
   * to unbind it while any bank was selected.
   */
  it('crosses off a bank-agnostic strip while a bank is active', () => {
    bindings = [binding(8, 'strip-1', { type: 'strip', target: { type: 'group', key: 'Movers' } })]
    activeBanks = { xtc: 'layer-a' }
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Edit bindings' }))
    expect(screen.getByLabelText('Remove the strip-1 binding')).toBeInTheDocument()
  })

  /**
   * The badge has to describe the bank being drawn. Naming a binding on another bank would tell the
   * operator a group is already on strip 1 while the panel beside it shows strip 1 empty — and the
   * drop they then make creates a second, separate row.
   */
  it('badges a library row only for bindings in force on the shown bank', () => {
    bindings = [
      {
        ...binding(8, 'strip-1', { type: 'strip', target: { type: 'group', key: 'Movers' } }),
        bank: 'layer-b',
      },
      binding(9, 'fader-1', { type: 'groupProperty', groupName: 'Hexes', propertyName: 'dimmer' }),
    ]
    activeBanks = { xtc: 'layer-a' }
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Edit bindings' }))
    // Bank A is showing: the bank-B strip is not, but a bank-agnostic control binding is in force
    // on every bank and still counts.
    expect(libraryPlacements.get('group:Movers')).toBeUndefined()
    expect(libraryPlacements.get('group:Hexes')).toBe('on 1 control')
  })
})
