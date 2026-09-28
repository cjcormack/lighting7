// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { getLocalSelection, resetDeskFollowStores, unlinkFromDesk, isFollowingDesk } from '@/lib/deskFollow'

vi.mock('@/lib/windowIdentity', () => ({
  useWindowName: () => 'Screen 1',
  windowId: () => 'w-1',
}))
// The badge's press takes the desk's selection as this window's own; the desk's fact is a fixture.
vi.mock('@/store/selection', async () => {
  const { unlinkFromDesk } = await import('@/lib/deskFollow')
  return { unlinkFromDeskNow: () => unlinkFromDesk({ targets: [{ type: 'group', key: 'Front wash' }], families: ['COLOUR'] }) }
})

import { DeskChip } from './DeskChip'

/**
 * The desk chip always says which selection the window is on (desk-follow plan D8, revisiting
 * busk-chrome D18), and is the one toggle between the two (D11): following, the link badge — a
 * glyph whose press takes the desk's selection as this window's own, or a mark where the host's
 * focus forces following; unlinked, the dashed *This window*, whose press relinks and whose
 * accessible name is whole whatever the host's subject class hides (D19).
 */
afterEach(() => {
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

describe('DeskChip', () => {
  it('draws the link badge while following the desk — a glyph that never takes the pill’s shrink, and a press unlinks (D11)', () => {
    render(<DeskChip showSubject className="min-w-0 shrink" />)
    const badge = screen.getByRole('button', { name: 'Following the desk selection' })
    expect(badge).toHaveAttribute('aria-pressed', 'true')
    expect(badge).toHaveAttribute('title', 'Following the desk selection — click to give this window a selection of its own')
    expect(badge).toHaveTextContent('')
    expect(badge.className).toContain('shrink-0')
    expect(badge.className).not.toContain('min-w-0')
    fireEvent.click(badge)
    // The desk's selection, kept as this window's own; the pill replaces the badge.
    expect(isFollowingDesk()).toBe(false)
    expect(getLocalSelection()).toEqual({ targets: [{ type: 'group', key: 'Front wash' }], families: ['COLOUR'] })
    expect(screen.getByRole('button', { name: 'Targets: This window' })).toBeInTheDocument()
  })

  it('is a mark, not a press, where the host’s focus forces following (D2), and says why', () => {
    render(<DeskChip showSubject forcedBy="pads" />)
    expect(screen.queryByRole('button')).toBeNull()
    const badge = screen.getByRole('img', { name: 'Following the desk selection' })
    expect(badge).toHaveAttribute('title', 'Following the desk selection — Pads focus always follows')
  })

  it('draws the dashed This window once unlinked, and its press follows the desk again', () => {
    unlinkFromDesk({ targets: [], families: null })
    render(<DeskChip />)
    const chip = screen.getByRole('button', { name: 'This window' })
    expect(chip).toHaveAttribute('aria-pressed', 'false')
    expect(chip.className).toContain('border-dashed')
    fireEvent.click(chip)
    expect(isFollowingDesk()).toBe(true)
    expect(screen.queryByRole('button', { name: /This window/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Following the desk selection' })).toBeInTheDocument()
  })

  it('names its subject on the busk band and keeps the whole name under the host’s fold class', () => {
    unlinkFromDesk({ targets: [], families: null })
    render(<DeskChip showSubject subjectClass="hidden @[700px]:inline" />)
    const chip = screen.getByRole('button', { name: 'Targets: This window' })
    const subject = chip.querySelector('[data-pill-subject]') as HTMLElement
    expect(subject.className).toContain('hidden @[700px]:inline')
    // The name is the `aria-label`, not the visible text, so hiding the subject changes nothing.
    expect(chip).toHaveAttribute('aria-label', 'Targets: This window')
  })
})
