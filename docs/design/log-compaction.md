# Log compaction and snapshots

*Design note. Written 2026-08-16, when nothing was built and nothing had been measured.
**Revised 2026-08-23**: the cost model was measured and is wrong, the recommendation
changes because of it, and the deployment question the original never asked turns out to
matter more than compaction does. The refusals of C and D still hold. The refusal of B
holds for one deployment and was never priced for the other.*

*Where this note disagrees with `BACKLOG.md` item 2, this file is right and that entry
has been brought into line.*

---

## The problem

The event log only grows, and every replica derives its own state by walking it. Five
call sites do it — `MarketStateHolder.loadLocal`, the `HostServer` constructor, the
`MarketClient` constructor, and `BranchDiff` twice, which `resetCost()` calls twice more,
so opening the reset dialog is four full walks from a UI thread.

That was the whole of the original problem statement, and it was reasoned about without a
single number. What follows is measured, on this machine, against chains built through
`EventLog.append` so they verify like real ones. Method is at the bottom; the numbers
below decide everything else in this note.

### Where a load actually goes

At 50,000 events — 1,132 ms today:

| Phase | Cost | Share |
| --- | --- | --- |
| `String.format("%02x")` in hex encoding | ~511 ms | 45% |
| Parsing, **twice** — `verifyChain()` then `replay()` | ~500 ms | 44% |
| `gson.toJson` inside the hash | ~82 ms | 7% |
| SHA-256 itself | ~24 ms | 2% |
| **`apply` — rebuilding `MarketState`** | **~12 ms** | **1%** |
| (reading the bytes off disk) | ~14 ms | 1% |

Cost is linear at about 23.4 µs/event from 1,000 to 100,000, so anything here scales by
hand.

### The thing this note was written to fix is one percent of it

**`apply` is 12 ms of 1,132.** A snapshot's win does not come from skipping the
reconstruction of `MarketState`; it comes from skipping the *verification* of the prefix.
The original recommendation rested on the unexamined assumption that rebuilding state was
the expensive part, and it is the cheapest part by a factor of forty.

Two consequences, both of which come before any snapshot:

**The hex encoding is 79% of hash cost and produces identical output.**
`String.format("%02x", b)`, called 64 times per event, is 511 ms of the 649 ms hashing
costs at 50k. Replacing it with a lookup table and reusing one `MessageDigest` was checked
to produce byte-identical hashes, so no existing log is invalidated. Note that
`computeHash` and `recomputeHash` in `EventLog` have **byte-identical bodies**, written
out twice — §4 in the one place it can do most damage, since fixing one and not the other
yields a chain that fails to verify against itself. Collapse them before touching either.

**Verification and replay each parse the whole file, separately.** `loadLocal` calls
`verifyChain()` and then `replay()`, so every load parses twice. One pass does both.

**Built 2026-08-23, and the real path was worse than the table above.** That table times
`verifyChain()` + `replay()`. A world load also constructs the `EventLog` — which walked
the whole file to find its last entry — and then called `damageReason()`, which verified
a second time. Four walks, not two. Measured end to end, opening a world:

```
 events        before         after     speedup
  10000      443.4 ms      146.6 ms       3.0x
  50000     2306.5 ms      596.1 ms       3.9x
 100000     4208.6 ms     1157.7 ms       3.6x
```

Thirty lines or so, no new file, no new trust surface, and no failure mode that can
produce a wrong balance.

### The memory cost, which nobody had looked at

`readFrom` materialises every event into an `ArrayList` before anything consumes it.
Measured at **720 bytes of heap per event** — and the synthetic log's signatures are 108
characters against a real log's 369, so a real log costs about **1 KB per event, retained
in a single allocation**.

A 730,000-event log is therefore a **~700 MB spike inside a Minecraft client**, on every
world load and every connect. Transient rather than a leak, but unlike a slow load it
fails hard.

**Fixed 2026-08-23** — `EventLog.forEach` walks one entry at a time and `readFrom` is that
plus a list, so nothing on a load path materialises the log any more. The demonstration is
better than the arithmetic: loading a 100,000-event log

```
  -Xmx256m    before 4931.9 ms      after 1180.8 ms
  -Xmx128m    before OutOfMemoryError    after 1278.1 ms
  -Xmx64m     before OutOfMemoryError    after 1299.7 ms
```

The old code cannot open that market in a 128 MB heap at all; the new code opens it in 64.
No format, no file, no trust question.

### What is not a bottleneck

**Append throughput is 2,408 events/sec sustained** through the sequencer and its single
write lock. The busiest deployment described below produces about 11,000 events *per day*.
The lock added in §0.9 is nowhere near binding and can be dropped as a worry.

---

## Scale, and why deployment mode is not the axis

Rotating-host mode is intended for friend groups of roughly 2–15; the dedicated server is
intended for larger groups, **and either can be used for either**. Population is therefore
not a property of deployment mode, and nothing here may key off `dedicated` merely to find
out how big a market is.

Measured from the largest real log in `run/saves` — 174 events over 1.58 hours of
two-client play, about **110 events/hour**, or 55 per player-hour at a pace where the
players are doing nothing but trading. The tables use 10 events/player-hour as an ordinary
rate, at 2 h/day.

**Days for a market to reach 25,000 events:**

| People | @10 ev/player-hour | @55 (heavy) |
| ---: | ---: | ---: |
| 2 | 625 | 114 |
| 5 | 250 | 45 |
| 8 | 156 | 28 |
| 15 | 83 | 15 |
| 50 | 25 | 4.5 |
| 100 | 12 | 2.3 |

**25,000 is not a "large server" threshold. It is a "market that lasted a season"
threshold**, and every deployment that succeeds arrives at it — the dedicated server just
gets there first. A fifteen-person friend group playing hard crosses it in a fortnight.
The reason no market here is near it is that the oldest is 174 events, which is a young
market rather than a small group.

**After one year, at 10 events/player-hour:**

| People | Events | Log on disk | Load, post-fix |
| ---: | ---: | ---: | ---: |
| 15 | 110k | 77 MB | 0.8 s |
| 50 | 365k | 256 MB | 2.7 s |
| 100 | 730k | 511 MB | 5.5 s |

At the bottom of that table the log has stopped being a speed problem and become a size
problem, and **a snapshot does not touch the size problem at all.**

### Where the bytes are, since shrinking them keeps being proposed

Of a real ~810-byte line, the RSA-2048 signature is **369 bytes (46%)** and
`hash` + `prevHash` another 150 (18%). Two-thirds of the log is crypto material.

- **gzip buys 2.2×**, measured on the real log (140,843 → 63,720). Not more: the
  signatures are high-entropy base64 and do not compress. An early measurement of 6.9× on
  a synthetic log was an artefact of reusing one signature string on every line, and is
  recorded here so nobody repeats it.
- **Ed25519** signatures are 88 base64 bytes against 369, which would take roughly a third
  off the log. That is a migration of every key and every existing history, and is named
  here as the standing cost of the current choice rather than as a proposal.

---

## What a client's local copy is actually for

Asked directly — *why does a client of a dedicated server need anything?* — and the answer
is three separate things that have been treated as one.

**1. The market outliving its hardware.** `dedicatedServesThisMarket()` is **live-only**:
true while connected to a dedicated host, or while one serving this market sits in the
discovery list. When the box stops, both sources go false and Host un-greys. So every
client replica is a disaster-recovery copy, and a hundred of them make a dedicated market
effectively un-killable. This is real, deliberate, and the strongest argument for the
local log — and a dedicated market with no surviving replica may not even be re-creatable
with the same policy, because of the `--creator-key` trap in the session log's §7.

**2. The host cannot fabricate a player's actions.** This comes from events being *signed
by their author*, not from the client replaying anything, and it survives whatever the
client chooses to store.

**3. The host cannot forge balances undetectably.** The only one of the three that
genuinely requires local derivation.

**To play, a client needs essentially nothing** — a snapshot and its head hash. The full
log is carried for (1) and (3).

Which suggests the shape of the answer for large deployments: you do not need a hundred
copies to keep a market alive, you need a few. `MarketArchive` already exists for this and
already verifies a history properly rather than trusting a stranger's file, since §0.2. So
a client on a dedicated market can keep a snapshot by default and the full log **only if
it opts in** — the operator's own backup, plus the handful of players who choose to be the
market's archive. Playing then costs kilobytes and the market still outlives the box.

---

## Options

### A. Local snapshot — self-computed, never shared

**Still the right thing to build, for reasons other than the ones originally given.** It
is worth much less than assumed to a friend group and much more to a large server, and
what it saves is verification of the prefix, not reconstruction of state.

Three corrections to the original description:

- **`hashAt` is O(n) and parses the whole file** — 245 ms at 50k events. The snapshot's own
  validity check, as specified, would eat most of the win. It must seek to its line.
- **`MarketState` had thirteen fields when this note was written and has twenty-one
  today**, seven days later. A serialiser written on 2026-08-16 would now be silently
  dropping `migratedIn`, `withdrawn`, `fillsEver`, `stipendedAtFill`, `listingFreeOrders`,
  `stipendAmount`, `stipendEveryFills` and `listingFee` — and `migratedIn` is the set that
  exists to stop §0.11's unbounded migration. That is the drift rate, measured, and it is
  the real risk in this feature.
- **The record must carry enough of itself to be checked.** §0.26 produced three defects in
  a nine-line class from a record of `{marketId, seq}`, all of them "a fact stored without
  enough of itself to be checked later." A snapshot is the same species. It needs
  marketId, seq, the chain hash at seq, **and a shape fingerprint**.

**The shape fingerprint is the answer to the drift problem.** Rather than trusting anyone
to bump a version when they add a field, compute a fingerprint by reflecting over
`MarketState`'s declared fields — names and types, sorted, hashed — and refuse any snapshot
whose fingerprint differs. Adding a field then invalidates every old snapshot
automatically, and the failure mode becomes a slow load instead of a wrong balance.
Reflection for the fingerprint only; the serialiser stays hand-written and explicit. It
will not catch a field whose meaning changes without its name, nor a new field inside
`OrderBook` unless the walk recurses into the nested state types — both worth doing.

Placement: beside the log at `MarketSlots.logPath(worldDir, slot)`. **`resetLog` must
delete it**, exactly as it already deletes the sibling `known-keys.json`. The chain-hash
binding makes a missed deletion safe rather than catastrophic, which is why that binding is
load-bearing and not an optimisation.

### B. Shared checkpoint — a host hands out state

**The blanket refusal was right for one deployment and was never priced for the other.**

The original argument stands unchanged where it was aimed: in rotating mode the host is
whoever happens to be up, hosting rotates by design, and trusting a host's arithmetic means
trusting all of them in turn. **Refused for rotating mode, permanently.**

For a dedicated server it is a different question, and this note never asked it. A new
joiner has exactly two options and there is no third:

- download and verify the whole history before playing — 511 MB on a year-old
  hundred-player market; or
- accept the server's state and play immediately, which **is** B.

What makes the trade different on a box somebody deliberately chose to join: the operator
already controls admission, deposit caps, attestation and migration, and can censor or stop
at will; signatures still prevent fabricated *actions*, so an operator could inflate a
number but could not make it look like a player sold something; and detection stays
available to anyone who pulls the archive, which is reputational enforcement rather than
none.

That does not make B correct. It makes the refusal too blunt — the right call for a friend
group, and an unpriced one where its cost is a 511 MB turnstile in front of every new
player. **This is a trust-model decision and it is open.** It is the largest question in
this note; log compaction is the smaller half of it.

### C. Quorum checkpoint — N replicas sign the same state root

**Refused, unchanged.** There is no membership model, no quorum, and no expectation that
anyone in particular is online. The cross-poll divergence check is the nearest available
thing and is already doing the useful half.

### D. Prune what is provably derivable

**Refused, unchanged**, and now with a number: in a busy market most events are balance
movements, and two-thirds of each line is crypto material that pruning does not touch.

---

## The plan

Ordered by what to do first. Items 1 and 2 are correct under every possible answer to the
open question in B, and depend on nothing else here.

**~~1. Stream `readFrom`.~~ DONE 2026-08-23.** `EventLog.forEach` is the primitive and
`readFrom` is it plus a list. The constructor, `verifyChain`, `hashAt`, `hashesAt`,
`headSeqOnDisk`, `genesis`, `rawLineFor` and the replay all stream now.

**~~2. Hex encoding, and one pass instead of two.~~ DONE 2026-08-23.** The two
byte-identical hash bodies are one; the hex table replaced `String.format`;
`EventApplier.verifyAndReplay` does in one walk what `verifyChain` + `damageReason` +
`replay` did in four.

*What proves it did not change the log format:* the 337 events already on disk in
`run/saves`, all written by the previous build, verify unchanged — including one world
whose chain was already broken, which is still reported broken at the same seq. And
`coreTests` L7 pins the hash of a fixed event to a literal, because **every other suite
writes its logs with the build that reads them**, so a hash that changed would agree with
itself everywhere and pass all of them while invalidating every market on disk. Verified
by breaking it: uppercase hex fails L7 and nothing else in 597 checks notices.

L8 pins `verifyAndReplay` against verifying and replaying separately, on a whole chain and
a tampered one — including the awkward part, that a broken chain does not stop the replay.
L9 pins the streaming walk against the list, and its early stop, which turns out to be
load-bearing for correctness and not only speed: with the stop ignored, `verifyChain`
reports the *last* bad entry rather than the first.

**~~3. Snapshot (A).~~ DONE 2026-08-23.** `MarketSnapshot`, written beside the log, keyed
to log length and never to `dedicated`. Loading a 100,000-event market:

```
  before this session                4208.6 ms
  after the two cheap fixes          1135.4 ms
  with a snapshot                     189.9 ms      (22x on where this started)
```

The snapshot file is 6.5 MB against a 57 MB log.

*Three things that were not obvious while building it.*

**The remaining cost was the `EventLog` constructor**, which walked the whole file to find
its last entry — so the first attempt at this measured 1164 ms against 1157 ms and saved
nothing at all. Finding the head is deferred now, and `EventApplier.load` tells the log
where it ends, because the walk it just did already knows. That is the safe direction of
the trap in `lastSeq()`: the head and the state come from one walk and cannot disagree.

**Skipping is not the same as filtering.** `forEach(N+1, …)` still parses every entry
below N to find out it does not want them. `forEachAfter(N, …)` counts lines instead —
entry N is line N on a chain that verifies — and checks that assumption on the entry it
lands on, falling back to the honest walk when line numbers and sequence numbers have
parted company.

**Making the head lazy broke two things that had quietly depended on it**, and only the
suite said so: `verifyChain` returned -1 for a log with an unparseable line, because the
flag it checks had always been set by the constructor beforehand. `isUnreadable()` still
reads the file so its answer means what it says; the load paths ask
`unreadableAtSoFar()`, which reports what their own walk found without starting another.

*What holds it down.* `coreTests` L10 builds a log carrying a partly-filled resting order,
two orders queued at one price, a cancel, a withdrawal, a policy change, a migration and a
stipend, then compares **every observable** of a restored market against a full replay —
built from the public questions rather than from the collections the serialiser reads, so
a dropped field shows up rather than being omitted twice. Verified by breaking it: dropping
`withdrawn`, `migratedIn` or `fillsEver` each fails it, and so does reversing the queue
order within a price level — which is the silent one, since it changes who gets filled
first and nothing else. L11 covers a re-chained edit, a truncated log, a foreign shape and
a corrupt file; L12 covers the fingerprint. Every one was verified failing with its check
disabled.

And the fingerprint does what it is for: adding a single field to `MarketState` moved it
from `6400af1f2bdcd673` to `7b603358f24ca00e`, which discards every snapshot written
before it.

**4. Snapshot-only clients on dedicated markets, with opt-in archiving.** *Half done
2026-08-23.*

**The half that needed no decision is built:** `MarketClient` goes through
`EventApplier.load` like the other two load paths, so a connect no longer rebuilds the
market from the beginning. This is the path a snapshot is worth most on, because it is the
only one that runs every session rather than once per world — on a market with a dedicated
server, every player pays it every time they join. It verifies now as well, which it did
not; the verdict is deliberately not acted on, since each event was validated as it
arrived and a break there is disk damage rather than dishonesty.

**The half that is left is the decision**: whether a client of a dedicated market should
hold the log at all, or keep only a snapshot and leave archiving to whoever opts in. The
`persist` flag already exists and is already the lever — it is `true` for every remote
connect and `false` only for a host's self-connect. Two cautions:

- It must be gated on **the same predicate that greys Host**. "Can I ever host this market"
  and "do I keep its log" are one question, and asking it twice is §4 — which §0.19 counts
  four defects from already, in this exact split.
- `hostIsDedicated` is **self-reported and only knowable after connecting**, so a lying host
  could claim dedicated to stop a client keeping records. The client still holds its
  snapshot and verified head hash, so divergence stays detectable, but this deserves the
  same audit B got rather than a shrug.

**5. First-join onboarding at scale.** The open question in B. Nothing above solves it, and
at a hundred players it is the largest cost in the system.

---

## Refused, deferred, and open

- **Built:** steps 1, 2 and 3, on 2026-08-23.
- **Refused:** C, in this architecture. D. B **for rotating mode**.
- **Deferred:** nothing. Everything not refused is in the plan.
- ~~**Known and left alone:** `MarketClient`.~~ **Done 2026-08-23.** It goes through
  `EventApplier.load` like the other two, so the one load path that runs every session
  rather than once per world gets the snapshot as well. It also verifies now, which it
  did not; the verdict is deliberately not acted on, because each event was already
  validated as it arrived and a break there is disk damage rather than dishonesty.
- **Also known:** a snapshot is written on load and not while a session runs, so a long
  sitting still replays its whole tail next time. `STRIDE` bounds that at 5,000 events,
  which is under a tenth of a second.
- **Open, and genuinely undecided:** B for a dedicated server — what a brand-new player
  does in front of a large market they cannot afford to verify. Also whether the log's
  storage format is worth revisiting, given that two-thirds of it is signature and hash.

---

## Reproducing the measurements

All of the above came from a scratch harness rather than anything in the tree, which is the
weakness of this section: nothing in CI will notice if these numbers rot. Building the
harness into the suites as a reported benchmark is worth doing alongside item 3, since that
is the item whose entire justification is a number.

The method, for whoever redoes it:

- Build chains through `EventLog.append` so they verify like real ones — a genesis, a key
  registration, welcome grants, then repeating deposit / crossing ask / crossing bid /
  resting ask so the log carries real fills and the books keep depth. Sizes 1k to 100k.
- Time `verifyChain()` and `replay()` separately, then the phases within them, discarding
  the first iteration as warm-up.
- For heap, force two collections either side of `readFrom(0)` and difference
  `totalMemory() - freeMemory()`.
- **Use realistic signature lengths.** A synthetic log with one repeated signature string
  understates line size by roughly 40% and overstates gzip by 3×. Both mistakes were made
  here first.
