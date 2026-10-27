---
name: next-session
description: Implement the next session of an active plan in docs/plans/ through /verified-ship, on a branch stacked on the previous session's PR, ending with that session's PR opened. Use when asked to run, start or continue the next session of a plan.
argument-hint: "[plan name, e.g. flaky-tests]"
---

# /next-session — the next session of a plan, stacked and verified

Arguments: `$ARGUMENTS`, an optional plan: its name (`flaky-tests`), its file name
(`flaky-tests-plan.md`) or its path (`docs/plans/flaky-tests-plan.md`). Empty means find it.

Invoking this skill **is invoking `/verified-ship`** for one session. On a PASS gate it authorises
exactly what that run does: one commit on this session's branch, a push of that branch, and one PR.
It does not authorise merging, auto-merge, force-pushing, commenting on or approving any PR, or
committing to any other session's branch. A BLOCKED gate ships nothing.

## 1. Find the plan and the session

A plan this skill can run has a `## Sessions` table with the columns
`Session | Title | Status | Branch | PR | Head SHA`, and one `### Session N — <title>` section per
row. *Status* is `planned`, `in progress`, `in review` or `merged`.

1. `git fetch origin main`. A plan has work when its Sessions table on main has a row that is not
   `merged` (step 3 may still find that row merged), or when it is a **standing plan with untaken
   work**: its document status says STANDING, it has a section saying how to add a session, and
   that section's conditions hold (the flaky-tests plan's: an Open row with no *Session*). With `$ARGUMENTS`, strip any `docs/plans/`
   prefix and any `-plan.md`, `-plan` or `.md` suffix to get the name, and use
   `docs/plans/<name>-plan.md` on `origin/main`. If that file does not exist or has no work, say so,
   list the plans that do have work, and stop. Without arguments, list `docs/plans/*-plan.md` (not
   `completed/`) on `origin/main` that have work. Exactly one: use it. None: say so and stop.
   Several: ask which.
2. **Find the sessions already in flight.** Main's table is not enough: a session's own row moves to
   `in progress` and `in review` on its branch, so main reads `planned` until its PR merges, and a
   second run would redo it. List the repo's **open** PRs (`gh pr list --state open --json
   number,url,headRefName,baseRefName` where `gh` is installed, else the GitHub MCP's
   `list_pull_requests` with `state: open`, loading its tools with ToolSearch; owner and repo from
   `git remote get-url origin`). For each, `git fetch origin <headRefName>` and read the plan at
   `origin/<headRefName>`. The PR is session *N*'s when that copy's row *N* is `in progress` or
   `in review` with *Branch* equal to `<headRefName>`. Do the same for remote `claude/*` branches
   ahead of main that touch the plan (`git log origin/main..origin/<b> -- <plan path>`) and have no
   open PR: that is a run that pushed but never opened its PR.
   - Two PRs or branches claim one session: stop, name them and ask which stands.
   - A run in another container that has not pushed yet cannot be seen. If the operator says one
     is going, wait for its PR.
3. **The effective table** is main's, with each session found in step 2 overlaid from its own
   branch: *Status* `in review` (`in progress` for a branch with no PR), *Branch*, *PR*, and
   *Head SHA* that branch's head, including a row that exists only on that branch (a session §1a
   drafted). A row main still shows `in review` or `in progress` that step 2 did not find has often
   merged: its own row is left at `in review` unless whoever merged it filled it in. Look up its
   *Branch*'s PRs in every state (`gh pr list --state all --head <branch> --json
   number,url,state,mergedAt,headRefOid`, or `list_pull_requests` with `state: all` and
   `head <owner>:<branch>`). A PR that has merged (`mergedAt` from gh, `merged_at` from the MCP), or
   whose head is an ancestor of `origin/main`, makes the row `merged`, with that PR and its head
   SHA. A row whose PR was closed without merging stops the run wherever it sits: name it, and say
   it must be settled first. The session to run is the **first effective row whose Status is
   `planned` or `in progress`**. If there is none:
   - every row `merged`, and the plan standing with untaken work (step 1): draft the next session
     (§1a);
   - every row `merged` otherwise: say so and stop;
   - a row still `in review`: say the plan is waiting on that PR and stop. A standing plan's
     untaken work waits for it too, so that two runs never draft one session twice.

   Every row before the chosen one must be `merged` or have an open PR: an earlier row with a
   pushed branch but no PR stops the run, since sessions run in order. If the chosen row is
   `in progress`, an earlier run stopped part way: look for its branch and any `REVIEW_STATE.md`,
   and if one exists, resume `/verified-ship` from it (empty arguments) instead of starting again.
4. Read the whole plan **at the base §2 picks** (the previous session's branch when stacking: it may
   have corrected the plan), its design record (the `*-design/INDEX.md` it links) and the root and
   `frontend/` `CLAUDE.md`s.

## 1a. Draft a session for a standing plan

Only when §1.3 sends the run here: every effective row is `merged` and the plan is standing with
untaken work. So the base is `main` (§2), and the new session is numbered after the last row.

1. Follow the plan's own section on adding a session: which rows to group into one session, and
   what its section must say. Use the earlier sessions' sections as the model for length and shape.
2. Before writing, read the code and tests each row names, far enough to say what is there and to
   name a suspected cause, marked as read rather than reproduced. Give the session a way to
   reproduce the row (the plan's D-items say what counts), a recipe for its done check, which tests
   run locally, and the docs it may touch. Correct a row's wording where the code shows it is wrong,
   for example a row that names an assertion message as a second test. A row you cannot say anything
   useful about still gets a session; say what is unknown.
3. Make these edits in §4, on the session's own branch. They go into the session's commit, so the
   section and its fix reach `main` in one PR, and step 2 of a second run finds the session on its
   branch. Add the session's row (*Status* `in progress`), its `### Session N — <title>` section,
   and *N* in each taken row's *Session* column.
4. Tell the operator in one line that you drafted session *N* and which rows it takes, then carry
   on. The review gate in `/verified-ship` reads the section with the fix.

## 2. Work out the base

- **Session 1, or the previous session's PR is merged**: the base is `main`.
- **The previous session's PR is open**: the base is its branch, at `origin/<branch>`'s head. The
  PRs before it in the stack must be ancestors of that head (`git merge-base --is-ancestor`); if one
  is not, the stack has forked, so stop and say which.
- **The previous session's PR was closed without merging**, or its CI is red on its head: stop and
  say so. Stacking on a broken or abandoned base is the previous session's problem to settle first.
  CI still running is not red; stack on it, and say so in §6.

Read a PR's state and checks with `gh pr view` / `gh pr checks` where `gh` is installed, else the
GitHub MCP's `pull_request_read` (`get`, `get_check_runs`).

## 3. Cut the branch

- If the environment names a branch to develop on (a cloud session's instructions do), use it.
  Otherwise use `claude/<plan slug>-s<N>`, the slug being the plan's file name without `-plan.md`.
- If that branch already exists with commits the base lacks, and they are not this session's own
  earlier work (§1.3), stop and ask.
- `git checkout -B <branch> origin/<base>` (`origin/main` when the base is `main`).

## 4. Book-keeping on the branch, before implementing

Edit the plan's Sessions table in the working tree; these edits go into this session's commit:

- Every earlier row: bring it to the effective table (§1.3): fill *PR* (a markdown link to the full
  URL) and *Head SHA* (its branch's head as fetched, short form) where they are empty, and set
  *Status* to `merged` where its PR has merged. A stacked base's copy has the previous row at
  `in review` with no PR, because that session opened its PR after committing.
- This row: *Status* `in progress`, *Branch* the branch from §3.
- A session drafted in §1a: its row, its section and the taken rows' *Session* column.

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
whether that PR's CI was still running when this one stacked on it, that its PR has to merge first, and that GitHub moves this PR's base to `main` when that branch is
deleted on merge; otherwise its base needs changing by hand.

If a later commit lands on the previous session's branch (a review fix) before this PR merges,
bring it in by **merging** that branch into this one, never by rebasing (root `CLAUDE.md`
§"Git workflow").
