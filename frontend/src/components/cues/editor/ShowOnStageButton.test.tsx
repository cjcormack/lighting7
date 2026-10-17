// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { editTarget, resetVisSourceStore, visSource } from '@/hooks/useVisSource'
import { SHOW_ON_STAGE_OFF_RIG, ShowOnStageButton } from './ShowOnStageButton'

describe('Show on stage', () => {
  beforeEach(() => resetVisSourceStore())
  afterEach(() => {
    window.sessionStorage.clear()
    resetVisSourceStore()
  })

  const button = (cueId: number) => screen.getAllByRole('button').find((b) => b.dataset.showOnStage === String(cueId))!

  it('sets this window’s source to the cue in edit, by its entry, and lights while it shows it', () => {
    render(<ShowOnStageButton cueId={17} entryId={170} />)
    expect(button(17)).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(button(17))
    expect(visSource()).toBe('edit')
    expect(editTarget()).toEqual({ kind: 'entry', entryId: 170 })
    expect(button(17)).toHaveAttribute('aria-pressed', 'true')
  })

  it('moves to another editor’s cue on its press, and back to Output on a press while lit', () => {
    render(
      <>
        <ShowOnStageButton cueId={17} entryId={170} />
        <ShowOnStageButton cueId={40} entryId={null} />
      </>,
    )
    fireEvent.click(button(17))
    fireEvent.click(button(40))
    expect(editTarget()).toEqual({ kind: 'cue', cueId: 40 })
    expect(button(17)).toHaveAttribute('aria-pressed', 'false')
    expect(button(40)).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(button(40))
    expect(visSource()).toBe('output')
    expect(button(40)).toHaveAttribute('aria-pressed', 'false')
  })

  it('is withheld, with the reason, for another project’s cue', () => {
    render(<ShowOnStageButton cueId={17} entryId={170} withheldReason={SHOW_ON_STAGE_OFF_RIG} />)
    expect(button(17)).toBeDisabled()
    expect(button(17)).toHaveAttribute('title', SHOW_ON_STAGE_OFF_RIG)
  })
})
