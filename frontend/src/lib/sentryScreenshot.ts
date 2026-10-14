import type { Scope } from '@sentry/react'

type Attachment = Parameters<Scope['addAttachment']>[0]

/**
 * A feedback screenshot that fits the desk's tunnel. The SDK captures it as a PNG at the screen's
 * pixel ratio, which can be over the tunnel's 1 MB cap with the feedback in the same envelope, and a
 * refused envelope loses the feedback too. Over [SCREENSHOT_BUDGET] it is redrawn as a JPEG no
 * wider than [SCREENSHOT_MAX_SIDE]; one that still won't fit is left out.
 */
export const SCREENSHOT_BUDGET = 700_000
export const SCREENSHOT_MAX_SIDE = 1920
const QUALITIES = [0.85, 0.7, 0.5] as const

export async function fitScreenshot(attachment: Attachment): Promise<Attachment | null> {
  const size = typeof attachment.data === 'string' ? attachment.data.length : attachment.data.byteLength
  if (size <= SCREENSHOT_BUDGET) return attachment
  try {
    const bitmap = await createImageBitmap(new Blob([typeof attachment.data === 'string' ? attachment.data : attachment.data.slice()], { type: attachment.contentType ?? 'image/png' }))
    const scale = Math.min(1, SCREENSHOT_MAX_SIDE / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(bitmap.width * scale)
    canvas.height = Math.round(bitmap.height * scale)
    const context = canvas.getContext('2d')
    if (context != null) {
      // JPEG has no alpha: transparent would come out black.
      context.fillStyle = '#fff'
      context.fillRect(0, 0, canvas.width, canvas.height)
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    }
    bitmap.close()
    for (const quality of QUALITIES) {
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
      if (blob != null && blob.size <= SCREENSHOT_BUDGET) {
        return { filename: 'screenshot.jpg', contentType: 'image/jpeg', data: new Uint8Array(await blob.arrayBuffer()) }
      }
    }
  } catch {
    // Undecodable: sent without it.
  }
  return null
}

/** Fits a feedback event's screenshots, in place. */
export async function fitScreenshots(hint: { attachments?: Attachment[] }): Promise<void> {
  if (hint.attachments == null || hint.attachments.length === 0) return
  const fitted = await Promise.all(hint.attachments.map(fitScreenshot))
  hint.attachments = fitted.filter((a): a is Attachment => a != null)
}
