import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import { useSelector } from 'react-redux'

/**
 * Whether the busk view is in **edit layout** mode, and which page is being edited.
 *
 * **Runtime view state — never persisted.** A reload lands in play mode, for `editLockSlice`'s own
 * reason one entity along: a busk page that opens with its pads inert, mid-show, is a bug rather
 * than a remembered preference.
 *
 * It lives in the store rather than in a React context because the audience is wider than the busk
 * subtree. The FX cue-slot overlay is rendered by `Layout.tsx` as a **sibling** of the routed page,
 * so a context provided inside `BuskingView` could never reach it — and session 3 of
 * `busk-layout-plan.md` needs exactly that: the overlay's tiles grow their crosses and become drop
 * targets *while the busk view is editing*. The alternative, lifting the busk provider into
 * `Layout.tsx`, would mount busk state on every route in the app.
 *
 * The drag *draft* deliberately stays out of here. It moves at hover rate, which is the standing
 * reason such state is kept out of the store (see `store/index.ts`'s `ignoredPaths` comment); this
 * slice holds a mode, which changes a few times a session.
 *
 * `BuskingView` must exit on unmount. Without it the overlay would keep drawing crosses on the Show
 * page after the operator navigated away — the same rule `CueSlotDndProvider` follows when its
 * panel hides.
 */
interface BuskEditState {
  editing: boolean
  /** The page being edited, so a surface outside the busk view knows which one a drop belongs to. */
  pageId: number | null
}

const buskEditSlice = createSlice({
  name: 'buskEdit',
  initialState: { editing: false, pageId: null } as BuskEditState,
  reducers: {
    enterBuskEdit(state, action: PayloadAction<number>) {
      state.editing = true
      state.pageId = action.payload
    },
    exitBuskEdit(state) {
      state.editing = false
      state.pageId = null
    },
  },
})

export const { enterBuskEdit, exitBuskEdit } = buskEditSlice.actions

export const selectBuskEdit = (state: { buskEdit: BuskEditState }) => state.buskEdit

export { buskEditSlice }

/**
 * Is the busk view editing its layout?
 *
 * Selects the **boolean**, not the state object, so a page change does not re-render every consumer
 * — the FX cue-slot overlay reads this once per tile, and it has eight of them.
 */
export function useBuskEditMode(): boolean {
  return useSelector((state: { buskEdit: BuskEditState }) => state.buskEdit.editing)
}
