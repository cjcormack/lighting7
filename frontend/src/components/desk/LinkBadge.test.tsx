// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { LinkBadge } from './LinkBadge'

/**
 * The link badge (desk-follow plan D7, D8): the glyph alone, or the glyph and the first linked
 * window's name with the rest counted — the names under the host's fold class, the accessible name
 * the host's whole sentence at every width.
 */
describe('LinkBadge', () => {
  it('is the glyph alone with no names, its title the accessible name', () => {
    render(<LinkBadge label="Following the desk selection" />)
    const badge = screen.getByRole('img', { name: 'Following the desk selection' })
    expect(badge.querySelector('[data-link-badge-names]')).toBeNull()
    expect(badge.querySelector('svg')).not.toBeNull()
  })

  it('draws the first name and counts the rest, under the host’s fold class', () => {
    render(
      <LinkBadge
        label="Paged with the desk, and with Screen 2 and iPad"
        names={['Screen 2', 'iPad']}
        namesClass="hidden @[700px]:inline"
      />,
    )
    const badge = screen.getByRole('img', { name: 'Paged with the desk, and with Screen 2 and iPad' })
    const names = badge.querySelector('[data-link-badge-names]') as HTMLElement
    expect(names).toHaveTextContent('Screen 2 +1')
    expect(names.className).toBe('hidden @[700px]:inline')
  })

  it('treats an empty list as no names', () => {
    render(<LinkBadge label="Paged with the desk" names={[]} />)
    expect(screen.getByRole('img').querySelector('[data-link-badge-names]')).toBeNull()
  })

  it('is a mark with no press, and a toggle button with one — the same box either way (desk-follow D11)', () => {
    const { rerender } = render(<LinkBadge label="Following the desk selection" title="Rig focus always follows" />)
    const mark = screen.getByRole('img', { name: 'Following the desk selection' })
    expect(mark).toHaveAttribute('title', 'Rig focus always follows')
    expect(screen.queryByRole('button')).toBeNull()
    const onUnlink = vi.fn()
    rerender(<LinkBadge label="Following the desk selection" title="click to leave" onUnlink={onUnlink} />)
    const button = screen.getByRole('button', { name: 'Following the desk selection' })
    expect(button).toHaveAttribute('aria-pressed', 'true')
    expect(button).toHaveAttribute('title', 'click to leave')
    // The mark's box to the pixel, so no ladder moves for the press.
    for (const cls of ['h-5', 'min-w-5', 'shrink-0', 'px-1', 'border']) expect(button.className).toContain(cls)
    fireEvent.click(button)
    expect(onUnlink).toHaveBeenCalledTimes(1)
  })
})
