// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { TooltipProvider } from '@/components/ui/tooltip'
import { OverviewToggle, useOverviewPanels } from './overviewPanels'

/**
 * The registry's whole promise is that the header toolbar and the command palette are two
 * renderings of **one array**, so a panel cannot reach one surface and miss the other — which is
 * how the Stage panel once ended up with a different icon depending on where you summoned it from.
 *
 * `Harness` is `Layout` reduced to exactly that: the toolbar maps `panels` to `OverviewToggle`s,
 * and the palette maps the same `panels` to its own `{label, icon, isVisible, onToggle}` rows.
 * Rendering `Layout` itself would mount the whole desk to assert this, which is what
 * `useSidebarOpen.test.tsx` says not to do.
 */
function Harness() {
  const { panels } = useOverviewPanels()
  return (
    <TooltipProvider>
      <div data-testid="toolbar">
        {panels.map((panel) => (
          <OverviewToggle key={panel.id} panel={panel} />
        ))}
      </div>
      <ul data-testid="palette">
        {panels.map((panel) => (
          <li key={panel.id} data-visible={panel.isVisible} onClick={panel.toggle}>
            {panel.label}
          </li>
        ))}
      </ul>
    </TooltipProvider>
  )
}

function paletteRow(label: string) {
  const row = screen
    .getAllByRole('listitem')
    .find((li) => li.textContent === label)
  if (!row) throw new Error(`no palette row labelled ${label}`)
  return row
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  cleanup()
  localStorage.clear()
})

describe('overview panel registry', () => {
  it('gives Speed Masters a toolbar toggle and a palette entry, from the one array', () => {
    render(<Harness />)

    // The toolbar's tooltips are the trigger's accessible description rather than its name, so the
    // toggle is identified the way the palette row is not: by count and by the palette agreeing.
    expect(screen.getByTestId('toolbar').children).toHaveLength(4)
    expect(
      screen.getAllByRole('listitem').map((li) => li.textContent),
    ).toEqual(['Stage Overview', 'Fixture Overview', 'Speed Master Overview', 'Cue Slots'])
  })

  it('pairs each panel with its own visibility, never by array position', () => {
    render(<Harness />)

    // Pressing the *third* toolbar button must move the *third* panel and nothing else. Pairing
    // the hooks to the descriptors by index rather than by id is the failure this pins: it would
    // put Speed Masters' label and icon on Cue Slots' storage key without changing a count.
    expect(paletteRow('Speed Master Overview').dataset.visible).toBe('false')
    fireEvent.click(screen.getByTestId('toolbar').children[2])

    expect(paletteRow('Speed Master Overview').dataset.visible).toBe('true')
    expect(paletteRow('Stage Overview').dataset.visible).toBe('false')
    expect(paletteRow('Fixture Overview').dataset.visible).toBe('false')
    expect(paletteRow('Cue Slots').dataset.visible).toBe('false')
  })

  it('gives Speed Masters a storage key of its own, so it persists separately', () => {
    render(<Harness />)
    fireEvent.click(paletteRow('Speed Master Overview'))

    // `usePersistentState` writes its fallback on mount, so the others are present and `false`
    // rather than absent — which is the stronger assertion anyway: four live keys, one moved.
    expect(localStorage.getItem('speed-master-overview-visible')).toBe('true')
    expect(localStorage.getItem('cue-slot-overview-visible')).toBe('false')
    expect(localStorage.getItem('stage-overview-visible')).toBe('false')
    expect(localStorage.getItem('fixture-overview-visible')).toBe('false')
  })
})
