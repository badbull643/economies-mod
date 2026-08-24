# Recovering a forked market

*Design note. Nothing here is built. Written after someone asked what happens when a
friend group that all started on one host later splits into two, both keep playing, and
then tries to come back together — which turns out to be the one case the recovery
machinery does not cover.*

## The problem

A market's id is fixed at genesis and never changes, so both halves of a split keep it
forever. That closes the only balance-preserving path the project has:

```java
// HostServer.handleMigrate
if (foreign.state.marketId().equals(state.marketId()))
    reply.reason = "that is this market, not a different one";

// EventApplier.validate
if (mb.fromMarketId.equals(state.marketId()))
    return Result.reject("cannot migrate a market into itself");
```

A group that never shared a market can migrate and keep everything. A group that split
from one cannot. The better-off case is the one with less shared history, which is
backwards.

What happens today:

| Situation | Detected as | Outcome |
| --- | --- | --- |
| Only one side traded | `AHEAD` → `offerCatchUp` | Fast-forward. Nothing lost. |
| Both sides traded | `FORK` | One side resets. Log file deleted. |

The second row is the default outcome of a group that split and kept playing, because
nothing enforces a single writer — hosting rotates by design.

**What a reset actually costs.** Everything after the split: every fill, every credit
earned, and every item deposited since. That last one destroys something real — those
items left the player's Minecraft inventory when they deposited them, and nothing hands
them back. What survives is everything before the split, which is in the other copy too,
plus `ordersLostToReset` snapshotting resting orders into a re-place checklist.

The UI's *"you keep everything from before you diverged; only what you did afterwards is
lost"* is accurate. "Only" is carrying a lot of weight if the split lasted three weeks.

## What the current code already gives us

Four things, all verified rather than assumed, and together they make a rebase far more
feasible than it first looks:

- **Signatures do not cover the sequence number.** `EventCanonical.canonicalPayload`
  builds from type, author, market, `clientEventId`, timestamp and the event's own
  fields — not `seq`. So an event can be re-sequenced and re-chained with its signature
  intact.
- **The host does not require a proposal to be authored by the proposer.** It verifies
  the signature against the log's key directory and nothing else. So one party can relay
  another player's events verbatim, which a rebase has to be able to do — the alternative
  is needing every member of the losing branch online at once.
- **`processProposal` already dedups `clientEventId`**, via `seenEventIds`. Read the
  declaration before relying on it: it is in-memory, per-session, *and* an LRU capped at
  `DEDUP_CACHE_SIZE = 10_000`, so it evicts. It stops the obvious accident and is not a
  correctness guarantee — a rebase of more than ten thousand events would push its own
  earliest ids out of the cache.
- **`CatchUp` already does the safe half.** It appends offered events that chain onto the
  head, verify, and validate — which is exactly the last step of a rebase, minus the
  re-sequencing.

## What it must respect

1. **`EventApplier` is the only thing that mutates `MarketState`.** So a recovery has to
   be *events*, proposed and validated like any other. Not a state edit, not a balance
   transfer computed by whoever is hosting.
2. **Nobody trusts anybody's arithmetic.** Every rebased event has to pass `validate` on
   its own merits against the branch it is landing on. Anything that does not, does not
   land — that is the whole safety property, and it is why some loss is unavoidable.
3. **No event may apply twice.** Which means the split point has to be exact, not
   estimated. Guessing low re-applies events both branches already have.

## Options

### A. Rebase — replay the losing tail as fresh proposals

Take the losing branch's events after the split point and re-propose them in order to the
winning host. Each goes through the ordinary path: verify, validate, append. What no
longer makes sense is refused and dropped — a cancel of an order that never existed on
this branch, a sell of items this ledger does not show.

- **Preserves** deposits, which is the loss that destroys something outside the ledger;
  registrations; and credits from fills that still happen.
- **Cannot preserve** anything whose preconditions changed — most obviously a fill that
  depended on a counterparty order that is not here.
- **Needs the split point**, which the protocol cannot currently find. See below.

The real risk is that "dropped" is silent. Somebody whose forty events rebase to
thirty-one needs to be told which nine and why, or this becomes a second way to lose
things without noticing. That reporting is most of the work.

### B. True merge — interleave both branches

Not available, and not for want of trying.

Matching is order-dependent, so interleaving produces fills that neither side
experienced: a trade that filled on branch A may not fill in the merged ordering, and
every balance downstream of it changes — potentially below zero. Order IDs are derived
from the sequence number (`new Order(se.seq, …)`) and `CancelOrder` references them, so
re-sequencing breaks those references as well.

"What everyone experienced" is not reconstructible from two branches. There is no merge
here, only a choice of whose history stands.

### C. Refund-only — return what the reset would destroy

Much smaller than A. Before deleting the log, walk the losing branch's tail for this
player's own deposits and hand those items back to their inventory. Forget everything
else.

Captures most of the *felt* loss, because items are what players notice and the only
thing a reset destroys outside the ledger. Needs the same split point as A — refunding
from a guessed point hands back items the winning branch also holds, which is minting.

### D. Prevent the fork — a single writer

Nothing stops two hosts serving one market. A lease or leader election would, and it is
the wrong shape for this project: hosting rotates precisely because people come and go,
and a lease needs somebody online to hold it.

`observeHostHead` is the affordable version and already exists — it compares our hash
against every host the discovery poll reports at the same market and raises the FORKED
banner as soon as the split is visible. Detection, not prevention, which is the right
trade here.

## Split-point discovery, which everything needs

Currently impossible, and deliberately so. From `noteForkFromRefusal`:

> The split is somewhere at or before our head, and locating it would need hashes below
> that point which neither side sends, so `ordersOnlyAfter` is handed our head and
> correctly finds nothing. That errs the safe way: a split point guessed too low would
> offer back orders the host still holds, which is how a reset creates duplicates.

So step one is a protocol addition — exchange hashes at intervals, or binary-search, to
find the last sequence number both chains agree on. A message type and a loop, in the
region of O(log n) round trips.

One trap: `EventLog.hashAt(seq)` exists but calls `readFrom(seq)`, which reads and parses
the **whole file** every time. Binary-searching with it is O(n log n) disk, on the path
that already annoys people with long logs. Read the chain once into a seq→hash index and
search that, or have the responder send hashes at a stride and refine from there.

Worth doing on its own merits even if neither A nor C is ever built: it would turn the
FORK banner from *"you disagree somewhere at or before here"* into *"you diverged N
events ago, and here is what you did since"*, which is the difference between a warning
somebody can act on and one they can only worry about.

## Recommendation

**Split-point discovery first.** Both A and C need it, neither can start without it, it
is small, and it improves the existing banner by itself.

**Then C.** It returns the items, which is the only part of a reset that destroys
something the ledger cannot recreate, and it is a fraction of A's work.

**Then A, if C turns out not to be enough.** It is a session of its own, and most of that
session is spent on telling somebody clearly what was dropped rather than on the replay.

**Never B.**

### What happened — 2026-08-23

Discovery and C were built on 2026-08-22, in that order, and both are in use. **C turned
out to be enough, so A is refused rather than deferred**; the audit is under backlog
item 1 and the short of it is that this section under-sold its own second paragraph.

The sentence *"the only part of a reset that destroys something the ledger cannot
recreate"* is doing more work than it looks. It is not a ranking, it is an exhaustive
list: everything else A could replay is a ledger entry, and a ledger entry restored onto
a chain that never recorded it is either minted money or a trade nobody made. So A was
never going to recover the two things this note describes it as preserving — it was only
ever going to re-propose the deposits and orders, which C hands back and the re-place
checklist lists.

And for orders, doing it automatically is worse than a checklist: an order replayed into a
different book can fill at once, at a price nobody agreed to. This note flagged the
silent-drop reporting as A's real cost. The trading-on-somebody's-behalf risk is the one
it missed, and it is the larger of the two.

## What is not the problem

Detection — `observeHostHead` already surfaces a fork passively, early, without anyone
having to try to reconnect. The gap is entirely in what you can do about it once you
know.
