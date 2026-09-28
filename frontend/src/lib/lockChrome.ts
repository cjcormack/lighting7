/**
 * The wash applied to every bar of chrome while a **running** show is unlocked.
 *
 * One constant rather than the literal in six files, for the reason `AUTO_CUE_NUMBER_CLASS` is one:
 * these have to match exactly or the band reads as stripes. That is not hypothetical — the wash
 * started on the header alone, which left the Prompt Book showing amber, then two rows of standard
 * chrome, then amber again.
 *
 * A whole string literal, so Tailwind's scanner still sees both classes; it reads complete class
 * strings and would find nothing in a template literal.
 *
 * The signal is for the *unlocked* state, not the locked one. Locked is the quiet default, and
 * believing you are locked when you are not is how a show gets edited by accident. A stopped show is
 * simply editable, so there is no lock to be wrong about and no wash.
 */
export const UNLOCKED_WARNING_CLASS = 'border-amber-500/50 bg-amber-400/15'
