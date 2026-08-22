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

**Do first:** split-point discovery. Small — `EventLog.hashAt` exists, so it is a message
type and a binary search — and useful alone, because it turns the FORK banner from "you
disagree somewhere at or before here" into "you diverged N events ago". Both recovery
options need it and neither can start without it.

**Then:** refund-only (return the deposited items, forget the rest), then a full rebase
if that is not enough. Never a merge — matching is order-dependent, so interleaving two
branches produces fills nobody experienced. The design note explains why at length.

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

## 3. `MAX_WELCOME_GRANT` is 1,000,000

Against items that trade for 1 or 2. Four orders of magnitude nobody needs, and it is the
number every migration problem is denominated in — the ceiling is what a market can hand
somebody to carry somewhere else. Lowering it to something like 10,000 would bound that
whole family at once without adding a rule anywhere.

**Cost of not doing it:** every cap and interlock downstream has to be sized against a
number far larger than any real market uses.

**Why it is not done:** it is a balance change rather than a repair, and it has a nasty
edge — any existing market that already set a grant above a new ceiling would have
`validate` refuse its own recorded policy on replay, making that market unopenable. Needs
a grandfathering decision before anyone touches it.

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

## 6. Sub-unit prices

Denominate in hundredths as integers, never floats — Java 8 without `strictfp` is not
reproducible across platforms, which would fork replicas. Gives roughly 100× headroom
before the integer price floor of 1 starts destroying the price signal.

**Cost of not doing it:** low, and lower than it was. This treats the symptom; the
stipend now addresses the cause by putting a floor under the money supply. Reach for it
only if a real market is seen pressing against the floor.

---

## Not on this list

**Anything Group E covers.** Built, and now tested — see
[`testing/group-e.md`](testing/group-e.md), done 2026-08-22. It was "the thing to do
before any of this", and it has been done, so this list is now the next thing rather than
the thing after the next thing.

**A true fork merge**, **a host-asserted checkpoint**, and **a quorum checkpoint.** All
three are refused with reasons in the design notes above, and refused is a different
state from deferred. If any of them shows up in a future plan, read why it was turned
down before arguing with it.
