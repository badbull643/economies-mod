# EconomiesMod — Session Log

*Continues the previous log of the same name; git history has it at `4fb4ec5`.
This file is the current picture, and where it disagrees with anything older, this one
is right.*

*Branch `trust-model-and-migration`, **21 commits ahead of
`origin/trust-model-and-migration`**. The branch has been pushed before and merged to
`main` through PR #6 — the previous log's "never pushed, `main` has no PR" is out of
date, and was repeated for most of this session before anyone checked. Local `main` is
53 behind `origin/main`; nothing depends on that.*

---

## 1. Where things stand

The roadmap is finished. Everything in Phases 0–5 is done or deliberately closed. This
session went on what running it turned up, then on one new feature.

```
coreTests 437   chunkTest 6   replayGuardTest 5   gapRecoveryTest 16
admissionTest 16   depositCapTest 6   attestationTest 12
```

Every engine change below was verified to **fail** with its fix disabled before being
trusted, per the project's existing discipline. Three failed the first time for a reason
that had not been predicted, which is the point of doing it.

## 2. Testing — where it actually got to

Checklist is `docs/testing-checklist.md`. The Group C sitting has a runbook at
`docs/testing/group-c-runbook.md` with ready-to-swap configs.

**Done live:** all of Group A, B1/B2/B4, and Group C except C2's "a refusal consumes no
allowance" half. Each is marked off with what it turned up.

**Never fired, and cannot be forced:** D1 (slow-client fan-out) and B3 (sequence gap).
Watch for them rather than chase them.

**Group E — everything built this session, untested in game.** This is the real gap: ten
commits of engine and UI work with 437 automated checks behind them and zero live
minutes. The UI half needs eyes most. The Market column now scrolls and hides rows that
fall outside the frame, and nobody has seen it.

## 3. What running it turned up

Nearly every bug this session came from playing, not from reading. Worth remembering
when deciding how much more to build before testing again.

- **The fork checklist was empty unless you polled.** `divergence` was set in exactly one
  place — the discovery poll — so a fork found by connecting recorded nothing. And the
  fork in the B2 recipe arrives as `AHEAD`, not `FORK`, because the host tests "client
  ahead of me" first. `offerCatchUp` was already finding the divergence and printing it,
  then throwing it away.
- **A reset then offered nothing back**, because `ordersLostToReset` passed the seq where
  the chains *disagree* to something that wanted the last seq they *agree* on.
- **A failed start still wrote to the market.** `start()` registered the host and issued
  its grant before binding, so a busy port appended two events and then failed.
- **Migration bypassed every deposit rule.** All three hang off `depositUnitsOf` in
  `processProposal`, and a migration is queued as host work that never goes near it.
- **The statistics rule never counted.** `needsCounting` omitted it, so each deposit was
  judged alone and the rule could be walked through by splitting one in two.
- **The host list was invisible and still clickable.** Render stood aside; the click
  handler did not.

## 4. The defect that keeps recurring

Every one of those is the same shape: **two things that must agree, kept in two places.**
Render and hit-test. `needsCounting` and the window validation. `depositUnitsOf` and the
migrate path. The poll and the connect. Genesis and the applier.

Each fix was the same move — collapse it to one place: `replaceCoversLeftPanel()`,
`countsDeposits()`, `stipendOutpacesItsFees()`, `registerKey` owning its own invariant.

New code is worth checking against this specifically. It caught me twice *in code written
this session*, both times by a test rather than by reading it back.

## 5. The stipend, which is new

The only feature added. The welcome grant was the sole way credits entered a market, so
goods accrued against a money supply that grew only when people did, and prices sink
toward the integer floor of 1 where the price signal dies.

An identity claims a fixed amount once per **50 fills** the market settles. Keyed to
fills rather than sequence numbers, and that is the whole design: `Deposit`, `Withdraw`
and `CancelOrder` are free events, so a stipend paid per sequence number could be farmed
in a market of one and the credits migrated out. A fill needs two orders to cross, and
placing an order costs a listing fee that is never zero.

**The interlock is the safety property, and it was wrong twice before it was right:**

- A fill was costed at two listing fees. One order sweeping a stacked book produces a
  fill per resting order it consumes — twenty fills for twenty-one fees, measured and
  pinned in `U8`. The floor is one fee per fill.
- It counted one claimant. Every registered identity claims per interval, so the payout
  multiplies by the head count while the fees do not.

Both corrected, and the rule is asked in three places rather than reimplemented in two.
It is re-checked **at every claim**, because a market can outgrow a stipend that was
affordable when it was set.

From the same thread: the listing fee now climbs with orders held open above an
allowance (zero means no escalation, so existing markets are unchanged), and the welcome
grant finally has a control — see §7.

## 6. Known gaps

- **Group E is untested.** See §2.
- **Log compaction unbuilt**, deliberately. `docs/design/log-compaction.md` has the full
  pass; its recommendation is build option A only, when it is worth a session of its own.
- **`.gitignore` is gone**, removed deliberately, so `build/`, `run/` and
  `server-identity.key` are tracked. That last is an unencrypted private key. Raised, and
  the decision reaffirmed — recorded here because it is not obvious from the tree.
- **Never built from a clean checkout, and no CI.** Every claim that this works rests on
  one machine.
- **Host rules do not travel.** Deposit caps, admission and attestation are per-host, read
  from that host's own `host-config.json`, so they change when hosting rotates and a group
  cannot agree them once. Market rules — fees, grant, stipend — live in the log and are
  uniform for everyone.
- **The play-hour rule is kept but off**, with the reasoning on the config field. It
  weighs a rolling window against a lifetime, and is a rate limit applied to a stock.

## 7. Things worth knowing before touching this

- **A `MarketPolicy` event is the whole policy.** Anything it does not restate is set to
  zero. This nearly wiped the welcome grant once, and did silently wipe the stipend the
  moment those fields were added. `submitPolicy` now builds from current state and hands
  it to the caller to mutate, so forgetting a field means it keeps its value. `U6` pins
  the behaviour.
- **Only the creator can set policy, and the creator is fixed at genesis.** On a dedicated
  server bootstrapped without `--creator-key` the creator is the box, which has no screen
  — so that market's policy is frozen forever. `listingFee` and `stipendAmount` in
  `ServerConfig` exist for exactly that case.
- **The welcome grant has a control now**, behind DANGER rather than a plain confirm.
  Rotating markets previously granted 1000 with nothing able to change it, against items
  trading for 1–2.
- **The Market column scrolls.** `place()` hides a row that falls outside the frame rather
  than drawing past the bottom, so without the scroll those rows would be unreachable.
- **`requireConnected()` is where trading is gated**, on every action, and it tests the
  live socket rather than the mode. There is no guard inside `submit()`; looking for one
  there will not find it.
- **A ban writes itself into whichever config file the host loaded.** It cost two sessions
  before anyone noticed. The runbook fixtures all ship `banOnWorldChange: false`.

## 8. Open decisions

- Whether the stipend's interval of 50 is right. It is a guess, and nothing has watched a
  real session. Fills only accrue while somebody is hosting and connected — there is no
  offline trading — so a small group produces them slowly.
- Sub-unit prices: denominate in hundredths as integers, never floats (Java 8 without
  `strictfp` is not reproducible across platforms, which would fork replicas). Gives
  roughly 100× headroom before the price floor bites. Treats the symptom rather than the
  cause, which the stipend now covers, so no longer urgent.
- Whether `MarketScreen` gets split. 3,900 lines, and three of this session's UI bugs
  lived in it. Split by component, never by layer — separating render from hit-test is
  the §4 defect waiting to happen.

## 9. Loose ends in the tree

- A worktree at `.claude/worktrees/practical-diffie-e9fbf4` sits at `4fb4ec5`. It was for
  a background task on the migration bypass that never ran; that bug was fixed here
  instead. Safe to remove.
- `server-market.jsonl` is the dedicated server's live market, bootstrapped during C4 with
  `welcomeGrant: 50`. It has no listing fee, so it cannot have a stipend without being
  recreated.
