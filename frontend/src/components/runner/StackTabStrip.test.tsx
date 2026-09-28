// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueStack } from '@/api/cueStacksApi'
import { StackTabStrip } from './StackTabStrip'

// jsdom implements neither, and the strip observes its own scroller.
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function stack(id: number, overrides: Partial<CueStack> = {}): CueStack {
  return {
    id,
    name: `Act ${id}`,
    loop: false,
    sortOrder: id,
    type: 'STACK',
    label: null,
    cues: [],
    activeCueId: null,
    nextCueId: null,
    canEdit: true,
    canDelete: true,
    ...overrides,
  }
}

describe('StackTabStrip', () => {
  it('renders nothing for a single-stack show', () => {
    // The ShowBar carries the stack name in that case; a one-tab switcher is noise.
    const { container } = render(
      <StackTabStrip
        stacks={[stack(1)]}
        selectedStackId={1}
        liveStackId={1}
        runnableStackCount={1}
        onSelectStack={() => {}}
      />,
    )
    expect(container.innerHTML).toBe('')
  })

  it('scrolls the selected tab into view when something else moves it', () => {
    // The selection changes without a click — a playhead follow, a deep link, another desk — and
    // the strip used to select a tab that could be off the right-hand edge with nothing saying so.
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView

    const stacks = [stack(1), stack(2), stack(3)]
    const { rerender } = render(
      <StackTabStrip
        stacks={stacks}
        selectedStackId={1}
        liveStackId={1}
        runnableStackCount={3}
        onSelectStack={() => {}}
      />,
    )
    scrollIntoView.mockClear()

    rerender(
      <StackTabStrip
        stacks={stacks}
        selectedStackId={3}
        liveStackId={3}
        runnableStackCount={3}
        onSelectStack={() => {}}
      />,
    )

    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(scrollIntoView.mock.instances[0]).toBe(
      document.querySelector('[data-stack-id="3"]'),
    )
    // Determinate, not animated: mid-show a jump beats a scroll you have to wait out.
    expect(scrollIntoView).toHaveBeenCalledWith(
      expect.objectContaining({ behavior: 'auto', inline: 'nearest' }),
    )
  })

  it('selects a stack on click, and draws separators as non-interactive', () => {
    const onSelectStack = vi.fn()
    const stacks = [
      stack(1),
      stack(2, { type: 'SEPARATOR', label: 'INTERVAL' }),
      stack(3),
    ]
    render(
      <StackTabStrip
        stacks={stacks}
        selectedStackId={1}
        liveStackId={1}
        runnableStackCount={2}
        onSelectStack={onSelectStack}
      />,
    )

    expect(screen.getByText('INTERVAL')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Act 3/ }))
    expect(onSelectStack).toHaveBeenCalledWith(stacks[2])
  })

  it('marks the live stack even while a different one is selected', () => {
    // The whole point of the prop split. Browsing off the playhead is now possible, so the strip
    // has to say where the show actually is — GO acts there, not on what you are reading.
    render(
      <StackTabStrip
        stacks={[stack(1), stack(2)]}
        selectedStackId={2}
        liveStackId={1}
        runnableStackCount={2}
        onSelectStack={() => {}}
      />,
    )
    const live = screen.getByRole('button', { name: /Act 1/ })
    const selected = screen.getByRole('button', { name: /Act 2/ })
    expect(live.querySelector('[aria-label="Live"]')).toBeTruthy()
    expect(selected.querySelector('[aria-label="Live"]')).toBeNull()
    expect(selected.getAttribute('aria-current')).toBe('page')
    expect(live.getAttribute('aria-current')).toBeNull()
  })

  it('marks the live stack when it is also the one selected', () => {
    // Previously impossible to express: the pip was drawn only on UNselected tabs, because
    // selected-==-live was an invariant. On the playhead both marks must show.
    render(
      <StackTabStrip
        stacks={[stack(1), stack(2)]}
        selectedStackId={1}
        liveStackId={1}
        runnableStackCount={2}
        onSelectStack={() => {}}
      />,
    )
    const tab = screen.getByRole('button', { name: /Act 1/ })
    expect(tab.querySelector('[aria-label="Live"]')).toBeTruthy()
    expect(tab.getAttribute('aria-current')).toBe('page')
  })

  it('draws no live mark while the show is stopped', () => {
    render(
      <StackTabStrip
        stacks={[stack(1), stack(2)]}
        selectedStackId={1}
        liveStackId={null}
        runnableStackCount={2}
        onSelectStack={() => {}}
      />,
    )
    expect(document.querySelector('[aria-label="Live"]')).toBeNull()
  })

  it('washes amber with the rest of the chrome band', () => {
    // The strip sits between the show bar and the stack's navigation row, all of which tint
    // together — tinting only the header above them reads as stripes.
    const { container } = render(
      <StackTabStrip
        stacks={[stack(1), stack(2)]}
        selectedStackId={1}
        liveStackId={1}
        runnableStackCount={2}
        onSelectStack={() => {}}
        unlockedWarning
      />,
    )
    expect(container.firstElementChild!.className).toContain('bg-amber-400/15')
  })

  it('offers a scroll affordance only on the side that has more', () => {
    const stacks = [stack(1), stack(2), stack(3)]
    render(
      <StackTabStrip
        stacks={stacks}
        selectedStackId={1}
        liveStackId={1}
        runnableStackCount={3}
        onSelectStack={() => {}}
      />,
    )
    const scroller = document.querySelector('[aria-label="Stack tabs"]') as HTMLElement

    // jsdom lays nothing out, so the widths are stubbed to describe "overflowing, scrolled left".
    Object.defineProperty(scroller, 'scrollWidth', { value: 1200, configurable: true })
    Object.defineProperty(scroller, 'clientWidth', { value: 400, configurable: true })
    scroller.scrollLeft = 0
    fireEvent.scroll(scroller)

    expect(screen.queryByLabelText('Scroll stacks left')).toBeNull()
    expect(screen.getByLabelText('Scroll stacks right')).toBeTruthy()

    scroller.scrollLeft = 800
    fireEvent.scroll(scroller)
    expect(screen.getByLabelText('Scroll stacks left')).toBeTruthy()
    expect(screen.queryByLabelText('Scroll stacks right')).toBeNull()
  })
})
