// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { DeskWindow } from '@/api/windowsApi'

const registry: { windows: DeskWindow[] } = { windows: [] }
const sent: unknown[] = []
vi.mock('@/store/windows', async () => {
  const real = await import('@/store/windows')
  return {
    thisWindowRow: real.thisWindowRow,
    useDeskWindows: () => registry.windows,
    showOnWindow: (targetId: string, view: string) => sent.push({ type: 'show', targetId, view }),
    renameWindowRow: (targetId: string, name: string) => sent.push({ type: 'rename', targetId, name }),
    setWindowFullscreen: (targetId: string, on: boolean) => sent.push({ type: 'fullscreen', targetId, on }),
    setWindowViewOptions: (targetId: string, view: string, options: Record<string, string>) =>
      sent.push({ type: 'viewOptions', targetId, view, options }),
    setWindowFollow: (targetId: string, on: boolean) => sent.push({ type: 'follow', targetId, on }),
  }
})
// The busk pages a Page picker resolves against, and the desk's showing page.
const busk = { pages: [{ id: 1, name: 'Colour' }, { id: 3, name: 'Position' }], deskPageId: 1 as number | null }
vi.mock('@/store/busk', () => ({
  useBuskPagesQuery: (_id: number, opts?: { skip?: boolean }) => ({ data: opts?.skip ? undefined : busk.pages }),
  useBuskShowingPageQuery: () => ({ data: busk.deskPageId }),
  setShowingBuskPage: (pageId: number) => {
    sent.push({ type: 'setPage', pageId })
    return true
  },
}))
vi.mock('@/lib/windowIdentity', () => ({ windowId: () => 'w-1', launchImmersive: () => null }))
vi.mock('@/ProjectSwitcher', () => ({ useViewedProject: () => ({ id: 1, name: 'Show', isCurrent: true }) }))

const fullscreen = { active: false, enter: vi.fn(), exit: vi.fn() }
vi.mock('@/lib/fullscreen', () => ({
  useFullscreenState: () => ({ active: fullscreen.active, wanted: false }),
  canFullscreen: () => true,
  enterFullscreen: () => fullscreen.enter(),
  exitFullscreen: () => fullscreen.exit(),
}))

// Radix's Select needs pointer-capture polyfills jsdom lacks; a native select over the same
// items carries the same contract (a value, a change) and is what both pickers are tested
// through. The items are read off the `SelectContent`'s `SelectItem` children at render, so the
// view picker and the page picker share one mock. Built inside the factory: `vi.mock` is hoisted
// above every import, so nothing declared in this file is in scope when it runs.
vi.mock('@/components/ui/select', async () => {
  const React = await import('react')
  type Item = { value: string; label: ReactNode }
  type Ctx = { value: string; onValueChange: (v: string) => void; disabled: boolean; items: Item[] }
  const Context = React.createContext<Ctx>({ value: '', onValueChange: () => {}, disabled: false, items: [] })
  const SelectContent = ({ children }: { children?: ReactNode }) => {
    void children
    return null
  }
  const SelectItem = ({ children }: { value: string; children?: ReactNode }) => {
    void children
    return null
  }
  const Select = ({ value, onValueChange, disabled, children }: Omit<Ctx, 'items'> & { children: ReactNode }) => {
    const items: Item[] = []
    React.Children.forEach(children, (child) => {
      if (!React.isValidElement(child) || child.type !== SelectContent) return
      React.Children.forEach((child.props as { children?: ReactNode }).children, (item) => {
        if (React.isValidElement(item) && item.type === SelectItem) {
          const props = item.props as { value: string; children?: ReactNode }
          items.push({ value: props.value, label: props.children })
        }
      })
    })
    return React.createElement(Context.Provider, { value: { value, onValueChange, disabled: disabled ?? false, items } }, children)
  }
  const SelectTrigger = (props: { 'aria-label'?: string; title?: string }) => {
    const ctx = React.useContext(Context)
    return React.createElement(
      'select',
      {
        'aria-label': props['aria-label'],
        title: props.title,
        value: ctx.value,
        disabled: ctx.disabled,
        onChange: (e: { target: { value: string } }) => ctx.onValueChange(e.target.value),
      },
      React.createElement('option', { value: '' }, ''),
      ...ctx.items.map((item) => React.createElement('option', { key: item.value, value: item.value }, item.label)),
    )
  }
  return {
    Select,
    SelectTrigger,
    SelectValue: () => null,
    SelectContent,
    SelectItem,
  }
})

import { ScreensSheet } from './ScreensSheet'
import { setScreensSheetOpen } from './screensSheetState'

/**
 * The Screens sheet (multi-screen plan §4, `Screens.dc.html` §2): a row per window with *this
 * window* on the right one, every write a `windows.*` command by row id, and the two ways to make
 * a new window — Open on Display N only behind `getScreenDetails`, Copy link with `%20`.
 */
const row = (id: string, windowId: string, name: string, extra: Partial<DeskWindow> = {}): DeskWindow => ({
  id,
  windowId,
  name,
  view: '/projects/1/programmer',
  fullscreen: false,
  follows: true,
  user: null,
  viewOptions: null,
  ...extra,
})

beforeEach(() => {
  registry.windows = [
    row('s-1', 'w-1', 'Screen 1'),
    row('s-2', 'w-2', 'Screen 2', {
      view: '/projects/1/busk',
      fullscreen: true,
      viewOptions: { focus: 'pads', sheet: 'none', pageFollows: 'false', page: '3' },
    }),
    row('s-3', 'w-3', 'Chris’s iPad', { follows: false, user: 'Chris' }),
  ]
  act(() => setScreensSheetOpen(true))
})

afterEach(() => {
  act(() => setScreensSheetOpen(false))
  sent.length = 0
  fullscreen.active = false
  vi.clearAllMocks()
  delete (window as { getScreenDetails?: unknown }).getScreenDetails
})

const rowFor = (name: string) => screen.getByRole('listitem', { name })

describe('ScreensSheet', () => {
  it('lists every window with this one marked, and each one’s framing', () => {
    render(<ScreensSheet />)
    expect(screen.getByRole('dialog', { name: 'Screens' })).toBeInTheDocument()
    const rows = within(screen.getByRole('list', { name: 'Windows' })).getAllByRole('listitem')
    expect(rows).toHaveLength(3)

    expect(rowFor('Screen 1')).toHaveTextContent('this window')
    expect(rowFor('Screen 1')).toHaveTextContent('in a browser tab')
    expect(rowFor('Screen 2')).not.toHaveTextContent('this window')
    expect(rowFor('Screen 2')).toHaveTextContent('full screen')
    // The selection caption went with the Selection segment (desk-follow D4): the segment says it.
    expect(rowFor('Chris’s iPad')).not.toHaveTextContent('own selection')
    expect(rowFor('Screen 1')).not.toHaveTextContent('follows the desk')
    // The user is not drawn while every name is unique.
    expect(rowFor('Chris’s iPad')).not.toHaveTextContent('· Chris')
  })

  it('badges one twin only when two rows share this tab’s windowId — the first, as the chip reads it', () => {
    registry.windows = [row('s-1', 'w-1', 'Screen 1'), row('s-2', 'w-1', 'Screen 1')]
    render(<ScreensSheet />)
    const rows = within(screen.getByRole('list', { name: 'Windows' })).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('this window')
    expect(rows[1]).not.toHaveTextContent('this window')
  })

  it('draws the server-stamped user only where two rows share a name', () => {
    registry.windows = [row('s-1', 'w-1', 'Desk', { user: 'Chris' }), row('s-2', 'w-2', 'Desk', { user: 'Sam' })]
    render(<ScreensSheet />)
    const rows = within(screen.getByRole('list', { name: 'Windows' })).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('· Chris')
    expect(rows[1]).toHaveTextContent('· Sam')
  })

  it('shows a view on another window by row id, and on this one the same way', () => {
    render(<ScreensSheet />)
    fireEvent.change(screen.getByRole('combobox', { name: 'View on Screen 2' }), { target: { value: 'busk' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'View on Screen 1' }), { target: { value: 'looks' } })
    expect(sent).toEqual([
      { type: 'show', targetId: 's-2', view: '/projects/1/busk' },
      { type: 'show', targetId: 's-1', view: '/projects/1/looks' },
    ])
  })

  it('renames a window by row id on ⏎, reverts a blank, and sends nothing for a no-op', () => {
    render(<ScreensSheet />)
    const field = within(rowFor('Screen 2')).getByRole('textbox', { name: 'Name of Screen 2' })
    field.focus()
    fireEvent.change(field, { target: { value: 'Desk right' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    // Exactly one frame: Enter blurs and the blur commits, rather than both committing.
    expect(sent).toEqual([{ type: 'rename', targetId: 's-2', name: 'Desk right' }])

    sent.length = 0
    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.blur(field)
    expect(sent).toEqual([])
    expect((field as HTMLInputElement).value).toBe('Screen 2')

    fireEvent.change(field, { target: { value: 'Screen 2' } })
    fireEvent.blur(field)
    expect(sent).toEqual([])
  })

  it('sends a fullscreen command to another window, and enters or exits directly for this one', () => {
    render(<ScreensSheet />)
    fireEvent.click(within(rowFor('Screen 2')).getByRole('button', { name: 'Exit full screen' }))
    fireEvent.click(within(rowFor('Chris’s iPad')).getByRole('button', { name: 'Full screen' }))
    expect(sent).toEqual([
      { type: 'fullscreen', targetId: 's-2', on: false },
      { type: 'fullscreen', targetId: 's-3', on: true },
    ])
    fireEvent.click(within(rowFor('Screen 1')).getByRole('button', { name: 'Full screen' }))
    expect(fullscreen.enter).toHaveBeenCalledTimes(1)
  })

  it('reads this window’s own full-screen state from the document, ahead of the registry', () => {
    fullscreen.active = true
    render(<ScreensSheet />)
    expect(rowFor('Screen 1')).toHaveTextContent('full screen')
    expect(rowFor('Screen 1')).not.toHaveTextContent('in a browser tab')
    fireEvent.click(within(rowFor('Screen 1')).getByRole('button', { name: 'Exit full screen' }))
    expect(fullscreen.exit).toHaveBeenCalledTimes(1)
  })

  it('offers Open a window on… only behind getScreenDetails, and nothing at all without it', () => {
    render(<ScreensSheet />)
    expect(screen.queryByText('Open a window on…')).toBeNull()
    expect(screen.queryByRole('button', { name: /Choose a display/ })).toBeNull()
  })

  it('lists the displays after the prompt and opens a named window on the chosen one', async () => {
    const current = { availLeft: 0, availTop: 0, availWidth: 1440, availHeight: 900, width: 1440, height: 900 }
    const other = { availLeft: 1440, availTop: 0, availWidth: 1920, availHeight: 1080, width: 1920, height: 1080 }
    ;(window as { getScreenDetails?: unknown }).getScreenDetails = async () => ({ screens: [current, other], currentScreen: current })
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    render(<ScreensSheet />)

    expect(screen.getByText('Open a window on…')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Choose a display/ }))
    const display2 = await screen.findByRole('button', { name: /Display 2 · 1920×1080/ })
    fireEvent.click(display2)
    // `noopener` is load-bearing and not decoration: without it the child is an *auxiliary*
    // browsing context and clones this tab's `sessionStorage`, which is the inherited-`windowId`
    // bug session 2.5 fixed on the other side. Kept pinned because that half is spec-asserted and
    // still unobserved — the preview pane creates no child context on any route, so neither this
    // open nor a plain one could be shown to clone there.
    expect(open).toHaveBeenCalledWith(
      `${window.location.origin}/?window=Screen%203`,
      '_blank',
      'left=1440,top=0,width=1920,height=1080,noopener',
    )
    // The name is spent on the open: the field clears to the placeholder so a second open
    // takes the registry's next free name rather than naming a twin.
    expect(screen.getByRole('textbox', { name: 'Name for a new window' })).toHaveValue('')
  })

  it('copies a link for another device with the next free Screen N, the space as %20', async () => {
    const writeText = vi.fn(async () => {})
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    render(<ScreensSheet />)
    // Screen 1 and Screen 2 are taken, so the default is Screen 3; the field can change it.
    expect(screen.getByRole('textbox', { name: 'Name for a new window' })).toHaveValue('Screen 3')
    fireEvent.click(screen.getByRole('button', { name: 'Copy link for another device' }))
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/?window=Screen%203`)
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()

    fireEvent.change(screen.getByRole('textbox', { name: 'Name for a new window' }), { target: { value: 'Front of house' } })
    fireEvent.click(screen.getByRole('button', { name: 'Copy link for another device' }))
    expect(writeText).toHaveBeenLastCalledWith(`${window.location.origin}/?window=Front%20of%20house`)
  })

  describe('a row’s view options (busk-further plan D13)', () => {
    it('draws Focus, Sheet and Page on a busk row from what it announced, and none of the three on a Prompt Book row', () => {
      registry.windows[2] = row('s-3', 'w-3', 'Chris’s iPad', { view: '/projects/1/prompt-book' })
      render(<ScreensSheet />)
      const busk2 = rowFor('Screen 2')
      expect(within(busk2).getByRole('radiogroup', { name: 'Focus on Screen 2' })).toBeInTheDocument()
      expect(within(busk2).getByRole('radio', { name: 'pads' })).toHaveAttribute('aria-checked', 'true')
      // Sheet is one enum with `none`, offering only the tabs that have landed.
      const sheet = within(busk2).getByRole('radiogroup', { name: 'Sheet on Screen 2' })
      expect(within(sheet).getAllByRole('radio').map((r) => r.getAttribute('aria-label'))).toEqual(['none', 'speed', 'colour', 'spread', 'show'])
      expect(within(sheet).getByRole('radio', { name: 'none' })).toHaveAttribute('aria-checked', 'true')
      // The page it holds, named through the project's page list, and whose it is.
      expect(within(busk2).getByRole('combobox', { name: 'Page on Screen 2' })).toHaveValue('3')
      // …and the Page segment says whose it is (desk-follow D6); the caption it replaced is gone.
      const paging = within(busk2).getByRole('radiogroup', { name: 'Paging on Screen 2' })
      expect(within(paging).getByRole('radio', { name: 'Own page' })).toHaveAttribute('aria-checked', 'true')
      expect(busk2).not.toHaveTextContent('own page')

      // The busk facts are the busk view's: a Prompt Book row and a programmer row draw neither
      // segment nor the picker (their one segment is Chrome — the next block).
      expect(within(rowFor('Chris’s iPad')).queryByRole('radiogroup', { name: /Focus|Sheet/ })).toBeNull()
      expect(within(rowFor('Chris’s iPad')).queryByRole('combobox', { name: /Page on/ })).toBeNull()
      expect(within(rowFor('Screen 1')).queryByRole('radiogroup', { name: /Focus|Sheet/ })).toBeNull()
    })

    it('draws nothing at all on a library row', () => {
      registry.windows[2] = row('s-3', 'w-3', 'Chris’s iPad', { view: '/projects/1/looks' })
      render(<ScreensSheet />)
      expect(within(rowFor('Chris’s iPad')).queryByRole('radiogroup')).toBeNull()
      expect(within(rowFor('Chris’s iPad')).queryByRole('button', { name: /Copy link for/ })).toBeNull()
    })

    it('shows a paged-with row on the desk’s page, and says it pages with the desk (desk-follow D6)', () => {
      registry.windows[1] = row('s-2', 'w-2', 'Screen 2', { view: '/projects/1/busk', viewOptions: { focus: 'split', sheet: 'speed', pageFollows: 'true' } })
      render(<ScreensSheet />)
      expect(within(rowFor('Screen 2')).getByRole('combobox', { name: 'Page on Screen 2' })).toHaveValue('1')
      const paging = within(rowFor('Screen 2')).getByRole('radiogroup', { name: 'Paging on Screen 2' })
      expect(within(paging).getByRole('radio', { name: 'Paged with the desk' })).toHaveAttribute('aria-checked', 'true')
      expect(rowFor('Screen 2')).not.toHaveTextContent('follows the desk')
      // A row that has not announced the key yet reads as paged with, as its own tab does.
      cleanup()
      registry.windows[1] = row('s-2', 'w-2', 'Screen 2', { view: '/projects/1/busk', viewOptions: { focus: 'split', sheet: 'speed' } })
      render(<ScreensSheet />)
      const unsaid = within(rowFor('Screen 2')).getByRole('radiogroup', { name: 'Paging on Screen 2' })
      expect(within(unsaid).getByRole('radio', { name: 'Paged with the desk' })).toHaveAttribute('aria-checked', 'true')
    })

    it('draws the Page segment with the picker, the words both long and short, the picker’s own label for a screen reader alone (D9)', () => {
      render(<ScreensSheet />)
      const group = rowFor('Screen 2').querySelector('[data-page-group]') as HTMLElement
      const paging = within(group).getByRole('radiogroup', { name: 'Paging on Screen 2' })
      expect(within(group).getByRole('combobox', { name: 'Page on Screen 2' })).toBeInTheDocument()
      const own = within(paging).getByRole('radio', { name: 'Own page' })
      const [long, short] = [...own.querySelectorAll('span')]
      expect(long).toHaveTextContent('Own page')
      expect(short).toHaveTextContent('Own')
      expect(long.className).toMatch(/^hidden @\[\d+px\]:inline$/)
      expect(short.className).toMatch(/^@\[\d+px\]:hidden$/)
      expect(long.className.match(/\d+/)![0]).toBe(short.className.match(/\d+/)![0])
      expect(within(paging).getByRole('radio', { name: 'Paged with the desk' })).toHaveTextContent('Paged with the deskWith desk')
      // One visible *Page* for the pair: the picker's is `sr-only`.
      expect([...group.querySelectorAll('span')].filter((el) => el.textContent === 'Page').map((el) => el.className.includes('sr-only'))).toEqual([false, true])
      // Drawn once, not again as a segment of its own after Sheet.
      expect(within(rowFor('Screen 2')).getAllByRole('radiogroup', { name: 'Paging on Screen 2' })).toHaveLength(1)
    })

    it('sets the Page segment both ways by a viewOptions frame (D6)', () => {
      render(<ScreensSheet />)
      const paging = within(rowFor('Screen 2')).getByRole('radiogroup', { name: 'Paging on Screen 2' })
      fireEvent.click(within(paging).getByRole('radio', { name: 'Paged with the desk' }))
      expect(sent).toEqual([{ type: 'viewOptions', targetId: 's-2', view: '/projects/1/busk', options: { pageFollows: 'true' } }])
      cleanup()
      sent.length = 0
      registry.windows[1] = row('s-2', 'w-2', 'Screen 2', { view: '/projects/1/busk', viewOptions: { focus: 'split', sheet: 'speed', pageFollows: 'true' } })
      render(<ScreensSheet />)
      const again = within(rowFor('Screen 2')).getByRole('radiogroup', { name: 'Paging on Screen 2' })
      fireEvent.click(within(again).getByRole('radio', { name: 'Own page' }))
      expect(sent).toEqual([{ type: 'viewOptions', targetId: 's-2', view: '/projects/1/busk', options: { pageFollows: 'false' } }])
    })

    it('pages the group from a paged-with row’s picker, and only that window from an own-page row’s (D6)', () => {
      registry.windows[1] = row('s-2', 'w-2', 'Screen 2', { view: '/projects/1/busk', viewOptions: { focus: 'split', sheet: 'speed', pageFollows: 'true' } })
      render(<ScreensSheet />)
      fireEvent.change(within(rowFor('Screen 2')).getByRole('combobox', { name: 'Page on Screen 2' }), { target: { value: '3' } })
      // The desk's showing page — every window paged with it moves — and no frame to the window.
      expect(sent).toEqual([{ type: 'setPage', pageId: 3 }])
      cleanup()
      sent.length = 0
      registry.windows[1] = row('s-2', 'w-2', 'Screen 2', { view: '/projects/1/busk', viewOptions: { focus: 'split', sheet: 'speed', pageFollows: 'false', page: '3' } })
      render(<ScreensSheet />)
      fireEvent.change(within(rowFor('Screen 2')).getByRole('combobox', { name: 'Page on Screen 2' }), { target: { value: '1' } })
      expect(sent).toEqual([{ type: 'viewOptions', targetId: 's-2', view: '/projects/1/busk', options: { page: '1' } }])
    })

    it('sets focus, sheet and page by one keyed command carrying the row’s view — a page set unlinks the target', () => {
      render(<ScreensSheet />)
      const busk2 = rowFor('Screen 2')
      fireEvent.click(within(busk2).getByRole('radio', { name: 'rig' }))
      fireEvent.click(within(busk2).getByRole('radio', { name: 'speed' }))
      fireEvent.change(within(busk2).getByRole('combobox', { name: 'Page on Screen 2' }), { target: { value: '1' } })
      expect(sent).toEqual([
        { type: 'viewOptions', targetId: 's-2', view: '/projects/1/busk', options: { focus: 'rig' } },
        { type: 'viewOptions', targetId: 's-2', view: '/projects/1/busk', options: { sheet: 'speed' } },
        { type: 'viewOptions', targetId: 's-2', view: '/projects/1/busk', options: { page: '1' } },
      ])
    })

    it('mints no page into a following row’s link, even with the desk on a page — the desk’s page is the desk’s to say', async () => {
      const writeText = vi.fn(async () => {})
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
      busk.deskPageId = 1
      registry.windows[1] = row('s-2', 'w-2', 'Screen 2', { view: '/projects/1/busk', viewOptions: { focus: 'split', sheet: 'speed', pageFollows: 'true' } })
      render(<ScreensSheet />)
      // The row shows the desk's page…
      expect(within(rowFor('Screen 2')).getByRole('combobox', { name: 'Page on Screen 2' })).toHaveValue('1')
      // …and the link names none of it, or the new window would arrive unlinked onto a page this one follows.
      fireEvent.click(within(rowFor('Screen 2')).getByRole('button', { name: 'Copy link for Screen 2' }))
      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/projects/1/busk?window=Screen%202&focus=split&sheet=speed`)
    })

    it('copies a link for the row that carries its whole setup', async () => {
      const writeText = vi.fn(async () => {})
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
      render(<ScreensSheet />)
      fireEvent.click(within(rowFor('Screen 2')).getByRole('button', { name: 'Copy link for Screen 2' }))
      expect(writeText).toHaveBeenCalledWith(
        `${window.location.origin}/projects/1/busk?window=Screen%202&page=3&focus=pads&sheet=none`,
      )
      expect(await within(rowFor('Screen 2')).findByRole('button', { name: 'Copied' })).toBeInTheDocument()
    })
  })

  describe('the Selection segment (desk-follow plan D4)', () => {
    const segment = (name: string) => within(rowFor(name)).getByRole('radiogroup', { name: `Selection on ${name}` })

    it('draws Desk · This window on every Busk and Programmer row from the row’s flag, and on no other', () => {
      registry.windows.push(row('s-4', 'w-4', 'Stage', { view: '/projects/1/show', viewOptions: { immersive: 'off' } }))
      render(<ScreensSheet />)
      expect(within(segment('Screen 1')).getByRole('radio', { name: 'Desk' })).toHaveAttribute('aria-checked', 'true')
      expect(within(segment('Chris’s iPad')).getByRole('radio', { name: 'This window' })).toHaveAttribute('aria-checked', 'true')
      expect(segment('Screen 2')).toBeInTheDocument()
      // Show has no selection to follow, so it has no segment.
      expect(within(rowFor('Stage')).queryByRole('radiogroup', { name: 'Selection on Stage' })).toBeNull()
    })

    it('writes windows.follow by row id — this window’s own included', () => {
      render(<ScreensSheet />)
      fireEvent.click(within(segment('Screen 1')).getByRole('radio', { name: 'This window' }))
      fireEvent.click(within(segment('Chris’s iPad')).getByRole('radio', { name: 'Desk' }))
      expect(sent).toEqual([
        { type: 'follow', targetId: 's-1', on: false },
        { type: 'follow', targetId: 's-3', on: true },
      ])
    })

    it('is disabled with its reason on a busk row in Rig or Pads focus — never hidden — and free in Split', () => {
      registry.windows.push(row('s-4', 'w-4', 'Screen 3', { view: '/projects/1/busk', viewOptions: { focus: 'rig' } }))
      registry.windows.push(row('s-5', 'w-5', 'Screen 4', { view: '/projects/1/busk', viewOptions: { focus: 'split' } }))
      render(<ScreensSheet />)
      for (const [name, reason] of [['Screen 2', 'Pads focus follows'], ['Screen 3', 'Rig focus follows']] as const) {
        const radios = within(segment(name)).getAllByRole('radio')
        expect(radios.every((r) => r.hasAttribute('disabled')), name).toBe(true)
        expect(rowFor(name)).toHaveTextContent(reason)
      }
      expect(within(segment('Screen 4')).getAllByRole('radio').some((r) => r.hasAttribute('disabled'))).toBe(false)
      expect(rowFor('Screen 4')).not.toHaveTextContent('focus follows')
      fireEvent.click(within(segment('Screen 2')).getByRole('radio', { name: 'This window' }))
      expect(sent).toEqual([])
    })
  })

  describe('the Chrome segment (busk-chrome plan D9)', () => {
    it('draws App · Immersive on a Programmer row and a Show row from the descriptor, reading what the window announced', () => {
      registry.windows[0] = row('s-1', 'w-1', 'Screen 1', { viewOptions: { immersive: 'off' } })
      registry.windows[2] = row('s-3', 'w-3', 'Chris’s iPad', { view: '/projects/1/show', viewOptions: { immersive: 'on' } })
      render(<ScreensSheet />)
      const programmer = within(rowFor('Screen 1')).getByRole('radiogroup', { name: 'Chrome on Screen 1' })
      expect(within(programmer).getAllByRole('radio').map((r) => r.getAttribute('aria-label'))).toEqual(['App', 'Immersive'])
      expect(within(programmer).getByRole('radio', { name: 'App' })).toHaveAttribute('aria-checked', 'true')
      const show = within(rowFor('Chris’s iPad')).getByRole('radiogroup', { name: 'Chrome on Chris’s iPad' })
      expect(within(show).getByRole('radio', { name: 'Immersive' })).toHaveAttribute('aria-checked', 'true')
      // And on the busk row too, last, after Focus · Sheet · Page.
      const busk2 = rowFor('Screen 2')
      expect(within(busk2).getAllByRole('radiogroup').map((g) => g.getAttribute('aria-label'))).toEqual(['Focus on Screen 2', 'Sheet on Screen 2', 'Paging on Screen 2', 'Chrome on Screen 2', 'Selection on Screen 2'])
    })

    it('sets it by the one keyed command carrying the row’s view, on the wire’s spelling', () => {
      registry.windows[0] = row('s-1', 'w-1', 'Screen 1', { viewOptions: { immersive: 'off' } })
      render(<ScreensSheet />)
      fireEvent.click(within(rowFor('Screen 1')).getByRole('radio', { name: 'Immersive' }))
      fireEvent.click(within(rowFor('Screen 2')).getByRole('radio', { name: 'App' }))
      expect(sent).toEqual([
        { type: 'viewOptions', targetId: 's-1', view: '/projects/1/programmer', options: { immersive: 'on' } },
        { type: 'viewOptions', targetId: 's-2', view: '/projects/1/busk', options: { immersive: 'off' } },
      ])
    })

    it('puts immersive= on Copy link only while it is on', async () => {
      const writeText = vi.fn(async () => {})
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
      registry.windows[0] = row('s-1', 'w-1', 'Screen 1', { viewOptions: { immersive: 'off' } })
      registry.windows[2] = row('s-3', 'w-3', 'Chris’s iPad', { view: '/projects/1/show', viewOptions: { immersive: 'on' } })
      render(<ScreensSheet />)
      fireEvent.click(within(rowFor('Screen 1')).getByRole('button', { name: 'Copy link for Screen 1' }))
      expect(writeText).toHaveBeenLastCalledWith(`${window.location.origin}/projects/1/programmer?window=Screen%201`)
      fireEvent.click(within(rowFor('Chris’s iPad')).getByRole('button', { name: 'Copy link for Chris’s iPad' }))
      expect(writeText).toHaveBeenLastCalledWith(`${window.location.origin}/projects/1/show?window=Chris%E2%80%99s%20iPad&immersive=on`)
    })
  })

  it('says under the button that a localhost link names the desk to itself', () => {
    render(<ScreensSheet />)
    // jsdom's origin is http://localhost:3000.
    expect(screen.getByText(/names the desk to itself/)).toBeInTheDocument()
  })
})
