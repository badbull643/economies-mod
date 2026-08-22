# Backlog — things deliberately not built

*Everything here was reached, understood, and put down on purpose. That is the point of
the file: the roadmap is finished, so there is no longer a document that says "this is
known about and not done", and without one an unbuilt thing is indistinguishable from an
overlooked one.*

*Ordered by what to do first, not by size. Each entry says what it costs to keep NOT
doing it, because that is the number that decides when it is worth a session.*

---

## 1. Split-point discovery, and forked-market recovery

**Design: [`design/fork-rebase.md`](design/fork-rebase.md). Nothing built.**

A friend group that all started on one host, later split into two groups, and both kept
trading, cannot come back together without one side discarding everything it did since
the split. Migration is refused for the same market id — deliberately, in two places —
so the balance-preserving path is closed to precisely the people who share the most
history. A group that had never met can migrate; a group that split cannot.

**Cost of not doing it:** a reset deletes fills, credits, and every item deposited since
the split. Those items already left the player's Minecraft inventory, so it is real
destruction rather than a ledger entry. Nothing hands them back.

**~~Do first: split-point discovery.~~ DONE 2026-08-22.** `MarketClient.findSplitPoint`
answers the question nothing could: the last sequence number two chains still agree on.
A `HashQuery`/`HashReply` pair, a bracketing search that narrows by 24× a round, and
`EventLog.hashesAt` so a whole round costs one pass over the log rather than one per
probe — which is the trap the design note flagged and would have made the search
O(n log n) disk on the path that already annoys people with long logs. `splitPointTest`.

The FORK banner now reads *"you parted after event N, and everything either of you did
since is on one branch only"* rather than naming a point of disagreement that could have
meant four events or four hundred. `Divergence.splitAt` carries it, `-1` when it could not
be found out, and asking is a separate round trip so a failure costs the detail rather
than the refusal.

**~~Next: refund-only.~~ DONE 2026-08-22.** `BranchDiff.depositsOnlyAfter` works out what
a reset destroys that nothing can restore — items deposited since the split, which left a
Minecraft inventory and whose only record is about to be deleted — and `resetLog` queues
them back to the inventory. `X3`, `X3b`, and `E19` for the live half.

Bounded twice, because this ends in items appearing in a world and the only unacceptable
direction is too many: by **what went in since the split**, netted against withdrawals, so
pre-split holdings (which return with the shared history) and already-withdrawn goods are
never handed over again; and by **what the ledger still says they hold**, counting goods
reserved in resting sells, so somebody who deposited and then *sold* gets nothing — they
hold credits, the buyer holds the goods. It can under-refund and cannot over-refund.

**Then** a full rebase, if refund-only turns out not to be enough — and it may well be
enough, since items were the only part of a reset that was unrecoverable. Never a merge:
matching is order-dependent, so interleaving two branches produces fills nobody
experienced. The design note explains why at length.

**What is still lost to a reset:** credits earned since the split, and fills. Both are
ledger entries with no existence outside it, so "restoring" them means choosing an
arithmetic rather than returning a thing — which is the rebase question, not this one.

---

## 2. Log compaction — local snapshots

**Design: [`design/log-compaction.md`](design/log-compaction.md). Nothing built.**

The log only grows, and three paths walk all of it: world load, host start, and client
connect. All O(events).

Build **option A only** — a self-computed snapshot bound to the chain hash, never shared.
It costs no trust: you only load state you computed yourself from a chain you verified,
and any change below the snapshot point invalidates it. Do not build B (a host asserting
balances breaks the one invariant the signed chain exists for). Do not plan for C.

**Cost of not doing it:** opening a long-lived world gets slower every day. Bounded, and
it falls on people with successful markets, which is a good problem. Network sync is
already incremental, so this is local computation and disk, not bandwidth.

**Why it needs a whole session:** the failure mode is silent and financial. `MarketState`
holds thirteen fields including `TreeMap<Long, Deque<Order>>` books that reflective
serialisation will not round-trip, so it wants a hand-written serialiser plus a
round-trip equivalence test comparing every observable against a full replay, on a log
rich enough to include fills, cancels, migrations and a policy change. A serialiser bug
means wrong balances.

---

## 3. ~~`MAX_WELCOME_GRANT` is 1,000,000~~ — DONE 2026-08-22

Somebody hosting from their game now caps the welcome grant at
`ROTATING_MAX_WELCOME_GRANT`, **10,000**, against items that trade for 1 or 2. A
dedicated server keeps the compiled 1,000,000: it is the deployment that might
legitimately want a large one, set once by an operator who thought about it, rather than
one keystroke from a player wondering what would happen. `maxWelcomeGrant` in
`host-config.json` moves it either way.

**Not the change this entry originally described, and the difference is the point.**
Lowering `MAX_WELCOME_GRANT` itself was the obvious move and was blocked by exactly the
objection recorded here: it lives in `EventApplier.validate`, which is replicated, so any
market that had already set a larger grant would stop being able to replay its own
recorded policy.

It is a **host rule** instead, beside admission and the deposit caps. History stays valid;
only the next change is judged; nothing existing breaks. And it had to be a host rule for
a second reason that only appeared while building it — "rotating" and "dedicated" describe
whoever is hosting *right now*, so a ceiling that told them apart inside `validate` would
make one policy event legal on one host and illegal on the next, and hosting rotates by
design.

`R1b` covers the figures, `A9` the refusal reaching the wire.

---

## 4. No CI, and never built from a clean checkout

Every claim that this works rests on one machine, and the suites are only ever run by
somebody remembering to run them.

**Cost of not doing it:** unknown, which is the point. A missing file or a machine-local
dependency would not be noticed until somebody else tried to build.

Cheap to fix badly (a workflow that runs the eight suites), and the cheap version is
most of the value.

---

## 5. Splitting `MarketScreen`

4,293 lines. Five of the thirteen defects in the session log's §0 lived in it, on top of
three the session before.

**Split by component, never by layer.** Separating render from hit-test is the recurring
defect of §4 in the session log waiting to happen — it has already been the cause twice,
and a layer split makes it structural.

**Cost of not doing it:** every UI change is made in a file nobody can hold in their head.

---

## 6. Host rules on the screen, and what a world does with a config it cannot use

Two loose ends from §0.18 in the session log, kept together because they are the same
question asked twice: what happens to a host rule nobody is looking at.

**The UI half.** `/trade hostconfig` made host rules reachable; it did not make them
visible to somebody who never types a command. The two worth surfacing are
`maxWelcomeGrant` and `acceptsMigration`, because their defaults *do something* — a
player refused inline at 10,000 has no way to learn the figure is movable, and migrations
being on or off by deployment type is a rule with no switch on screen. Admission and the
deposit caps are fine where they are: they are "I have a problem with a specific person"
settings, and a person goes looking for those.

**Cost of not doing it:** a player who does not know the command is back where §0.18
started, and the command is discoverable only from a console line printed at host time.

Whoever does this should read backlog item 5 first. It means more of `MarketScreen`,
which is already 4,293 lines and already the file most defects live in.

**The refusal half.** A world hosting on an unusable `host-config.json` prints one line
and hosts on the friend-group defaults; the dedicated launcher exits. Deliberate for a
file that cannot be *read* — refusing "would strand somebody over a file they may not
know exists" — and arguably wrong for one that parsed fine and states a contradiction,
where the operator plainly knows the file exists and has written something impossible in
it. The failure mode is a host running with none of the rules its operator wrote.

**Cost of not doing it:** small and silent, which is the bad combination. One scrolled
console line stands between an operator and a market with no admission control.

`/trade hostconfig` asks the same `problem()` and answers in chat, which covers somebody
who thinks to check. It does not cover somebody who does not.

---

## 7. The split point is only found on one of the three paths that need it

**From §0.20 in the session log. `MarketClient.findSplitPoint` exists and works.**

**Two of the three are done.** `noteForkFromRefusal` has always supplied `splitAt`, and
`offerCatchUp` does now — see §0.22, where the AHEAD path cost a real run five items and
five entries on its re-place checklist, under a comment asserting the fallback was safe.
**The discovery poll is what is left**, and it is the one that runs without anybody
pressing anything.

So the banner somebody sees without pressing Connect is still the pre-`E18` one — *"differs
at event N"*, where N is the other side's head — and two clients polling each other show
two different numbers, neither of which is a split point.

It reaches past the banner. `depositsLostToReset` falls back to `divergence.seq - 1` when
the split is unknown. That is a true upper bound and a very loose one: against a
poll-sourced divergence at 52, on a pair of chains that parted at 22, it looks for
deposits after event 51 and finds nearly none.

**Cost of not doing it:** a reset started from the poll's banner refunds almost nothing
while the confirmation names what it will hand back. It errs the safe way — under-refunding
cannot create items, and that bound is deliberate — so this is wrong quietly rather than
dangerously. Bounded, silent, and financial in the way that matters to whoever loses the
items.

**It has already happened once**, on the sibling path, and nothing caught it: every number
printed was self-consistent and no test failed. §0.22 was found by comparing what the
console said went in against what it said came back. A guard that makes failure safe also
makes it silent, which is the argument for finishing this rather than leaving one third of
it.

**Why it was built this way, which is worth keeping.** The search is a second round trip
to a host that has just refused you, on purpose: a failure costs the detail and not the
refusal. The poll runs on a timer against every host it can see, so making it search every
cycle would turn one background poll into several.

The shape that fits both: find the split once per host and head, cache it against that
pair, and let the poll and the reset read the cache. A new head invalidates it, which is
the same rule the chain hash already uses everywhere else. Collapsing all three
constructions onto one path that knows the answer is the §4 move, and this is the fourth
entry in this file whose root is a fact known in one place and needed in three.

---

---

## Dropped

**Sub-unit prices** — denominating in hundredths so prices have room below 1. Dropped
2026-08-22 rather than deferred, so it stops being reconsidered every time somebody reads
this file. It treated a symptom the stipend already treats at the cause by putting a floor
under the money supply, and the price floor has never actually been hit by a real market.
If one is ever seen pressing against it, that is a new observation and this can be argued
again from it.

The constraint it carried is worth keeping wherever prices are touched: **integers, never
floats.** Java 8 without `strictfp` is not reproducible across platforms, and two replicas
that disagree in the last bit have forked.

## Not on this list

**Anything Group E covers.** Built, and now tested — see
[`testing/group-e.md`](testing/group-e.md), done 2026-08-22. It was "the thing to do
before any of this", and it has been done, so this list is now the next thing rather than
the thing after the next thing.

**A true fork merge**, **a host-asserted checkpoint**, and **a quorum checkpoint.** All
three are refused with reasons in the design notes above, and refused is a different
state from deferred. If any of them shows up in a future plan, read why it was turned
down before arguing with it.
