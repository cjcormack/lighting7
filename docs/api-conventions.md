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

## Project scoping

Every endpoint sits on one of two sides, and which one is not a matter of taste:

**Live-runtime surfaces are global.** `/fx`, `/groups`, `/programmer`, `/locate`, `/ai/chat` and
the direct channel-control surface — these address *the show that is currently running*, not a row
in the database. There is no other show to address, so they carry no `{projectId}`, and there is
no id for them to 409 about. Adding one would be a lie: the parameter would have exactly one legal
value.

Being global does not mean ignoring the project. Where a global surface reaches a per-project
table it must still filter by the *running show's* project — `/fx/definitions/{definitionId}` and
`/ai/chat`'s conversation lookup both do, because a bare `findById` would otherwise let one
project's id reach another's row. `POST /ai/chat` is also the one live-runtime endpoint that can
answer 409: its tool loop spans several round trips to the model, and it refuses rather than
finish against a project that changed underneath it.

**Persisted project data is project-scoped**, under `/projects/{projectId}/…`, where `{projectId}`
is a numeric id or the literal `current`. Everything an operator authors and the desk stores lives
here: cues, cue stacks, looks, templates, scripts, patches, riggings, stage regions, universe
configs, surface bindings, speed masters, prompt books, AI conversation history.

Inside that second side, mutations split again, on **whether the write is also a live-show
mutation**:

* **409 `Cannot modify - not current project`** when the handler changes the running show as part
  of the write — cues, cue stacks, looks, templates, scripts, `/show`, prompt books. There is no
  coherent way to apply a cue belonging to a project that isn't loaded, so the request is refused
  rather than half-performed. Use `withCurrentProject`.
* **Ungated** when the write is DB-only and the running show is re-synced afterwards *if it
  happens to be looking at that project* — patches, riggings, patch groups, stage regions,
  universe configs, surface bindings, speed masters, cloud sync, AI conversation history. These
  read `withProject` and then branch on `state.isCurrentProject(project)` to reload fixtures or
  retune the clock. Patching a rig you are not currently running is a real workflow and this is
  what keeps it working.

Note that "persisted project data" here is a *routing* claim, not a sync one: AI conversation
history is scoped to a project and stored, but `SyncCoverageTest` classifies it `Excluded` and it
never leaves the desk. Which URL a table hangs off and whether it is portable are separate
questions — see `docs/sync-engineering.md` for the second.

Reads are never gated: `GET` on project data accepts any project id, current or not.

So the test for a new endpoint is: *does this address the running show, or stored data?* — and if
stored data, *does writing it require the show to be loaded?* The 409 is a statement about live
state, which is why it is not `?force=true`-overridable (see below).

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

## Mutation responses

**Deletes answer `204 No Content`.** Nothing is coming back — the resource is gone — so there is no
body, and no `200` with an empty one either.

**Updates answer the updated DTO**, not `204`. The point of a `PUT` is "tell me what this looks
like now" — a client that just edited a name doesn't want a second round trip to see whether a
collision handler renamed it further, or whether a derived field moved. The one recurring exception
is an update whose "resource" is a secret rather than something with a representable shape — a
password change, a stored PAT — those answer `204` because there is nothing safe to echo back.

The two-stack-verb family (`/show/activate`, `/show/advance`, `/show/go-to`, `/show/deactivate`)
answers with the same DTO shape across all four, `activeStackId`/`activatedStackName` both `null`
after a deactivate — a client watching "what's live now" reads one response shape regardless of
which verb got it there, rather than inferring the deactivated state from a bare status code.

## POST-for-read

An endpoint that only computes and returns something — no write, no side effect a `force`-style
retry would need to worry about — is a `GET`, even if an earlier draft of the route grew up as a
`POST`. `cue-stacks/{stackId}/preview` is the model case: it takes one scalar (`cueId`), so it
takes it as a query parameter (absent means "the stack's effective next").

The exception is a route whose input is a body too shaped to live in a query string — a source
script, or an unsaved draft with its own list of rows — because there is no clean way to spell
that as `?rows=...` and no cache key worth building around it anyway. `fx/definitions/compile`,
`fx/definitions/{id}/compile` and `templates/resolve` all stay `POST` for this reason:
`templates/resolve` in particular answers "resolve *this* body", not "resolve the template", so
even though it has no side effect, there is no stable resource behind it for a `GET` to name.

---

WebSocket message naming and snapshot rules are **F5**, and live in
[`websocket-engineering.md`](websocket-engineering.md).
