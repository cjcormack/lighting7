import { describe, expect, it, vi } from 'vitest'
import { openAddFxDoor, registerAddFxDoor } from './addFxDoor'

/** ⌘K on the programmer opens its *+ Effect* while the programmer is mounted, and nothing otherwise. */
describe('the add-effect door', () => {
  it('opens the mounted programmer’s door, and reports none once it is gone', () => {
    expect(openAddFxDoor()).toBe(false)
    const open = vi.fn()
    const unregister = registerAddFxDoor(open)
    expect(openAddFxDoor()).toBe(true)
    expect(open).toHaveBeenCalledTimes(1)
    unregister()
    expect(openAddFxDoor()).toBe(false)
  })
})
