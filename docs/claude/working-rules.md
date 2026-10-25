# Working rules

A checked-in copy of the general rules in `~/.claude/CLAUDE.md`, so cloud sessions, which have no `~/.claude`, follow them too. Change both together.

## Shipping needs a shipping verb

**Never commit, push, open a PR, post PR comments, approve/decline PRs or resolve review threads without an explicit shipping verb in my most recent message.** Verbs that count: "commit", "push", "open a PR", "create a PR", "branch and PR", "merge", "land", "approve", "reply on", "post", "comment". If none of those appear in my last message, edit the working tree (and only the working tree) and stop.

Phrases that do NOT count as authorization:
- Design observations: "I don't like X", "I think we should Y", "this feels wrong"
- Questions: "what would happen if X?", "is X the right call?", "should we Z?"
- Code-review reactions: "this could be cleaner", "X is right about Y"
- Approvals of my plan: "yes that sounds good", "go ahead with the plan" — this approves the plan; it does not approve shipping the diff

When I do give a shipping verb, the authorization covers only the changes in scope of that specific turn. After you've completed it, the authorization is spent — even within the same session, even if I've authorized similar work before. Treat every new round of changes as starting from "no shipping allowed" again.

**`/verified-ship` is a shipping verb for its own run.** Invoking it authorizes, on a PASS gate only, the commit, pushing its branch, and opening one PR. It never authorizes merging or auto-merge, and a BLOCKED gate ships nothing.

**`/next-session` is `/verified-ship` for one plan session**, and authorizes exactly the same: on a PASS gate, one commit on that session's branch, its push, and one PR stacked on the previous session's. Nothing else.

**Auto mode does not relax this.** Auto mode authorizes you to *execute work* without asking; it does not authorize you to *publish work*.

Before running `git commit`, `git push`, `gh pr create`, `gh pr comment`, `gh pr review` or any equivalent: scan my most recent message for one of the shipping verbs above. Absence is a hard stop, regardless of how ready the work seems.

## PR descriptions

The description should include a summary of the changes, the motivation for the change, and any relevant and non-obvious design decisions along the way. Before drafting it, read [prose.md](prose.md) and follow its principles, especially "Truth-Seeking" and "One True Sentence". Avoid AI-generated filler and fluff. Be concise, clear, and human-readable. Leave out process detail the reviewer doesn't need (review-gate rounds, agent findings, a recap of the session).

## Comment guidelines

🚨 DO NOT ADD COMMENTS TO CODE WITHOUT REFLECTING ON THESE GUIDELINES. THIS HAS BEEN A CONSISTENT AREA OF DISAPPOINTMENT. 🚨

1. The comment you want to write is too long. You can say the same thing in fewer words.
2. The comment you want to write references changes or decisions that are not material to the reader. Comments are about the code, not a history lesson or a recap of the agent session. You have a habit of documenting every code change that I ask for with a comment. Do not do this.
3. The comment you want to write is about something that is already very clear in the code. You probably don't need it at all.
4. The comment you want to write references a Jira ticket or commit or PR. Do not do this.

**Module-level comments** are generally good: a short summary of what the module does and why, following the principles above.

**Function-level comments** are fine as documentation if they are short and follow the language's standard (KDoc, JSDoc).

**Inline comments** only when the code would be genuinely baffling to a future reader, or has nasty side effects that are not obvious. In the vast majority of cases, that means none.
