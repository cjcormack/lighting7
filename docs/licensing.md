# Licensing

The desk is MIT ([LICENSE](../LICENSE), cloud-p0 plan D1): the code the project wrote, frontend
included. The licence covers the code, not the name of the hosted Lighting7 Cloud.

Everything else the desk ships is under its own licence, listed in
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md), which a gate in CI keeps true.

## The gate

`scripts/licences.mjs` is the one check, run two ways:

- **`./gradlew licenceCheck`**, part of `check` and so of CI's Backend job. Gradle writes the
  `group:name` of every module whose jar ships on the desk's and the launcher's runtime classpaths
  (`build/licences/runtime-modules.txt`; a BOM ships no jar, so is not one) and the script fails on a
  module `licences/jvm.json` does not list, or lists but no classpath has any more.
- **`npm run licences`** in `frontend/`, part of `npm run check`, `check:quick` and CI's Frontend job.
  It runs the script's tests (`scripts/licences.test.mjs`), then the check without the module list.
- **CI's Link job** builds the sidecar and hands the script `go version -m` over the binary
  (`--go-binary-modules`), §"Go modules".

Both fail on:

- a licence `licences/allowed.json` does not allow, in `licences/jvm.json`, `licences/go.json`,
  `licences/bundled.json` or a production package of `frontend/package-lock.json`;
- an `allowed.json` entry in the GPL family (GPL, LGPL, AGPL), which is never allowed;
- `LICENSE` existing while any entry is marked `pendingRemoval`;
- `THIRD-PARTY-NOTICES.md` differing from what the data generates.

A licence is an SPDX expression. `A OR B` passes when either side is allowed, `A AND B` when both
are, and `A WITH exception` is judged as `A`.

## Go modules

The sidecar, `lighting7-link` (`link/`, `docs/cloud-engineering.md` §"The sidecar"), is a Go binary, so
its dependencies are Go modules, listed in `licences/go.json` as `"<module path>": "<SPDX id>"`. The
gate reads the modules **linked into the built binary**, the `dep` lines of `go version -m`, not
`go.sum`, which also lists test-only modules and ones the build never links. With that list it fails on
a linked module `go.json` does not name and on an entry the binary no longer links; without it (Gradle's
`licenceCheck`, `npm run licences`) the entries are checked as written. CI's *Link* job runs it once
over the modules of every binary an installer carries (`linux/amd64`, `darwin/arm64`, `darwin/amd64`,
`windows/amd64`) together, since the modules a binary links can differ by OS: a module one OS links must
be listed, and an entry is stale only when none links it (today all four link the same 23). `go.json`'s `toolchain` entry is
the Go standard library and runtime, BSD-3-Clause, which every binary carries and `go version -m` does
not list.

To add one: `go get` it in `link/`, build (`CGO_ENABLED=0 go build -o lighting7-link ./cmd/lighting7-link`),
read its licence from the `LICENSE` file in its module (`go env GOMODCACHE`), add the entry, then run
`go version -m lighting7-link > modules.txt && node ../scripts/licences.mjs --check
--go-binary-modules modules.txt` and `node scripts/licences.mjs --write`.

## What is allowed, and why

| Licence | Why |
|---|---|
| MIT, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD | Permissive: keep the notice. The Eclipse Distribution License 1.0 is the BSD 3-Clause and is recorded as it. |
| Apache-2.0 | Permissive, with a patent grant. |
| EPL-1.0, EPL-2.0, MPL-2.0 | Weak copyleft that reaches only the library's own files. Allowed for a library used unmodified, as every one here is. |
| blessing | SQLite's public-domain dedication. |

Anything else is a decision, not a data entry: add it to `allowed.json` with its reason in this table,
in its own commit.

## Adding a dependency

1. Add it to the build as usual. `./gradlew licenceCheck` (or `check`) then names each new module.
2. Read the licence from the module's **own POM**, following its `<parent>` when the POM has none, and
   from its source repository when the POM is vague. Add `"group:name": "<SPDX id>"` to
   `licences/jvm.json`, or an object `{licence, why}` when the entry needed a judgement: a dual
   licence, a POM that names the licence loosely.
3. For an npm package the lock file's `license` field is read for you. Only a package with none, or a
   wrong one, needs an entry in `licences/npm.json`'s `overrides`, with its `why`.
4. A jar carrying native code, or a file vendored into the tree, also gets a row in
   `licences/bundled.json`: what it is, where it lands, its licence and why. Find a jar's natives by
   listing a `-PnativePayloadOs=all` fat jar (`unzip -l build/libs/lighting7.jar`) for `.so`,
   `.dll`, `.dylib` and `.jnilib` entries; `shadowJar`'s `nativePayloads` table lists only the ones it
   filters by OS, and a jar can shade another's natives (kotlin-compiler-embeddable carries lz4-java's
   and Jansi's).
5. `node scripts/licences.mjs --write` regenerates `THIRD-PARTY-NOTICES.md`.

`pendingRemoval` (naming the session that removes it) is the only way an entry outside the
allowlist passes, and it keeps `LICENSE` out of the tree while it stands.

## The audit (2026-11-02, cloud-p0 session 1)

- **artnet4j blocked MIT.** Its POM and its upstream `LICENSE` are the GPL v3. The desk used it only to
  send ArtDMX, and jaxb-core and jaxb-impl (CDDL 1.1 or GPL 2 with the Classpath exception) only for
  artnet4j. Session 2 replaced it with the desk's own ArtDMX sender (`dmx/ArtNetTransport.kt`,
  `docs/dmx-engineering.md` §"The Art-Net transport"), and the three left the build; then `LICENSE`
  went in.
- **CoreMIDI4J is EPL-1.0**, its native library included, and allowed as a separate library.
- **Dual licences** are taken under their allowed side: logback (EPL-2.0), JNA, JavaCPP, Jetty ALPN
  and the java-json-tools modules (Apache-2.0).
- **Natives.** libremidi links the system's JACK and loads its ALSA, and alsa-javacpp's bindings link
  the system's ALSA; neither library is bundled. The sqlite-jdbc, JNA, CoreMIDI4J and jkeychain
  payloads are under their jars' licences, and kotlin-compiler-embeddable shades lz4-java's and
  Jansi's natives into every OS's jar. Each is a row in `licences/bundled.json`.
- **Vendored code.** `frontend/src/kotlinScript/` is a modified react-kotlin-playground and
  `frontend/src/components/ui/` holds shadcn/ui's generated components, both MIT. Each keeps its
  upstream licence file beside it and is a row in `licences/bundled.json`.
- **The manufacturers' manuals** (`Manuals/*.pdf` and the Laserworld PDF at the root) were not ours to
  redistribute and left the tree. Fixture KDocs cite them by title. `TCH_2026_.pdf`, a MagicVis patch
  export from the operator's own show rather than a manual, left with them, as the owner asked.
- **The MagicQ personality transcriptions** (`Manuals/personalities/`) left too. They reproduce
  ChamSys's head files, whose terms do not grant redistribution, and the facts the desk needs are in
  the fixture classes. The KDocs cite each by its MagicQ file name.
- **The gel and lantern libraries** (`src/main/resources/gels.json`, `src/main/resources/lanterns/`)
  are the project's own: the gels' codes and names identify Lee and Rosco filters, whose names are
  their makers' trademarks, and the colours are the project's approximations; the lanterns' figures
  come from makers' datasheets or, for the generic ones, typical values, each named in its `source`.
- Removing a file from the tree leaves it in git history. Rewriting history is the owner's call.

## Not judged by the gate

- **A Java module's licence after a bump.** `licences/jvm.json` is keyed by `group:name`, so a module
  is judged when it is added; a later version is not re-read, and a relicensing bump passes
  (`FU-LICENCE-POM-DRIFT`). The npm half has no such gap: it reads each version's licence from the
  lock file.
- **The Java runtime** that jlink bundles into the installers is the Temurin JDK, under
  `GPL-2.0-only WITH Classpath-exception-2.0`. The exception lets a program under any licence run on
  the runtime and ship beside it, so it does not reach the desk's code. The notices name it.
- **Build and test tooling** (Gradle plugins, `testImplementation`, the frontend's devDependencies,
  the sidecar's test-only Go modules) does not ship. The one exception is the Sentry Vite plugin (MIT): in a release build, with
  `SENTRY_AUTH_TOKEN` set, it injects a debug-id snippet into each chunk. A package whose code or CSS
  the bundle carries belongs in `dependencies`, which is why `tailwindcss-animate` is there.
- **Licence texts.** The notices name each licence by SPDX id rather than reproduce its text or each
  library's copyright line (`FU-LICENCE-TEXTS`).
