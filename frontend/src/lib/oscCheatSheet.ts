import type { OscAddress, OscAddressList } from './oscAddresses'

/** The cheat sheet's sections, by what an address's pattern starts with, in print order. */
const SECTIONS: { title: string; prefix: string[] }[] = [
  { title: 'Cue stacks', prefix: ['/stack/'] },
  { title: 'Loose cues', prefix: ['/cue/'] },
  { title: 'Looks', prefix: ['/look/'] },
  { title: 'Busk pads and pages', prefix: ['/busk/'] },
  { title: 'Masters and tempo', prefix: ['/blackout', '/grandmaster', '/tempo/'] },
]

const escape = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

function sectionOf(list: OscAddressList, address: OscAddress): string {
  const tail = address.pattern.slice(list.prefix.length)
  return SECTIONS.find((s) => s.prefix.some((p) => tail.startsWith(p)))?.title ?? 'Other'
}

/**
 * Every address [list] says the show answers to, as one printable page for the sound department
 * (lighting7 OSC plan §4): each address, its arguments and what it reaches, by kind, then the
 * names that have none and why. A whole document, so it opens and prints on its own.
 */
export function oscCheatSheetHtml(list: OscAddressList, showName: string, printedAt: Date): string {
  const groups = new Map<string, OscAddress[]>()
  for (const address of list.addresses) {
    const title = sectionOf(list, address)
    groups.set(title, [...(groups.get(title) ?? []), address])
  }
  const order = [...SECTIONS.map((s) => s.title), 'Other']
  const tables = order
    .filter((title) => groups.has(title))
    .map((title) => {
      const rows = groups
        .get(title)!
        .map(
          (a) =>
            `<tr><td class="addr">${escape(a.address)}</td><td>${escape(a.args === '–' ? '' : a.args)}</td><td>${escape(a.reaches)}</td></tr>`,
        )
        .join('')
      return `<h2>${escape(title)}</h2><table><thead><tr><th>Address</th><th>Args</th><th>Reaches</th></tr></thead><tbody>${rows}</tbody></table>`
    })
    .join('')
  const missing = [
    ...list.ambiguous.map((a) => `<li>${escape(a.message)} — ${a.addresses.map((x) => `<span class="addr">${escape(x)}</span>`).join(', ')} answer to none of them</li>`),
    ...list.unaddressed.map((u) => `<li>${escape(u.name)}: ${escape(u.reason)}</li>`),
  ]
  const missingSection = missing.length > 0 ? `<h2>No address</h2><ul>${missing.join('')}</ul>` : ''
  return `<!doctype html><html><head><meta charset="utf-8"><title>OSC addresses · ${escape(showName)}</title><style>
body{font:12px/1.4 system-ui,sans-serif;margin:24px;color:#111}
h1{font-size:18px;margin:0 0 4px}
p{margin:0 0 12px;color:#555}
h2{font-size:14px;margin:18px 0 6px;break-after:avoid}
table{border-collapse:collapse;width:100%}
th,td{text-align:left;padding:3px 8px 3px 0;border-bottom:1px solid #ddd;vertical-align:top}
tr{break-inside:avoid}
.addr{font-family:ui-monospace,Menlo,monospace;white-space:nowrap}
</style></head><body>
<h1>OSC addresses · ${escape(showName)}</h1>
<p>Send to the desk on UDP. Names are lower case, with a dash for each run of spaces and punctuation. Printed ${escape(printedAt.toLocaleString())}; a rename, a renumber or a moved pad changes an address.</p>
${tables || '<p>This show answers to no addresses yet.</p>'}${missingSection}
</body></html>`
}

/**
 * A window for the cheat sheet, opened in the click itself: a browser blocks one opened after the
 * listing's round trip, once the click's user activation has lapsed. Null when it is blocked anyway.
 */
export function openCheatSheetWindow(): Window | null {
  const win = window.open('', '_blank')
  win?.document.write('<!doctype html><title>OSC addresses</title><p style="font:12px system-ui">Reading the addresses…</p>')
  return win
}

/** Write the cheat sheet into [win] and ask it to print. */
export function printOscCheatSheet(win: Window, list: OscAddressList, showName: string) {
  win.document.open()
  win.document.write(oscCheatSheetHtml(list, showName, new Date()))
  win.document.close()
  win.focus()
  win.print()
}
