/**
 * Whether an element is a text-entry target — a field the user could be typing
 * into right now.
 *
 * Global keyboard shortcuts must bail when this is true for
 * `document.activeElement`, or they hijack ordinary typing: the stage editor
 * binds plain arrow keys and Delete, and its side panels are full of inputs.
 */
export function isEditableTarget(el: Element | null): boolean {
  if (!el) return false
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return true
  if (el instanceof HTMLSelectElement) return true
  return el instanceof HTMLElement && el.isContentEditable
}
