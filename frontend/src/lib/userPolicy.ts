/**
 * The display-name length cap, mirrored from the backend.
 *
 * Two things it tracks, and they have to move together: `MAX_DISPLAY_NAME_LENGTH` in
 * `routes/users.kt` (which both `PUT /users/{id}` and `PUT /auth/profile` validate against)
 * and the `varchar("display_name", 100)` column it exists to protect. SQLite stores an
 * over-long value happily, so the Kotlin check is the only enforcement — and this constant
 * is what stops a form offering to type something the server will refuse.
 *
 * Unlike [MIN_PASSWORD_LENGTH](./passwordPolicy.ts), **only the Profile form gates on this
 * today**. `SetupScreen`, `CreateUserSheet` and `UserDetailSheet` still let you type past it
 * and rely on the 400. That's a gap rather than a decision; it just isn't this file's job to
 * hide it.
 */
export const MAX_DISPLAY_NAME_LENGTH = 100
