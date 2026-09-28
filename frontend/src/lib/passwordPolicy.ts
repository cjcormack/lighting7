/**
 * The desk's password floor, mirrored from the backend's `auth/Passwords.kt`.
 *
 * Shared rather than re-declared per form: five surfaces now ask for a password (desk
 * setup, change-your-own, create user, an admin setting someone else's, and the phone
 * reset page), and a floor that disagreed with the server in one of them would read as a
 * bug in that form rather than as a policy.
 *
 * The backend also rejects anything over 72 UTF-8 **bytes**, because bcrypt silently
 * truncates there. That one is rare enough to leave to the server's message rather than
 * duplicate the byte counting client-side.
 */
export const MIN_PASSWORD_LENGTH = 8
