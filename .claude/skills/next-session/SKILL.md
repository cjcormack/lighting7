---
name: next-session
description: Implement the next session of an active plan in docs/plans/ through /verified-ship, on a branch stacked on the previous session's PR, ending with that session's PR opened. Use when asked to run, start or continue the next session of a plan.
---

# /next-session — the next session of a plan, stacked and verified

Arguments: `$ARGUMENTS`, an optional plan path (`docs/plans/<name>-plan.md`). Empty means find it.

Invoking this skill **is invoking `/verified-ship`** for one session. On a PASS gate it authorises
exactly what that run does: one commit on this session's branch, a push of that branch, and one PR.
It does not authorise merging, auto-merge, force-pushing, commenting on or approving any PR, or
committing to any other session's branch. A BLOCKED gate ships nothing.

## 1. Find the plan and the session

A plan this skill can run has a `## Sessions` table with the columns
`Session | Title | Status | Branch | PR | Head SHA`, and one `### Session N — <title>` section per
row. *Status* is `planned`, `in progress`, `in review` or `merged`.

1. With `$ARGUMENTS`, use that file. Otherwise list `docs/plans/*-plan.md` (not `completed/`) whose
   Sessions table has a row that is not `merged`. Exactly one: use it. None: say so and stop.
   Several: ask which.
2. Read the whole plan, its design record (the `*-design/INDEX.md` it links) and the root and
   `frontend/` `CLAUDE.md`s.
3. The session to run is the **first row whose Status is `planned` or `in progress`**. If an
   earlier row has no PR, stop: sessions run in order. If that row is `in progress`, an earlier run
   stopped part way: look for its branch and any `REVIEW_STATE.md`, and if one exists, resume
   `/verified-ship` from it (empty arguments) instead of starting again.

## 2. Work out the base

`git fetch origin main`, plus the previous session's branch when there is one.

- **Session 1, or the previous session's PR is merged**: the base is `main`.
- **The previous session's PR is open**: the base is its branch, at `origin/<branch>`'s head.
- **The previous session's PR was closed without merging**, or its CI is red on its head: stop and
  say so. Stacking on a broken or abandoned base is the previous session's problem to settle first.

Read a PR's state with `gh pr view` where `gh` is installed. Otherwise use the GitHub MCP
(`pull_request_read`, `list_pull_requests` with `head`), loading its tools with ToolSearch first.
Take owner and repo from `git remote get-url origin`.

## 3. Cut the branch

- If the environment names a branch to develop on (a cloud session's instructions do), use it.
  Otherwise use `claude/<plan slug>-s<N>`, the slug being the plan's file name without `-plan.md`.
- If that branch already exists with commits the base lacks, and they are not this session's own
  earlier work (§1.3), stop and ask.
- `git checkout -B <branch> origin/<base>`, or `git checkout -B <branch> main` after a fetch when the
  base is `main`.

## 4. Book-keeping on the branch, before implementing

Edit the plan's Sessions table in the working tree; these edits go into this session's commit:

- The previous row: fill *PR* (a markdown link to the full URL) and *Head SHA* (its branch's head
  as fetched, short form) if they are empty; set *Status* to `merged` if its PR has merged.
- This row: *Status* `in progress`, *Branch* the branch from §3.

## 5. Hand the session to `/verified-ship`

Invoke `Skill(skill: "verified-ship", args: <task>)`. The task text is self-contained. Fill in every
`<…>`:

> Implement **Session `<N>` — `<title>`** of `<plan path>`, exactly as its `### Session <N>` section
> says, under the plan's decisions (§2) and its own rules (P-items); the design record is
> `<INDEX.md path>`. Out of scope: anything the plan gives to another session, and the plan's §7.
> Where the plan is wrong about the code, follow the code, and record the correction in the plan in
> this commit.
>
> **Repo context for Phase 0:**
> - Branching: already on `<branch>`, cut from `<base>`; commit here and cut nothing.
> - `pr_base`: `<base>`.
> - PR route: `gh pr create` if `gh` is installed. Otherwise the GitHub MCP's `create_pull_request`
>   (owner `<owner>`, repo `<repo>`, head `<branch>`, base `<base>`), loading its tools with ToolSearch.
>   Checking for an existing PR uses `list_pull_requests` with `head` the same way.
> - Tests: the session's section says whether the full suite runs locally; follow it.
> - Before committing, set this session's row in the plan's Sessions table to `in review`, *Branch*
>   `<branch>`, and record anything the session measured where the plan asks for it.
> - PR title: the one the plan's §5 gives, else `<plan's subject>: session <N> — <title>`. The PR description says what changed and why
>   (CLAUDE.md's prose rules), links the plan, and, when `<base>` is not `main`, opens with
>   "Stacked on `<previous PR link>`; merge that first."

`/verified-ship` owns everything from here: implementation, the review gate, the commit, the push
and the PR.

## 6. Finish

Report the PR link, its base, whether the gate passed or what blocked it, and whether the full suite
ran locally or is CI's to run. Nothing is merged. If the base is a previous session's branch, say
that its PR has to merge first, and that GitHub moves this PR's base to `main` when that branch is
deleted on merge; otherwise its base needs changing by hand.

If a later commit lands on the previous session's branch (a review fix) before this PR merges,
bring it in by **merging** that branch into this one, never by rebasing (root `CLAUDE.md`
§"Git workflow").
