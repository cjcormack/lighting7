/**
 * The programmer's row C width ladder (programmer-toolbar plan D12), by the row's own width — the
 * `@container` `ProgrammerGrid` wraps it in:
 *
 * - **≥ 1100** every word, ⏎ on Set and ⌫ on Release, Locate and Highlight worded;
 * - **860–1099** Locate and Highlight iconic; Set, Fan, FX and Release keep their words (measured:
 *   with FX among them a marquee's row needed 852px);
 * - **800–859** the verbs iconic too, the mask still whole;
 * - **600–799** every control iconic, the mask abbreviated (`MaskRowFace`), and under 660 the heads
 *   chip counts a marquee's cells alone (measured: the arm needed 656px with the heads and FX);
 * - **< 600** the phone arm: Locate and Highlight move into the heads chip's menu, the menus become
 *   bottom sheets (`ToolbarMenu`, from the row's measured width), the chip counts cells alone, and
 *   the verbs close up — no divider, tighter gaps and padding — to fit a 360px phone.
 *
 * A leaf module, so the list container and the bar both import it without a cycle.
 */

/** Set, Fan, FX and Release's words. */
export const ROW_C_VERB_WORD_CLASS = 'hidden @[860px]:inline'

/** The ⏎ and ⌫ key caps on Set and Release. */
export const ROW_C_KBD_CLASS = 'hidden @[1100px]:inline'

/** Locate and Highlight's words. */
export const ROW_C_RIG_WORD_CLASS = 'hidden @[1100px]:inline'

/** Locate and Highlight themselves, which the heads chip's menu carries on a phone. */
export const ROW_C_RIG_CLASS = '@max-[600px]:hidden'

/** Where the row is a phone's, measured (`useContainerBand`'s floors, widest first). */
export const ROW_C_PHONE_FLOORS: readonly number[] = [600, 0]
