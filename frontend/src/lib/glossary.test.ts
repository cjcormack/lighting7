import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import glossary from '../../../docs/glossary.md?raw'
import {
  BLEND_MODE_OPTIONS,
  EFFECT_CATEGORY_INFO,
  ELEMENT_MODE_OPTIONS,
} from '@/components/fx/fxConstants'
import { ORDER_LABELS } from '@/lib/orderLabels'

/**
 * Holds the frontend's label tables to `docs/glossary.md` (terminology plan D17): every `done`
 * row's frontend locators must give the row's word. The `fx:`, `category:` and `usage:` locators
 * are `GlossaryVocabularyTest`'s; the glossary's header says what each prefix reads.
 */

interface Row {
  section: string
  idea: string
  word: string
  status: string
  keys: string[]
}

const BACKEND_PREFIXES = new Set(['fx', 'category', 'usage'])
const FRONTEND_PREFIXES = new Set(['src', 'ORDER_LABELS', 'EFFECT_CATEGORY_INFO', 'BLEND_MODE_OPTIONS', 'ELEMENT_MODE_OPTIONS'])

const SOURCES = import.meta.glob<string>(['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}'], {
  query: '?raw',
  import: 'default',
})

function splitRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split(/(?<!\\)\|/)
    .map((cell) => cell.trim().replace(/\\\|/g, '|'))
}

const GLOSSARY_COLUMNS = ['Idea', 'Word', 'Status', 'Key']

/**
 * Every row of every table under a `##` section. Such a table must have Idea, Word, Status and Key
 * columns, so a mistyped header fails rather than dropping its rows unchecked.
 */
function parseGlossary(text: string): Row[] {
  const rows: Row[] = []
  let section = ''
  let columns: string[] | null = null
  for (const line of text.split('\n')) {
    if (line.startsWith('## ')) section = line.slice(3).trim()
    if (!line.startsWith('|')) {
      columns = null
      continue
    }
    const cells = splitRow(line)
    if (columns == null) {
      const glossaryTable = GLOSSARY_COLUMNS.every((c) => cells.includes(c))
      if (!glossaryTable && section !== '') {
        throw new Error(`the table under "${section}" has columns ${cells.join(', ')}; a glossary table needs ${GLOSSARY_COLUMNS.join(', ')}`)
      }
      columns = glossaryTable ? cells : []
      continue
    }
    if (columns.length === 0 || cells.every((c) => /^-+$/.test(c))) continue
    const header = columns
    const cell = (name: string) => cells[header.indexOf(name)] ?? ''
    const keys = cell('Key')
      .split(/[\s,]+/)
      .map((k) => k.replace(/^`|`$/g, ''))
      .filter((k) => k !== '')
    rows.push({ section, idea: cell('Idea'), word: cell('Word'), status: cell('Status'), keys })
  }
  return rows
}

function prefixOf(key: string): string {
  return key.split(/[:.]/)[0]
}

function describeRow(row: Row): string {
  return `glossary row "${row.idea}" (${row.word}) under ${row.section}`
}

function optionLabel(options: readonly { value: string; label: string }[], value: string): string | undefined {
  return options.find((o) => o.value === value)?.label
}

/** Every string literal and JSX text in [source], parsed rather than matched, so a comment never counts. */
function sourceLabels(path: string, source: string): Set<string> {
  const labels = new Set<string>()
  const visit = (node: ts.Node) => {
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) labels.add(node.text)
    else if (ts.isJsxText(node)) labels.add(node.text.trim())
    ts.forEachChild(node, visit)
  }
  visit(ts.createSourceFile(path, source, ts.ScriptTarget.Latest, false, ts.ScriptKind.TSX))
  return labels
}

/** Whether [source] carries [word], or its plural in *s*, as a whole string literal or JSX text. */
function sourceCarries(path: string, source: string, word: string): boolean {
  const labels = sourceLabels(path, source)
  return labels.has(word) || labels.has(`${word}s`)
}

/** What [key] says the row's word should be checked against, or why it cannot be: null when it holds. */
async function problemAt(key: string, word: string): Promise<string | null> {
  if (key.startsWith('src:')) {
    const load = SOURCES[`/src/${key.slice(4)}`]
    if (load == null) return `${key} names no file, expected one carrying '${word}'`
    return sourceCarries(key, await load(), word) ? null : `${key} has no string or JSX text '${word}'`
  }
  const label = labelAt(key)
  if (label === undefined) return `${key} names nothing, expected '${word}'`
  return label === word ? null : `${key} is '${label}', expected '${word}'`
}

function labelAt(key: string): string | undefined {
  const [table, entry] = [key.slice(0, key.indexOf('.')), key.slice(key.indexOf('.') + 1)]
  switch (table) {
    case 'ORDER_LABELS':
      return ORDER_LABELS[entry]
    case 'EFFECT_CATEGORY_INFO':
      return EFFECT_CATEGORY_INFO[entry]?.label
    case 'BLEND_MODE_OPTIONS':
      return optionLabel(BLEND_MODE_OPTIONS, entry)
    case 'ELEMENT_MODE_OPTIONS':
      return optionLabel(ELEMENT_MODE_OPTIONS, entry)
  }
  return undefined
}

const rows = parseGlossary(glossary)

describe('docs/glossary.md', () => {
  it('parses into rows with a known status, and a key exactly when done', () => {
    expect(rows.length, 'too few glossary rows; has the table layout changed?').toBeGreaterThan(50)
    for (const row of rows) {
      expect(['done', 'planned'], `${describeRow(row)}: status '${row.status}'`).toContain(row.status)
      if (row.status === 'done') expect(row.keys.length, `${describeRow(row)} is done but names no key`).toBeGreaterThan(0)
      else expect(row.keys, `${describeRow(row)} is planned but has keys`).toEqual([])
      for (const key of row.keys) {
        const prefix = prefixOf(key)
        expect(
          BACKEND_PREFIXES.has(prefix) || FRONTEND_PREFIXES.has(prefix),
          `${describeRow(row)}: key '${key}' has a prefix neither glossary test reads`,
        ).toBe(true)
      }
    }
  })

  it("gives every done row's word at each of its frontend keys", async () => {
    const problems: string[] = []
    for (const row of rows.filter((r) => r.status === 'done')) {
      for (const key of row.keys.filter((k) => FRONTEND_PREFIXES.has(prefixOf(k)))) {
        const problem = await problemAt(key, row.word)
        if (problem != null) problems.push(`${describeRow(row)}: ${problem}`)
      }
    }
    expect(problems).toEqual([])
  })
})
