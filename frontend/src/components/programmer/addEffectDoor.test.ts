import { describe, expect, it, vi } from 'vitest'
import { openAddEffectDoor, registerAddEffectDoor } from './addEffectDoor'

/** ⌘K on the programmer opens the rail's *+ Effect* while a rail is mounted, and nothing otherwise. */
describe('the add-effect door', () => {
  it('opens the mounted rail’s door, and reports none once it is gone', () => {
    expect(openAddEffectDoor()).toBe(false)
    const open = vi.fn()
    const unregister = registerAddEffectDoor(open)
    expect(openAddEffectDoor()).toBe(true)
    expect(open).toHaveBeenCalledTimes(1)
    unregister()
    expect(openAddEffectDoor()).toBe(false)
  })
})
