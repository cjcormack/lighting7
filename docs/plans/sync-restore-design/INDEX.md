# Snapshot restore: design record

Source: a design session on 2026-11-07. Chris asked whether cloud sync could restore an earlier
version. It can't from the desk today: the sync page's History lists snapshots but has no actions,
and going back means rebuilding an old commit as a new one in git by hand.

The engineering half is [`../sync-restore-plan.md`](../sync-restore-plan.md). Where the plan and the
page disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| Sync before restoring and push after, or stay local until the next *Sync now*? | **Sync first and push after** (D5). Offline, the restore happens locally and goes out on the next sync. |
| Does the venue pin come back with the rows, or does the show keep its current pin? | **The pin comes back** (D7). Keeping today's pin under old rows would need Update's review folded in; *restore, then update* already does that. |
| Is *Bring back selected* part of this plan? | **Session 3 of this plan** (D10), run once sessions 1–2 have been used to restore a real show. |
| May the admin add a note to a restore? | **Yes, optional** (D11), appended to the restore's commit message. |

## What other tools do

From what is generally documented about them, not researched in this session.

- **git** itself: `git revert` adds commits that undo others, and `git restore --source` puts an old
  tree back for a new commit; a reset and force-push rewrites what others have.
- **Google Docs** and similar: *Version history* shows a version and *Restore this version* makes it
  the current one, as a new entry, with the version before it still listed.

## The page

[`sync-restore.html`](sync-restore.html) is one theme-aware page: the goals, what exists today, the
History row's menu, the review dialog, the seven steps of a restore, why it is a commit forward, what
other desks do, the live show and the venue hold, undoing a restore, the rules, the REST routes,
bringing back one record, testing, the decisions, the questions as answered and the three sessions.

It is published at <https://claude.ai/artifact/JkBANr7242ux1ga5WH45we>, private to Chris and a
convenience; the files here are the authority. Republish it from this file after a change.

## Names on the page

The show *Sleeping Beauty*, its cue and Look names, the venue *Commemoration Hall*, the commit ids and
the desk names are illustrative.
