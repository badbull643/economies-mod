# EconomiesMod — Session Log

*Continues the previous log of the same name; git history has it at `4fb4ec5`.
This file is the current picture, and where it disagrees with anything older, this one
is right.*

---

## Start here

**The build is green and the tree is clean.** Nothing is half-finished; there is no
in-progress edit to pick up.

```
./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest \
    depositCapTest attestationTest hostTrustTest splitPointTest
```

Expect `565 / 6 / 5 / 16 / 25 / 6 / 12 / 16 / 22`. If any of it fails on a clean
checkout, that is news — it has only ever been run on one machine (backlog item 4).

**Where the code is.** Branch `trust-model-and-migration`, ahead of its remote and 0
behind. Don't trust that sentence for a number — the count has been wrong in this header
twice. Ask git:

```
git rev-list --left-right --count origin/trust-model-and-migration...HEAD
git log --no-merges origin/main ^HEAD        # empty ⇒ main holds no work of its own
```

`origin/main` carries commits this branch does not, and they are all merge commits *of
this branch*, so nothing on `main` is missing from here. The branch has been pushed
before and merged to `main` through PR #6. Local `main` is far behind `origin/main`;
nothing depends on that.

**What to read, in order.**

1. **§4 below** — one paragraph, and it predicted most of two sessions' worth of bugs.
   If you read nothing else, read that.
2. **§0** — an inspection that found seventeen defects behind 437 passing checks, then
   what a play session found on top. Long, but it is the current state of the code.
3. **`docs/BACKLOG.md`** — everything deliberately not built, in the order worth doing,
   each saying what it costs to keep not doing it. This is where the next work is.
4. **`docs/testing/group-e.md`** — done as of 2026-08-22, with six items wanting a short
   re-check because their code moved afterwards or is newer than the sitting.

**What is being worked on right now:** nothing. The last thing finished was §0.19, which
`E17` turned up the moment §0.18 gave somebody a way to run it: lowering the grant
ceiling in a world switched off every other host rule in the file. **The rest of `E17`
and `E17b` have not been run past that point** — see `docs/testing/group-e.md`.

---

## 0. The read-through, and what it found

*Added after §1–§9 below, which describe the session that built Group E. Nothing was
built here: the whole of it was an inspection plus what the first play session turned up,
and it found nineteen defects in code that had 437 passing checks behind it — one a whole
feature that could not be switched on, one an unbounded mint that the guard written to
stop it never fired against, and one a button that did not do what it said.
Read this before §4, which predicted almost all of them.*

Six of the first seven are the §4 shape exactly — **two things that must agree, kept in two
places** — and two of them are in the code §4 was written about. The suites are now
`565 / 6 / 5 / 16 / 25 / 6 / 12 / 16 / 22`, the eighth being a new `hostTrustTest`, and each
engine fix was verified to **fail** with the fix disabled before being trusted.

1. **A sell you could not afford duplicated the items.** `DepositAndList` was validated
   in one place and applied in another, and only the applier knew about the listing fee:
   `validate` passed, the event went into the log, and `apply` deposited the goods and
   *then* refused. The client answers a refusal by handing the physical items back, so
   they existed on both sides — and the host, having appended but not broadcast, left
   every other client to take a sequence gap. Both halves now ask
   `MarketState.canDepositAndList`. **`A5` in the checklist already ran this recipe and
   was marked passing**; the message it looked for could only have come from the path
   that had already deposited.

2. **`MarketArchive.verifyLines` called `apply` without `validate`** — on the one path
   documented as treating a history as "a file from a stranger". `apply` enforces none of
   the money rules; they live in `validate`, because that is where a host asks them
   before appending. So a hand-built log could carry a welcome grant for any sum,
   repeated as often as you like, and the balance it replayed to is what a migration
   brings in. Measured before the fix: an archive replaying to 3,000,999,997 credits
   against a published grant of 10. Nothing downstream caught it either —
   `migrationObjection` weighs a migrant's *items* against their own statistics and never
   their credits. Both are called now, in that order.

3. **`MarketScreen` kept its own copy of the stipend interlock**, and the copy was the
   version from before the rule was corrected twice — two listing fees per fill, one
   claimant. It advertised "the most is 199" where the engine refused anything from 100.
   `stipendOutpacesItsFees` is public now and the screen asks it. `ServerConfig` had
   already been through this and says so in a comment; the second copy was missed.

4. **The Market column's last row could never be scrolled into view.** `place()` clips a
   row to `panelTop`/`panelBottom`; the scroll extent was measured against
   `frameTop`/`frameH`. Those differ by the panel's five-pixel inset at each end, leaving
   the extent nine pixels short — so the bottom control was unreachable at any offset.
   In `E1`'s own setup that row is **Remove this market**. `noteScrollable` now takes the
   view height separately from the rectangle that catches the wheel, because they were
   never the same rectangle.

5. **The escalating listing fee was off by one** against its own javadoc and `E2`: it
   counted orders already resting and not the one being placed, so an allowance of three
   let four orders through at the base fee. `T1e` pinned that — while its own comment
   said "the fourth starts climbing". Both corrected.

6. **The render thread read the whole of `MarketState` unsynchronised.** `markets`,
   `WalletRegistry`, `ItemBalanceRegistry`, `TradeHistory` and every `OrderBook` are
   plain maps written by the applier thread and read every frame while drawing —
   `TradeHistory.tradedItems()` handed out a live `keySet` view. The reasoning was
   already written down on `keyDirectory` and simply not carried anywhere else. Each
   class guards its own collections now.

7. **The About line offered a stipend to people who cannot claim one.** An unregistered
   viewer has no last claim on record, which reads as fill zero, so the countdown went
   negative and said "yours is waiting" beside a button that would never appear.

Then two more, done straight after because leaving either half-closed was worse than not
having started:

8. **`MarketClient.applyLine` had #2's hole on the path everybody uses.** It verified the
   signature and called `apply`, never `validate` — so a modified host could sequence
   itself a grant for any sum, correctly signed with its own key, and every connected
   replica applied it, **persisted it, and would re-serve it the next time that player
   hosted**. Measured with the fix disabled: the client joins, banks 999,999,999 credits
   and writes the event into its own log. It asks `validate` now. Safe because every path
   by which a host appends validates first against its state at seq-1, and a client's
   state at that point is the same state — `hostTrustTest` H2 exists to hold that claim
   down, syncing an honest history of exactly the same shape.

9. **The atomicity fix 6 left open, closed.** Per-collection monitors stop any single
   read catching a map mid-write; they cannot make a *set* of reads agree, and settling
   one event touches several. `submitOrder` takes the buyer's credits and *then* books
   the order, so a reader between those two steps sees credits that are in no wallet and
   no reservation. `EventApplier.apply` now holds one write lock across a whole event and
   `openOrderCount` takes the matching read lock, so the fee it feeds is a fact rather
   than a race. `X1` runs a reader thread against a live market and checks credits
   balance on every pass; with the lock removed it catches a torn read within a second.

   Found while doing it: **the render thread was calling `bookFor`**, which creates the
   book it fails to find. Four call sites, one of them per frame — writing to the market
   from a draw call, and filling the map with empty books for items nobody has traded.
   All four are `peekBook` now. Its javadoc had said exactly this since it was written.

And a tenth, found by someone asking how to run `E2`:

10. **The free-order allowance had no control of any kind**, so the escalating listing
    fee was unreachable code. `listingFreeOrders` was written in exactly one place —
    `submitPolicy`, which copies whatever it already was — and `MarketBootstrap` never
    set it, so it was zero at genesis and zero forever in every market that has ever
    existed. No field, no `ServerConfig` key, no command; the checklist said "set the
    free allowance above 0 by hand" and there was no by hand. A feature built, tested,
    documented, and shipped switched permanently off, with #5's off-by-one sitting inside
    it where nobody could ever have hit it.

    The **Listing fee** field now takes `2` or `2/3`, the way the stipend control sets
    amount and interval together — one decision, one control, per §4. Parsing lives in
    `MarketState.listingFeeFromText` for the reason `bpsFromPercent` does: so it can be
    tested without Minecraft, which `T5b` does.

    Worth noticing how it was found. Nothing in the code says a field is unreachable, and
    every test passed — `T1e` sets `listingFreeOrders` directly, which no user can. It
    took somebody trying to follow the instructions.

And two more, from being asked what happens when a market with a big welcome grant joins
one with a small one:

11. **Migrating twice was unbounded.** The guard against it names the attack in its own
    comment — *"join, take the grant, reset, create your own market, take that grant too,
    migrate it back, repeat"* — and tested whether the beneficiary was **registered** or
    **already granted** here. A `MigrateBalance` sets neither, and the per-branch guard is
    keyed to the *source* market id, which is a fresh random id every time somebody makes
    a market. So the rule never fired against the thing it was written for. Measured:
    **4,000,000 credits in four passes**, into a market whose founder held 50, by an
    identity that never registered. The guard now asks `hasMigratedIn` — has *this*
    beneficiary carried a balance in here before, from anywhere — which is a set added
    for it. `M6b`.

    **The first attempt used `isAccountedElsewhere` and was wrong**, which `E11` caught
    the next day. That set holds everyone who was *registered* in a market somebody
    migrated out of, and exists to deny them a second welcome grant — its own note says
    they get their own balance "if they turn up". So the first arrival from a shared
    market filed all their friends, and the second was turned away as though already
    paid. Two people leaving one market together is the ordinary case, not the abusive
    one.

    `M6b` passed throughout, because it only ever tested one identity arriving
    repeatedly — the attack — so a guard that refused everyone from a migrated-out-of
    market satisfied it completely. `M6e` is the ordinary case, and the lesson is that a
    test which only describes the attack cannot tell you the fix is safe.

12. **Nothing bounded migrated credits at all**, honestly or otherwise.
    `migrationObjection` weighs the *items* a migrant brings against their own Minecraft
    statistics and never looks at their balance. Two people arriving from a market that
    grants 1000, into one that grants 50, take it from a supply of 100 to 2100 — the
    people already there keep every credit and go from holding all the money to **4.8%**
    of it. Nobody is robbed; everybody is outbid, and nobody is told. New
    `maxMigratedCredits` in `host-config.json`, off by default, beside the deposit caps —
    host-local because the receiving market is the only party that can say what it will
    absorb. `M6c`, and `E11` for the live version.

    Worth keeping separate in your head: 11 is a hole, 12 is a policy. An honest merge
    produces identical arithmetic to a deliberate one, which is exactly why it needed a
    setting rather than a rule.

And a thirteenth, which is the one that justifies §0.8 on its own — **found by §0.8
firing** on an ordinary self-connect, three minutes into the first real play session:

13. **A client could hold state one event ahead of where it believed it was**, and so
    apply that event twice. `EventLog` caches `lastSeq` when it is constructed;
    `readFrom` re-reads the file on every call. `MarketClient` took its position from the
    first and its state from the second, and anything appending between them separated
    the two. Starting a host does exactly that — it counts down its `bound` latch
    *before* issuing its opening welcome grants, and the self-connect that follows opens
    a second `EventLog` on the very file those grants are landing in. The client then
    told the host "I have 4", was sent event 5 which it already held, and **applied its
    own welcome grant twice**: a replica a thousand credits richer than the host it
    mirrors, for the rest of the session.

    Silent until §0.8 made the client check what it was being sent. It surfaced as
    `[client] REFUSING event 5 from host: breaks this market's rules — already granted in
    this market`, which reads exactly like a false refusal and was not one.

    `MarketClient` now takes state, position and hash from one read
    (`EventApplier.replayWithHead`), and the handshake reports what it has applied rather
    than what the log object thinks. `H3` reproduces the race with two log handles and no
    threads at all, so it fails every run without the fix rather than one run in ten.

    **The general point, which is worth more than the bug.** A validation you add to
    catch dishonesty finds your own bugs first, because your own bugs are far more
    common than attackers. §0.8 was justified on a threat nobody here faces; it paid for
    itself within minutes on a defect that was corrupting an ordinary single-player
    session. Two things that must agree, kept in two places — §4 again, in the constructor
    of the class the previous fix was written into.

And a fourteenth, reported from play:

14. **Disconnect did not stop hosting.** Both Network buttons were always live, side by
    side, in every mode. While hosting, the one people reach for is Disconnect — left of
    the pair, and the word for what they want — and it dropped only the self-connection.
    The server stayed bound, kept serving whoever was on it, and kept answering the
    discovery poll, which is how it was noticed: *"it still showed I was hosting and I
    could still connect from the other client."*

    The visible half was the smaller half. `stopHosting` is the only thing that releases
    the HostServer's `EventLog`, so `disconnect()` fell through to `loadLocal` and opened
    a **second** `EventLog` on the file the running host still owned. Two writers, one
    log: duplicate sequence numbers and a broken chain. `connect()` has guarded against
    precisely this since it was written and says so in a comment — the guard was never
    carried to `disconnect()`. §4 again: one invariant, enforced in one of the two places
    that needed it.

    `disconnect()` now stops the host if there is one, and the buttons grey by mode.
    `E13` is the live test; there is no unit test, because the fix is in
    Minecraft-dependent client code and `HostServer.stop()` itself was never broken —
    adding a test for the part that worked would buy coverage of the wrong thing.

And a fifteenth, raised as a design question rather than a bug — correctly:

15. **A dedicated server offered migration like any other host.** The Migrate button
    tested only whether a foreign market existed, and the refusal advice said *"Migrate
    (carries your balance) or Reset log"* to a public box exactly as to a friend.

    Migration solves bootstrapping among people who know each other. The balance it
    carries was set by a welcome grant the migrant chose, in a world they control, up to
    `MAX_WELCOME_GRANT` — so on a public server it is not "bringing your savings", it is
    "naming your opening balance". That is the deployment admission, deposit caps and
    attestation all exist for, and migration walked past all of them.

    `acceptsMigration` in `host-config.json`, **boxed so unset is not false**: unset means
    off for a dedicated server and on for somebody's game, and either can be set
    explicitly. Enforced in `handleMigrate` before admission and before any chunk is
    read, so a refusal arrives without the sender uploading a history first.

    **And the better answer got named.** The UI offered import-your-wealth or
    destroy-it, and never mentioned the route that costs nothing: market slots are
    separate logs, so *Add another market* and connecting from the new slot joins anywhere
    while leaving your own market exactly where it is. That now leads the advice
    everywhere, and is what a dedicated server's refusal points at.

    Also worth recording: there was no way for any operator to refuse a migration at all
    before this. `maxMigratedCredits: 0` means unlimited, not none — a gap in §0.12,
    added the same day.

And a sixteenth, from a screenshot: **the market switcher disappeared.**

16. The Market tab's left column had no bottom, and every piece in it independently gave
    up when it ran out of room — `if (y > panelBottom() - 24) { slotListTop = -1;
    return; }` for the switcher, `panelBottom() - 40` for the About block. The switcher is
    drawn last, so it went first, and it is the only *control* in that column: the one
    thing that had to survive was the one guaranteed not to. `E14`'s dedicated-server
    paragraph was enough to push it over.

    Same disease as §0.4 and the same fix, applied to the other half of the screen. The
    column scrolls; one `guideLine` decides what is drawn, so nothing can be lost a new
    way; and `slotRowVisible` is asked by both the drawing and the hit test, because a
    row you cannot see that still switches your market when clicked is §4 exactly.

    Worth noting what the two halves of this screen now have in common: both were built
    stacking content downwards with no bound, and both failed by hiding a control rather
    than by drawing outside the box. Anything else in this file that stacks without a
    scroll is the next one. `E15`.

And a seventeenth, from the same play session:

17. **Host was live on a market a dedicated server already served.** That is the one
    reliable way to fork a market permanently: the box does not stop, so both hosts keep
    sequencing, and `docs/design/fork-rebase.md` is the whole essay on why there is no way
    back. The collision warning that existed offered **"Take over"** — fair against
    somebody's game, never against a server that is always up — and a comment beside the
    Migrate button had claimed Host was greyed on a dedicated market since before
    anything greyed it. Now greyed, with the host list saying why, and the warning has no
    confirm button at all.

    The fix nearly introduced this file's oldest bug. Explaining the greying means a line
    of text above the host list, and the host rows had their positions computed **twice**
    — a cursor walked down while drawing, and the same walk redone in the click handler.
    Inserting a line moved every drawn row and left every clickable row where it was.
    Both go through `discoveryRowY` now. Worth noticing that the code carried a comment
    warning about exactly this — "rows stay one line each so the click test keeps
    measuring in fixed steps" — and the warning was not enough, because the duplication
    it was guarding was still there to be tripped over.

And an eighteenth, found by somebody trying to run `E17` and asking where the file was:

18. **No host rule could be reached without already knowing the file existed.**
    `host-config.json` is created by nothing, sits in a directory the game never names
    until you have hosted once, and has no control anywhere in the UI — `MarketScreen`
    does not contain the string. Admission, deposit caps, attestation, `acceptsMigration`,
    `maxMigratedCredits` and `maxWelcomeGrant` all live there and nowhere else. A
    dedicated operator has `--write-config` to generate the file; somebody hosting from
    their game had no equivalent.

    Defensible for admission and the deposit caps, which are "I have a problem with a
    specific person" settings that a person goes looking for. Not defensible for the two
    whose defaults *do something*: a player refused inline at 10,000 has no way to learn
    the figure is movable, and migrations being on or off by deployment type is a rule
    with no visible switch. **§0.10's shape without §0.10's severity** — the feature
    works and the default is the right one, but the control is unreachable from where the
    decision is made, and that is the same failure one step milder.

    Two halves. The console line printed when a world hosts with no file now names the
    ceiling among the defaults and names the command, because that is the moment the
    defaults start applying. And `/trade hostconfig` lists every host rule at the value
    in force, with `write` creating the file holding exactly those lines.

    **`TradeCommands` said "every command here reads", and this is the one exception**, so
    the class javadoc now argues it rather than leaving it to be noticed. The refused
    verbs are refused because they mutate the ledger, which has no undo; a config file
    takes effect only at the next host start, can be edited back by hand, and `write`
    refuses when the file is already there. That refusal is not politeness: the writer
    round-trips through Gson, so an overwrite would silently drop any key Gson does not
    know — a misspelt one being exactly the case somebody is trying to see.

    Three §4 collapses fell out of building it, and each was a pair that already existed:

    - `hostConfigPathFor` — the command and hosting must open the same file, or the
      command creates one nothing ever reads.
    - `hostRulesTree` — what the command lists and what `write` saves are one object, so
      a printed key that never reaches the file is not expressible.
    - `asWorldHost` — hosting stamped port, name, id and `dedicated` onto the config
      before asking `problem()`, in four assignments. The command has to reach the same
      verdict, and `dedicated` is the sharp one: it is what `acceptsMigration()` and
      `maxWelcomeGrant()` resolve against, so a config copied from a dedicated server's
      file would otherwise have the command print, and write, a 1,000,000 ceiling into a
      world that will never honour it. One method now, called from both.

    `R1c`, and `E17b` for the live half. Two things worth recording about writing the
    test. The launcher-only keys were checked against a default config, where they are
    null and Gson omits them anyway — the check passed with the stripping disabled, which
    makes it not a check. And the ceiling was compared as a substring of the file, where
    `"maxWelcomeGrant": 1000000` contains `"maxWelcomeGrant": 10000`: it passed against
    the exact value it existed to rule out. Both were caught by running the suite with
    the fix disabled, which is the only reason either is known.

    **And `E17`'s last step was wrong.** It said a world refuses to start on an unusable
    `host-config.json`, naming both figures. Only the dedicated launcher does that;
    `hostPolicyFor` catches `problem()` itself and falls back before `HostServer` ever
    sees the config, so a world hosts anyway with none of the operator's rules and one
    console line about it. Deliberate for a file that cannot be read — refusing "would
    strand somebody over a file they may not know exists" — and less obviously right for
    one that parsed fine and states a contradiction. Left as it is, because that argument
    is about hosting rather than about discovery; `/trade hostconfig` now asks the same
    `problem()` in chat, which is where somebody editing the file is looking. Whether the
    world path should refuse the way the launcher does is open, and in the backlog.

And a nineteenth, found by running `E17` against the command §0.18 had just added:

19. **Lowering the welcome-grant ceiling in a world switched off every other host rule.**
    `problem()` refused any config where `welcomeGrant` exceeded `maxWelcomeGrant()`, on
    every host. The file `/trade hostconfig write` generates carries `welcomeGrant` at its
    compiled default of **1000**, so setting `maxWelcomeGrant: 100` — the one edit the
    setting exists for — made the file unusable. And `hostPolicyFor` answers unusable by
    discarding all of it and hosting on the friend-group defaults, so admission, deposit
    caps, attestation, migration limits **and the ceiling being lowered** all silently
    stopped applying, reported in one console line.

    The pair is real on the host it was written for and meaningless on the other, which
    its own comment says without noticing: *"a market this host bootstraps writes its
    grant into genesis"*. Only the launcher bootstraps from this file. A world's market is
    created by `MarketBootstrap` through the Market screen, which never reads it — so
    there `welcomeGrant` is a switch and nothing more, tested against zero by
    `issueWelcomeGrant`, with the amount taken from the market because the amount is not
    a host's to overrule. Two settings about different things, refused for disagreeing.

    Now asked only of a host that bootstraps. `R1d`, and the existing `R1b` check that
    covered this had to be told which host it meant — its comment said "a server that
    bootstraps a market above its own ceiling" while its config was a world, and it passed
    for the wrong reason. That is the same sentence being true of one host and asked of
    both, one layer up.

    **§4 again, and this is the fourth time the rotating/dedicated split has produced it.**
    Backlog item 3 recorded that a ceiling inside `validate` would be legal on one host
    and illegal on the next. §0.15 found migration offered by a box that should not take
    it. §0.17 found Host live on a market a dedicated server already served. Each is one
    rule that is about *who is hosting* being asked somewhere that cannot tell. Anything
    else that reads `dedicated`, or that should and does not, is the next one.

    Worth recording how it surfaced: as a screenshot of `/trade hostconfig` correctly
    reporting a file as unusable. The command worked exactly as designed and the design
    was right — it was the rule underneath that was wrong, and nothing but running the
    thing would have separated those two.

`E8` and `E9` in `docs/testing/group-e.md` cover what wants an eye in game.

**What this says about the balance of effort.** §3 below says nearly every bug that
session came from playing rather than reading. That was true and is still true of the UI
— but the two that mattered most here were found by reading, and neither was reachable
from a checklist: one needed a crafted file, the other needed a seller with no money at
the moment a fee was set. §3's lesson holds for the screen; it does not generalise to the
engine.

## 1. Where things stand

The roadmap is finished. Everything in Phases 0–5 is done or deliberately closed. This
session went on what running it turned up, then on one new feature.

```
coreTests 565   chunkTest 6   replayGuardTest 5   gapRecoveryTest 16
admissionTest 25   depositCapTest 6   attestationTest 12   hostTrustTest 16
splitPointTest 22
```

*(437 across seven suites when this section was written; the extra 57 checks and the
eighth suite belong to §0.)*

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

*[2026-08-22] **Group E is done** — every item run in game, E9 and E12 covered
incidentally by the migration sitting rather than stepped through. What it turned up is
§0.13 through §0.17, plus the correction inside §0.11: the switcher deleting itself, Host
live on a market a dedicated server already served, a warning running off the panel edge,
Disconnect not stopping hosting, and a migration guard that turned away the second person
to leave a shared market. **Most of those were introduced by fixes made earlier the same
day**, which is the thing to carry forward from this list rather than any item on it.*

*Two items want a five-minute re-check, because their code moved at 13:58 after the
sitting ended — `docs/testing/group-e.md` names exactly which and why. Nothing else has
changed since it was run.*

*The paragraph above is what this looked like beforehand. Worth leaving: "zero live
minutes" was the right thing to have been worried about, and the ratio held — a day of
reading found seventeen defects, and an evening of playing found six more that the
reading had just created.*

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

*Four places now, and it was two when this was written: §0.3 found `MarketScreen` still
holding a copy — the pre-correction one. The sentence above was true of core and not of
the screen, which is how a claim about "everywhere" survives being wrong.*
It is re-checked **at every claim**, because a market can outgrow a stipend that was
affordable when it was set.

From the same thread: the listing fee now climbs with orders held open above an
allowance (zero means no escalation, so existing markets are unchanged), and the welcome
grant finally has a control — see §7.

## 6. Known gaps

- **Group E is tested**, as of 2026-08-22 — see §2 and `docs/testing/group-e.md`. Two
  items want a short re-check, named there, because their code changed after the sitting.
  The live gap is no longer Group E; it is that nothing has been played for long enough
  for a market to get big, which is what the log-compaction and price-floor entries in the
  backlog are both waiting on.
- **Everything deliberately unbuilt now lives in `docs/BACKLOG.md`**, in the order it is
  worth doing, with what it costs to keep not doing it. Added because the roadmap is
  finished, so nothing else says "this is known about and not done" — and without such a
  file an unbuilt thing cannot be told apart from an overlooked one. §0.10 was exactly
  that failure in miniature: a feature nobody had noticed was switched off.
- **Log compaction unbuilt**, deliberately. `docs/design/log-compaction.md` has the full
  pass; its recommendation is build option A only, when it is worth a session of its own.
  Backlog item 2.
- **A forked market cannot be recovered from**, only reset away from — and a reset
  destroys items that have already left somebody's Minecraft inventory. This is the case
  a friend group hits by splitting into two play groups and both continuing: same market
  id, so migration is refused, and no merge exists. `docs/design/fork-rebase.md` is the
  pass; backlog item 1, and split-point discovery is the small piece to do first.
- **`.gitignore` is gone**, removed deliberately, so `build/`, `run/` and
  `server-identity.key` are tracked. That last is an unencrypted private key. Raised, and
  the decision reaffirmed — recorded here because it is not obvious from the tree.
- **Never built from a clean checkout, and no CI.** Every claim that this works rests on
  one machine.
- **Host rules do not travel.** Deposit caps, admission, attestation and now
  `maxMigratedCredits` are per-host, read from that host's own `host-config.json`, so they
  change when hosting rotates and a group cannot agree them once. Market rules — fees,
  grant, stipend — live in the log and are uniform for everyone. §0.12 is the sharpest
  case of this: the migration cap protects whoever happens to be hosting when somebody
  arrives, and rotating to a host that has not set it opens the door again.
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

*Judgements with nothing to build behind them. Anything that is "a thing to build when it
is worth a session" is in `docs/BACKLOG.md` instead, and is not repeated here — two lists
of the same items is §4 again, in prose.*

- Whether the stipend's interval of 50 is right. It is a guess, and nothing has watched a
  real session. Fills only accrue while somebody is hosting and connected — there is no
  offline trading — so a small group produces them slowly.
- ~~Whether the welcome-grant ceiling should come down.~~ **Decided and done,
  2026-08-22** — 10,000 for somebody hosting from their game, the compiled 1,000,000 kept
  for a dedicated server, `maxWelcomeGrant` to move either. The blocker recorded here was
  real and was routed around rather than solved: it is a host rule, so `validate` is
  untouched and no existing market's recorded policy becomes invalid. Backlog item 3.
- ~~Whether sub-unit prices are ever worth it.~~ **Dropped, 2026-08-22.** Not deferred —
  removed from the backlog so it stops being reconsidered. The stipend treats the cause;
  this treated the symptom; and no real market has been seen pressing against the price
  floor. See the Dropped section there for the one constraint worth keeping.
- Whether `MarketScreen` gets split — backlog item 5.

## 9. Loose ends in the tree

- A worktree at `.claude/worktrees/practical-diffie-e9fbf4` sits at `4fb4ec5`. It was for
  a background task on the migration bypass that never ran; that bug was fixed here
  instead. Safe to remove.
- ~~`server-market.jsonl` is the dedicated server's live market, bootstrapped during C4
  with `welcomeGrant: 50`.~~ **Recreated 2026-08-22, and it disagrees with its own
  config.** The market on disk holds `grantAmount 1000, listingFee 0, stipendAmount 0`;
  `server-config.json` says `50 / 2 / 20`. It was created during an E6 run at a moment
  when the config file was absent, so it took the compiled defaults, and **genesis is
  permanent** — that market was bootstrapped without `--creator-key`, so its creator is
  the server itself and its policy is frozen forever.

  Nothing is broken by this; the server serves that market perfectly well. But the two
  files describe different economies, and anyone reading the config to learn what the
  server does will be wrong. To make them agree, stop the server, delete
  `server-market.jsonl`, and start it — E6's first instruction, for exactly this reason.

  The config also predates `maxWelcomeGrant`, since it is only rewritten when the server
  has no identity yet. Absent means the default, which for a dedicated box is the
  compiled ceiling — see backlog item 3.
