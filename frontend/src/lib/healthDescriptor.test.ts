import { describe, expect, it } from 'vitest'
import { describeHealth } from './healthDescriptor'

/**
 * The operator-facing line per dead-binding variant. Two pins: the transient `missingWindow` arm
 * the busk-further plan's window-addressed targets added (D14), and the generic fallback that keeps
 * a variant this build has never heard of *visible* rather than healthy.
 */
describe('describeHealth', () => {
  it('reads null for ok and for nothing', () => {
    expect(describeHealth(undefined)).toBeNull()
    expect(describeHealth({ type: 'ok' })).toBeNull()
  })

  it('names the window a busk focus or sheet button addresses when no screen of that name is signed in', () => {
    expect(describeHealth({ type: 'missingWindow', windowName: 'Screen 2' })).toBe('“Screen 2” is not signed in on any screen')
  })

  it('names a dead stack or cue without a number, since a binding holds only its uuid', () => {
    expect(describeHealth({ type: 'missingStack', stackId: 0, stackUuid: 'a' })).toBe('The cue stack this binding names no longer exists')
    expect(describeHealth({ type: 'missingCue', cueId: 0, cueUuid: 'b' })).toBe('The cue this binding names no longer exists')
  })

  it('keeps a variant this build does not know visible as a problem', () => {
    expect(describeHealth({ type: 'missingSomethingNew' } as never)).toBe('This reference no longer resolves')
  })
})
