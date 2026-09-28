// @vitest-environment jsdom
import { Provider } from 'react-redux'
import { DndContext } from '@dnd-kit/core'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from '@/store'
import type { BuskPad } from '@/api/buskApi'
import type { TemplateSummary } from '@/api/templatesApi'
import { BuskPadButton } from './BuskPad'

/**
 * The one pad, and the two faces inside it.
 *
 * A `DndContext` is required because the pad registers itself as both a draggable and a droppable —
 * it does not create one (the app's single context is the whole point), so the test supplies one.
 */

const AT = { row: 0, column: 0, bank: 0, pad: 0 }

function templatePad(overrides: Partial<TemplateSummary> = {}): BuskPad {
  return {
    id: 1,
    uuid: 'p1',
    kind: 'TEMPLATE',
    template: {
      id: 7,
      uuid: 't7',
      name: 'Amber Key',
      notes: 'the warm one',
      fadeDurationMs: null,
      family: 'COLOUR',
      isGeneric: true,
      kind: 'value',
      rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'colour', value: '#FF9D4A' }],
      effect: null,
      layerCount: 0,
      ...overrides,
    } as TemplateSummary,
  }
}

const cuePad: BuskPad = {
  id: 2,
  uuid: 'p2',
  kind: 'CUE',
  cue: { id: 12, uuid: 'c12', name: 'Verse 2', cueNumber: '12', cueStackId: 3, cueStackName: 'Main Show' },
}

function draw(
  pad: BuskPad,
  props: Partial<React.ComponentProps<typeof BuskPadButton>> = {},
) {
  return render(
    <Provider store={store}>
      <DndContext>
        <BuskPadButton
          pad={pad}
          at={AT}
          presence="none"
          isLive={false}
          editing={false}
          onPress={() => {}}
          onRemove={() => {}}
          onInspect={() => {}}
          onPickUp={() => {}}
          {...props}
        />
      </DndContext>
    </Provider>,
  )
}

afterEach(cleanup)

describe('a template or Look pad', () => {
  it('draws its name, its detail line and its colour', () => {
    const { container } = draw(templatePad())
    expect(screen.getByText('Amber Key')).toBeTruthy()
    expect(screen.getByText('Colour')).toBeTruthy()
    expect(container.querySelector('[style*="rgb(255, 157, 74)"]')).toBeTruthy()
  })

  it('keeps the notes off the face and in the tooltip', () => {
    draw(templatePad())
    expect(screen.queryByText('the warm one')).toBeNull()
    expect(screen.getByRole('button').getAttribute('title')).toBe('the warm one')
  })

  it('climbs the presence ladder', () => {
    const { container: none } = draw(templatePad(), { presence: 'none' })
    expect(none.querySelector('button')!.className).toContain('bg-card')
    expect(none.querySelectorAll('.rounded-full')).toHaveLength(0)
    cleanup()

    const { container: some } = draw(templatePad(), { presence: 'some' })
    expect(some.querySelector('button')!.className).toContain('bg-primary/10')
    expect(some.querySelector('.bg-primary\\/50')).toBeTruthy()
    cleanup()

    const { container: all } = draw(templatePad(), { presence: 'all' })
    expect(all.querySelector('button')!.className).toContain('ring-primary/50')
    expect(all.querySelector('button')!.getAttribute('aria-pressed')).toBe('true')
  })

  it('marks an effect template with the wave rather than a swatch', () => {
    const { container } = draw(
      templatePad({
        kind: 'effect',
        rows: [],
        effect: {
          effectType: 'Colour Pulse',
          category: 'colour',
          beatDivision: 0.5,
          blendMode: 'HTP',
          distribution: 'ALL',
          parameters: {},
          timingSource: 'BEAT',
        },
      }),
    )
    expect(container.querySelector('.lucide-audio-waveform')).toBeTruthy()
    expect(container.querySelector('[style*="background"]')).toBeNull()
  })
})

describe('a cue pad', () => {
  it('draws the number, the name and the stack', () => {
    draw(cuePad)
    expect(screen.getByText('12')).toBeTruthy()
    expect(screen.getByText('Verse 2')).toBeTruthy()
    expect(screen.getByText('Main Show')).toBeTruthy()
  })

  it('goes green when its stack has that cue on stage, and not otherwise', () => {
    const { container } = draw(cuePad, { isLive: true })
    expect(container.querySelector('button')!.className).toContain('border-green-500/70')
    cleanup()
    const { container: dark } = draw(cuePad)
    expect(dark.querySelector('button')!.className).not.toContain('border-green-500/70')
  })
})

describe('pressing', () => {
  it('presses on a click', () => {
    const onPress = vi.fn()
    draw(templatePad(), { onPress })
    const pad = screen.getByRole('button')
    fireEvent.pointerDown(pad, { clientX: 0, clientY: 0 })
    fireEvent.pointerUp(pad)
    expect(onPress).toHaveBeenCalledTimes(1)
  })

  it('does not press while editing, and offers a cross instead', () => {
    const onPress = vi.fn()
    const onRemove = vi.fn()
    draw(templatePad(), { onPress, onRemove, editing: true })
    const pad = screen.getByTitle('Amber Key')
    fireEvent.pointerDown(pad, { clientX: 0, clientY: 0 })
    fireEvent.pointerUp(pad)
    expect(onPress).not.toHaveBeenCalled()

    fireEvent.click(screen.getByLabelText('Remove Amber Key'))
    expect(onRemove).toHaveBeenCalledTimes(1)
  })

  it('offers no cross outside edit mode', () => {
    draw(templatePad())
    expect(screen.queryByLabelText('Remove Amber Key')).toBeNull()
  })
})

/**
 * The hold menu (multi-screen plan §3.5).
 *
 * The hold used to navigate straight to the library. It opens a **menu** now, because the hand
 * needed a door on a pad and a hold cannot mean two things — so *Pick up* is first and *View* is
 * the old behaviour, one item down.
 */
describe('the hold menu', () => {
  /** A hold, as `useLongPress` sees it: down, wait past the delay, and the menu opens under it. */
  function hold(pad: HTMLElement) {
    vi.useFakeTimers()
    fireEvent.pointerDown(pad, { clientX: 5, clientY: 5 })
    act(() => {
      vi.advanceTimersByTime(600)
    })
    vi.useRealTimers()
  }

  it('offers Pick up first, then View', () => {
    draw(templatePad())
    hold(screen.getByRole('button'))
    const items = screen.getAllByRole('menuitem').map((i) => i.textContent)
    expect(items).toEqual(['Pick up', 'View'])
  })

  it('picks the record up, and leaves the pad where it is', () => {
    const onPickUp = vi.fn()
    const onPress = vi.fn()
    draw(templatePad(), { onPickUp, onPress })
    hold(screen.getByRole('button'))
    fireEvent.click(screen.getByText('Pick up'))
    expect(onPickUp).toHaveBeenCalledTimes(1)
    // A hold is not a press: the pad must not fire on the way into its own menu.
    expect(onPress).not.toHaveBeenCalled()
  })

  it('keeps View as the hold’s old behaviour', () => {
    const onInspect = vi.fn()
    draw(templatePad(), { onInspect })
    hold(screen.getByRole('button'))
    fireEvent.click(screen.getByText('View'))
    expect(onInspect).toHaveBeenCalledTimes(1)
  })

  it('draws no menu while editing — there the pad is a drag handle', () => {
    draw(templatePad(), { editing: true })
    fireEvent.contextMenu(screen.getByTitle('Amber Key'))
    expect(screen.queryByRole('menuitem')).toBeNull()
  })
})
