// @vitest-environment jsdom
import { useRef, useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { EditorSurface, resetEditorSurfaceMedia } from './EditorSurface'

/**
 * **A file of its own, and that is the point.** React warns about an unknown DOM prop once per
 * property name per module registry, so this assertion run beside the rest of the surface's tests
 * was deduped by whichever of them rendered first and could never fail. Vitest gives each test file
 * a fresh registry, which is what makes it able to.
 *
 * What it guards: the trigger is cloned through `Slot` when the popover is anchored at the Set
 * button, and `Slot` has no `asChild` of its own — so passing one reaches the `<button>` and React
 * warns on every open. Invisible twice over: the warning is a `console.error` the default reporter
 * swallows, and React strips the attribute afterwards, so the rendered HTML looks innocent.
 */

afterEach(() => {
  resetEditorSurfaceMedia()
  vi.unstubAllGlobals()
})

function Harness() {
  const [open, setOpen] = useState(false)
  const anchorRef = useRef<HTMLButtonElement | null>(null)
  return (
    <>
      <button type="button" ref={anchorRef} onClick={() => setOpen(true)}>
        Set
      </button>
      <EditorSurface
        open={open}
        onOpenChange={setOpen}
        title="Dimmer"
        contentClassName="w-64"
        triggerOpens={false}
        anchorRef={anchorRef}
        trigger={<button type="button">cell</button>}
      >
        <p>editor body</p>
      </EditorSurface>
    </>
  )
}

it('clones the cell trigger without handing the DOM a prop it has no use for', () => {
  // Through the click, because that is the only way the Set-button anchor is taken: the ref is
  // still null on the render that opens the editor.
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
  const errors: string[] = []
  const spy = vi.spyOn(console, 'error').mockImplementation((...args) => errors.push(String(args[0])))
  try {
    render(<Harness />)
    fireEvent.click(screen.getByText('Set'))
    expect(screen.getByText('editor body')).toBeVisible()
    expect(errors).toEqual([])
  } finally {
    spy.mockRestore()
  }
})
