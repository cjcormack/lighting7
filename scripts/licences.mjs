#!/usr/bin/env node
// The licence gate (docs/licensing.md). Reads the audit in licences/, the frontend's lock file and,
// from Gradle, the desk's and the launcher's runtime modules; fails on a licence outside
// licences/allowed.json and on THIRD-PARTY-NOTICES.md drifting from the data.
//
//   node scripts/licences.mjs --check [--jvm-modules <file>] [--go-binary-modules <file>]
//                                        validate; exit 1 on any problem
//   node scripts/licences.mjs --write    regenerate THIRD-PARTY-NOTICES.md
//
// `--jvm-modules` is a file of `group:name` lines, one per runtime module (Gradle's
// `licenceCheck` writes it). With it the gate also fails on a module licences/jvm.json does not
// list, and on an entry no runtime classpath has any more. Without it (`npm run licences`) the
// JVM entries are checked as written.
//
// `--go-binary-modules` is `go version -m` run over a built lighting7-link, which names the modules
// linked into it (CI's Link job writes it). With it the gate fails on a linked module
// licences/go.json does not list, and on an entry the binary does not link. Without it the Go
// entries are checked as written.

import { existsSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const at = (...p) => join(root, ...p)
const readJson = (p) => JSON.parse(readFileSync(at(p), 'utf8'))

const NOTICES = 'THIRD-PARTY-NOTICES.md'
const GPL_FAMILY = /^(A|L)?GPL-/

/**
 * The modules `go version -m` says a binary links: each `dep` line's module path, its `=>`
 * replacement keeping the path it replaces.
 */
export function goModulesOf(versionOutput) {
  const modules = new Set()
  for (const line of versionOutput.split('\n')) {
    const fields = line.trim().split(/\s+/)
    if (fields[0] === 'dep' && fields[1]) modules.add(fields[1])
  }
  return modules
}

/** Tokenises and evaluates an SPDX licence expression: WITH binds tightest, then AND, then OR. */
export function licenceAllowed(expression, allowed) {
  const tokens = expression.replace(/[()]/g, ' $& ').trim().split(/\s+/)
  let i = 0
  const peek = () => tokens[i]
  const next = () => tokens[i++]
  const fail = (why) => {
    throw new Error(`"${expression}": ${why}`)
  }
  function primary() {
    const t = next()
    if (t === undefined) fail('ends early')
    if (t === '(') {
      const v = or()
      if (next() !== ')') fail('unbalanced parentheses')
      return v
    }
    if (['AND', 'OR', 'WITH', ')'].includes(t)) fail(`unexpected ${t}`)
    // `X WITH exception` is judged as X: an exception adds permissions, and a GPL-family
    // licence stays one whatever it adds.
    if (peek() === 'WITH') {
      next()
      if (next() === undefined) fail('WITH names no exception')
    }
    return !GPL_FAMILY.test(t) && allowed.has(t)
  }
  function and() {
    let v = primary()
    while (peek() === 'AND') {
      next()
      v = primary() && v
    }
    return v
  }
  function or() {
    let v = and()
    while (peek() === 'OR') {
      next()
      v = and() || v
    }
    return v
  }
  const v = or()
  if (i !== tokens.length) fail(`unexpected ${tokens[i]}`)
  return v
}

function load() {
  const allowedFile = readJson('licences/allowed.json')
  const allowed = new Set(Object.keys(allowedFile.allowed))
  const jvm = Object.entries(readJson('licences/jvm.json').modules).map(([module, v]) =>
    typeof v === 'string' ? { module, licence: v } : { module, ...v },
  )
  const goFile = readJson('licences/go.json')
  const go = Object.entries(goFile.modules).map(([module, v]) =>
    typeof v === 'string' ? { module, licence: v } : { module, ...v },
  )
  const npmData = readJson('licences/npm.json')
  const bundled = readJson('licences/bundled.json').bundled
  const lock = readJson('frontend/package-lock.json')
  const npm = new Map()
  for (const [path, pkg] of Object.entries(lock.packages)) {
    // `dev: true` is only reachable through devDependencies; everything else ships or can.
    if (path === '' || pkg.dev || pkg.link) continue
    const name = path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length)
    const override = npmData.overrides[name]
    const licence = override?.licence ?? pkg.license
    const seen = npm.get(name)
    if (seen && seen.licence !== licence) {
      seen.conflict = `${seen.licence} and ${licence}`
    } else if (!seen) {
      npm.set(name, { name, licence, override })
    }
  }
  return { allowedFile, allowed, jvm, go, goToolchain: goFile.toolchain, npmData, bundled, npm }
}

function problems(data, jvmModulesFile, goBinaryModulesFile) {
  const { allowedFile, allowed, jvm, go, goToolchain, npmData, bundled, npm } = data
  const out = []
  const check = (what, licence, pendingRemoval) => {
    if (!licence) return out.push(`${what}: no licence recorded`)
    let ok
    try {
      ok = licenceAllowed(licence, allowed)
    } catch (e) {
      return out.push(`${what}: ${e.message}`)
    }
    if (!ok && !pendingRemoval) out.push(`${what}: ${licence} is not allowed (licences/allowed.json)`)
    if (ok && pendingRemoval) out.push(`${what}: ${licence} is allowed, so it needs no pendingRemoval`)
  }

  for (const id of Object.keys(allowedFile.allowed)) {
    if (GPL_FAMILY.test(id)) out.push(`licences/allowed.json: ${id} is GPL-family, which is never allowed`)
  }

  const pending = []
  for (const e of jvm) {
    check(e.module, e.licence, e.pendingRemoval)
    if (e.pendingRemoval) pending.push(`${e.module} (${e.pendingRemoval})`)
  }
  for (const e of go) check(`go ${e.module}`, e.licence, e.pendingRemoval)
  check(`go toolchain`, goToolchain?.licence)
  for (const p of npm.values()) {
    if (p.conflict) out.push(`npm ${p.name}: two copies with different licences, ${p.conflict}`)
    check(`npm ${p.name}`, p.licence)
  }
  for (const name of Object.keys(npmData.overrides)) {
    if (!npm.has(name)) out.push(`licences/npm.json: ${name} is not a production dependency any more`)
  }
  for (const b of bundled) {
    check(`bundled ${b.name}`, b.licence, b.pendingRemoval)
    if (b.pendingRemoval) pending.push(`${b.name} (${b.pendingRemoval})`)
  }

  if (pending.length && existsSync(at('LICENSE'))) {
    out.push(`LICENSE exists, but these are not under an allowed licence yet: ${pending.join(', ')}`)
  }

  if (jvmModulesFile) {
    const onClasspath = new Set(
      readFileSync(jvmModulesFile, 'utf8')
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean),
    )
    const listed = new Set(jvm.map((e) => e.module))
    for (const m of onClasspath) {
      if (!listed.has(m)) {
        out.push(`${m}: on a runtime classpath but not in licences/jvm.json; add it with the licence its POM names`)
      }
    }
    for (const m of listed) {
      if (!onClasspath.has(m)) out.push(`licences/jvm.json: ${m} is on no runtime classpath any more`)
    }
  }

  if (goBinaryModulesFile) {
    const linked = goModulesOf(readFileSync(goBinaryModulesFile, 'utf8'))
    if (linked.size === 0) out.push(`${goBinaryModulesFile}: names no module; is it \`go version -m\` over lighting7-link?`)
    const listed = new Set(go.map((e) => e.module))
    for (const m of linked) {
      if (!listed.has(m)) out.push(`go ${m}: linked into lighting7-link but not in licences/go.json; add it with the licence its LICENSE file names`)
    }
    for (const m of listed) {
      if (!linked.has(m)) out.push(`licences/go.json: ${m} is not linked into lighting7-link any more`)
    }
  }

  const expected = notices(data)
  const actual = existsSync(at(NOTICES)) ? readFileSync(at(NOTICES), 'utf8').replace(/\r\n/g, '\n') : ''
  if (actual !== expected) out.push(`${NOTICES} is out of date: run \`node scripts/licences.mjs --write\``)
  return out
}

function notices({ allowedFile, jvm, go, goToolchain, npm, bundled }) {
  const cell = (s) => String(s).replace(/\|/g, '\\|')
  const byName = (a, b) => (a < b ? -1 : a > b ? 1 : 0)
  const lines = [
    '# Third-party notices',
    '',
    'Generated from `licences/` by `node scripts/licences.mjs --write`; do not edit by hand.',
    'How the data is kept, and what the gate allows, is in [docs/licensing.md](docs/licensing.md).',
    '',
    'Lighting7 ships the components below, each under its own licence. A licence written as',
    '`A OR B` is offered both ways by its author, and Lighting7 takes it under the first allowed',
    'one. Each identifier is an SPDX licence; its text is at `https://spdx.org/licenses/<id>.html`.',
    '',
    '## Java libraries',
    '',
    'In `lighting7.jar` (the desk) and `launcher.jar`.',
    '',
    '| Module | Licence |',
    '|---|---|',
    ...jvm
      .slice()
      .sort((a, b) => byName(a.module, b.module))
      .map((e) => `| \`${e.module}\` | ${cell(e.licence)}${e.pendingRemoval ? ' (being removed)' : ''} |`),
    '',
    '## Go modules',
    '',
    'In `lighting7-link`, the desk\'s WebRTC sidecar, built from `link/`.',
    '',
    '| Module | Licence |',
    '|---|---|',
    ...go
      .slice()
      .sort((a, b) => byName(a.module, b.module))
      .map((e) => `| \`${e.module}\` | ${cell(e.licence)}${e.pendingRemoval ? ' (being removed)' : ''} |`),
    `| ${cell(goToolchain.name)} | ${cell(goToolchain.licence)} |`,
    '',
    '## Browser libraries',
    '',
    'The frontend\'s production dependencies and everything they pull in, from',
    '`frontend/package-lock.json`. Most are bundled into the desk\'s web UI; a few are build tools.',
    '',
    '| Package | Licence |',
    '|---|---|',
    ...[...npm.values()]
      .sort((a, b) => byName(a.name, b.name))
      .map((p) => `| \`${p.name}\` | ${cell(p.licence)} |`),
    '',
    '## Native code and vendored files',
    '',
    '| Component | Where | Licence |',
    '|---|---|---|',
    ...bundled
      .slice()
      .sort((a, b) => byName(a.name, b.name))
      .map((b) => `| ${cell(b.name)} | ${cell(b.where)} | ${cell(b.licence)}${b.pendingRemoval ? ' (being removed)' : ''} |`),
    '',
    '## Java runtime',
    '',
    'The installers also carry a Java runtime that jlink builds from the Eclipse Temurin JDK, under',
    'its own licence, `GPL-2.0-only WITH Classpath-exception-2.0`. The exception is what lets a',
    'program under any licence run on it and ship with it; docs/licensing.md says why the gate does',
    'not judge it.',
    '',
    '## Licences',
    '',
    ...Object.entries(allowedFile.allowed)
      .sort(([a], [b]) => byName(a, b))
      .map(([id, name]) => `- \`${id}\`: ${name}`),
    '',
  ]
  return lines.join('\n')
}

function main(argv) {
  const mode = argv.includes('--write') ? 'write' : argv.includes('--check') ? 'check' : null
  const modulesAt = argv.indexOf('--jvm-modules')
  const jvmModulesFile = modulesAt >= 0 ? argv[modulesAt + 1] : null
  const goAt = argv.indexOf('--go-binary-modules')
  const goBinaryModulesFile = goAt >= 0 ? argv[goAt + 1] : null
  if (!mode || (modulesAt >= 0 && !jvmModulesFile) || (goAt >= 0 && !goBinaryModulesFile)) {
    console.error('usage: licences.mjs --check [--jvm-modules <file>] [--go-binary-modules <file>] | --write')
    return 2
  }
  const data = load()
  if (mode === 'write') {
    writeFileSync(at(NOTICES), notices(data))
    console.log(`Wrote ${NOTICES}`)
    return 0
  }
  const found = problems(data, jvmModulesFile, goBinaryModulesFile)
  if (found.length) {
    console.error(`Licence gate failed (docs/licensing.md):\n${found.map((p) => `  - ${p}`).join('\n')}`)
    return 1
  }
  console.log(
    `Licence gate passed: ${data.jvm.length} Java modules, ${data.go.length} Go modules, ${data.npm.size} npm packages, ${data.bundled.length} bundled components.`,
  )
  return 0
}

// Node names the main module by its real path, so a run through a symlink must compare real paths.
if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)))
}
