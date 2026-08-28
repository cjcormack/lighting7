# REST/HTTP API conventions

The desk's HTTP surface grew one subsystem at a time, and by the end of the post-refactor sweep it
carried three spellings for the same idea. These are the rules it settled on. They are short on
purpose: the point is that a new endpoint has an obvious shape, not that every shape is legislated.

The decision behind all of it is **normalize hard, no aliases**. A renamed path is renamed, not
dual-mounted — this is a single-install desk with its frontend in one adjacent repo
(`../lighting-react`), so there is no third-party client to keep compatible, and a compatibility
alias would only be a second spelling that never dies.

## Paths

**Kebab-case, always.** `/speed-masters`, `/cue-stacks`, `/universe-configs`,
`/control-surface-types`, `/stage-regions`, `/surface-bindings`. Not camelCase, not snake_case, and
not a bare run-on word. Path segments are read by humans in a browser address bar and in a network
tab; the DTO fields inside them stay `camelCase`, because those are read by TypeScript.

**Collections are plural, and the collection GET lives on the collection.** `GET /api/rest/projects`
lists projects; `GET /api/rest/projects/{id}` is one. There is no `/list` sub-path and no singular
collection root — `GET /project/list` and `GET /fixture/list` were the last two and are gone. A
nested collection follows the same rule under its parent: `/projects/{projectId}/cues`,
`/projects/{projectId}/looks`.

Reserved literal words inside a collection are fine where they are genuinely a distinguished
member rather than a list: `GET /projects/current`. Ktor scores a literal segment above a parameter
one, so this does not shadow `/projects/{id}` — but it does mean an entity whose id is literally
`current` would be unreachable, which is why this is used only for ids the desk mints itself.

**Actions on a resource are a trailing verb segment on that resource**, POSTed:
`/projects/{id}/clone`, `/cues/{cueId}/apply`, `/cue-stacks/{stackId}/standby`,
`/templates/{templateId}/toggle`. Not a top-level `/doThing` endpoint carrying the id in a body.

## Vocabulary enumerations

Endpoints that enumerate *what the build knows how to do*, as opposed to what is patched or
authored, are kebab-case plural nouns naming the thing enumerated. One spelling for all of them:

* `GET /api/rest/fixture-types` — every fixture type compiled into this build.
* `GET /api/rest/control-surface-types` — every control-surface device profile.
* `GET /api/rest/groups/distribution-strategies` — the phase-distribution strategies.
* `GET /api/rest/fx/library` — the effect vocabulary. The odd one out, and deliberately: it is not
  a list of *types* but of registrations, script-defined ones included, so it changes at runtime.

They sit at the top level when they are independent of any project (`/fixture-types` describes the
build, not the rig — most of what it returns is not patched anywhere), and nested under a parent
when they only mean something inside it (`distribution-strategies` is a group concept). What they
must **not** do is hide under the resource they merely resemble: `/fixture-types` is a sibling of
`/fixtures`, not `/fixtures/types`, because it does not describe the patched rig.

## Guard overrides: `?force=true`

A mutation that refuses because of a *referential* consequence — deleting something another row
still points at — answers 409 with a machine-readable `code` and a usage summary, and accepts
`?force=true` as the operator's "yes, I meant it". The forced path is allowed to leave dangling
references on purpose; the resolver treats a dangling reference as absent.

Current users, all deletes: `/projects/{id}/templates/{templateId}`,
`/projects/{id}/looks/{lookId}`, `/projects/{id}/speed-masters/{masterId}`. It is a **query
parameter**, never a body field, so that the destructive form of the call is visible in a server
log. (Two `force` fields survive in `POST` bodies under `/programmer/record` and
`/programmer/update`; those are inert leftovers of the retired `cueEdit` session — see sweep item
D1 — kept only so the frontend's unconditional sender doesn't 400, and they are not this
convention.)

A refusal that is about *state* rather than references does not get a `force`: `409` for "that
project isn't current" is not overridable, and neither is 409 `SPEED_MASTER_PROTECTED` on master 1.

## Unbounded lists are fine

Collection GETs return everything, unpaginated, and that is deliberate rather than unfinished. The
whole database is one lighting desk: the largest collections are fixtures (tens), cues (hundreds),
and sync activity (thousands, and that one *is* paginated because it grows without bound). Adding
`?limit`/`?offset` to a list that is structurally bounded by how much rig one operator can patch
buys nothing and costs a second code path on every client.

The exception is anything that accumulates over time rather than being authored: sync activity and
history endpoints paginate newest-first with `?limit=&beforeId=`.

---

Two further conventions belong here and are still being applied by their own sweep items: which
surfaces are project-scoped and 409-guarded versus global (**F2**), and the response shape of
mutations — 204 versus the updated DTO (**F3**). WebSocket message naming and snapshot rules are
**F5**, and live in [`websocket-engineering.md`](websocket-engineering.md).
