# Lighting7 Cloud: design record (desk side)

Source: the Lighting7 Cloud PRD (Draft v0.2, 2026-11-02), written with Chris in an earlier session.
Its canonical copy is `docs/prd.md` in the private cloud repo, `cjcormack/lighting7-cloud`; a rendered
copy is at <https://claude.ai/artifact/J6qFr7Wc5MNATSTAxZfiHt>, private to Chris. This repo is public,
so the PRD itself is not copied here: each desk plan restates the requirements it implements, by ID.

This folder is the design record for **every** cloud phase plan in this repo
(`docs/plans/completed/cloud-p0-plan.md`, and `cloud-p1-plan.md` onwards as they are written). A
phase plan links this file; anything that applies to more than one phase lives here, not in a plan.

## The programme

The PRD's phases run in order (P0 Foundations, P1 Remote access + MCP, P2 Cloud sync v1, P3 Fleet
health + backups, P4 Update distribution, P5 Growth), and each ends with exit criteria that need both
repos. So **each phase is two plans**, one per repo, written together:

| Phase | Desk plan (this repo, public) | Cloud plan (`lighting7-cloud`, private) |
|---|---|---|
| P0 | `docs/plans/completed/cloud-p0-plan.md` (done) | `docs/plans/completed/p0-plan.md` (done) |
| P1 | `docs/plans/cloud-p1-plan.md` (active) | `docs/plans/p1-plan.md` (active) |
| … | `cloud-pN-plan.md` | `pN-plan.md` |

Each plan is run by `/next-session` in its own repo, which keeps its own copy of the skill. A session
opens its PR in its plan's repo and nowhere else. The phase's exit criteria are an operator check in
the desk's `docs/plans/manual-validation.md` (one row per phase, `FU-MANUAL-CLOUD-PN`), because only the
owner can watch a real desk reach a real cloud.

## Asked and answered

| Question | Answer |
| --- | --- |
| Where do the PRD and the plans live? (2026-11-02) | **Split by repo.** PRD and cloud plans in the private repo; desk plans here, self-contained and citing PRD IDs. One plan in this repo with a *Repo* column was declined: it would publish the cloud's design. |
| REC-2, REC-3, REC-4 for P0? (2026-11-02) | **Confirmed**: AWS Lightsail in London under Terraform; Kotlin/Ktor/Exposed/Postgres with a React 19 + Vite + RTK Query portal; the cloud's own IdP and OAuth server in Ktor. |
| The cloud's domain (OQ-1)? (2026-11-02) | **Decided before accounts.** Passkeys bind to the domain, so the cloud's accounts session waits for a registered product domain; infra may start on a placeholder. |
| P1's open questions (asked 2026-11-05) | **OQ-3:** no remote-access trial before P5 (D-11). **OQ-4:** the MCP URL is per install in P1 (D-12). **OQ-5:** a cloud org role never implies a desk role; reaching a desk as a user always needs a link (D-13). **OQ-7:** "approve new remote sessions on the desk" is offered, off by default (D-14, FR-RA-12). **OQ-10** (an MIT shell) and **OQ-11** (a WebSocket relay of last resort) are decided at P1a's gate. |
| Decisions taken while planning P1 (2026-11-05) | **D-15:** MCP through the cloud has its own desk switch, apart from remote sessions', since approval covers sessions only (FR-RA-13). **D-16:** a desk's invite is mailed only once an owner or admin of the org approves it in the portal, since the install key proves the desk, not the person. **D-17:** the switches that limit what comes through the cloud (remote sessions, MCP, approval, and the scripts, effects and commands switches) change only from the desk's own listener. |

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
- **The SPA runs at `/` of its own origin** (P1). §9.5 frames the desk's SPA under a path of the
  cloud's origin; the cloud's P1 plan frames it at `/` of a per-install origin instead, so the SPA needs
  no base path, and the desk learns that origin from `welcome` (`remoteOrigin`) for its Origin check
  (cloud-p1 D4, D9).
- **The sidecar's local API is its stdio** (P1). §9.2's "loopback-only local API" is JSON lines on the
  sidecar's stdin and stdout, so there is no port to guard (cloud-p1 D7).
- **The SPA announces remote mode** (P1). §9.5 has the parent announce it; in P1 the SPA's `hello` is
  answered with the port its socket rides, so nothing is posted before the SPA listens (cloud-p1 D8, D9).
- **Social sign-in moves out of P0.** FR-ACC-1's Google and Apple sign-in are SHOULD; P0 ships passkeys,
  email verification and recovery codes, and the cloud plan records social sign-in as a follow-up.
