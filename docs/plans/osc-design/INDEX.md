# OSC: design record

Source: a design session on 2026-11-07. Chris asked how the desk could take OSC from QLab, so a
QLab cue can trigger GO, busk buttons and the like.

The engineering half is [`../osc-plan.md`](../osc-plan.md). Where the plan and the page disagree,
the plan wins on behaviour and the page on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| A fixed address set, or learnable addresses? | **Fixed first**, named after the show's own stacks, cues, Looks and pages, since QLab sends whatever is typed. Learnable addresses come later, for senders that can't be told what to send. |
| How are names written in an address? | **As slugs** (`Act 1` → `act-1`), an ambiguous match refused. An exact-name argument form and short OSC numbers were declined. |
| Bare GO, or GO TO a numbered cue? | **Both**, with GO TO and explicit on and off recommended on cue sheets, so a QLab cue fired twice changes nothing: a GO TO onto the cue already live, or an on that is already so, does nothing rather than re-firing it. |
| Do cue events fire on an OSC GO TO? | **Yes, by today's rule**: a GO TO fires the events of the cue it lands on. The question was first asked on the belief that GO TO fired none; the code says it does, and Chris kept today's rule. |
| May OSC arm the desk? | **No.** Arming stays a gesture made at the desk. |
| Who may send? | **Allowlisted senders only**, each named; others dropped and shown with *Allow this sender*. |
| Who sees the address log? | **Every signed-in window.** Only admins change the settings. |
| Which port? | **UDP 8415**, beside 8413 and 8414. |
| What does session 1 hold? | **Stacks, Looks, loose cues and busk.** Masters and tempo moved to session 2. |
| Replies and feedback? | **Session 3**, with Learn. |

## What other desks do

From what is generally documented about them, not researched in this session.

- **ETC Eos:** a published address set (`/eos/cue/{list}/{cue}/fire`, `/eos/key/go_0`) and
  user-defined OSC triggers; replies to the sender.
- **ChamSys MagicQ:** OSC in and out, with a fixed address set for playbacks and cue stacks.
- **QLab** itself: its Network cues send OSC or plain UDP to a patch, over UDP or TCP, and read
  nothing back.

## The page

[`osc.html`](osc.html) is one theme-aware page: the goals, how OSC joins the surface dispatch, the
address set, names in addresses, the explicit forms, an example QLab Network cue and a run of a cue
sheet, the transport, security, the log, history, learnable addresses, the wire and storage,
testing, the decisions and the three sessions.

It is published at <https://claude.ai/artifact/6c25TVCQjpaSuFhjgqQ788>, private to Chris and a
convenience; the files here are the authority. Republish it from this file after a change.

## Names on the page

The stacks *Act 1* and *Act 2*, the Look *Haze fill*, the busk page *Storm*, the QLab cue numbers and
the IP addresses are illustrative.
