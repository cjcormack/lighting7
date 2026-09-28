import { createSlice } from '@reduxjs/toolkit'

/**
 * Whether the operator has asked for the show-editing lock to be *off*.
 *
 * **Runtime view state — never persisted.** A running show always opens locked; that is a safety
 * property, not a preference, and a remembered "unlocked" would defeat it after a reload.
 *
 * It lives in the store rather than in a component because the lock is shared between Show and the
 * Prompt Book: "I am in a fix-it session" is one fact about the operator, and one GO should end it
 * everywhere. Those two are separate routes, so per-page state would silently re-lock every time
 * the operator moved between them — which reads as the lock fighting you rather than protecting
 * you.
 *
 * Only the *request* is stored. Whether the desk is actually locked is derived per surface, because
 * it also depends on whether the show is running and whether the backend will accept a write at all
 * — see `useEditLock`.
 */
const editLockSlice = createSlice({
  name: 'editLock',
  initialState: { lockRequested: true },
  reducers: {
    lockRequested(state) {
      state.lockRequested = true
    },
    unlockRequested(state) {
      state.lockRequested = false
    },
  },
})

export const { lockRequested, unlockRequested } = editLockSlice.actions
export const selectLockRequested = (state: { editLock: { lockRequested: boolean } }) =>
  state.editLock.lockRequested
export { editLockSlice }
