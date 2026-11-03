import assert from 'node:assert/strict'
import { test } from 'node:test'
import { licenceAllowed } from './licences.mjs'

const allowed = new Set(['MIT', 'Apache-2.0', 'LGPL-2.1-only'])

test('one licence is allowed only when listed', () => {
  assert.equal(licenceAllowed('MIT', allowed), true)
  assert.equal(licenceAllowed('BSD-3-Clause', allowed), false)
})

test('OR needs one allowed side, AND needs both', () => {
  assert.equal(licenceAllowed('MIT OR GPL-3.0-only', allowed), true)
  assert.equal(licenceAllowed('MIT AND GPL-3.0-only', allowed), false)
  assert.equal(licenceAllowed('(GPL-3.0-only OR MIT) AND Apache-2.0', allowed), true)
  assert.equal(licenceAllowed('GPL-3.0-only OR MIT AND BSD-3-Clause', allowed), false)
})

test('the GPL family is refused even when listed, and an exception does not change that', () => {
  assert.equal(licenceAllowed('LGPL-2.1-only', allowed), false)
  assert.equal(licenceAllowed('GPL-2.0-only WITH Classpath-exception-2.0', allowed), false)
  assert.equal(licenceAllowed('Apache-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0', allowed), true)
})

test('a malformed expression throws', () => {
  assert.throws(() => licenceAllowed('MIT AND', allowed), /ends early/)
  assert.throws(() => licenceAllowed('(MIT', allowed), /unbalanced/)
  assert.throws(() => licenceAllowed('MIT MIT', allowed), /unexpected MIT/)
})
