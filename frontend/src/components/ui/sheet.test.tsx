// @vitest-environment jsdom
import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { Sheet, SheetClose, SheetContent, SheetHeader, SheetTitle, useUnsavedChanges } from './sheet'
import { hasUnsavedSheets, resetUnsavedSheets } from '@/lib/unsavedSheets'

/**
 * Escape closes a sheet, except while the Kotlin editor's completion popup is open — that popup
 * is a bare element on `<body>`, so without this guard dismissing it also threw away whatever was
 * being edited in the sheet behind it. Radix reads Escape in the capture phase, which is what
 * makes a DOM check inside the handler reliable rather than a race with CodeMirror's own close.
 */
function TestSheet() {
  const [open, setOpen] = useState(true)
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Edit</SheetTitle>
        </SheetHeader>
      </SheetContent>
    </Sheet>
  )
}

/** A sheet body that reports its own dirty state, the way a form component does. */
function DirtyBody({ unsaved }: { unsaved: boolean }) {
  useUnsavedChanges(unsaved)
  return <p>body</p>
}

/**
 * The **wrong** shape, kept as a harness: the hook called in the component that renders the
 * `<Sheet>` rather than in a body inside it.
 */
function SelfGuardedSheet({ unsaved }: { unsaved: boolean }) {
  const [open, setOpen] = useState(true)
  useUnsavedChanges(unsaved)
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Edit</SheetTitle>
        </SheetHeader>
      </SheetContent>
    </Sheet>
  )
}

/** The same component, guarding the way it should: through the prop. */
function PropGuardedSheet({ unsaved }: { unsaved: boolean }) {
  const [open, setOpen] = useState(true)
  return (
    <Sheet open={open} onOpenChange={setOpen} unsavedChanges={unsaved}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Edit</SheetTitle>
        </SheetHeader>
      </SheetContent>
    </Sheet>
  )
}

function GuardedSheet({ unsaved }: { unsaved: boolean }) {
  const [open, setOpen] = useState(true)
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Edit</SheetTitle>
        </SheetHeader>
        <DirtyBody unsaved={unsaved} />
        <SheetClose asChild>
          <button type="button">Cancel</button>
        </SheetClose>
      </SheetContent>
    </Sheet>
  )
}

describe('SheetContent', () => {
  it('closes on Escape', () => {
    render(<TestSheet />)
    expect(screen.getByText('Edit')).toBeInTheDocument()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByText('Edit')).not.toBeInTheDocument()
  })

  it('stays open when Escape dismisses the completion popup instead', () => {
    render(<TestSheet />)
    // What CodeMirror appends while its hint list is showing.
    const hints = document.createElement('ul')
    hints.className = 'CodeMirror-hints'
    document.body.appendChild(hints)

    try {
      fireEvent.keyDown(document, { key: 'Escape' })
      expect(screen.getByText('Edit')).toBeInTheDocument()
    } finally {
      hints.remove()
    }

    // …and once the popup is gone, Escape closes the sheet as usual.
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByText('Edit')).not.toBeInTheDocument()
  })

  it('asks before discarding when the body reports unsaved changes', () => {
    render(<GuardedSheet unsaved />)

    fireEvent.keyDown(document, { key: 'Escape' })

    // Still open, with the question in front of it.
    expect(screen.getByText('Edit')).toBeInTheDocument()
    expect(screen.getByText('Discard changes?')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }))
    expect(screen.getByText('Edit')).toBeInTheDocument()

    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }))
    expect(screen.queryByText('Edit')).not.toBeInTheDocument()
  })

  it('closes without asking when nothing is unsaved', () => {
    render(<GuardedSheet unsaved={false} />)

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByText('Discard changes?')).not.toBeInTheDocument()
    expect(screen.queryByText('Edit')).not.toBeInTheDocument()
  })

  it('asks when a Cancel wired through SheetClose is pressed', () => {
    // A Cancel calling the parent's own setOpen(false) closes the sheet without Radix knowing,
    // so it never reaches the guard. Wrapping it in SheetClose is what puts it back on that path.
    render(<GuardedSheet unsaved />)

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.getByText('Edit')).toBeInTheDocument()
    expect(screen.getByText('Discard changes?')).toBeInTheDocument()
  })
})

/**
 * **Where the hook is called decides whether it does anything**, and getting it wrong is silent.
 *
 * `useUnsavedChanges` reads a context that `Sheet` itself provides, so a call in the component that
 * *renders* the `<Sheet>` resolves against providers **above** that component — the `<Sheet>` is in
 * the value it returns, which is below it. `register` is then null and the registration no-ops.
 *
 * Four sheets shipped with that shape and no discard guard at all: an edit could be lost to Escape
 * or a click outside with no prompt. Pinned in both directions so the working form cannot be
 * "simplified" into the broken one — and the fix is the `unsavedChanges` prop, which exists for a
 * component that owns both the dirty state and the `<Sheet>`.
 */
describe('where useUnsavedChanges is called', () => {
  it('does nothing from the component that renders the Sheet', () => {
    render(<SelfGuardedSheet unsaved />)

    fireEvent.keyDown(document, { key: 'Escape' })

    // No prompt, and the sheet is gone — the edit went with it.
    expect(screen.queryByText('Discard changes?')).not.toBeInTheDocument()
    expect(screen.queryByText('Edit')).not.toBeInTheDocument()
  })

  it('guards from that same component through the unsavedChanges prop', () => {
    render(<PropGuardedSheet unsaved />)

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.getByText('Discard changes?')).toBeInTheDocument()
    expect(screen.getByText('Edit')).toBeInTheDocument()
  })
})

/**
 * The module-level dirty count (`lib/unsavedSheets.ts`), fed from the provider so a `windows.show`
 * from another screen can decline while a form here is half-edited (multi-screen plan §3.4). Both
 * ways of saying dirty feed it; a closed sheet, and an unmounted one, do not.
 */
describe('the dirty-sheet count', () => {
  afterEach(resetUnsavedSheets)

  function BodyGuarded({ unsaved }: { unsaved: boolean }) {
    const [open, setOpen] = useState(true)
    return (
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent>
          <SheetHeader>
            <SheetTitle>Edit</SheetTitle>
          </SheetHeader>
          <DirtyBody unsaved={unsaved} />
        </SheetContent>
      </Sheet>
    )
  }

  it('counts a body reporting through the hook, and clears when it stops', () => {
    const { rerender, unmount } = render(<BodyGuarded unsaved />)
    expect(hasUnsavedSheets()).toBe(true)
    rerender(<BodyGuarded unsaved={false} />)
    expect(hasUnsavedSheets()).toBe(false)
    rerender(<BodyGuarded unsaved />)
    expect(hasUnsavedSheets()).toBe(true)
    unmount()
    expect(hasUnsavedSheets()).toBe(false)
  })

  it('counts the parent’s own prop, but not while the sheet is closed', () => {
    const { rerender } = render(
      <Sheet open unsavedChanges onOpenChange={() => {}}>
        <SheetContent>
          <SheetHeader>
            <SheetTitle>Edit</SheetTitle>
          </SheetHeader>
        </SheetContent>
      </Sheet>,
    )
    expect(hasUnsavedSheets()).toBe(true)
    // The form state outlives the panel; a closed sheet loses nothing on a navigation.
    rerender(
      <Sheet open={false} unsavedChanges onOpenChange={() => {}}>
        <SheetContent>
          <SheetHeader>
            <SheetTitle>Edit</SheetTitle>
          </SheetHeader>
        </SheetContent>
      </Sheet>,
    )
    expect(hasUnsavedSheets()).toBe(false)
  })
})
