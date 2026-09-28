// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLongPress } from './useLongPress'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

beforeEach(() => {
  vi.useFakeTimers()
})

function Pad({
  onLongPress,
  onPress,
  disabled,
}: {
  onLongPress: () => void
  onPress?: () => void
  disabled?: boolean
}) {
  const { handlers } = useLongPress({ onLongPress, onPress, disabled })
  return (
    <button {...handlers} type="button">
      pad
    </button>
  )
}

function down(x = 0, y = 0) {
  fireEvent.pointerDown(screen.getByRole('button'), { clientX: x, clientY: y })
}
function tick(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms)
  })
}

describe('useLongPress', () => {
  it('fires the hold once the delay elapses, and not the short press', () => {
    const onLongPress = vi.fn()
    const onPress = vi.fn()
    render(<Pad onLongPress={onLongPress} onPress={onPress} />)

    down()
    tick(499)
    expect(onLongPress).not.toHaveBeenCalled()
    tick(2)
    expect(onLongPress).toHaveBeenCalledTimes(1)

    fireEvent.pointerUp(screen.getByRole('button'))
    expect(onPress).not.toHaveBeenCalled()
  })

  it('fires the short press on release before the delay', () => {
    const onLongPress = vi.fn()
    const onPress = vi.fn()
    render(<Pad onLongPress={onLongPress} onPress={onPress} />)

    down()
    tick(100)
    fireEvent.pointerUp(screen.getByRole('button'))

    expect(onPress).toHaveBeenCalledTimes(1)
    expect(onLongPress).not.toHaveBeenCalled()
  })

  /**
   * The move threshold is why this is pointer events and not `click`: a pad grid scrolls, and on a
   * touchscreen every scroll begins as a press on whatever is under the finger.
   */
  it('abandons the gesture once the pointer travels past the threshold', () => {
    const onLongPress = vi.fn()
    const onPress = vi.fn()
    render(<Pad onLongPress={onLongPress} onPress={onPress} />)

    down(0, 0)
    fireEvent.pointerMove(screen.getByRole('button'), { clientX: 0, clientY: 40 })
    tick(600)
    fireEvent.pointerUp(screen.getByRole('button'))

    expect(onLongPress).not.toHaveBeenCalled()
    expect(onPress).not.toHaveBeenCalled()
  })

  it('keeps the gesture through a jitter inside the threshold', () => {
    const onPress = vi.fn()
    render(<Pad onLongPress={() => {}} onPress={onPress} />)

    down(0, 0)
    fireEvent.pointerMove(screen.getByRole('button'), { clientX: 3, clientY: 3 })
    fireEvent.pointerUp(screen.getByRole('button'))

    expect(onPress).toHaveBeenCalledTimes(1)
  })

  /**
   * The origin is what lets a hold become a drag: a hold-to-slide control seeds its value from the
   * point the finger landed on, so nothing jumps at the moment the gesture arms.
   */
  it('hands the hold the point the press started at', () => {
    const onLongPress = vi.fn()
    render(<Pad onLongPress={onLongPress} />)

    down(42, 17)
    tick(600)

    expect(onLongPress).toHaveBeenCalledWith({ x: 42, y: 17 })
  })

  it('cancels the hold when the pointer leaves', () => {
    const onLongPress = vi.fn()
    render(<Pad onLongPress={onLongPress} />)

    down()
    fireEvent.pointerLeave(screen.getByRole('button'))
    tick(600)

    expect(onLongPress).not.toHaveBeenCalled()
  })

  it('arms nothing while disabled', () => {
    const onLongPress = vi.fn()
    const onPress = vi.fn()
    render(<Pad onLongPress={onLongPress} onPress={onPress} disabled />)

    down()
    tick(600)
    fireEvent.pointerUp(screen.getByRole('button'))

    expect(onLongPress).not.toHaveBeenCalled()
    // A short press is still a short press — `disabled` withholds the hold, not the click.
    expect(onPress).toHaveBeenCalledTimes(1)
  })
})

/**
 * The container form: handlers on a parent whose children are buttons of their own. Capture runs
 * root-to-child, so `consumeLongPress` in `onClickCapture` is what keeps the click that ended a hold
 * from also activating whatever was under the finger.
 */
describe('useLongPress — consumeLongPress', () => {
  function Card({ onLongPress, onChild }: { onLongPress: () => void; onChild: () => void }) {
    const { handlers, consumeLongPress } = useLongPress({ onLongPress })
    return (
      <div
        {...handlers}
        onClickCapture={(e) => {
          if (consumeLongPress()) e.stopPropagation()
        }}
      >
        <button type="button" onClick={onChild}>
          child
        </button>
      </div>
    )
  }

  it('swallows the click that ended a hold, and only that one', () => {
    const onLongPress = vi.fn()
    const onChild = vi.fn()
    render(<Card onLongPress={onLongPress} onChild={onChild} />)
    const child = screen.getByRole('button')

    fireEvent.pointerDown(child, { clientX: 0, clientY: 0 })
    tick(600)
    fireEvent.pointerUp(child)
    fireEvent.click(child)

    expect(onLongPress).toHaveBeenCalledTimes(1)
    expect(onChild).not.toHaveBeenCalled()

    // The flag is one-shot: the next plain click gets through.
    fireEvent.click(child)
    expect(onChild).toHaveBeenCalledTimes(1)
  })
})

describe('useLongPress, the edges the grid reached', () => {
  it('a second pointer drops the pending hold instead of firing it early for the newcomer', () => {
    const onLongPress = vi.fn()
    const onPress = vi.fn()
    render(<Pad onLongPress={onLongPress} onPress={onPress} />)
    fireEvent.pointerDown(screen.getByRole('button'), { pointerId: 1, clientX: 0, clientY: 0 })
    tick(300)
    fireEvent.pointerDown(screen.getByRole('button'), { pointerId: 2, clientX: 40, clientY: 0 })
    tick(600)
    expect(onLongPress).not.toHaveBeenCalled()
    fireEvent.pointerUp(screen.getByRole('button'))
    expect(onPress).not.toHaveBeenCalled()
  })

  it('cancels the hold when the element unmounts under the finger', () => {
    const onLongPress = vi.fn()
    const { unmount } = render(<Pad onLongPress={onLongPress} />)
    down()
    tick(200)
    unmount()
    tick(600)
    expect(onLongPress).not.toHaveBeenCalled()
  })

  it('forgets a fired hold after a short window, so an unclicked release does not eat the next activation', () => {
    const onLongPress = vi.fn()
    function Chip() {
      const { handlers, consumeLongPress } = useLongPress({ onLongPress })
      return (
        <button {...handlers} type="button" onClick={() => (consumeLongPress() ? null : onLongPress('click'))}>
          pad
        </button>
      )
    }
    render(<Chip />)
    down()
    tick(501)
    expect(onLongPress).toHaveBeenCalledTimes(1)
    fireEvent.pointerUp(screen.getByRole('button'))
    tick(400)
    fireEvent.click(screen.getByRole('button'))
    expect(onLongPress).toHaveBeenLastCalledWith('click')
  })
})
