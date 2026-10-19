import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from '../../../lib/syncStore'

/**
 * **Bodies · Plain | By colour** (tables-colours plan D12, `Colours.dc.html` §C): whether this
 * window's housings take their fixture's colour. Off by default, because a tinted rig is a
 * patching aid and the room as lit is what the hall screen shows. The labels carry the swatch
 * either way, and a selected housing is the desk's blue in both.
 *
 * **Per window and announced**, as the work lights are: `sessionStorage`, riding `viewOptions` as
 * `bodies` beside the source, so a Screens row can set it. Every canvas in the window follows it:
 * the Stage view and the Positions plan. A `render_view` capture draws plain bodies.
 *
 * Free of three.js for `workLights.ts`'s reason: `lib/stageViewpoint.ts` imports it on the app
 * shell's path.
 */
export const BODY_COLOURS = ['plain', 'colour'] as const
export type BodyColours = (typeof BODY_COLOURS)[number]

export const DEFAULT_BODY_COLOURS: BodyColours = 'plain'

export const BODY_COLOURS_KEY = 'stage.bodies'

/** The key it rides under in a Stage window's `viewOptions`. */
export const VIEW_OPTION_BODIES = 'bodies'

export const BODY_COLOURS_LABELS: Record<BodyColours, string> = { plain: 'Plain', colour: 'By colour' }

export function isBodyColours(value: unknown): value is BodyColours {
  return typeof value === 'string' && (BODY_COLOURS as readonly string[]).includes(value)
}

const store = createSyncStore<BodyColours>({
  key: BODY_COLOURS_KEY,
  fallback: DEFAULT_BODY_COLOURS,
  parse: (parsed) => (isBodyColours(parsed) ? parsed : DEFAULT_BODY_COLOURS),
  storage: sessionStorageArea,
})

export function useBodyColours(): BodyColours {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** This window's bodies, read outside React. */
export function bodyColours(): BodyColours {
  return store.getSnapshot()
}

/** Switch this window's bodies. A value outside the vocabulary is ignored. */
export function setBodyColours(next: BodyColours): void {
  if (isBodyColours(next)) store.set(next)
}

/** Test seam: the store back to plain, with no listeners. */
export function resetBodyColoursStore(): void {
  store.reset()
}
