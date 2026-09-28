import { configureStore } from '@reduxjs/toolkit'
import {restApi} from "./restApi";
import { setupListeners } from "@reduxjs/toolkit/query"
import { runnerSlice } from './runnerSlice'
import { errorToastMiddleware } from './errorToastMiddleware'
import { saveStatusSlice } from './saveStatusSlice'
import { selectionSlice } from './selectionSlice'
import { editLockSlice } from './editLockSlice'
import { buskEditSlice } from './buskEditSlice'

export const store = configureStore({
  reducer: {
    [restApi.reducerPath]: restApi.reducer,
    runner: runnerSlice.reducer,
    // Matches on RTK Query's mutation lifecycle actions — see saveStatusSlice.
    saveStatus: saveStatusSlice.reducer,
    // Fixtures-list row selection, keyed by list. In the store so surfaces outside the list
    // (RecordSheet's "selected fixtures only") can read it — see selectionSlice.
    selection: selectionSlice.reducer,
    // The show-editing lock, shared between Show and the Prompt Book so one GO ends a fix-it
    // session everywhere. Deliberately not persisted — see editLockSlice.
    editLock: editLockSlice.reducer,
    // Busk "edit layout" mode. In the store because the FX cue-slot overlay reads it from
    // outside the busk subtree — see buskEditSlice.
    buskEdit: buskEditSlice.reducer,
  },

  middleware: (getDefaultMiddleware) => {
    // The dev-only invariant middleware deep-traverses every non-ignored path on EVERY dispatch,
    // twice (before/after) — whichever slice the action touched. Dispatches arrive continuously
    // during a show (WS run-state frames, RTK Query lifecycle actions, selection updates at
    // drag-select rate), so the hand-written slices are excluded along with RTK Query's
    // internals — otherwise dev profiling is dominated by the invariant scans rather than the
    // app. The trade-off is real: an in-place mutation of, say, `completedCueIds` outside a
    // reducer now goes undetected in dev — small, plain-Immer slices, judged worth it.
    const ignoredPaths = [
      // RTK Query internal paths - subscription functions / large cache state
      'restApi.queries',
      'restApi.mutations',
      'restApi.subscriptions',
      'runner',
      'selection',
      'saveStatus',
      'editLock',
      'buskEdit',
    ]
    return getDefaultMiddleware({
      serializableCheck: { ignoredPaths },
      immutableCheck: { ignoredPaths },
      // Runs after restApi.middleware so it sees the rejected endpoint actions RTK Query
      // dispatches. Any mutation failure not claimed by its call site becomes a toast.
    }).concat(restApi.middleware, errorToastMiddleware)
  }
})

setupListeners(store.dispatch)
