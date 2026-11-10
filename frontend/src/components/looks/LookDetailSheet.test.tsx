// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { LookDetails, LookSummary } from '@/api/looksApi'

/**
 * The Look sheet's references (palette-references plan D22): a reference row drawn as its teal chip
 * with what it resolves to, the sentence, and *Copy the values…* over the picked chips, else all.
 */

const harden = vi.hoisted(() => vi.fn())
const DSR = { paletteId: 2, paletteUuid: 'dsr', name: 'DSR Focus' }
const DETAIL = vi.hoisted(() => ({ current: null as LookDetails | null }))

vi.mock('@/store/looks', () => ({
  useLookQuery: () => ({ currentData: DETAIL.current, isFetching: false }),
  useSaveLookMutation: () => [vi.fn(), { isLoading: false, error: undefined }],
  useHardenLookMutation: () => [
    (args: unknown) => {
      harden(args)
      return { unwrap: () => Promise.resolve({ hardened: 1 }) }
    },
  ],
}))
const CURRENT = vi.hoisted(() => ({ id: 1 }))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: CURRENT }) }))
vi.mock('@/store/fixtureFx', () => ({ useFxLibraryQuery: () => ({ data: [] }) }))
vi.mock('@/store/scenery', () => ({ useSetLookSceneryMutation: () => [vi.fn()] }))
vi.mock('@/components/programmer/useInclude', () => ({ useInclude: () => ({ include: vi.fn(), isLoading: false }) }))
vi.mock('@/components/busking/AddToBuskPageMenu', () => ({ AddToBuskPageMenu: () => null }))
vi.mock('@/components/scenery/SceneryEditor', () => ({ SceneryEditor: () => null }))
vi.mock('./useLookDelete', () => ({ useLookDelete: () => ({ run: vi.fn(), busy: false, dialog: null }) }))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

import { LookDetailSheet } from './LookDetailSheet'
import { assignTargetNow, closeAssignToSurface } from '@/components/surfaces/AssignToSurface'

const SUMMARY: LookSummary = {
  id: 5,
  uuid: 'l5',
  name: 'Act 1 Spots',
  notes: null,
  families: ['POSITION'],
  rowCount: 3,
  fxCount: 0,
  targetCount: 3,
  preview: ['-60,+30'],
  referenceCount: 2,
  layerCount: 0,
  buskPageCount: 0,
}

function detail(): LookDetails {
  return {
    id: 5,
    uuid: 'l5',
    name: 'Act 1 Spots',
    notes: null,
    families: ['POSITION'],
    layerCount: 0,
    usedByCueIds: [],
    usedByCueNames: [],
    fx: [],
    rows: [
      { targetType: 'fixture', targetKey: 'spot-1', attribute: 'position', value: '68,158', uuid: 'r1' },
      {
        targetType: 'fixture',
        targetKey: 'spot-2',
        attribute: 'position',
        value: 'pal:dsr',
        uuid: 'r2',
        reference: DSR,
        resolvedByHead: { 'spot-2': '114,150' },
      },
      {
        targetType: 'fixture',
        targetKey: 'spot-3',
        attribute: 'position',
        value: 'pal:dsr',
        uuid: 'r3',
        reference: { ...DSR, unresolved: 'DSR Focus has no row for spot-3' },
        resolvedByHead: { 'spot-3': '142,150' },
      },
    ],
  }
}

function draw() {
  DETAIL.current = detail()
  render(<LookDetailSheet open onOpenChange={() => {}} projectId={1} look={SUMMARY} onDuplicate={() => {}} />)
}

/** Opens the question, answers what it asked, and confirms it. */
async function copy(): Promise<string> {
  fireEvent.click(screen.getByRole('button', { name: 'Copy the values…' }))
  const dialog = screen.getByRole('alertdialog')
  const asked = dialog.textContent ?? ''
  await act(async () => {
    fireEvent.click(within(dialog).getByRole('button', { name: 'Copy the values' }))
  })
  return asked
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('LookDetailSheet — Assign to surface…', () => {
  it('opens the sheet on the Look', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Assign to surface…' }))
    expect(assignTargetNow()?.subjects).toEqual([{ type: 'record', kind: 'LOOK', uuid: 'l5' }])
    closeAssignToSurface()
  })
})

describe('LookDetailSheet — references', () => {
  it('draws a reference as a teal chip naming the palette and what it resolves to, a literal as itself', () => {
    draw()
    const chips = document.querySelectorAll('[data-reference-chip]')
    expect(chips).toHaveLength(2)
    expect(chips[0]).toHaveTextContent('DSR Focus-14,+22')
    // Unresolved (D3): dashed, and its hover says why.
    expect(chips[1].className).toContain('border-dashed')
    expect(chips[1].getAttribute('title')).toContain('DSR Focus has no row for spot-3')
    expect(screen.getByText('-60,+30')).toBeInTheDocument()
  })

  it('says how many values follow the palette', () => {
    draw()
    expect(document.querySelector('[data-copy-the-values]')).toHaveTextContent('2 values follow DSR Focus.')
  })

  it('copies every reference when none is picked', async () => {
    draw()
    expect(await copy()).toContain('Copy all 2 values?')
    expect(harden).toHaveBeenCalledWith({ projectId: 1, lookId: 5 })
  })

  it('copies only the picked references', async () => {
    draw()
    const chips = document.querySelectorAll<HTMLButtonElement>('[data-reference-chip]')
    fireEvent.click(chips[1])
    expect(chips[1]).toHaveAttribute('aria-pressed', 'true')
    expect(await copy()).toContain('Copy 1 value?')
    expect(harden).toHaveBeenCalledWith({ projectId: 1, lookId: 5, rowUuids: ['r3'] })
    // The copy lands and the picks go with it.
    expect(chips[1]).toHaveAttribute('aria-pressed', 'false')
  })

  it('refuses to copy on a project the desk is not running, and says why', () => {
    CURRENT.id = 2
    try {
      draw()
      const button = screen.getByRole('button', { name: 'Copy the values…' })
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('title', 'Copy the values works on the current project only')
    } finally {
      CURRENT.id = 1
    }
  })

  it('offers nothing for a Look with no references', () => {
    DETAIL.current = { ...detail(), rows: [detail().rows[0]] }
    render(<LookDetailSheet open onOpenChange={() => {}} projectId={1} look={SUMMARY} onDuplicate={() => {}} />)
    expect(document.querySelector('[data-copy-the-values]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Copy the values…' })).toBeNull()
  })
})
