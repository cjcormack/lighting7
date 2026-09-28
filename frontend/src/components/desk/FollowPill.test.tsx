// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { FollowPill } from './FollowPill'

/**
 * The pill's parts fold by the host's classes (busk-chrome plan D19) — the *from* suffix first,
 * then the subject, then the value truncates — and the accessible name is the whole reading at
 * every width, so a test or a screen reader gets the same name whatever the classes hide.
 */
afterEach(cleanup)

describe('FollowPill', () => {
  it('carries the whole reading as its name, with each part in its own span under the host’s class', () => {
    render(
      <FollowPill
        following
        subject="Targets"
        subjectClass="hidden @[700px]:inline"
        from="Screen 2"
        fromClass="hidden @[960px]:inline"
        label="Desk"
        title="t"
        onClick={() => {}}
      />,
    )
    const pill = screen.getByRole('button', { name: 'Targets: Desk · from Screen 2' })
    expect(pill).toHaveAttribute('aria-pressed', 'true')
    expect(pill.querySelector('[data-pill-subject]')!.className).toContain('hidden @[700px]:inline')
    expect(pill.querySelector('[data-pill-from]')!.className).toContain('hidden @[960px]:inline')
    expect(pill.querySelector('[data-pill-from]')).toHaveTextContent('· from Screen 2')
    // The value is what truncates last: `min-w-0 truncate` on its own span.
    expect(pill.querySelector('[data-pill-label]')!.className).toContain('truncate')
  })

  it('reads the bare value with no subject and no suffix, dashed when not following', () => {
    const onClick = vi.fn()
    render(<FollowPill following={false} label="This window" title="t" onClick={onClick} />)
    const pill = screen.getByRole('button', { name: 'This window' })
    expect(pill).toHaveAttribute('aria-pressed', 'false')
    expect(pill.className).toContain('border-dashed')
    expect(pill.querySelector('[data-pill-subject]')).toBeNull()
    expect(pill.querySelector('[data-pill-from]')).toBeNull()
    fireEvent.click(pill)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('names the subject and value without a suffix', () => {
    render(<FollowPill following={false} subject="Page" label="Own" title="t" onClick={() => {}} />)
    expect(screen.getByRole('button', { name: 'Page: Own' })).toBeInTheDocument()
  })
})
