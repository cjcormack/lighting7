import { describe, expect, it } from 'vitest'
import {
  IMMERSIVE_OPTION,
  PAGE_FOLLOWS_OPTION,
  STAGE_SOURCE_OPTION,
  STAGE_VIEWPOINT_OPTION,
  WINDOW_VIEWS,
  projectIdOfPath,
  windowViewLabel,
  windowViewOf,
  windowViewPath,
} from './windowViews'

/**
 * The seven views one window can put on another: the four live views in `ViewSwitcher` order, the
 * Stage view, then the two libraries. Matching is segment-aware, so the legacy `/program` and the
 * old `/fx` never answer for a view.
 */
describe('WINDOW_VIEWS', () => {
  it('is the four live views in ViewSwitcher order, then Stage, then Looks and Templates', () => {
    expect(WINDOW_VIEWS.map((v) => v.id)).toEqual(['programmer', 'show', 'prompt-book', 'busk', 'stage', 'looks', 'templates'])
  })

  it('gives Stage its viewpoint and its source and nothing else — no immersive, it is not a live view (stage-view plan sessions 1–3)', () => {
    const stage = WINDOW_VIEWS.find((v) => v.id === 'stage')!
    expect(stage.segment).toBe('/stage')
    expect(stage.options).toEqual([STAGE_VIEWPOINT_OPTION, STAGE_SOURCE_OPTION])
    // Source is the board's two (`Screens.dc.html` §1): what a hall screen is set to.
    expect(STAGE_SOURCE_OPTION).toMatchObject({ key: 'source', kind: 'enum', values: ['output', 'nextGo'] })
    // A picker since session 2: the cameras, saved views and seats are the Stage view's control,
    // handed to the Screens sheet by the shell — the descriptor names no vocabulary of its own.
    expect(STAGE_VIEWPOINT_OPTION).toEqual({ key: 'viewpoint', label: 'Viewpoint', kind: 'picker' })
    expect(windowViewOf('/projects/3/stage')?.id).toBe('stage')
  })

  it('mints a project route per view', () => {
    expect(windowViewPath(WINDOW_VIEWS[3]!, 7)).toBe('/projects/7/busk')
  })

  it('gives every live view the one Chrome option, App · Immersive over off | on, and the libraries none (busk-chrome D9)', () => {
    for (const view of WINDOW_VIEWS.slice(0, 4)) {
      const chrome = view.options?.find((o) => o.key === 'immersive')
      expect(chrome, view.id).toBe(IMMERSIVE_OPTION)
    }
    expect(IMMERSIVE_OPTION).toMatchObject({ label: 'Chrome', kind: 'enum', values: ['off', 'on'], valueLabels: { off: 'App', on: 'Immersive' } })
    // Busk keeps its own and takes Chrome last, so the segment sits at the row's end everywhere.
    expect(WINDOW_VIEWS[3]!.options!.map((o) => o.key)).toEqual(['focus', 'sheet', 'pageFollows', 'page', 'immersive'])
    expect(WINDOW_VIEWS.find((v) => v.id === 'looks')!.options).toBeUndefined()
    expect(WINDOW_VIEWS.find((v) => v.id === 'templates')!.options).toBeUndefined()
  })
})

describe('windowViewOf', () => {
  it('reads a view off its route, and off a route below it', () => {
    expect(windowViewOf('/projects/1/busk')?.id).toBe('busk')
    expect(windowViewOf('/projects/1/show/stacks/4')?.id).toBe('show')
    expect(windowViewOf('/projects/1/show/stacks/4/table')?.id).toBe('show')
  })

  it('never lets a prefix answer — /program is not the programmer, /fx-library is not busk', () => {
    expect(windowViewOf('/projects/1/program')).toBeNull()
    expect(windowViewOf('/projects/1/fx-library')).toBeNull()
    expect(windowViewOf('/projects/1/fixtures')).toBeNull()
    expect(windowViewOf('/install')).toBeNull()
  })

  it('labels a view by name and any other route by its path', () => {
    expect(windowViewLabel('/projects/1/prompt-book')).toBe('Prompt Book')
    expect(windowViewLabel('/projects/1/fixtures')).toBe('/projects/1/fixtures')
  })
})

describe('projectIdOfPath', () => {
  it('reads the project from a project route and nothing from an install one', () => {
    expect(projectIdOfPath('/projects/12/busk')).toBe(12)
    expect(projectIdOfPath('/projects/12')).toBe(12)
    expect(projectIdOfPath('/install/users')).toBeNull()
    expect(projectIdOfPath('/projects/abc/busk')).toBeNull()
  })
})

describe('PAGE_FOLLOWS_OPTION (desk-follow plan D6, D9)', () => {
  it('is the announce’s own true | false, said as the job and shortened for a narrow row, just before the picker', () => {
    expect(PAGE_FOLLOWS_OPTION).toMatchObject({
      key: 'pageFollows',
      label: 'Page',
      name: 'Paging',
      kind: 'enum',
      values: ['true', 'false'],
      valueLabels: { true: 'Paged with the desk', false: 'Own page' },
      shortValueLabels: { true: 'With desk', false: 'Own' },
    })
    const busk = WINDOW_VIEWS.find((v) => v.id === 'busk')!.options!
    expect(busk.indexOf(PAGE_FOLLOWS_OPTION) + 1).toBe(busk.findIndex((o) => o.kind === 'page'))
  })
})
