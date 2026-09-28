// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import { useTransportKeys } from './useTransportKeys'

/**
 * The guards matter more than the bindings here: every one of them exists because of a way a stray
 * keypress could move a running show.
 */

function Harness({
  enabled = true,
  onGo,
  onBack,
  onToggleLock,
}: {
  enabled?: boolean
  onGo?: () => void
  onBack?: () => void
  onToggleLock?: () => void
}) {
  useTransportKeys({ enabled, onGo, onBack, onToggleLock })
  return (
    <div>
      <input aria-label="field" />
      <div contentEditable aria-label="notes" />
      <button>a button</button>
      <div role="dialog">
        <button>in a dialog</button>
      </div>
      <span data-testid="inert">inert</span>
    </div>
  )
}

afterEach(() => vi.clearAllMocks())

describe('useTransportKeys', () => {
  it('fires GO and BACK from the transport keys', () => {
    const onGo = vi.fn()
    const onBack = vi.fn()
    render(<Harness onGo={onGo} onBack={onBack} />)

    fireEvent.keyDown(window, { code: 'Space' })
    fireEvent.keyDown(window, { code: 'Backspace' })
    expect(onGo).toHaveBeenCalledTimes(1)
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('leaves a key another handler has already claimed alone — including L', () => {
    // The cue sheet's keyboard runs in the capture phase and claims a character typed at the grid
    // by preventing its default. `l` is a character, so a name typed into a cue cell must not
    // toggle the lock as it goes in.
    const onGo = vi.fn()
    const onToggleLock = vi.fn()
    render(<Harness onGo={onGo} onToggleLock={onToggleLock} />)
    const claim = (e: Event) => e.preventDefault()
    window.addEventListener('keydown', claim, true)
    try {
      fireEvent.keyDown(document.body, { code: 'KeyL', key: 'l' })
      fireEvent.keyDown(document.body, { code: 'Space', key: ' ' })
    } finally {
      window.removeEventListener('keydown', claim, true)
    }
    expect(onToggleLock).not.toHaveBeenCalled()
    expect(onGo).not.toHaveBeenCalled()
  })

  it('leaves modified keys to the browser', () => {
    const onGo = vi.fn()
    render(<Harness onGo={onGo} />)
    fireEvent.keyDown(window, { code: 'Space', metaKey: true })
    fireEvent.keyDown(window, { code: 'Space', ctrlKey: true })
    expect(onGo).not.toHaveBeenCalled()
  })

  it('does not fire while typing in a field', () => {
    const onGo = vi.fn()
    const { getByLabelText } = render(<Harness onGo={onGo} />)
    fireEvent.keyDown(getByLabelText('field'), { code: 'Space' })
    expect(onGo).not.toHaveBeenCalled()
  })

  it('does not fire while typing in a contentEditable', () => {
    // Neither predecessor guarded this: both tested tag names, so Space fired a cue mid-word in a
    // rich-text field.
    const onGo = vi.fn()
    const { getByLabelText } = render(<Harness onGo={onGo} />)
    fireEvent.keyDown(getByLabelText('notes'), { code: 'Space' })
    expect(onGo).not.toHaveBeenCalled()
  })

  it('does not fire from inside an open dialog', () => {
    const onGo = vi.fn()
    const { getByText } = render(<Harness onGo={onGo} />)
    fireEvent.keyDown(getByText('in a dialog'), { code: 'Space' })
    expect(onGo).not.toHaveBeenCalled()
  })

  it('does not fire while a button holds focus', () => {
    // The important one. A focused button takes Space as its own activation, so firing GO here as
    // well makes one press do two things — press Space after clicking GO with the mouse and the
    // show advances twice. Run guarded only dialogs and had exactly that defect.
    const onGo = vi.fn()
    const { getByText } = render(<Harness onGo={onGo} />)
    fireEvent.keyDown(getByText('a button'), { code: 'Space' })
    expect(onGo).not.toHaveBeenCalled()
  })

  it('holds the transport when disabled', () => {
    const onGo = vi.fn()
    const onBack = vi.fn()
    render(<Harness enabled={false} onGo={onGo} onBack={onBack} />)
    fireEvent.keyDown(window, { code: 'Space' })
    fireEvent.keyDown(window, { code: 'Backspace' })
    expect(onGo).not.toHaveBeenCalled()
    expect(onBack).not.toHaveBeenCalled()
  })

  it('keeps L live even when the transport is held', () => {
    // Otherwise the state in which Space is deliberately dead is also the state you cannot leave
    // from the keyboard.
    const onToggleLock = vi.fn()
    render(<Harness enabled={false} onToggleLock={onToggleLock} />)
    fireEvent.keyDown(window, { code: 'KeyL' })
    expect(onToggleLock).toHaveBeenCalledTimes(1)
  })

  it('ignores L where no surface offers a lock', () => {
    const onGo = vi.fn()
    render(<Harness onGo={onGo} />)
    fireEvent.keyDown(window, { code: 'KeyL' })
    expect(onGo).not.toHaveBeenCalled()
  })

  it('unbinds on unmount', () => {
    const onGo = vi.fn()
    const { unmount } = render(<Harness onGo={onGo} />)
    unmount()
    fireEvent.keyDown(window, { code: 'Space' })
    expect(onGo).not.toHaveBeenCalled()
  })
})
