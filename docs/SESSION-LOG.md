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

Expect `572 / 6 / 5 / 16 / 25 / 6 / 12 / 16 / 27`. CI runs the same nine on every push
since 2026-08-23, so a failure here that passes there — or the reverse — is about the
machine rather than the code, and is worth chasing as such.

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

**What is being worked on right now:** nothing. The last things finished were backlog item
4 — CI, and the port race that had to be fixed before it — and clearing §9, which turned
up a 443-line test suite in a worktree the log had called safe to remove.

Still a person at a keyboard: `E9` and `E23` in `docs/testing/group-e.md`, which the
author has said they will run later. Nothing in CI launches Minecraft.

**Before starting the dedicated server again**, read §9: its market was deleted so the
config and the log would stop describing different economies, and the restart that
recreates it is the only moment `--creator-key` can be passed. Without it that market's
policy is frozen for good.

---

## 0. The read-through, and what it found

*Added after §1–§9 below, which describe the session that built Group E. Nothing was
built here: the whole of it was an inspection plus what the first play session turned up,
and it found twenty-seven defects in code that had 437 passing checks behind it — one a whole
feature that could not be switched on, one an unbounded mint that the guard written to
stop it never fired against, and one a button that did not do what it said.
Read this before §4, which predicted almost all of them.*

Six of the first seven are the §4 shape exactly — **two things that must agree, kept in two
places** — and two of them are in the code §4 was written about. The suites are now
`572 / 6 / 5 / 16 / 25 / 6 / 12 / 16 / 27`, the eighth being a new `hostTrustTest`, and each
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

And a twentieth, from running `E18` and `E19` — **both of which passed, exactly**:

20. **The reset confirmation read as though nothing came back, on a screen about to hand
    back 61 items.** Reported from play in those words. The arithmetic underneath was
    right: Alice's branch reached 71 events and Bob's 54, the split search returned
    `parted after event 22, 32 of ours since`, and comparing the two logs on disk
    afterwards put the last common sequence at 22 with the first disagreement at 23. Bob's
    only post-split deposits were seq 53 (60 cobblestone) and seq 54 (1 crafting table);
    those two came back and his four pre-split ones did not. Nothing to fix in either.

    What was wrong was the sentence. One paragraph, opening with what you lose and
    burying the recovery in a subordinate clause — *"if you are rejoining a market you
    diverged from"* — which is a condition the reader cannot evaluate about themselves at
    the moment they are deciding. Three kinds of outcome were three clauses of one
    sentence: what comes back by itself, what comes back if you act, and what is gone.

    And the third was missing entirely. **The old text listed three kinds of recovery and
    never once said what a reset destroys for good** — credits earned since the split, and
    the trades themselves, which have no existence outside this branch's ledger. The
    backlog has said so since item 1 was written; the dialog never did.

    Now built from `ResetCost`, which `resetLog` also acts on, so the dialog names the
    real items and counts rather than describing the shape of them. *"60 cobblestone, 1
    crafting table"* is the same sentence as *"items you deposited after the split"* with
    the doubt removed, and the doubt is the whole problem with a button that cannot be
    undone. **One computation on purpose:** a confirmation promising something the action
    does not do is worse than no confirmation, because it was believed.

    Two things fell out of making the message longer, both this file's oldest disease:

    - `overlayLines` handed the whole body to vanilla's `wrapLines` and hoped. Paragraph
      breaks are now made here, because separating the three categories is the entire
      point of the rewrite and is not worth trusting somebody else's line-breaking for.
    - `overlayBox` computed `top = Math.max(20, centred)`, which centres a box that fits
      and pins a taller one to `y=20` — where it grows downwards and takes its own buttons
      off the bottom of the screen. Nothing anchored the bottom. **That is the third time
      in this file:** §0.4 was the Market column, §0.16 the market switcher, and both were
      content stacked downwards with no bound, hiding the one control that had to survive.
      Clamped, and the list is capped at three item kinds with a count for the rest so the
      body cannot grow with somebody's inventory. `E19b`.

    **The other finding is not fixed, and is the more interesting one.** `Divergence` is
    built in three places and only one supplies `splitAt` — the FORK refusal. The
    discovery poll and the AHEAD path both pass `-1`, so the banner you get without
    pressing Connect is still *"differs at event N"*, naming the other side's head. Two
    clients polling each other show two different numbers, which is what prompted the
    question: **52 on one and 47 on the other, neither a split point, both correct for
    what that message actually says.**

    It reaches further than the banner. `depositsLostToReset` falls back to
    `divergence.seq - 1` when the split is unknown, which is a true upper bound and a very
    loose one: against a poll-sourced divergence at 52 it would have looked for deposits
    after event 51 on a chain that parted at 22, and refunded almost nothing while the
    dialog promised otherwise. It errs the safe way — under-refunding cannot create
    items — but "safe" there means silently returning nothing. Backlog item 7.

    §3 said nearly every bug that session came from playing rather than reading, and §0's
    closing note argued that did not generalise to the engine. This is the cleanest case
    for §3 yet: the engine was right, the tests were right, the numbers were right, and
    the thing that was wrong was a paragraph — which no test can fail on and only a person
    can notice.

And a twenty-first, reported from the same evening:

21. **A fork warning could not be retired by the fork ending.** Alice warned that Bob was
    on a different branch; Bob discarded his branch and joined her; the banner stayed up
    for the rest of the session, and `MS_FORKED` drives more than a message.

    `observeHostHead` was the only thing that ever cleared a divergence, and it clears one
    by watching **the named host** advertise a matching head. Somebody who stops hosting
    in order to come and join you is no longer a host. So the single most conclusive piece
    of evidence available — that peer completing a handshake with our own HostServer,
    which by definition hands them our chain — was the one source nothing consulted.

    Two ways out now, one per direction. `hasSyncedClient` answers "is this participant on
    my chain right now", asked from `divergence()` so every consumer gets the same answer
    and the banner cannot disagree with the reset about whether a fork is still on. And
    adopting a host's chain on a successful connect retires every claim on record, because
    each was measured against a head we no longer have — the poll recomputes any that
    still hold the next time it sees that host, so nothing is lost.

    **Matched by name, which is the honest weakness.** `MarketClient.Refused` carries
    `hostName` and no identity, so a name is what the two sides have in common; giving the
    refusal an identity is a protocol change and this is not worth one. The asymmetry is
    the argument: a collision retires a warning early on a market the player is already
    connected to, and not asking left a warning that never went away. `E20` covers both,
    including the three-participant case where Carol syncing must not answer for Alice.

    Two smaller things came in with it, from the same report:

    - **"I remember it saying 70 cobblestone, not 60."** Both were on screen and both were
      right. The refund was 60 — the post-split deposit. The old dialog opened with
      `describeLoss`, which is `NetPosition`: **every** credit and item held, pre-split
      ones included, plus goods reserved in resting sells. Two numbers measuring different
      things, in adjacent sentences, with nothing saying so. §0.20's rewrite drops
      `describeLoss` from the forked case entirely for exactly this reason, and the
      report is the evidence that it needed dropping rather than explaining.
    - **`ALSOFT WaitForSingleObjectEx error` and `Can't keep up! Running 3343ms behind`**
      are not this mod. The first is OpenAL's mixer thread and the second is vanilla's
      tick watchdog, both routine with two clients and a host on one machine. Worth
      writing down because they arrived in the same breath as two real bugs and look
      alike from outside. The mod's per-tick work is a fill flush, a cheats check and a
      refund drain; a 71-event replay is not a stall. If this ever *is* us, it will be
      backlog item 2's territory — a long log walked on world load — and it will scale
      with the log rather than appearing at 71 events.

And a twenty-second and twenty-third, from re-running `E19` and `E20` the same night:

22. **§0.21's fix works, and the run it was verified on under-refunded by five items.**
    `E20` passed — Alice's banner went the moment Bob joined her. The reset that followed
    handed back **9 cobblestone** against **14** deposited on the losing branch.

    That run took the **AHEAD** path rather than FORK: Bob at 98, Alice at 90, and Bob's
    hash at 90 not matching hers. `offerCatchUp` handles that case and set a `Divergence`
    with no split point, so `depositsLostToReset` fell back to `hostSeq - 1` = 89 and
    offered back only what came after event 89 — nine deposits, and nine of fourteen
    orders on the re-place checklist.

    **The comment sitting over it said the fallback was safe, and its reasoning was false
    in the one branch it was written in:** *"AHEAD means their head is below ours, so
    anything after it on our chain is genuinely ours alone."* That is true when our chain
    extends theirs, and this branch is reached only when it demonstrably does not — their
    hash at their own head disagrees with ours. A shorter chain is not a prefix, and its
    head number says nothing about where two chains parted. Alice's events 85 to 90 were
    her own deposits; Bob's 85 to 98 were his. They had parted at or below 84.

    `findSplitPoint` was already there, and `offerCatchUp` already had the host and port
    in hand — it just never asked. It asks now, exactly as `noteForkFromRefusal` does.
    Backlog item 7 is down to the discovery poll alone.

    Two things worth keeping from how this was found. It was **invisible from inside**:
    every number the program printed was consistent, nothing failed, and the only way to
    see it was to compare what the console said was deposited against what it said was
    returned. And it was **quiet by construction** — under-refunding cannot create items,
    which is the bound the whole feature was designed around, and that bound is exactly
    what let a wrong answer go unnoticed for two sittings. A guard that makes failure
    safe also makes it silent.

23. **The re-place list could not be scrolled, and it is the only record of what to put
    back.** Nine orders after the reset; the box fits fewer; the rest were drawn nowhere
    and reachable by nothing. `renderReplaceList` broke out of its loop at
    `panelBottom()` and no `noteScrollable` had ever been written for it.

    **Fourth time in this file.** §0.4 was the Market column, §0.16 the market switcher,
    §0.20 the reset overlay, and now this — all four stacked content downwards with no
    bound and lost the far end. §0.16 closed by saying *"anything else in this file that
    stacks without a scroll is the next one"*, and this was the next one, four days later,
    found by a player rather than by anybody going to look.

    Fixed the way §0.16 was: the scroll subtracts inside `replaceRowY`, which the render
    and the hit test both already came through, and `replaceRowVisible` is asked by both
    — a row you cannot see that still re-places an order when clicked is §4 exactly, and
    scrolling creates that at the top as well as the bottom. `E21`.

    The pattern across all four is worth stating plainly, because a fifth is likely:
    **every one was found by a person looking at a screen, and none by a test.** The
    geometry is arithmetic and could be tested; what cannot be tested is noticing that a
    list has more in it than the box was built for. That is the argument for backlog item
    5 that the line count never made.

And a twenty-fourth and twenty-fifth, closing backlog item 7:

24. **The poll could not tell a longer chain from a different one, and guessed.** A probe
    carries a host's head and nothing below it, so when that head is *above* ours there
    is no point the two chains can be compared at. `observeHostHead` returned without an
    opinion, and two things went wrong with that.

    A fork with a longer peer stayed **invisible** until somebody pressed Connect — so
    which side saw the warning was decided by nothing but which branch happened to be
    longer. Both sittings show it: on the first, Alice at 90 against Bob's 98 saw nothing
    and Bob saw everything; on the second, roles reversed, the poll flagged it before
    anybody connected. Same code, opposite outcome, decided by an accident.

    And the height was recorded **anyway**. `observeMarketHeight` was the first line of
    the method, before a single hash had been compared, so a forked peer's head was filed
    as this market advancing. Alice came away permanently "8 events behind" a branch that
    was never hers, in a mark that is monotonic and persisted. Not cosmetic: `eventsBehind`
    gates Host, and it told the participant on the chain everybody else shared that
    hosting it would split the market, then advised catching up from a peer who would
    refuse them. **The warning designed to prevent a fork was fired by one, at the only
    person who had not caused it.**

    It asks now. One `HashQuery` for their hash at **our** head: matching means our chain
    is a prefix of theirs and they genuinely extend us, so the height is real and "behind"
    is true; not matching means a fork, the height is not ours to record, and the split is
    worth finding. `MarketClient.hashAt` is a single question with a yes-or-no answer,
    deliberately not `findSplitPoint` — searching is for after the answer is no.

    **What it costs, which is what kept it out.** One round trip per peer, and only when
    that peer's head has moved: `checkedHeads` was already keyed by (peer, head) and its
    early return pays for this, so a peer sitting still costs nothing, which is the
    ordinary case for a poll on a timer. The split point is looked up before it is
    searched for, because it does not move — two branches that have parted stay parted,
    and both only grow — so an active fork does not re-run a bracketing search every time
    either side places an order.

25. **Discovering any host on a different market wiped the watermark.**
    `MarketHighWater.observe` starts a fresh record when the id it is handed differs from
    the one on file — reasonably, since it holds one market — and `observeHostHead` called
    it **before** checking the market id was ours. So a poll that found a friend serving
    something else reset the note of how far our market had reached.

    That destroys precisely what the thing exists for. Its own design note says checking
    live peers is not enough because *"someone returns after a week, nobody else happens
    to be online, discovery finds nothing, and they host a log that is hundreds of events
    behind"* — the watermark is what survives to tell them. A watermark that any foreign
    host can clear does not survive anything.

    Fixed by where §0.24 put the call: after the market id is confirmed ours, and after
    the chain is confirmed ours too. Found by reading the call order while moving it,
    which is the only way it could have been found — nothing observable changes until the
    day somebody needed the warning and it had been quietly zeroed.

    `hashAt` is pinned by `splitPointTest`, now 27 checks. Neither of these two is
    testable where it lives: `observeHostHead` is client code with a `MinecraftClient` in
    it, which is the same reason `E13` has no unit test. `E22` is the live half, and it is
    the one that matters, because both defects were about what happens between two
    machines rather than inside one.

And a twenty-sixth, from re-running the fork test with §0.24 in place:

26. **A watermark taken honestly is invalidated by your own later fork, and a bare number
    cannot notice.** Reported as the "connect to catch up" alert still standing after the
    fork was over. §0.24 was working; this is a different defect underneath it.

    The timing is the whole thing:

    ```
    00:00:57  Bob hosting, replayed 123 events
    00:01:13  high-water.json written: 129
    00:01:17  [host] seq 124        ← Bob's first event on his own branch
    ```

    At 00:01:13 Bob was at **123**, and Alice's 129-event chain genuinely contained his —
    they agreed through 123. His poll asked, got a match, and recorded 129. **That was the
    correct answer at that moment.** Four seconds later he appended his own event 124 and
    left the chain he had just verified. The 129 then described a branch nobody was on,
    `eventsBehind` read 1, and since it gates Host he was told that serving his own market
    would split it.

    Nothing could notice, because the record was `{marketId, seq}` — no hash, no source.
    §0.24 stopped bad marks being written; it cannot retract one that was good when
    written.

    **A claim now belongs to whoever made it.** The record carries `fromUserId`, and a
    peer's current head replaces their previous one **in either direction**: they are not
    asserting a record, they are saying where they are, and when they come back lower —
    because they reset, or because the branch they were on is gone — the evidence for the
    old number went with it. A *different* peer can only raise it, because their being at
    50 says nothing about whether somebody else's 300 was real. Bob's case resolves
    exactly: Alice reported 129, Alice now reports 128 on his chain, so 129 is withdrawn.

    **Files written before provenance are discarded on load**, with a line saying so.
    There is nobody to withdraw them, so they cannot be reasoned about at all — and every
    file that exists today is one of them, including the 129 that caused this. The cost is
    the offline warning being unavailable until the next poll rebuilds it, which is one
    poll cycle when anybody is around and exactly the situation the mark is for when
    nobody is.

    `L6` covers all of it and both halves were verified failing with the rule disabled.
    Worth noting what the three sittings did to this one area: §0.24 fixed what the poll
    records, §0.25 fixed a foreign market silently zeroing it, and this fixes a mark
    outliving the chain it described. **Three defects in a nine-line class**, all of the
    same kind — a fact stored without enough of itself to be checked later.

*A note for backlog item 4.* Running the nine suites in one gradle invocation failed the
build twice tonight with every suite reporting all checks passed, and succeeded on an
immediate re-run. Nothing in the checks; most likely a port race in the suites that bind
sockets — `freePort()` picks one, closes it, and hands it to a server to rebind. Worth
knowing before somebody writes the CI workflow and concludes the tests are broken.

And a twenty-seventh, which is the flake from §0.26's closing note, found on purpose:

27. **Every suite picked its port from the range the operating system hands to outgoing
    connections.** Eight copies of the same five lines:

    ```java
    try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    ```

    which opens a port, **closes it**, and hands the number to a HostServer to bind.
    Between those two steps the port belongs to nobody — and port 0 asks for one from the
    *ephemeral* range, which is where the OS also draws source ports for outbound sockets.
    These suites open a great many outbound sockets. So the number reserved could be taken
    by this very process before the server bound it.

    It went red twice on 2026-08-22 with **every check passing**, which is the failure
    shape that teaches people to ignore a CI tick — so it was fixed before the workflow
    that would have hit it. `TestPorts` draws from 20000–30000, below the ephemeral range
    on both Linux and Windows; remembers what it has handed out, so two suites in one JVM
    cannot collide; and probes a candidate exactly as the server will bind it —
    `new ServerSocket(port)` on the wildcard address, not loopback, because a probe that
    binds more narrowly than the server proves nothing. Three consecutive full runs green.

    The window between probe and bind is still there. Closing it means handing the bound
    socket itself to `HostServer`, which is changing production code to suit its tests;
    what changed instead is that nothing else is competing for the number.

    §4 again, eight times over, in the one place nobody thinks to look for it — a helper
    duplicated across eight files, all wrong in the same way because they were copied from
    each other. Worth noting it took a CI plan to find: the suites had run hundreds of
    times on one machine, in ones and twos, and only running all nine together made it
    likely enough to see.

**And backlog item 4 is done.** `.github/workflows/tests.yml` runs the nine suites on
every push and then builds. Two JDKs, since Gradle 9 needs 17 to run itself and the mod
compiles against 8. The checkout's tracked `build/` and `.gradle/` directories are deleted
before anything is built — this repository carries one machine's compiled classes and
Gradle file hashes by design, and leaving them in place would prove only that this
machine's build state still works, which was never in doubt.

What it does **not** cover is worth being clear about, because a green tick is persuasive.
Nothing in CI launches Minecraft, so every item in `docs/testing/group-e.md` is still a
person at a keyboard — and four of the defects in §0 were geometry, a list drawn past the
bottom of its box, which no suite here would have seen.

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
coreTests 572   chunkTest 6   replayGuardTest 5   gapRecoveryTest 16
admissionTest 25   depositCapTest 6   attestationTest 12   hostTrustTest 16
splitPointTest 27
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
- ~~**Never built from a clean checkout, and no CI.**~~ Both since 2026-08-23 — see §0.27
  and backlog item 4. What CI does not do is launch Minecraft, so every live item in
  `docs/testing/group-e.md` still rests on somebody running it, and four of §0's defects
  were geometry no suite would have caught.
- **Host rules do not travel, and should not.** Deposit caps, admission, attestation and
  `maxMigratedCredits` are per-host, read from that host's own `host-config.json`, so they
  change when hosting rotates. Market rules — fees, grant, stipend — live in the log and
  are uniform for everyone. §0.12 is the sharpest case: the migration cap protects whoever
  happens to be hosting when somebody arrives, and rotating to a host that has not set it
  opens the door again.

  **Asked properly on 2026-08-23 and decided.** They cannot travel — a time-windowed rule
  would be enforced against a client-set timestamp, the world checks judge evidence that
  is not in the log, and item 3 already showed a deployment-dependent default forks the
  market when hosting rotates. And they need not: host rules are a host's defence against
  its clients, not the market's defence against its host, so travelling would add
  consistency rather than security. What is worth building is making them **agreeable
  once** — published as defaults each host adopts, never as replicated enforcement. That
  is backlog item 8, and this bullet is a decision now rather than an oversight.
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
- ~~Whether host rules should travel with the market.~~ **Decided 2026-08-23: no.** They
  cannot — a time-windowed cap would judge a client-set timestamp, the world checks weigh
  evidence no replica receives, and a deployment-dependent default forks the market when
  hosting rotates (item 3). And they need not, because a host rule is a host's defence
  against its clients rather than the market's against its host, so replicating one buys
  consistency and not safety. Making them **agreeable once** is worth building and is
  backlog item 8; making them travel is refused, not deferred.

## 9. Loose ends in the tree

*Cleared 2026-08-23. Both entries below are done; they are kept because what each turned
out to be is worth more than the fact that it is gone.*

- ~~A worktree at `.claude/worktrees/practical-diffie-e9fbf4` sits at `4fb4ec5`. It was for
  a background task on the migration bypass that never ran; that bug was fixed here
  instead. **Safe to remove.**~~ **It was not safe to remove, and "never ran" was wrong.**
  It held eight modified files and a **443-line `MigrationCapTest`** that exists nowhere
  else — migration weighed against deposit caps, attestation, creative worlds, the
  statistics multiple and the free allowance. The bug it was written for was indeed fixed
  on this branch instead, so none of it was ever needed; that is a reason not to merge it
  and not a reason to delete it unread.

  Committed to its own branch as `c22abf6` before the directory went, so
  `claude/practical-diffie-e9fbf4` still holds all of it. Nothing there is claimed to
  build against the current tree — it sits on `4fb4ec5` and the code around it has moved a
  very long way. **Whether any of those checks are worth porting is an open question**;
  `depositCapTest` is six checks and `admissionTest` twenty-five, and neither reads a
  `MigrateBalance`.

  The lesson is the entry itself. It said "safe to remove" for four days, in a file that
  is otherwise careful, because whoever wrote it knew what the worktree was *for* and did
  not look at what was *in* it. A directory's purpose and its contents are two facts.

- ~~`server-market.jsonl` disagrees with `server-config.json`.~~ **Done 2026-08-23.** The
  market held five events — genesis, one policy, one key registration and two welcome
  grants — with no trade or deposit in it, so deleting it discarded nothing anybody had
  done. It is gone, and the next start bootstraps from the config, which is what makes the
  two agree.

  `server-config.json` was rewritten through `--write-config` at the same time, which
  validated it (the `50 / 2 / 20` grant, fee and stipend pass the interlock) and added
  `maxWelcomeGrant: 1000000` — the compiled dedicated ceiling, previously absent and
  therefore invisible, which is §0.18's whole argument applied to the file it was written
  about.

  **One thing to decide before starting it**, and it is the only moment the decision can
  be made: bootstrapping without `--creator-key` makes the box itself the creator, and a
  market whose creator is a machine with no screen has its policy frozen for good. That is
  recorded in §7 as a known trap, and this restart is the one chance to avoid it. Passing
  `--creator-key` with a player's key, and that player's uuid in `creatorUserId`, leaves
  the policy changeable from the Market screen afterwards.

- **`server-identity.key` is tracked, unencrypted**, along with the rest of `run/`, because
  `.gitignore` was removed on purpose. Raised before and reaffirmed, so this is not a new
  objection — but CI now exists and this branch is pushed to GitHub, so the question is no
  longer theoretical. If that repository is public, so is the key. Worth answering
  deliberately rather than by default.
