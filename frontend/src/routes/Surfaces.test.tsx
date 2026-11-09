// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ControlSurfaceBinding, SurfaceDeviceInfo } from '@/store/surfaces'
import { CATALOGUE, FIXTURE_PROFILE, MAP_FRAME } from '@/components/surfaces/surfaceFixtures'

/**
 * The Surfaces tab's structural claims: a matched device is drawn as a picture from the desk's map
 * and an unmatched one as the explain-yourself card, the dead count is a header badge, viewing a
 * layer moves nothing on the hardware (D13), the table is the other view, and `?binding=` lands on
 * the control it names.
 */

let devices: SurfaceDeviceInfo[] = []
let bindings: ControlSurfaceBinding[] = []
let activeBanks: Record<string, string> = {}
const sent = vi.hoisted(() => [] as unknown[])

vi.mock('@/store/surfaces', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/store/surfaces')>()
  return {
    ...actual,
    useSurfaceDevices: () => devices,
    useActiveBanks: () => activeBanks,
    useSurfaceControls: () => ({}),
    usePickupStates: () => ({}),
    useControlSurfaceTypeListQuery: () => ({ data: [FIXTURE_PROFILE] }),
    useSurfaceBindingsQuery: () => ({ data: bindings }),
    useSurfaceFunctionsQuery: () => ({ data: CATALOGUE }),
    useSurfaceMap: () => MAP_FRAME,
    useWriteSurfaceSlotMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) })), {}],
    useClearSurfaceSlotMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve() })), {}],
    useIdentify: () => ({ wait: { waiting: false, endsAt: null, ended: null }, begin: vi.fn(), cancel: vi.fn() }),
  }
})
vi.mock('@/store/selection', () => ({ useDeskSelection: () => [], clearDeskSelection: vi.fn() }))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtures', () => ({ useVisibleFixtureListQuery: () => ({ data: [] }) }))
vi.mock('@/components/surfaces/recordOptions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/surfaces/recordOptions')>()
  return { ...actual, useRecordOptions: () => actual.EMPTY_RECORD_OPTIONS }
})
vi.mock('@/components/surfaces/SurfaceSheet', () => ({ SurfaceSheet: () => <div data-testid="surface-sheet" /> }))
vi.mock('@/components/CurrentProjectRedirect', () => ({ CurrentProjectRedirect: () => null }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
// Every namespace answers a no-op subscriber, since store slices subscribe at module load. The
// surfaces namespace records every call that is not a subscription or a read, which is how the
// layer tests see that nothing was sent.
vi.mock('@/api/lightingApi', () => {
  const noSubscription = { unsubscribe: () => {} }
  const anyNamespace = new Proxy({}, { get: () => () => noSubscription })
  const surfaces = new Proxy(
    {},
    {
      get: (_target, name: string) => (...args: unknown[]) => {
        if (name.startsWith('subscribe')) return noSubscription
        if (name.startsWith('get')) return null
        sent.push([name, ...args])
        return true
      },
    },
  )
  return { lightingApi: new Proxy({ surfaces } as Record<string, unknown>, { get: (target, prop: string) => target[prop] ?? anyNamespace }) }
})

import { SurfacesContent } from './Surfaces'

const device = (displayKey: string, isMatched = true): SurfaceDeviceInfo => ({
  displayKey,
  displayName: displayKey,
  typeKey: isMatched ? FIXTURE_PROFILE.typeKey : null,
  isMatched,
  hasInputPort: true,
  hasOutputPort: true,
  activeBank: null,
})

function renderRoute(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SurfacesContent projectId={1} />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  devices = [device('xtouch')]
  bindings = []
  activeBanks = { xtouch: 'layer-a' }
  sent.length = 0
})
afterEach(cleanup)

describe('Surfaces', () => {
  it('draws the picture for a device with a layout, labelled from the desk\'s map', () => {
    renderRoute()
    expect(screen.getByTestId('surface-panel')).toBeInTheDocument()
    expect(within(screen.getByTitle('Button 3')).getByText('GO · Act 1')).toBeInTheDocument()
  })

  it('explains an unmatched device rather than drawing an empty panel', () => {
    devices = [device('mystery', false)]
    renderRoute()
    fireEvent.click(screen.getByRole('button', { name: /mystery/ }))
    expect(screen.queryByTestId('surface-panel')).toBeNull()
    expect(screen.getByText(/didn’t match any registered/)).toBeInTheDocument()
  })

  it('counts every dead binding in the header', () => {
    bindings = [1, 2].map((id) => ({
      id,
      projectId: 1,
      deviceTypeKey: FIXTURE_PROFILE.typeKey,
      controlId: `btn-${id}`,
      part: 'MAIN',
      bank: null,
      binding: { does: { type: 'go' } },
      takeoverPolicy: null,
      sortOrder: 0,
      health: { type: 'missingStack', stackId: 0 },
    }))
    renderRoute()
    expect(screen.getByText('2 dead bindings')).toBeInTheDocument()
  })

  it('views a layer without switching the desk (D13)', () => {
    renderRoute()
    fireEvent.click(screen.getByRole('button', { name: /^Layer B/ }))
    expect(within(screen.getByTitle('Fader 1')).getByText('Dimmer · Hex 1')).toBeInTheDocument()
    expect(screen.getByText(/the desk is on Layer A, so its live state is faint/)).toBeInTheDocument()
    expect(screen.getByTestId('surface-panel').getAttribute('data-dimmed')).toBe('true')
    expect(sent).toEqual([])
  })

  it('views no layer while the desk is on none, where only bindings on both layers fire', () => {
    activeBanks = {}
    renderRoute()
    const noLayer = screen.getByRole('button', { name: /^No layer/ })
    expect(noLayer.getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByText(/only bindings on both layers fire/)).toBeInTheDocument()
    // The strip is on both layers; Layer B's own fader row is not.
    expect(within(screen.getByTitle('Fader 1')).queryByText('Dimmer · Hex 1')).toBeNull()
    expect(screen.getByTestId('surface-panel').getAttribute('data-dimmed')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^Layer A/ }))
    expect(screen.getByTestId('surface-panel').getAttribute('data-dimmed')).toBe('true')
    expect(sent).toEqual([])
  })

  it('draws no No-layer chip while the desk is on a layer', () => {
    renderRoute()
    expect(screen.queryByRole('button', { name: /^No layer/ })).toBeNull()
  })

  it('opens the inspector on a ?binding= link\'s control and views its layer, moving nothing', () => {
    bindings = [
      {
        id: 7,
        projectId: 1,
        deviceTypeKey: FIXTURE_PROFILE.typeKey,
        controlId: 'fader-1',
        part: 'MAIN',
        bank: 'layer-b',
        binding: { does: { type: 'level', attribute: 'dimmer' }, on: { type: 'fixture', key: 'hex-1' } },
        takeoverPolicy: null,
        sortOrder: 0,
        health: { type: 'ok' },
      },
    ]
    renderRoute('/?binding=7')
    expect(within(screen.getByTestId('surface-inspector')).getByText('Fader 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Layer B/ }).getAttribute('aria-pressed')).toBe('true')
    expect(sent).toEqual([])
  })

  it('swaps the picture for the table', () => {
    renderRoute()
    fireEvent.click(screen.getByRole('button', { name: 'Table' }))
    expect(screen.getByTestId('surface-table-view').className).not.toContain('md:hidden')
    expect(screen.getByTestId('surface-sheet')).toBeInTheDocument()
  })

  it('keeps the table as the only view below tablet width', () => {
    renderRoute()
    // The picture's wrapper is hidden below md; the table's is shown there and hidden from md up.
    expect(screen.getByTestId('surface-panel').parentElement!.className).toContain('hidden')
    expect(screen.getByTestId('surface-table-view').className).toContain('md:hidden')
  })
})
