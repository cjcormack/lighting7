// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { useState, type ComponentProps } from 'react'
import { StageViewMenu, type StageViewMenuTab } from './StageViewMenu'
import { DEFAULT_VIEW_FLAGS } from './useStageView'
import { DEFAULT_SCENE_LAYERS } from './scene/sceneView'
import { createStageStats } from './scene/stageStats'

/**
 * The View popover: every group shows its value, says whose it is, and calls its setter.
 */

type Props = ComponentProps<typeof StageViewMenu>

function setup(over: Partial<Props> = {}) {
  const props: Props = {
    flags: { ...DEFAULT_VIEW_FLAGS, regions: false },
    setFlag: vi.fn(),
    setLabelMode: vi.fn(),
    onTestRecovery: vi.fn(),
    visSource: 'output',
    setVisSource: vi.fn(),
    sourceStatus: {},
    layers: { ...DEFAULT_SCENE_LAYERS, seating: false },
    setLayer: vi.fn(),
    setHaze: vi.fn(),
    lightBudget: 64,
    setLightBudget: vi.fn(),
    goboSurfaces: 'all',
    setGoboSurfaces: vi.fn(),
    boxShadows: 'some',
    setBoxShadows: vi.fn(),
    stats: createStageStats(),
    frameRateReadout: false,
    setFrameRateReadout: vi.fn(),
    ...over,
  }
  render(<StageViewMenu {...props} />)
  return props
}

const open = () => fireEvent.click(screen.getByRole('button', { name: /View options/ }))

// Radix Tabs selects on pointer-down, not click.
const goTo = (name: 'View' | 'Performance') => {
  const tab = screen.getByRole('tab', { name })
  fireEvent.mouseDown(tab)
  fireEvent.focus(tab)
}

const group = (label: string) => screen.getByRole('region', { name: label })
const isOn = (el: HTMLElement) => el.getAttribute('data-state') === 'on' || el.getAttribute('aria-pressed') === 'true'

describe('the trigger', () => {
  it('is plain View on Output and names any other source', () => {
    const { unmount } = render(
      <StageViewMenu flags={DEFAULT_VIEW_FLAGS} setFlag={vi.fn()} setLabelMode={vi.fn()} visSource="output" setVisSource={vi.fn()} />,
    )
    expect(screen.getByRole('button', { name: 'View options' })).toHaveTextContent(/^View$/)
    unmount()
    render(
      <StageViewMenu flags={DEFAULT_VIEW_FLAGS} setFlag={vi.fn()} setLabelMode={vi.fn()} visSource="nextGo" setVisSource={vi.fn()} />,
    )
    const trigger = screen.getByRole('button', { name: 'View options · Next GO' })
    expect(trigger).toHaveTextContent('ViewNext GO')
  })
})

describe('the View tab', () => {
  it('shows the source as a 2 × 2 with only its own hint, and sets it', () => {
    const props = setup({ visSource: 'nextGo', sourceStatus: { nextGo: 'Nothing on deck — showing output' } })
    open()
    const source = group('Source')
    expect(within(source).getByText('this window')).toBeInTheDocument()
    expect(within(source).getByText('Screens')).toBeInTheDocument()
    const items = within(source).getAllByRole('radio')
    expect(items.map((i) => i.textContent)).toEqual(['Output', 'Output + Programmer', 'Programmer only', 'Next GO'])
    expect(items.filter(isOn).map((i) => i.textContent)).toEqual(['Next GO'])
    expect(within(source).getByText(/What the next GO would look like/)).toBeInTheDocument()
    expect(within(source).queryByText(/Final merged DMX/)).toBeNull()
    expect(within(source).getByText('Nothing on deck — showing output')).toBeInTheDocument()

    fireEvent.click(within(source).getByRole('radio', { name: 'Programmer only' }))
    expect(props.setVisSource).toHaveBeenCalledWith('programmer')
    // Pressing the chosen one again is not a way to choose nothing.
    fireEvent.click(within(source).getByRole('radio', { name: 'Next GO' }))
    expect(props.setVisSource).toHaveBeenCalledTimes(1)
  })

  it("shows Show's seven toggles, Light where Beam cones was, and flips them", () => {
    const props = setup()
    open()
    const show = group('Show')
    expect(within(show).getByText('this window')).toBeInTheDocument()
    const toggles = within(show).getAllByRole('button')
    expect(toggles.map((t) => t.textContent)).toEqual(['Fixtures', 'Light', 'Rigging', 'Regions', 'Venue', 'Set', 'Seating'])
    expect(toggles.filter(isOn).map((t) => t.textContent)).toEqual(['Fixtures', 'Light', 'Rigging', 'Venue', 'Set'])
    expect(within(show).getByRole('button', { name: 'Light' })).toHaveAttribute('title', 'Beams and every pool')
    expect(within(show).queryByText('Beam cones')).toBeNull()

    fireEvent.click(within(show).getByRole('button', { name: 'Light' }))
    expect(props.setFlag).toHaveBeenCalledWith('beamCones', false)
    fireEvent.click(within(show).getByRole('button', { name: 'Regions' }))
    expect(props.setFlag).toHaveBeenCalledWith('regions', true)
    fireEvent.click(within(show).getByRole('button', { name: 'Seating' }))
    expect(props.setLayer).toHaveBeenCalledWith('seating', true)
  })

  it('shows Haze with its hint, and Labels, and sets both', () => {
    const props = setup({ flags: { ...DEFAULT_VIEW_FLAGS, labels: 'all' } })
    open()
    const haze = group('Haze')
    expect(within(haze).getAllByRole('radio').filter(isOn).map((i) => i.textContent)).toEqual(['Stage'])
    expect(within(haze).getByText('Upstage of the proscenium, or the stage edge.')).toBeInTheDocument()
    fireEvent.click(within(haze).getByRole('radio', { name: 'Everywhere' }))
    expect(props.setHaze).toHaveBeenCalledWith('everywhere')

    const labels = group('Labels')
    expect(within(labels).getAllByRole('radio').filter(isOn).map((i) => i.textContent)).toEqual(['All fixtures'])
    fireEvent.click(within(labels).getByRole('radio', { name: 'None' }))
    expect(props.setLabelMode).toHaveBeenCalledWith('none')
  })

  it('writes every group as this window, and has no Work lights yet', () => {
    setup()
    open()
    for (const label of ['Source', 'Show', 'Haze', 'Labels']) {
      expect(within(group(label)).getByText('this window')).toBeInTheDocument()
    }
    expect(screen.queryByText('this machine')).toBeNull()
    expect(screen.queryByText(/Work lights/)).toBeNull()
  })
})

describe('the Performance tab', () => {
  it("leads with this canvas's live numbers and the readout toggle", () => {
    const props = setup()
    open()
    goTo('Performance')
    const canvas = group("This window's canvas")
    expect(within(canvas).getByText('this window')).toBeInTheDocument()
    expect(screen.getByTestId('stage-live-rate')).toHaveTextContent('idle')
    expect(screen.getByTestId('stage-live-lights')).toHaveTextContent('No lights packed')
    expect(screen.getByTestId('stage-live-haze')).toHaveTextContent('Haze full')

    act(() => {
      props.stats!.setFrameRate({ fps: 58, ms: 17.2 })
      props.stats!.setLights({ packed: 64, lit: 71 })
      props.stats!.setHazeTier(1)
    })
    expect(screen.getByTestId('stage-live-rate')).toHaveTextContent('58 fps · 17.2 ms a frame')
    expect(screen.getByTestId('stage-live-lights')).toHaveTextContent('Lights 64 on surfaces of 71 lit')
    expect(screen.getByTestId('stage-live-haze')).toHaveTextContent('Haze 66%')

    const toggle = within(canvas).getByRole('switch', { name: 'Frame rate on the canvas' })
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(toggle)
    expect(props.setFrameRateReadout).toHaveBeenCalledWith(true)
  })

  it('says haze is off when the window draws none', () => {
    setup({ layers: { ...DEFAULT_SCENE_LAYERS, haze: 'off' } })
    open()
    goTo('Performance')
    expect(screen.getByTestId('stage-live-haze')).toHaveTextContent('Haze off')
  })

  it("shows the machine's three settings as this machine, and sets them", () => {
    const props = setup()
    open()
    goTo('Performance')
    const budget = group('Light budget')
    expect(within(budget).getByText('this machine')).toBeInTheDocument()
    expect(within(budget).getAllByRole('radio').map((i) => i.textContent)).toEqual(['32', '64', '128', '256'])
    expect(within(budget).getAllByRole('radio').filter(isOn).map((i) => i.textContent)).toEqual(['64'])
    fireEvent.click(within(budget).getByRole('radio', { name: '128' }))
    expect(props.setLightBudget).toHaveBeenCalledWith(128)

    const gobos = group('Gobos on surfaces')
    expect(within(gobos).getByText('this machine')).toBeInTheDocument()
    expect(within(gobos).getAllByRole('radio').filter(isOn).map((i) => i.textContent)).toEqual(['Every gobo light'])
    fireEvent.click(within(gobos).getByRole('radio', { name: 'Selected heads' }))
    expect(props.setGoboSurfaces).toHaveBeenCalledWith('selected')

    const shadows = group('Box shadows')
    expect(within(shadows).getByText('this machine')).toBeInTheDocument()
    expect(within(shadows).getAllByRole('radio').map((i) => i.textContent)).toEqual(['64 a light', '16 a light', 'Off'])
    expect(within(shadows).getAllByRole('radio').filter(isOn).map((i) => i.textContent)).toEqual(['16 a light'])
    fireEvent.click(within(shadows).getByRole('radio', { name: 'Off' }))
    expect(props.setBoxShadows).toHaveBeenCalledWith('off')
  })

  it('ends with Test recovery, which closes the popover', () => {
    const props = setup()
    open()
    goTo('Performance')
    fireEvent.click(screen.getByRole('button', { name: 'Test recovery' }))
    expect(props.onTestRecovery).toHaveBeenCalledOnce()
    expect(screen.queryByRole('tab', { name: 'Performance' })).toBeNull()
  })
})

describe('opened from outside', () => {
  it('opens on the tab it is handed, as the readout opens it on Performance', () => {
    function Host() {
      const [open, setOpen] = useState(false)
      const [tab, setTab] = useState<StageViewMenuTab>('view')
      return (
        <>
          <button
            type="button"
            onClick={() => {
              setTab('performance')
              setOpen(true)
            }}
          >
            readout
          </button>
          <StageViewMenu
            flags={DEFAULT_VIEW_FLAGS}
            setFlag={vi.fn()}
            setLabelMode={vi.fn()}
            visSource="output"
            setVisSource={vi.fn()}
            setFrameRateReadout={vi.fn()}
            open={open}
            onOpenChange={setOpen}
            tab={tab}
            onTabChange={setTab}
          />
        </>
      )
    }
    render(<Host />)
    fireEvent.click(screen.getByRole('button', { name: 'readout' }))
    expect(screen.getByRole('tab', { name: 'Performance' })).toHaveAttribute('data-state', 'active')
    expect(screen.getByRole('switch', { name: 'Frame rate on the canvas' })).toBeInTheDocument()
  })
})
