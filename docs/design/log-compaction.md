# Log compaction and snapshots

*Design note. Nothing here is built. The roadmap lists this as the hardest remaining
item and asks for a design pass before code; this is that pass, and its conclusion is
that most of what "compaction" usually means cannot be built without giving up something
the project is for.*

## The problem

The event log only grows. Three runtime paths walk all of it:

| Path | Work | Where |
| --- | --- | --- |
| World load | `verifyChain()` then `EventApplier.replay()` | `MarketStateHolder.loadLocal` |
| Host start | same | `HostServer` constructor |
| Client connect | `replay()` of the local log | `MarketClient` constructor |

All three are O(events). A market that stays busy for a year makes opening a world
slower every day, and none of it is avoidable by the client — the whole point is that
each replica derives state itself rather than being told it.

Network sync is **already incremental**: `Hello.lastSeq` means a returning client
receives only what it missed. So the growing cost is local computation and disk, not
bandwidth — except for a genuinely new joiner, who must receive everything.

## What compaction would have to respect

Two invariants, both load-bearing.

**1. `EventApplier` is the only thing that mutates `MarketState`.** Every mutator on that
class is marked *"Called only by EventApplier"*. It is why a replica cannot disagree with
the log, and why `LogTamper` can be pointed at a log with confidence about what should
happen. A snapshot restore is, unavoidably, a second way to set state.

**2. Nobody trusts anybody's arithmetic.** A client verifies the chain and computes
balances itself. That is the difference between this and a mod where the host says what
you own.

## Options, and what each costs

### A. Local snapshot — self-computed, never shared

After a full verified replay, a client writes its own state alongside its log, bound to
the chain hash at that sequence. On the next load it checks `log.hashAt(seq)` against the
recorded hash; if they agree, it restores and replays only what came after.

- Fixes world load, host start and client connect.
- **Costs no trust at all.** You only ever load state you computed yourself, from a chain
  you verified, and any change below the snapshot point changes the chain hash and
  invalidates it.
- Does **not** help a new joiner, and does not shrink the log.

Conflicts with invariant 1, but weakly: restoring is reconstruction rather than
incremental mutation, and it is checkable — a wrong snapshot is detectable by replaying
and comparing, which a test can do on every build.

The real cost is implementation risk. `MarketState` holds thirteen fields including
`TreeMap<Long, Deque<Order>>` order books and a `Map<String, Deque<Trade>>` history.
Reflective serialisation of those is not reliable (`Deque` is an interface; Gson needs
concrete types), so it wants a hand-written serialiser plus a restore path — and a
serialiser bug means wrong balances, which is the worst failure this project has. It
needs a round-trip equivalence test comparing every observable before it is trusted at
all.

### B. Shared checkpoint — a host hands out state

The only option that helps a new joiner or shrinks the log, and it **breaks invariant 2
outright**. A host that can assert balances can forge them, which is precisely what the
signed chain exists to prevent. A signature does not fix it: it proves who asserted the
numbers, not that the numbers follow from the history.

### C. Quorum checkpoint — N replicas sign the same state root

The standard answer, and the architecture deliberately lacks what it needs. There is no
membership, no quorum, and no expectation that anyone in particular is online — hosting
rotates precisely because people come and go. "N replicas agree" is not a question this
system can ask.

The nearest available thing is the cross-poll divergence check that already exists: hosts
report signed `(seq, hash)` pairs, and disagreement surfaces passively. That is evidence,
not proof, and it is worth remembering it is already doing the useful half of this.

### D. Prune what is provably derivable

Drop the interior of filled and cancelled orders, keep everything that moves a balance.
Preserves verifiability but saves little: in a busy market most events *are* balance
movements.

## Recommendation

Build **A**, and only A, when it is worth a session of its own. Do not build B. Do not
plan for C unless the project ever acquires a membership model, at which point much else
would change too.

A is worth doing carefully rather than quickly, because:

- it is the only one that helps without weakening anything;
- the failure mode is silent and financial;
- it needs a round-trip test that reconstructs from a snapshot and compares every
  observable against a full replay, on a log rich enough to include fills, cancels,
  migrations and a policy change.

Until then the cost is real but bounded, and it falls on people with long-lived markets —
which is a good problem to have and a bad one to fix hastily.

## What is not the problem

Bandwidth for returning clients, which is already incremental, and disk, which is cheap
and would only be addressed by B anyway.
