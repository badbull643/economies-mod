# Backlog — things deliberately not built

*Everything here was reached, understood, and put down on purpose. That is the point of
the file: the roadmap is finished, so there is no longer a document that says "this is
known about and not done", and without one an unbuilt thing is indistinguishable from an
overlooked one.*

*Ordered by what to do first, not by size. Each entry says what it costs to keep NOT
doing it, because that is the number that decides when it is worth a session.*

---

## 1. ~~Split-point discovery, and forked-market recovery~~ — DONE 2026-08-22, and the rest refused 2026-08-23

**Design: [`design/fork-rebase.md`](design/fork-rebase.md).** The note recommends
split-point discovery, then refund-only, then *"A, if C turns out not to be enough"*. Both
were built. C turned out to be enough, and the audit below is why the third step is
refused rather than deferred.

A friend group that all started on one host, later split into two groups, and both kept
trading, could not come back together without one side discarding everything it did since
the split. Migration is refused for the same market id — deliberately, in two places — so
the balance-preserving path was closed to precisely the people who share the most history.

**~~Do first: split-point discovery.~~ DONE 2026-08-22.** `MarketClient.findSplitPoint`
answers the question nothing could: the last sequence number two chains still agree on.
A `HashQuery`/`HashReply` pair, a bracketing search that narrows by 24× a round, and
`EventLog.hashesAt` so a whole round costs one pass over the log rather than one per
probe — which is the trap the design note flagged and would have made the search
O(n log n) disk on the path that already annoys people with long logs. `splitPointTest`.

The FORK banner now reads *"you parted after event N, and everything either of you did
since is on one branch only"* rather than naming a point of disagreement that could have
meant four events or four hundred. All three paths that detect a fork ask for it — see
item 7, which took two more sittings to finish.

**~~Next: refund-only.~~ DONE 2026-08-22.** `BranchDiff.depositsOnlyAfter` works out what
a reset destroys that nothing can restore — items deposited since the split, which left a
Minecraft inventory and whose only record is about to be deleted — and `resetLog` queues
them back to the inventory. `X3`, `X3b`, and `E19` for the live half, which ran correctly
on three separate forks.

Bounded twice, because this ends in items appearing in a world and the only unacceptable
direction is too many: by **what went in since the split**, netted against withdrawals, so
pre-split holdings (which return with the shared history) and already-withdrawn goods are
never handed over again; and by **what the ledger still says they hold**, counting goods
reserved in resting sells. It can under-refund and cannot over-refund.

---

### The rebase is refused, not deferred

*Audited 2026-08-23 against what a reset now actually costs. Refused is a different state
from unbuilt; read this before arguing with it.*

A rebase replays the losing branch's post-split events as fresh proposals. Taken event by
event, against the code as it now stands:

| What it would replay | Already handled |
|---|---|
| `Deposit`, `DepositAndList` | Items go back to the player's inventory |
| `PlaceOrder` | Listed as a checklist to re-place by hand |
| `CancelOrder` | Names an order that does not exist on the winning branch; a rebase drops it too |
| Fills | Cannot be replayed by anything — they are consequences, not proposals |
| Credits earned since the split | Cannot be restored by anything — see below |

**Two of those are impossible rather than unbuilt.**

*Credits earned are minting.* You earned them because somebody on your branch paid you.
On the surviving branch that person never paid, so crediting you invents money nothing on
the chain accounts for — which breaks the one invariant the signed chain exists for.

*Fills would be different fills.* Your sell rested at 7 because nobody on your branch was
buying; on theirs somebody has a bid at 7. The design note refuses a true merge for
exactly this reason and a rebase has the same problem in weaker form.

**So the whole remaining value of a rebase is automation** — replaying deposits and orders
without the player clicking. And for orders that is worse than what exists: an order
re-placed automatically into a different book **can fill immediately at a price nobody
agreed to**. The checklist makes that a decision; a rebase makes it a side effect of
recovery. The design note already says the reporting — telling somebody which of their
forty events became thirty-one, and why — is most of A's work, which is a session spent
on a mechanism whose own failure mode is trading on somebody's behalf.

### The one residual, recorded rather than fixed

Deposit something after the split, **sell it on the losing branch**, then reset. The
refund is capped by what the ledger says you still hold, and you hold credits, not goods.
So nothing comes back, the credits die with the branch, and those items are destroyed —
they left a Minecraft inventory and no history anywhere says they exist.

That is the only case left where a reset destroys something outside the ledger, and the
bound causing it is deliberate: if somebody else stayed on the losing branch and kept
hosting it, they still hold those goods in a live market, and refunding the seller would
duplicate them. Under-refunding cannot create items; the alternative can.

Worth knowing how reachable it is before anybody spends a session on it. Across every fork
run on 2026-08-22, **no post-split deposit was ever sold before the reset** — they either
rested or crossed the depositor's own pre-split bids. The gap is real and nobody has
walked into it.

**Never a merge.** Matching is order-dependent, so interleaving two branches produces
fills nobody experienced. See the design note, and the Not-on-this-list section below.

---

## 2. Log compaction — and what a long log actually costs

**Design: [`design/log-compaction.md`](design/log-compaction.md), rewritten 2026-08-23
against measurements. All five steps are resolved: 1 to 4 built, and 5 closed once it
turned out to be two memory faults rather than the trust question it was written as.**

**Read the design note rather than this entry.** What was here before said "build option A
only" and gave a cost model that measurement contradicts — it is summarised below only so
this file does not disagree with the note it points at.

The log only grows, and five call sites walk all of it: `loadLocal`, the `HostServer`
constructor, the `MarketClient` constructor, and `BranchDiff` twice — which `resetCost()`
calls twice more, so opening the reset dialog is four full walks from a UI thread.

**The measured plan, in order.** All five are resolved — 1 to 4 built on 2026-08-23, 5
closed on 2026-08-24. The first two were cheap, correct under every open question, and
absent from the original note.

1. **~~Stream `readFrom`~~ — DONE.** It materialised every event into an `ArrayList`
   before its caller saw one, about 1 KB of heap each. `EventLog.forEach` is the
   primitive now. The old code cannot open a 100,000-event market in a 128 MB heap at
   all — it dies with an `OutOfMemoryError`; the new code opens it in 64 MB.
2. **~~Hex encoding, then one pass instead of two~~ — DONE.** `String.format("%02x")` was
   45% of a load and a lookup table produces the same characters. The two byte-identical
   copies of the hash function are one. `EventApplier.verifyAndReplay` does in a single
   walk what the load path did in four. Measured end to end: **3.0×–3.9×**.
3. **~~The snapshot (option A)~~ — DONE.** `MarketSnapshot`, beside the log, keyed to log
   length and never to `dedicated`. A 100,000-event load went 4208 ms → 1135 ms → **190
   ms**, twenty-two times where this started. Note what it actually saved: not `apply`,
   which is 1% of a load, but verifying and parsing the prefix — and the last full pass
   left was the `EventLog` constructor finding its own head, which is deferred now.
4. **~~Snapshot-only clients on dedicated markets~~ — DONE.** A client of a market a
   dedicated server serves keeps a snapshot and stops writing history; `/trade archive on`
   makes this machine one of the copies that keeps the market alive. Three things it
   needed that the plan did not say: an empty log now validates a snapshot on its own
   authority (a short one still does not), a replica with no history is barred from
   hosting, and the archive question is asked about the host's market rather than ours —
   otherwise a first-time joiner archives everything, and they are who this is for.
5. **~~First-join onboarding at scale~~ — CLOSED 2026-08-24.** It was not a trust
   question. Both ends built the whole market in memory to move it — the host 63.6 MB for
   a 57.7 MB log, the client the same again in a Minecraft heap — so a large first join
   failed on memory long before anything about verification mattered. Both stream now,
   bounded by the chunk budget. What remains is a download, once, of a history a
   dedicated market's client then discards, and an interrupted one resumes. **Option B
   stays refused everywhere**, and this is closed rather than open so nobody re-argues it.

**Cost of not doing it:** 25,000 events is not a large-server number, it is a
market-that-lasted-a-season number — 83 days for fifteen people, 25 for fifty. Every
deployment that succeeds arrives there. At a hundred players the log reaches ~511 MB in a
year and stops being a speed problem at all.

**Why it needs a whole session:** the failure mode is silent and financial. `MarketState`
held thirteen fields when the note was written and holds **twenty-one** seven days later,
including `TreeMap<Long, Deque<Order>>` books that reflective serialisation will not
round-trip. A serialiser written then would silently drop eight fields today, one of them
the set that stops §0.11's unbounded migration. Hand-written serialiser, shape
fingerprint, and a round-trip equivalence test against a full replay on a log rich enough
to include fills, cancels, migrations and a policy change.

**What is open, and it is bigger than the feature.** Option B — a host handing out state —
stays refused for rotating mode, permanently. For a dedicated server it was never priced:
a new joiner either downloads and verifies ~511 MB before playing, or accepts the server's
state, and there is no third option. That is a trust-model decision nobody has made. Do
not treat B as refused across the board on the strength of the old entry.

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

## 4. ~~No CI, and never built from a clean checkout~~ — DONE 2026-08-23

`.github/workflows/tests.yml` runs the nine suites on every push, then builds. Two JDKs,
because Gradle 9 needs 17 or later to run itself and the mod compiles against 8 —
`setup-java` exports `JAVA_HOME_8_X64` and `org.gradle.java.installations.fromEnv` points
the toolchain straight at it rather than leaving Gradle to guess or to download one.

**The clean-checkout half needed a step of its own.** This repository tracks `build/` and
`.gradle/` — deliberately, since `.gitignore` was removed on purpose — so a fresh checkout
arrives carrying one machine's compiled classes, Loom caches and Gradle file hashes. They
are removed before anything is built, because otherwise the run proves that this machine's
build state still works, which is the thing already known.

**The port race was fixed first, and had to be.** Two nine-suite runs went red on
2026-08-22 with every check passing — the shape of failure that teaches people to ignore
CI. Eight suites each had their own copy of `new ServerSocket(0)`, which opens a port,
closes it, and hands the number to a HostServer to bind; port 0 draws from the *ephemeral*
range, which is the same range the operating system hands out for outgoing connections,
and these suites make a great many of those. `TestPorts` draws from 20000–30000 instead,
remembers what it gave out, and probes a candidate the way the server will bind it. Three
consecutive full runs, all green.

**What is not covered**, and is worth knowing before trusting a green tick: nothing here
launches Minecraft, so every live item in `docs/testing/group-e.md` is still a person at a
keyboard. Four of the defects in the session log's §0 were geometry — a list drawn past
the bottom of its box — and no suite here would have seen any of them.

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

## 7. ~~The poll cannot tell a longer chain from a different one~~ — DONE 2026-08-22

All three paths that build a `Divergence` supply a split point now. `noteForkFromRefusal`
always did; `offerCatchUp` was given one in §0.22 after the AHEAD path cost a real run
five items and five entries on its re-place checklist; and the discovery poll asks in
§0.24.

**The poll's version needed a question nobody could ask before.** A probe carries a head
and nothing below it, so a peer *above* us could not be compared with at all — the poll
held no opinion about whether we had forked, and recorded their head as this market's
height regardless. `MarketClient.hashAt` asks one `HashQuery` for their hash at **our**
head: matching means they extend us, so the height is real and "behind" is true; not
matching means a fork, the height is not ours to record, and the split is worth finding.

**The cost that kept it out is paid by a cache that already existed.** `checkedHeads` was
keyed by (peer, head) and returned early when neither had moved, so a peer sitting still
costs nothing — the ordinary case for a poll on a timer. And the split point is looked up
before it is searched for, because it does not move: two branches that have parted stay
parted and both only grow.

**What it was worth, which was more than the banner.** `eventsBehind` gates Host, and a
height recorded from a forked peer told the participant on the chain everybody else shared
that hosting it would split the market — then advised catching up from a peer who would
refuse them. The warning built to prevent a fork was fired by one, at the only person who
had not caused it.

§0.25 came out of the same change: `observeMarketHeight` was called before the market id
was checked, and `MarketHighWater.observe` starts a fresh record when handed a different
id — so polling any host serving a different market silently zeroed the watermark, which
is the one thing meant to survive everybody being offline.

`splitPointTest` covers `hashAt`; `E22` is the live half, and it is the half that matters,
because all of this is about what happens between two machines.
---

## 8. Host rules a group can agree once — as defaults, never as enforcement

**Decided 2026-08-23, after the question "should they travel?" was asked properly.**
The answer is no, and the distinction is the whole entry: *travelling* means whoever hosts
is made to enforce them, *agreeing once* means the group wrote them down and each host
starts from that. Only the second is worth building, and it is much the cheaper.

**Why not travelling.** Three separate blockers, and any one of them is enough:

- **Time-windowed rules have no clock.** `maxDepositUnitsPerWindow` is "N units per 60
  minutes", and a replicated rule must be a deterministic function of the log — so the
  only clock available is the event's own `timestamp`, which is set **client-side** before
  signing. Replicating it means rate-limiting somebody against a clock they control.
- **Some rules judge evidence that is not in the log.** Attestation, `refuseCreativeWorlds`,
  `refuseCheatWorlds`, `maxDepositMultipleOfHandled` and the play-hour rule all weigh an
  out-of-band claim about a player's Minecraft world. No replica can re-verify a claim it
  never received.
- **And item 3 already paid for the third.** A rule whose default differs by deployment
  type makes one policy event legal on one host and illegal on the next, which forks the
  market the moment hosting rotates.

**The reframing that settles it.** Host rules are a host's defence against its *clients*,
not the market's defence against its *host*. Making them travel adds no security, because
the party enforcing them is the party who could ignore them either way — a dishonest host
already ignores its own deposit caps. What travelling would add is consistency, which is a
usability property, and the trust model should not be reshaped to buy one.

**What to build.** A record in the log that the creator can publish and that each host
reads and adopts as its starting configuration, with local override still allowed. Nothing
in `EventApplier.validate` changes, no replica has to agree about validity, and nothing can
fork. A friend rotating in picks up the group's caps instead of having none, which is the
actual failure today.

**The subset, and the line.** Deposit caps, `acceptsMigration`, `maxMigratedCredits`,
`maxWelcomeGrant`, admission. **Not** attestation, the world checks, or a ban — those are
personal by nature: "I do not want this person on my machine" is a different decision from
"this group excludes them", and forcing the first to mean the second is heavier than it
looks.

**Cost of not doing it:** a group's economy is only as protected as its most permissive
host. A cap that applied on Tuesday and not Wednesday capped nothing, because the goods
deposited on Wednesday are in the ledger for good. Rotating to somebody who never opened
the file is enough, and nothing warns anyone that it happened.

**Two things to get right, both already known.** §7's trap sits right next to this: *"A
`MarketPolicy` event is the whole policy. Anything it does not restate is set to zero"* —
which silently wiped the stipend once. Anything policy-shaped needs `submitPolicy`'s
build-from-current-state treatment from the first commit, not after it bites.

And the cheaper alternative deserves stating, because any design here should beat it:
`host-config.json` is a file in a world directory, and a group could simply share it.
Nobody will — which is the same reason `/trade hostconfig` had to exist at all — but that
is the baseline.

**Below items 5 and 6 in value.** It is a session's work, and unlike them nothing is
currently broken by its absence: what it prevents is a slow leak nobody notices, rather
than something a player can walk into this evening.

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

**A rebase of a forked branch**, as of 2026-08-23, for the same treatment and a newer
reason: refund-only turned out to be enough. Everything a rebase could still restore is
either a ledger entry with no existence outside it, or a trade the player never made. The
audit is under item 1 and the one residual it leaves is named there.
