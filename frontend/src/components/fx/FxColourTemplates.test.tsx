// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * Which templates an FX colour parameter may name, and what a reference is drawn as.
 *
 * Three exclusions decide the offer, and all three are silent when they misfire: a template that
 * should be offerable and isn't simply doesn't appear, with nothing to say why. They are asserted
 * one at a time here, from a base template that passes all three, so a regression names the clause
 * it broke.
 *
 * `labelFor`'s three-way split is the other half. "Not loaded yet" and "gone" look identical from
 * this side — both are a uuid that matches nothing — and only one of them is worth telling the
 * operator about, so the distinction is `isLoading` and nothing else.
 */

let templates: TemplateSummary[] = []
let isLoading = false
const listQuery = vi.fn()

vi.mock('@/store/templates', () => ({
  useTemplateListQuery: (args: unknown, options?: unknown) => {
    listQuery(args, options)
    return { data: templates, isLoading }
  },
  useCreateTemplateMutation: () => [vi.fn(), { isLoading: false }],
}))

const { useColourTemplates } = await import('./FxColourTemplates')

function template(over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Warm Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    rows: [
      { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#ff9d4a;policy=extract' },
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

const WARM = template()
const REF = 'tmpl:u1'

/** Mounted under a project, which is what `useColourTemplates` reads the route for. */
function render() {
  return renderHook(() => useColourTemplates(), {
    wrapper: ({ children }) => (
      <MemoryRouter initialEntries={['/projects/7/show']}>
        <Routes>
          <Route path="/projects/:projectId/show" element={children} />
        </Routes>
      </MemoryRouter>
    ),
  })
}

afterEach(() => {
  templates = []
  isLoading = false
  listQuery.mockClear()
})

describe('useColourTemplates offerability', () => {
  it('offers a generic single-row colour template', () => {
    templates = [WARM]
    expect(render().result.current.templates.map((t) => t.name)).toEqual(['Warm Key'])
  })

  it('excludes a template that is not the COLOUR family', () => {
    templates = [template({ family: 'INTENSITY' })]
    expect(render().result.current.templates).toEqual([])
  })

  it('excludes a per-fixture template', () => {
    // An effect's colour output is one colour applied to every head it targets, so there is nothing
    // for it to take from a template holding a different colour per head.
    templates = [template({ isGeneric: false })]
    expect(render().result.current.templates).toEqual([])
  })

  it('offers a colour template that also names an emitter', () => {
    // The row count used to exclude this, because everything downstream read `rows[0]`. Both halves
    // now fold the whole colour-family row set into one colour — `templateRowsSwatch` here,
    // `resolveColourGeneric` on the desk — so the template means something exact to a single-colour
    // output rather than "one of these".
    templates = [
      template({
        requiredEmitters: ['uv'],
        rows: [
          { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#ff9d4a' },
          { targetType: 'deferred', targetKey: '', propertyName: 'uv', value: 'dmx:200' },
        ],
      }),
    ]
    const { result } = render()
    expect(result.current.templates.map((t) => t.name)).toEqual(['Warm Key'])
    // The hex, not the UV row that follows it.
    expect(result.current.swatchFor('tmpl:u1')).toBe('#ff9d4a')
  })

  it('excludes an effect template — an effect is not a colour', () => {
    // fx-templates D12. This used to pass by accident, through the `rows.length === 1` clause that
    // an effect template fails by holding no rows; that clause is gone and `kind !== 'effect'` is
    // the explicit check its own docblock said to put in its place. An effect template has nothing
    // for a fixture-agnostic colour output to take, and `resolveTemplateColour` refuses one
    // server-side for the same reason.
    templates = [template({ id: 9, uuid: 'u9', name: 'Amber Breathe', kind: 'effect', rows: [] })]
    expect(render().result.current.templates).toEqual([])
  })

  it('skips the query entirely outside a project', () => {
    renderHook(() => useColourTemplates(), {
      wrapper: ({ children }) => <MemoryRouter>{children}</MemoryRouter>,
    })
    expect(listQuery).toHaveBeenCalledWith(expect.anything(), { skip: true })
  })
})

describe('useColourTemplates lookups', () => {
  it('labels a resolved reference with the template name', () => {
    templates = [WARM]
    expect(render().result.current.labelFor(REF)).toBe('Warm Key')
  })

  it('says loading rather than missing while the library is still arriving', () => {
    isLoading = true
    expect(render().result.current.labelFor(REF)).toBe('Loading…')
  })

  it('says missing once the library has arrived without it', () => {
    templates = [template({ uuid: 'somethingelse' })]
    expect(render().result.current.labelFor(REF)).toBe('Missing template')
  })

  it('resolves a reference to the swatch of its only row, and a literal to nothing', () => {
    templates = [WARM]
    const { result } = render()
    expect(result.current.swatchFor(REF)).toBe('#ff9d4a')
    expect(result.current.swatchFor('#123456')).toBeNull()
    expect(result.current.templateFor('#123456')).toBeNull()
  })

  it('resolves nothing for a reference to a template that exists but is not offerable', () => {
    // The lookups read the *filtered* list, so an excluded template is not reachable by uuid
    // either — a picker cannot draw a reference it would never have offered.
    templates = [template({ isGeneric: false })]
    const { result } = render()
    expect(result.current.templateFor(REF)).toBeNull()
    expect(result.current.swatchFor(REF)).toBeNull()
  })
})
