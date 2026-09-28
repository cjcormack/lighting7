// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useEscapeEditorSnapshot } from './useEscapeEditorSnapshot'

/**
 * The half of the grid's Escape ladder that is about **listener phases**, which is the half that
 * was wrong and which no amount of reading the handler would have shown.
 *
 * The grid's keydown handler is on the window, and Radix's dismissable layer listens on the
 * document with `{capture: true}`. On the way back up Radix has already closed the panel — and a
 * keydown is discrete, so React has already flushed the unmount — so asking "is an editor open"
 * from the bubble handler always answers no, and Escape clears the selection the editor was opened
 * for. The fix is to ask in the window's capture phase, and that is what these tests pin: not that
 * the answer is read, but that it is read *before anything else can act on the same key*.
 */

/** Stands in for an open cell editor: the attribute `editorIsOpen` looks for. */
function mountEditor(): HTMLElement {
  const el = document.createElement('div')
  el.setAttribute('data-cell-editor-surface', 'popover')
  document.body.appendChild(el)
  return el
}

function pressEscape(target: EventTarget = document.body) {
  target.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
  )
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('useEscapeEditorSnapshot', () => {
  it('answers false with no editor on screen', () => {
    const { result } = renderHook(() => useEscapeEditorSnapshot())
    pressEscape()
    expect(result.current.current).toBe(false)
  })

  it('answers true while an editor is open, wherever the key was pressed', () => {
    // Focus is deliberately not part of the question: after a second press of the bar's Set it sits
    // on the button rather than in the panel, which is exactly when the old guard answered wrongly.
    const { result } = renderHook(() => useEscapeEditorSnapshot())
    mountEditor()
    const button = document.createElement('button')
    document.body.appendChild(button)
    pressEscape(button)
    expect(result.current.current).toBe(true)
  })

  it('records the answer BEFORE a document listener can close the editor', () => {
    // The ordering contract, stated as a test. Radix's layer is a document-capture listener that
    // closes the panel; this stands in for it and removes the editor from the DOM mid-flight. The
    // snapshot must still say "there was one", or the selection is cleared out from under it.
    const { result } = renderHook(() => useEscapeEditorSnapshot())
    const editor = mountEditor()
    const closeIt = () => editor.remove()
    document.addEventListener('keydown', closeIt, true)
    try {
      pressEscape()
    } finally {
      document.removeEventListener('keydown', closeIt, true)
    }

    expect(document.querySelector('[data-cell-editor-surface]')).toBeNull()
    expect(result.current.current).toBe(true)
  })

  it('is rewritten on every Escape, so it can never be read stale', () => {
    const { result } = renderHook(() => useEscapeEditorSnapshot())
    const editor = mountEditor()
    pressEscape()
    expect(result.current.current).toBe(true)

    editor.remove()
    pressEscape()
    expect(result.current.current).toBe(false)
  })

  it('stops listening when the grid unmounts', () => {
    const { result, unmount } = renderHook(() => useEscapeEditorSnapshot())
    const ref = result.current
    unmount()
    mountEditor()
    pressEscape()
    expect(ref.current).toBe(false)
  })
})
