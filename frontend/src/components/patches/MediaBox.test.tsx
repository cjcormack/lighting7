// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import gelsJson from '../../../../src/main/resources/gels.json'
import { indexGels, type Gel } from '@/lib/gels'
import type { SettingPropertyDescriptor } from '@/store/fixtures'
import type { PatchPlacementInput } from '@/api/patchApi'
import type { FittedMedia } from '@/lib/fittedMedia'

// The served gel library (`GET /gels`), read from the resource the desk serves it from.
vi.mock('@/hooks/useGelIndex', async () => {
  const { indexGels } = await import('@/lib/gels')
  const gels = (await import('../../../../src/main/resources/gels.json')).default
  return { useGelIndex: () => indexGels(gels) }
})

import { MediaBox } from './MediaBox'

/**
 * The patch sheet's **Media** box (fixture optics plan session 3): a scroller's string as an ordered
 * list of gel swatches, a module wheel's slots as gobo or gel pickers, each unit — the patch and
 * every placement — edited as its own. Nothing here writes; it hands the form the media it holds.
 */
afterEach(cleanup)

const gels = indexGels(gelsJson as Gel[])

const SCROLLER: SettingPropertyDescriptor = {
  type: 'setting',
  name: 'gelScroller',
  displayName: 'Gel scroller',
  category: 'colour',
  channel: { universe: 1, channelNo: 13 },
  media: 'GEL',
  options: [
    { name: 'OPEN_LEADER', level: 0, displayName: 'Open Leader', colourPreview: '#FFFFFF', loadable: true },
    { name: 'R02_BASTARD_AMBER', level: 18, displayName: 'R02 Bastard Amber', colourPreview: '#fbcc9a', loadable: true },
    { name: 'L201_FULL_CT_BLUE', level: 165, displayName: 'L201 Full Ct Blue', colourPreview: '#9bbede', loadable: true },
  ],
}
const WHEEL: SettingPropertyDescriptor = {
  type: 'setting',
  name: 'fbWheelPos',
  displayName: 'Front wheel position',
  category: 'gobo',
  channel: { universe: 1, channelNo: 16 },
  media: 'GOBO_OR_GEL',
  options: [
    { name: 'OPEN', level: 0, displayName: 'Open', loadable: false },
    { name: 'SLOT_1', level: 14, displayName: 'Slot 1', loadable: true },
  ],
}

function draw(over: { media?: FittedMedia | null; placements?: PatchPlacementInput[] } = {}) {
  const onMediaChange = vi.fn()
  const onPlacementsChange = vi.fn()
  render(
    <MediaBox
      settings={[SCROLLER, WHEEL]}
      gels={gels}
      media={over.media ?? null}
      onMediaChange={onMediaChange}
      placements={over.placements ?? []}
      onPlacementsChange={onPlacementsChange}
    />,
  )
  return { onMediaChange, onPlacementsChange }
}

const slot = (option: string) => document.querySelector(`[data-media-slot="${option}"]`) as HTMLElement

describe('MediaBox', () => {
  it('draws the scroller as an ordered list of frames, numbered as the manual numbers them', () => {
    draw()
    const scroller = screen.getByRole('region', { name: 'Gel scroller' })
    const rows = within(scroller).getAllByRole('listitem')
    expect(rows.map((r) => r.getAttribute('data-media-slot'))).toEqual(['OPEN_LEADER', 'R02_BASTARD_AMBER', 'L201_FULL_CT_BLUE'])
    expect(rows[2]).toHaveTextContent('2L201 Full Ct Blue')
    expect(rows.every((r) => r.getAttribute('data-state') === 'stock')).toBe(true)
    // The wheel's open hole takes nothing, so it is not offered.
    expect(slot('OPEN')).toBeNull()
  })

  it('fits a gel from the library in a frame, and reads open white as an empty frame', async () => {
    const { onMediaChange } = draw()
    fireEvent.click(within(slot('L201_FULL_CT_BLUE')).getByRole('button', { name: /Fit a gel/ }))
    const search = await screen.findByRole('combobox', { name: 'Search gels' })
    fireEvent.change(search, { target: { value: 'R26' } })
    fireEvent.click(screen.getByRole('option', { name: /R26/ }))
    expect(onMediaChange).toHaveBeenLastCalledWith({ slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' } } } })

    fireEvent.click(within(slot('R02_BASTARD_AMBER')).getByRole('button', { name: /Fit a gel/ }))
    fireEvent.click(await screen.findByRole('option', { name: /Open white/ }))
    expect(onMediaChange).toHaveBeenLastCalledWith({ slots: { gelScroller: { R02_BASTARD_AMBER: {} } } })
  })

  it('shows a fitted slot, resets it to the stock, and fits a gobo in a wheel slot', () => {
    const { onMediaChange } = draw({ media: { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' } } } } })
    expect(slot('L201_FULL_CT_BLUE')).toHaveAttribute('data-state', 'fitted')
    expect(slot('L201_FULL_CT_BLUE')).toHaveTextContent('R26 Light Red')
    fireEvent.click(within(slot('L201_FULL_CT_BLUE')).getByRole('button', { name: /Reset/ }))
    expect(onMediaChange).toHaveBeenLastCalledWith(null)

    fireEvent.change(within(slot('SLOT_1')).getByRole('combobox', { name: /Fit a gobo/ }), { target: { value: 'breakup' } })
    expect(onMediaChange).toHaveBeenLastCalledWith({
      slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' } }, fbWheelPos: { SLOT_1: { gobo: 'breakup' } } },
    })
  })

  it("edits a placement as its own unit, starting from the patch's slots", async () => {
    const placements: PatchPlacementInput[] = [
      { uuid: 'sl', label: 'SL', riggingUuid: null, stageX: 4, stageY: -16, stageZ: 2.8, baseYawDeg: null, basePitchDeg: 180 },
    ]
    const { onMediaChange, onPlacementsChange } = draw({
      media: { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' } } } },
      placements,
    })
    fireEvent.click(screen.getByRole('tab', { name: 'Unit 2 · SL' }))
    expect(slot('L201_FULL_CT_BLUE')).toHaveAttribute('data-state', 'unit')
    expect(slot('L201_FULL_CT_BLUE')).toHaveTextContent('R26 Light Red')

    fireEvent.click(within(slot('L201_FULL_CT_BLUE')).getByRole('button', { name: /Fit a gel/ }))
    fireEvent.change(await screen.findByRole('combobox', { name: 'Search gels' }), { target: { value: 'L106' } })
    fireEvent.click(screen.getByRole('option', { name: /L106/ }))
    expect(onMediaChange).not.toHaveBeenCalled()
    expect(onPlacementsChange).toHaveBeenLastCalledWith([
      { ...placements[0], media: { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'L106' } } } } },
    ])
  })
})
