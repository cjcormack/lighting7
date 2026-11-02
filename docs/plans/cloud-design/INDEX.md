# Lighting7 Cloud: design record (desk side)

Source: the Lighting7 Cloud PRD (Draft v0.2, 2026-11-02), written with Chris in an earlier session.
Its canonical copy is `docs/prd.md` in the private cloud repo, `cjcormack/lighting7-cloud`; a rendered
copy is at <https://claude.ai/artifact/J6qFr7Wc5MNATSTAxZfiHt>, private to Chris. This repo is public,
so the PRD itself is not copied here: each desk plan restates the requirements it implements, by ID.

This folder is the design record for **every** cloud phase plan in this repo
(`docs/plans/cloud-p0-plan.md`, and `cloud-p1-plan.md` onwards as they are written). A phase plan links
this file; anything that applies to more than one phase lives here, not in a plan.

## The programme

The PRD's phases run in order (P0 Foundations, P1 Remote access + MCP, P2 Cloud sync v1, P3 Fleet
health + backups, P4 Update distribution, P5 Growth), and each ends with exit criteria that need both
repos. So **each phase is two plans**, one per repo, written together:

| Phase | Desk plan (this repo, public) | Cloud plan (`lighting7-cloud`, private) |
|---|---|---|
| P0 | `docs/plans/cloud-p0-plan.md` | `docs/plans/p0-plan.md` |
| P1 | `docs/plans/cloud-p1-plan.md` | `docs/plans/p1-plan.md` |
| … | `cloud-pN-plan.md` | `pN-plan.md` |

Each plan is run by `/next-session` in its own repo, which keeps its own copy of the skill. A session
opens its PR in its plan's repo and nowhere else. The phase's exit criteria are an operator check in
the desk's `docs/plans/manual-validation.md` (one row per phase, `FU-MANUAL-CLOUD-PN`), because only the
owner can watch a real desk reach a real cloud.

## Asked and answered

| Question | Answer (2026-11-02) |
| --- | --- |
| Where do the PRD and the plans live? | **Split by repo.** PRD and cloud plans in the private repo; desk plans here, self-contained and citing PRD IDs. One plan in this repo with a *Repo* column was declined: it would publish the cloud's design. |
| REC-2, REC-3, REC-4 for P0? | **Confirmed**: AWS Lightsail in London under Terraform; Kotlin/Ktor/Exposed/Postgres with a React 19 + Vite + RTK Query portal; the cloud's own IdP and OAuth server in Ktor. |
| The cloud's domain (OQ-1)? | **Decided before accounts.** Passkeys bind to the domain, so the cloud's accounts session waits for a registered product domain; infra may start on a placeholder. |

## Rules every phase plan follows

- **R1 — Cite PRD IDs.** Decisions, requirements and risks are named `D-n`, `REC-n`, `FR-AREA-n`,
  `NFR-AREA-n`, `RISK-n`, `OQ-n`, as the PRD names them. A plan's own decisions are `D1`, `D2` (no
  hyphen), as every plan here names them, so the two never collide.
- **R2 — Cross-repo order is a *Needs* line.** A session whose work depends on the other repo opens its
  section with `**Needs:**` and a list of checkable items: `<owner/repo> <plan path> session N merged`,
  or an operator step (`operator: the domain is registered`). `/next-session` checks them before it
  starts (its §1 step 5) and stops with what is missing. The Sessions table keeps the six columns the
  skill reads; *Needs* never becomes a column.
- **R3 — The protocol is the desk's.** The control-channel envelope, the adoption API, the token claims
  and the error codes live in `cloud-protocol/`, an MIT Gradle build in this repo (PR-6, §9.2). A
  protocol change is a desk PR first. Within protocol version `v` changes are additive (a new message
  type, a new optional field, a new capability in `hello`); anything else is a new `v`, which the
  cloud serves beside the old one for at least one desk minor version (NFR-COMPAT-1). The golden
  fixtures under `cloud-protocol/src/test/resources/` are the contract both repos test against.
- **R4 — The cloud consumes the desk by a pinned commit.** The cloud repo names one desk commit in
  `desk.ref`; its CI and its sessions sparse-check out `cloud-protocol/` (and, for the portal,
  `frontend/design-system/`) at that commit and use them through a Gradle composite build and a `file:`
  npm dependency. Moving the pin is a cloud PR. Nothing is published to a package registry until a
  third party needs to build against the protocol (`FU-CLOUD-PROTOCOL-PUBLISH`).
- **R5 — Desk tests never need the cloud.** Desk sessions test against `FakeCloud`, a test-only server
  built on `cloud-protocol`. The real cloud is exercised only by the operator checks.
- **R6 — The cloud is never on the show's path** (PR-1, RISK-10). Every desk session's review asks
  "does this work with the cloud down?", and the connector keeps to the rules in
  `docs/cloud-engineering.md` §"Show safety" (own dispatcher, bounded queues, no database write on a
  timer, no lock the show takes).
- **R7 — Machine-local by default.** Every desk table the cloud work adds is machine-local in
  `sync/SyncDispositions.kt`, and its secrets go in `CredentialStore`, never a table.

## Corrections to the PRD found while planning

Each lands in the PRD's next revision; until then, the plan named is the authority.

- **Reconnect backoff.** §9.3 caps the control channel's backoff at 60 s, but NFR-PERF-3 wants a
  reconnect within 30 s of the cloud's return. P0 caps it at 30 s with full jitter (cloud-p0 D9).
- **Adoption is local only.** The PRD does not say where adoption may start. Adopting from the public
  listener would let a remote admin give a cloud org standing reach into the rig, so P0 refuses it
  there, as *Quit desk* is refused (cloud-p0 D5).
- **The control channel's frame limit is 8 MB from P0** (§9.7 asks for it in P1), so P1's
  `render_view` results fit without a protocol change.
- **No TURN in P0.** §10.3's lean start runs coturn on the app instance; P0 has no remote sessions, so
  coturn and its ports arrive with P1.
- **Social sign-in moves out of P0.** FR-ACC-1's Google and Apple sign-in are SHOULD; P0 ships passkeys,
  email verification and recovery codes, and the cloud plan records social sign-in as a follow-up.
