// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * The template library's structural claims: the **family chips** are the page's only partition — a
 * sticky view, deep-linked by `?family=` — and under *All* the sheet is **grouped by family
 * dividers** (library-sheets plan D3), name-ordered within each and in the plan's family order.
 *
 * The chips moved here from `/looks` in session 3 along with the argument for them: on `/templates` a
 * family really is an exact partition of the library. The sheet's own columns and verbs are
 * `TemplateSheet.test.tsx`'s.
 */
let templates: TemplateSummary[] = []

vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: templates, isLoading: false }),
  useCreateTemplateMutation: () => [vi.fn(), { isLoading: false }],
  useSaveTemplateMutation: () => [vi.fn(), { isLoading: false }],
  useDeleteTemplateMutation: () => [vi.fn()],
  useCopyTemplateMutation: () => [vi.fn()],
}))
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterListQuery: () => ({ data: [] }) }))
vi.mock('@/store/hand', () => ({ handPickUp: () => true }))
vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1, name: 'Hamlet' }, isLoading: false }),
  useProjectQuery: () => ({ data: { id: 1, name: 'Hamlet', isCurrent: true }, isLoading: false }),
  useProjectListQuery: () => ({ data: [{ id: 1, name: 'Hamlet', isCurrent: true }] }),
}))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))
vi.mock('@/components/templates/TemplateEditor', () => ({
  TemplateEditor: ({ open }: { open: boolean }) => (open ? <div data-testid="editor" /> : null),
}))
vi.mock('@/components/Breadcrumbs', () => ({ Breadcrumbs: () => null }))

const { ProjectTemplates } = await import('./Templates')

function template(over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Amber Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    rows: [
      { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=extract' },
    ],
    kind: 'value',
    requiredEmitters: [],
    effect: null,
    layerCount: 0,
    lastPressedAt: null,
    buskPageCount: 0,
    ...over,
  }
}

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/projects/:projectId/templates" element={<ProjectTemplates />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  localStorage.clear()
  templates = [
    template(),
    template({
      id: 2,
      uuid: 'u2',
      name: 'Half Up',
      family: 'INTENSITY',
      rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:50' }],
    }),
    template({
      id: 3,
      uuid: 'u3',
      name: 'Downstage Centre',
      family: 'POSITION',
      isGeneric: false,
      rows: [
        { targetType: 'fixture', targetKey: 'mover-1', propertyName: 'position', value: 'deg:12,-8' },
        { targetType: 'fixture', targetKey: 'mover-2', propertyName: 'position', value: 'deg:-14,-8' },
      ],
    }),
  ]
})
afterEach(cleanup)

/** The sheet's rows, dividers included, in order — a divider by its label, a member by its name. */
function sheetRows(): string[] {
  return [...document.querySelectorAll('[data-row-id]')].map((el) =>
    el.getAttribute('data-row-id')!.startsWith('family:')
      ? `— ${el.textContent}`
      : (el.querySelector('[data-first-column]')?.textContent ?? ''),
  )
}

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})
afterEach(() => vi.unstubAllGlobals())

describe('ProjectTemplates', () => {
  it('groups every template under family dividers under All, in the plan’s order', () => {
    templates = [...templates, template({ id: 4, uuid: 'u4', name: 'Deep Blue' })]
    renderAt('/projects/1/templates')
    // Intensity · Colour · Position · Beam — not `ATTRIBUTE_FAMILIES`' declaration order — and the
    // server's name order within each. No stored order either way.
    expect(sheetRows()).toEqual([
      '— Intensity · 1',
      'Half Up',
      '— Colour · 2',
      'Amber Key',
      'Deep Blue',
      '— Position · 1',
      'Downstage Centre',
    ])
  })

  it('lands filtered from a ?family= deep link, with no dividers', () => {
    // Cmd+K's four per-family entries are query params on this one route, so arriving by link has to
    // filter — `navigation.test.ts` pins the links themselves.
    renderAt('/projects/1/templates?family=colour')
    expect(sheetRows()).toEqual(['Amber Key'])
  })

  it('partitions exactly — every template is in one family and no other', () => {
    renderAt('/projects/1/templates?family=position')
    expect(sheetRows()).toEqual(['Downstage Centre'])
  })

  it('filters on a chip and remembers the choice for next time; All brings the dividers back', () => {
    const { unmount } = renderAt('/projects/1/templates')
    fireEvent.click(screen.getByRole('button', { name: /^Intensity/ }))
    expect(sheetRows()).toEqual(['Half Up'])
    unmount()

    // Sticky, so the sidebar's single row lands where you left it.
    renderAt('/projects/1/templates')
    expect(sheetRows()).toEqual(['Half Up'])
    fireEvent.click(screen.getByRole('button', { name: /^All/ }))
    expect(sheetRows()[0]).toBe('— Intensity · 1')
  })

  it('counts each family on its chip', () => {
    renderAt('/projects/1/templates')
    expect(screen.getByRole('button', { name: /^All/ })).toHaveTextContent('All3')
    expect(screen.getByRole('button', { name: /^Colour/ })).toHaveTextContent('Colour1')
    expect(screen.getByRole('button', { name: /^Beam/ })).toHaveTextContent('Beam0')
  })

  it('says which shape each template is, because applying them differs', () => {
    // A per-fixture template applied to a head it holds no entry for asserts nothing for that head,
    // so the Value column has to say which it is.
    renderAt('/projects/1/templates')
    expect(screen.getByText('2 heads · per fixture')).toBeInTheDocument()
    expect(screen.getByText(/^#FF9D4A · /)).toBeInTheDocument()
  })

  it('offers New template — a template is authored, not captured', () => {
    // The line D9 draws: cues and Looks are recorded and so have no create button; templates,
    // separators and stacks are not captured states and keep theirs.
    renderAt('/projects/1/templates')
    fireEvent.click(screen.getByText('New template'))
    expect(screen.getByTestId('editor')).toBeInTheDocument()
  })

  it('opens the editor from ?action=new, for the command palette', () => {
    renderAt('/projects/1/templates?action=new')
    expect(screen.getByTestId('editor')).toBeInTheDocument()
  })

  it('shows the empty state rather than a blank page', () => {
    templates = []
    renderAt('/projects/1/templates')
    expect(screen.getByText(/No templates yet/)).toBeInTheDocument()
  })

  it('narrows by the library row’s filter, on name or notes', () => {
    templates = [...templates, template({ id: 4, uuid: 'u4', name: 'Wash', notes: 'amber-ish' })]
    renderAt('/projects/1/templates')
    fireEvent.change(screen.getByLabelText('Filter by name or notes'), { target: { value: 'amber' } })
    expect(sheetRows()).toEqual(['— Colour · 2', 'Amber Key', 'Wash'])
  })
})
