// @vitest-environment jsdom
import { fireEvent, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { undoKeyBlocked, undoKeyOf, undoShortcutLabel, useUndoKeys } from './undoKeys'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('the undo keys', () => {
  it('reads ⌘Z and ⇧⌘Z, Ctrl as ⌘, and nothing with ⌥', () => {
    const key = (k: string, mods: Partial<KeyboardEvent> = {}) =>
      undoKeyOf({ key: k, metaKey: false, ctrlKey: false, altKey: false, shiftKey: false, ...mods })
    expect(key('z', { metaKey: true })).toBe('undo')
    expect(key('Z', { metaKey: true, shiftKey: true })).toBe('redo')
    expect(key('z', { ctrlKey: true })).toBe('undo')
    expect(key('z', { metaKey: true, altKey: true })).toBeNull()
    expect(key('z')).toBeNull()
    expect(key('y', { metaKey: true })).toBeNull()
  })

  it('stands aside in a text field, a contenteditable and an open dialog, but not a sheet', () => {
    const input = document.body.appendChild(document.createElement('input'))
    const editable = document.body.appendChild(document.createElement('div'))
    editable.setAttribute('contenteditable', 'true')
    const plain = document.body.appendChild(document.createElement('button'))
    expect(undoKeyBlocked(input)).toBe(true)
    expect(undoKeyBlocked(editable)).toBe(true)
    expect(undoKeyBlocked(plain)).toBe(false)

    const sheet = document.body.appendChild(document.createElement('div'))
    sheet.setAttribute('role', 'dialog')
    sheet.setAttribute('data-slot', 'sheet-content')
    expect(undoKeyBlocked(plain)).toBe(false)

    document.body.appendChild(document.createElement('div')).setAttribute('role', 'dialog')
    expect(undoKeyBlocked(plain)).toBe(true)
  })

  it('stands aside for an alert dialog', () => {
    const plain = document.body.appendChild(document.createElement('button'))
    document.body.appendChild(document.createElement('div')).setAttribute('role', 'alertdialog')
    expect(undoKeyBlocked(plain)).toBe(true)
  })

  it('undoes on ⌘Z and redoes on ⇧⌘Z while mounted, and leaves a key taken first or held', async () => {
    const undo = vi.fn()
    const redo = vi.fn()
    const { unmount } = renderHook(() => useUndoKeys(undo, redo))
    const settled = () => new Promise<void>((resolve) => setTimeout(resolve))
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await settled()
    expect(undo).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(window, { key: 'z', metaKey: true, shiftKey: true })
    await settled()
    expect(redo).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(window, { key: 'z', metaKey: true, repeat: true })
    await settled()
    expect(undo).toHaveBeenCalledTimes(1)
    const taken = new KeyboardEvent('keydown', { key: 'z', metaKey: true, cancelable: true })
    taken.preventDefault()
    window.dispatchEvent(taken)
    await settled()
    expect(undo).toHaveBeenCalledTimes(1)
    const input = document.body.appendChild(document.createElement('input'))
    fireEvent.keyDown(input, { key: 'z', metaKey: true })
    await settled()
    expect(undo).toHaveBeenCalledTimes(1)
    unmount()
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await settled()
    expect(undo).toHaveBeenCalledTimes(1)
  })

  it('leaves the key to a handler added after it that takes it, as the Timeline’s ⌘Z does', async () => {
    const undo = vi.fn()
    renderHook(() => useUndoKeys(undo, vi.fn()))
    const later = (e: KeyboardEvent) => e.preventDefault()
    window.addEventListener('keydown', later)
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await new Promise<void>((resolve) => setTimeout(resolve))
    window.removeEventListener('keydown', later)
    expect(undo).not.toHaveBeenCalled()
  })

  it('names the key for the platform', () => {
    expect(undoShortcutLabel('undo', 'MacIntel')).toBe('⌘Z')
    expect(undoShortcutLabel('redo', 'MacIntel')).toBe('⇧⌘Z')
    expect(undoShortcutLabel('undo', 'Win32')).toBe('Ctrl+Z')
  })
})
