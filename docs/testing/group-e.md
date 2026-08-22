# Group E — everything built after the roadmap closed

## DONE — run 2026-08-22

*Every item on this list has been run in game. E9 and E12 were not run deliberately and
were covered incidentally by the migration sitting, which is the honest way to record
them: exercised, not stepped through.*

**What the sitting turned up, all fixed:** the market switcher deleting itself when the
guidance above it grew (`E15`); Host still live on a market a dedicated server was
already serving (`E16`); the dedicated-server warning running off the panel edge; and a
regression in the migration guard that stopped the second person leaving a shared market
from arriving at all (`E11b`). Three of those four were introduced by fixes made earlier
in the same session, which is the thing to remember about this list rather than any
individual item on it.

`E11` also had to be rewritten: it pointed at the migrant's own credit counter, which is
the one number migration guarantees will not change. It looked like a failure and was not.

### Six items want a short re-check

Everything was tested against the code as it stood. Two runtime changes landed at 13:58
after the session ended (`59eb94f`), both in `MarketScreen`, and everything from `E17`
down is newer than the sitting:

- **`E13`** — `Disconnect` is now keyed to the mode rather than the live socket. The new
  case is the only one worth re-running: **let the host drop you, then confirm Disconnect
  is still live.** Keyed to the socket it went grey, stranding you in a market you could
  neither trade in nor leave
- **`E14`** — `foreignHost()` now prefers a host you could actually migrate to. This only
  differs when **two or more** foreign markets are visible at once, one of them a
  dedicated server: Migrate should be offered, targeting the non-dedicated one. With only
  the server visible, nothing changed
- **`E17`** is new since the sitting and has not been run at all: the welcome-grant
  ceiling now differs by who is hosting. Worth five minutes on its own, and its last
  bullet has been corrected — a world does not refuse to start over an unusable config,
  it falls back and hosts anyway
- **`E17b`** is newer still: `/trade hostconfig` and `/trade hostconfig write`, which are
  the first way to reach a host rule without knowing the file exists
- ~~**`E18`**~~ **run 2026-08-22 and exact** — parted after event 22, confirmed against
  both logs on disk. It also turned up that the discovery poll never asks for a split
  point, so the banner you get without pressing Connect is still the old one
- ~~**`E19`**~~ **run 2026-08-22 and correct** — the two post-split deposits came back and
  the four pre-split ones did not. `E19b` is new: the confirmation was rewritten around
  what it found, and has not been run
- ~~**`E20`**~~ **run 2026-08-22 and correct** — the banner went the moment Bob joined.
  The same run under-refunded by five items, which is §0.22 and is now fixed: the AHEAD
  path never asked where the two chains parted
- **`E21`** is new from that run: the re-place list could not be scrolled, and it is the
  only record of what to put back

Nothing else on this list has moved since it was run.

---

*The stipend, the escalating listing fee, the welcome grant control, and a Market column
that now scrolls. The UI half mattered most, because nobody had looked at it.*

Run the suites first. Expect `531 / 6 / 5 / 16 / 25 / 6 / 12 / 16 / 22`:

```
./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest \
    depositCapTest attestationTest hostTrustTest splitPointTest
```

`hostTrustTest` is new — it checks a host is trusted to order events and for nothing
else.

Do **E1 before anything else** — the layout changed under every other test on this list,
so a fault there will look like a fault in whatever you were actually testing.

---

## E1. The Market column, which changed shape

The controls column gained a fourth policy row and now hides rows that fall outside the
frame instead of drawing past the bottom. It scrolls to reach them.

Set up the fullest case it can reach: **be the creator**, connected to a host, with a
second market in the world (Add another market) and a foreign host visible on Network.

- Nothing draws below the panel's bottom edge. This is the fault that has recurred most
  in this project and is the reason for the whole change
- The four policy controls — trading fee, listing fee, stipend, welcome grant — each sit
  on one row, field left, button right, and neither half is cut off
- Scroll the **controls** column with the wheel. Rows should come into view from the
  bottom, and the panel beside it should not scroll instead
- **Scroll all the way down and confirm the last row is reachable.** This is the one to
  spend time on. The scroll extent was measured against the frame while `place()` clipped
  rows to the panel's interior — five pixels in at each end — so the extent came up nine
  pixels short and the bottom row could never be scrolled into view at all. In this
  setup that row is **Remove this market**. Fixed, and confirmed in the sitting
- Scrolled-off rows must not be clickable. Scroll a button half out of view and click
  where it was
- Shrink the window to its smallest and confirm the column still scrolls rather than
  simply losing its lower half

## E2. The listing fee climbing with orders held

**The allowance** is how many orders you may hold open before the fee starts climbing.
Two credits with an allowance of three means three orders at 2 each; the fourth costs 4,
the fifth 6, the sixth 8. Cancel one and it comes back down — it prices what you are
holding open, not what you have ever placed. An allowance of **0 turns escalation off**
and gives the flat fee, which is what every market had before this.

Type both into the **Listing fee** field on the Market tab, creator only:

```
2       a flat fee of 2, no escalation
2/3     a fee of 2, with 3 orders free before it climbs
0       no listing fee at all
```

*This is new. Until now the field took only the fee, and the allowance had no control
anywhere — not a field, not a server-config key, not a command. `listingFreeOrders` was
written in exactly one place, `submitPolicy`, which copies whatever it already was, so it
was zero at genesis and zero forever. Every market ever created charged the flat fee and
the escalating half of this feature was unreachable. The doc used to say "set the free
allowance above 0 by hand", and there was no by hand.*

Set the fee to `2/3` and watch the credit counter as you place sell orders on something
cheap:

- The **first three** orders each cost 2
- The **fourth** costs 4, the **fifth** 6
- `About this market` gains a second line naming the allowance, and tells you what your
  own next order would cost once you are past it
- **Cancel one** → the next order is cheaper again
- Somebody with nothing resting still pays the base 2. Never free is what the stipend's
  safety rests on, so this is the one that matters if any of them fail
- Set it back to `2` → the extra About line goes and the fee is flat again
- Try `2/` and `/3` → refused, naming the format, rather than quietly setting something
- Try an allowance with no fee, `0/3` → refused, because an allowance needs a fee to
  climb from

The schedule above is also not what the code did when this was written. It counted only
the orders already resting, so an allowance of three let *four* through at the base fee —
and `T1e` pinned that while its own comment described the corrected behaviour. Both
fixed; `T1e` and `T5b` now read it the way this list does.

## E3. The stipend, end to end

A stipend needs a listing fee first. Setting one without gives:

> a stipend needs a listing fee — without one, fills cost nothing to produce and anyone
> could trade with themselves for it

That wording comes from `EventApplier.stipendOutpacesItsFees` rather than from the screen.
The screen used to phrase this itself, alongside its own copy of the interlock's
arithmetic — and that copy was the version from before the rule was corrected twice, so
it advertised a ceiling four times the real one. It now asks the one function, so
whatever the engine would refuse is what you are told, in the engine's words.

Then, as creator: listing fee `2`, stipend `20`.

- `About this market` gains a line naming the stipend and counting down — *"yours in N
  more"*
- Trade with the second client until the count reaches zero
- **Claim stipend** appears on the Market tab only then. Credits land, no confirmation
- The button goes, and the countdown restarts

The interval is 50 trades and is not settable from the UI. That is deliberate — it is one
of three numbers that have to agree — but it does mean this test needs 50 fills. One order
crossing a stacked book produces a fill per resting order it consumes, so rest a row of
small asks and sweep them to get there quickly.

**The number to sanity-check:** at a listing fee of 2 with two registered identities, the
most a stipend can be is under 50. Try 60 and it should refuse *inline, before the
confirmation overlay*, naming the head count. Until this was fixed, 60 sailed past the
screen's own check — which would have let anything under 200 through — and was refused by
the engine after you had confirmed it, as a raw `Rejected:` line.

## E4. Turning it off

- Set the stipend to `0` → *"Stop paying a stipend?"*
- The claim button and the About line both go
- A client that still had the old figure in flight should be told *"this market pays no
  stipend"*, not that its number is wrong
- Setting it to zero must work even with no listing fee left. If it did not, the fee could
  never be lowered either

## E5. The welcome grant control

New, and behind a DANGER overlay rather than the plain confirm the fees get.

- The confirmation says the change reaches only people who have not joined yet
- Set it to something small, join with a fresh identity, confirm they receive that rather
  than 1000
- Anyone already in the market is unaffected

Worth doing simply because every rotating market until now granted exactly 1000 with no
way to change it, against items trading for 1 or 2.

## E6. A dedicated server opening a market with rules

```json
{ "welcomeGrant": 50, "listingFee": 2, "stipendAmount": 20 }
```

Delete `server-market.jsonl` first, with the server stopped.

- Startup names all three: `created '<name>' (<id>) — welcome grant 50, listing fee 2,
  stipend 20 every 50 trades`
- A bad pairing is refused at startup rather than written into genesis. Try
  `stipendAmount: 500` against `listingFee: 2` and it should refuse before listening,
  naming the largest that fee can carry

This is the only route to a stipend on a dedicated market bootstrapped without
`--creator-key`, because that market's creator is the server itself and a server has no
screen to set policy from.

## E7. A policy change must not wipe the others

The bug that nearly shipped. A `MarketPolicy` event carries the whole policy, so anything
not restated goes to zero.

- Set a stipend, then change the **trading fee**
- The stipend must still be there afterwards, at the same figure

Same for the listing fee and the grant, in any order.

## E8. Selling with no credits, which used to duplicate items

The one on this list that was losing nothing and gaining items. A sell is a single
`DepositAndList` event, and its two halves were checked in two places that disagreed:
`validate` looked at quantity, price and item id, while `apply` deposited the goods and
*then* asked whether the seller could pay the listing fee. So the event passed validation,
went into the log, and was refused afterwards — with the deposit already applied. The
client answers a refusal by handing the physical items back, so they existed on both
sides.

Note that **A5 already exercised this and was marked passing.** Its "spend down to under
5 credits and try to sell → refused, naming the amount" is exactly the recipe; the message
it checked for could only ever have come from the path that had already deposited.

Set a listing fee of `5` and spend down to fewer than 5 credits, holding items.

- Try to sell → refused **before the items leave your inventory**, naming the fee and what
  you have. The stack should not flicker out and back
- Your item count is unchanged, and so is your credit balance
- Nothing appears in your market ledger for that item — check the Markets list, not just
  the book
- On the host's console there should be **no** `[host] BUG: validate passed but apply
  rejected` line. That message is the fingerprint of this bug; if it appears, something
  else has come apart the same way
- Other clients should see no sequence gap. The refused event never enters the log now, so
  nothing after it shifts
- Earn or receive 5 credits and sell again → it goes through, and the fee is taken

## E9. Migrating a doctored history

Engine-tested (`K5b`), and worth an eye if a migration sitting happens anyway.
`MarketArchive.verifyLines` checked signatures and the hash chain and then called `apply`,
which enforces none of the money rules — those live in `validate`, because that is where a
host asks them before appending. A hand-built history could therefore carry a welcome
grant for any sum, repeated, and the balance it replayed to is what a migration brings in.
Nothing downstream caught it: the plausibility check weighs the *items* a migrant brings
against their own statistics, and never their credits.

- An ordinary migration between two real markets still works end to end. That is the whole
  risk in the fix — refusing too much — and it is what to actually check
- The refusal for a bad archive names the rule, not the signature: *"event N breaks this
  market's own rules"*

## E10. A host that is trusted too far

Engine-tested (`hostTrustTest` H1/H2), and the reason `E9` is only half the story.
`MarketClient` verified every broadcast's signature and then called `apply` — never
`validate`. A signature proves who wrote an event, not that the event was allowed, and
everything deciding that lives in `validate`. So a modified host could sequence itself a
grant for any sum, sign it correctly with its own key, and every connected replica would
apply it, **write it to its own log, and re-serve it the next time that player hosted.**

The client asks `validate` now, and refuses the connection if it fails. That is a real
change to the live sync path, so the thing to watch for is the opposite failure:

- Join a host, trade for a while, have somebody else join and trade too. **Nothing should
  ever disconnect you with "host sent an event this market's own rules refuse".** That
  message appearing during ordinary play means validate and the host's own append path
  have come apart, which is a bug in this change and not in the host
- The same across a reconnect, and across a host handover — the two moments where a
  client's state and the host's are most likely to differ
- A dedicated server, since its market's creator is the box and its policy path differs

If it does fire, the console line names the rule and the sequence number. Keep it.

**It already fired once, and was right.** The first real session produced
`REFUSING event 5 from host: breaks this market's rules — already granted in this market`
on a plain self-connect. Not a false refusal: the client was holding state through event
5 while believing it was at event 4, so the host re-sent it and the welcome grant was
applied to that replica twice. See ``0.13` in the session log; fixed, with `H3` behind it.
The reason it looked like a false positive is the reason to keep reading these lines
rather than assuming.

## E11. Two markets with different grants, meeting

The question that produced this section: A and B run a market granting 50; C and D run
one granting 1000; C and D migrate in. Measured before anything was changed —

```
market X (A,B) grant 50    supply 100    A=50   B=50
market Y (C,D) grant 1000  supply 2000   C=1000 D=1000

after both migrations:     supply 2100
A and B now hold 4.8% of the money in their own market
```

Nobody is robbed — A and B keep every credit. But share is what buys things, and theirs
went from all of it to a twentieth. Prices reprice to what C and D can pay.

### Do not watch your own credit counter

**It is supposed to be unchanged, and watching it is how this test looks like it failed.**
You had 1000 before migrating and you have 1000 after; that is what "carries your balance"
means. The migrant's number is the one number that does not move. Neither does the
founder's — they still have their 50.

What moves is the market underneath both of them:

| | before | after |
| --- | --- | --- |
| founder's credits | 50 | **50** |
| migrant's credits | 1000 | **1000** |
| the market's total | 50 | **1050** |
| founder's share of it | 100% | **4.8%** |

There is no screen that shows a total, so read it one of two ways. Add the two credit
counters together across the two clients — or, better, watch the effect, which is the
thing that actually matters:

- **As the founder, list something for sale. As the migrant, outbid them twenty to one.**
  Then try it the other way round and watch the founder be unable to compete for anything
  the migrant wants. That is the whole finding, and it is invisible in any single number

To confirm it landed at all, the host console is unambiguous:

```
[host] seq N MigrateBalance
[host] migrated 1000 credits for <uuid> from '<market>'
[host] welcome grant … refused: already accounted for by a migration
```

That last line is a pass, not a fault — a migrant brings their balance *instead of* a
welcome grant, never as well.

### The cap

`maxMigratedCredits` in `host-config.json` is the answer to the dilution, and it is **off
by default** so nothing changes until a host sets it:

```json
{ "maxMigratedCredits": 500 }
```

- Build the two markets above, or any pair with different grants
- With the cap unset, the migration goes through and the supply jumps. This is the
  behaviour, not a bug, and it is worth seeing once
- Set `maxMigratedCredits` to something under what the incoming player holds → the
  migration is refused before anything is written, naming both figures
- Set it above → it goes through as before
- `maxMigratedCredits: -1` → the server refuses to start, saying to use 0

It bounds one migration, not a career of them. Somebody arriving repeatedly under the cap
still adds up — but they cannot, because of E12.

**See also `E11b`**, which is further down the page rather than here: two people leaving
one market together, which is a different guard and regressed once already. The numbering
is out of order because the items are cited from commit messages and renumbering them
would break those.

## E12. Migrating twice, which used to be unbounded

Engine-tested (`M6b`). The guard meant to stop this is in `EventApplier` and its comment
names the attack exactly — *"join, take the grant, reset, create your own market, take
that grant too, migrate it back, repeat"* — but it tested only whether the beneficiary was
**registered** or **already granted** here. A migration sets neither. And the per-branch
guard is keyed to the *source* market id, which is a fresh random id every time somebody
creates a market.

So the same identity could create a market at the 1,000,000 grant ceiling, take it,
migrate in, reset, and repeat. Measured at **4,000,000 credits in four passes**, against a
market whose founder held 50, without ever registering.

- Migrate into a market, then create a second market of your own and try to migrate that
  in too → refused: *"you already hold a position in this market"*
- A player who has genuinely never been there still migrates in fine. That is the half
  that matters — the fix must bound migration, not close it

## E13. Disconnect, while hosting

Reported from play: disconnected, and the host list still showed the market being
hosted — and the other client could still connect to it.

Both buttons on the Network tab were always live, side by side, whatever mode you were
in. While hosting, the obvious one to reach for is **Disconnect** — it is the left one
and it is the word people use — and it only dropped the self-connection. The server
stayed bound, kept serving whoever was already on it, and kept answering the discovery
poll. The button said Disconnect and nothing disconnected.

The quiet half was worse and nobody would have seen it. `stopHosting` is the only thing
that releases the HostServer's `EventLog`, so disconnecting fell through to `loadLocal`
and opened a **second** `EventLog` on the file the running host still owned. Two writers
on one log is duplicate sequence numbers and a broken chain — the exact failure
`connect()` has guarded against since it was written, with a comment saying so. The guard
was never carried across to `disconnect()`.

Now: `disconnect()` stops the host if there is one, and the two buttons grey by mode so
only the one that applies is live.

**Start hosting, have the second client connect, then press Disconnect.**

- The second client is dropped, and says so
- It **cannot reconnect** — the port is closed, not merely unadvertised
- Your own Network tab no longer lists you as hosting, and neither does theirs after a
  **Refresh hosts**
- **Stop hosting** is live only while hosting; **Disconnect** only while on somebody
  else's host. Offline, **both are greyed** — there is nothing to leave
- **Disconnect stays live if the host drops you.** That state exists (the Trading tab
  says *"Connection to the host was lost — reconnect to trade"*), and it is exactly when
  somebody wants to press it. It is keyed to the mode, not to the live socket, because
  keying it to the socket left you in a market you could neither trade in nor leave
- Now trade or change a policy → your log is still healthy. No *"your log is damaged"*
  banner, no duplicate sequence numbers. This is the half that was silent
- Repeat with **Stop hosting** instead of Disconnect; both should do the same thing now

Then the same from the other side: connect out to somebody else's host and press
Disconnect. That path was always correct and must stay correct — it is the one the
greying could plausibly break.

## E14. Joining a dedicated server without giving up your own market

Migration is for people who know each other. The balance it carries was set by a welcome
grant the migrant *chose*, in a world they control, up to the 1,000,000 ceiling — so on a
public box it is not "bringing your savings", it is "naming your opening balance". A
dedicated server now declines migrations unless its operator turns them on:

```json
{ "dedicated": true, "acceptsMigration": true }   // to allow them again
```

Unset is not false: unset means **off for a dedicated server, on for somebody's game**.

The route that replaces it costs nothing, and is worth knowing regardless of servers —
market slots are separate log files, so joining from a new one leaves your existing
market untouched. That is now what the UI recommends first, everywhere.

With a dedicated server running and a market of your own in your world:

- The Network tab lists it with its `[server]` badge, as before
- On the Market tab, **Migrate is not offered** for it, and the guidance beside the
  column says why and names the alternative
- Use **Add another market**, then Connect → you sync the server's market and receive
  its welcome grant like any newcomer
- **Switch back to your first market slot** → it is exactly as you left it. Same balance,
  same orders, same history. This is the whole point of the recommendation
- Set `acceptsMigration: true` on the server, restart, **Refresh hosts** → Migrate is
  offered again and works

And the refusal itself, which needs a modified or older client to reach — or just point a
second in-game host at it — should arrive **before** you upload a history, and should say
what to do instead rather than only "no".

Then confirm nothing regressed for the ordinary case: two in-game hosts, different
markets, Migrate between them still offered and still works. That path is unchanged and
is the one this could plausibly have broken.

## E15. The guidance column, which now scrolls too

Reported from play, with a screenshot: the **Markets in this world** switcher was simply
absent. Not cut off — gone.

The left column on the Market tab stacks a heading, a paragraph, sometimes a second one
about a host on the network, this market's facts, and the switcher. It had no bottom, and
each piece independently gave up when it ran out of room:

```java
if (y > panelBottom() - 24) { slotListTop = -1; return; }   // the switcher
if (y > panelBottom() - 40) return y;                        // About this market
```

The switcher is last, so it went first — and it is the only *control* in that column, so
the one thing that had to survive was the one thing guaranteed not to. A longer paragraph
was enough to trigger it, which is exactly what `E14`'s dedicated-server advice added.

It scrolls now, on the same terms as the controls column beside it — `E1`'s fix, applied
to the other half of the screen.

Set up the fullest case: **be the creator**, with a second market in the world, a foreign
host visible on Network, a listing fee with an allowance set, and a stipend running. That
fills the column past its bottom.

- **The switcher is present.** Scroll to it if it is below the fold, but it must exist
- Scroll the **left** column with the wheel. The controls column beside it must not move
- Everything reachable: heading, both paragraphs, every About line, every market row
- **Scroll a market row half out of view and click where it was** → nothing happens. A row
  you cannot see must not switch your market
- Scroll to the very bottom → the last market row is fully readable, not clipped
- Shrink the window to its smallest and confirm the column still scrolls rather than
  losing its lower half
- With only one market in the world the switcher is correctly absent — that case is
  "nothing to choose between", not "no room"

## E16. Hosting a market a dedicated server already has

Reported from play: a player can still press **Host** on a market a dedicated server is
serving. That is the one reliable way to fork a market permanently — the box does not
stop, so both of you keep sequencing, and two branches of one market cannot be merged.
See `docs/design/fork-rebase.md` for why there is no way back from it.

There was a collision warning, but it offered **"Take over"**, which is a fair offer
against somebody's game — they may be about to log off — and never against a server that
is always up. A comment beside the Migrate button had claimed the Host button was greyed
on a dedicated market since before anything greyed it.

With the dedicated server running and your client holding that same market:

- **Host is greyed** on the Network tab, and the host list says why:
  *"a dedicated server has this market — connect, don't host"*
- The greying holds whether you are connected to the server or merely see it in the list
- Refresh hosts, then **click a host row** → it still joins the right one. This is the
  regression to watch: the explanation line sits above the rows, and the row positions
  used to be computed twice — once for drawing, once for clicking. They are one function
  now, but a shifted row that joins the wrong host would be silent
- Stop the dedicated server, **Refresh hosts** → Host comes back, and the line goes
- Hosting a market *no* dedicated server has is unchanged, warning and all

If you can still reach the warning (a stale poll leaves the button live), it now has no
confirm button — only **OK**. There is no "Take over" for a box that never leaves.

## E11b. Two people leaving one market together

Split from E11 because it is a different guard and it regressed once already.

The fix for repeated migration (`§0.11` in the session log) first used the wrong set: the
one recording everyone who was *registered* in a market somebody migrated out of, which
exists to deny them a second welcome grant. The first arrival filed all their friends, so
the second was turned away as though they had already been paid. `M6b` passed throughout,
because it only ever tested one identity arriving repeatedly.

With **two** players in one market, both migrating into a third:

- **Both land.** The second must not be told *"you already hold a position in this
  market"* — they hold nothing here
- Each keeps their own balance, and the receiving market's total goes up by both
- **Neither collects a welcome grant on top.** The console says *"already accounted for by
  a migration"* for each of them, which is the correct refusal
- Then try a **second** migration for one of them, from a market they make fresh → refused.
  That is the mint, and it stays shut

## E17. The welcome-grant ceiling, which differs by who is hosting

Somebody hosting from their game may now grant at most **10,000**. A dedicated server
keeps the compiled 1,000,000, because it is the deployment that might legitimately want a
large one — set once by an operator — rather than one keystroke from a player wondering
what happens. `maxWelcomeGrant` in `host-config.json` moves either.

It is a **host rule**, not a market rule, and that is not a detail. The ceiling in
`EventApplier` is replicated: every replica must reach the same verdict on the same event.
"Rotating" and "dedicated" describe whoever is hosting *at this moment*, so a ceiling that
told them apart there would make one policy event legal on one host and illegal on the
next — and hosting rotates by design. It also means **nothing existing breaks**: a market
that already set a larger grant still replays its own policy, because only the next change
is judged.

As the creator of a market hosted from your own game:

- Type a grant of `20000` → refused inline, naming 10,000, before any confirmation
- `5000` → the DANGER overlay as before, and it goes through
- **Check an existing market with a big grant still opens.** If one is lying around from
  earlier testing with a grant above 10,000, load it: it must replay and trade normally.
  Only *setting* a new one is capped
- Now host that same market from a dedicated server with `welcomeGrant` under its own
  ceiling → the larger figure is still sequenced there

And the console refusal, which needs a second client proposing it rather than the screen:

```
[host] refused a welcome grant of 20000 — this host allows 10000
```

- Set `maxWelcomeGrant: 500` in a world's `host-config.json`, leaving `welcomeGrant` at
  the 1000 the generated file carries → **nothing complains, and the ceiling applies.**
  The two are unrelated on a world: nothing there bootstraps a market from this file, so
  `welcomeGrant` is only the switch deciding whether grants go out at all, and the amount
  is the market's. `/trade hostconfig` must show the file as usable, and a grant of 1000
  proposed by the creator must then be refused at 500
- **This is the step that found the bug**, so it is worth knowing what it used to do.
  `problem()` paired those two settings on every host, so lowering the ceiling in a
  generated file made the file unusable — and `hostPolicyFor` answers unusable by
  discarding all of it and hosting on the friend-group defaults. The one edit the file
  exists for silently turned off every rule in it, *including the ceiling being lowered*,
  and said so in one console line. Pinned by `R1d`
- The same numbers on a dedicated server still refuse to start, naming both figures: a
  box **does** bootstrap a market from this file, and one that would decline to sequence
  the grant it had just created a market with is arguing with itself
- Set `welcomeGrant: 0` in a world → grants stop going out entirely. Newcomers arrive
  with nothing. This is the only thing that key does on a world, and it is worth seeing
  once so the other value never gets read as an amount

### E17b. Finding the file at all

New with the command. Before it, `host-config.json` was created by hand, in a directory
nothing named until you had already hosted once, and no host rule had a control anywhere
in the UI. The point of these steps is discovery, so run them in a world that has never
had the file.

- Host from a world with no `host-config.json` → the console names the file, says what
  the defaults are **including the welcome-grant ceiling**, and names the command
- `/trade hostconfig` with no file → every host rule, at the value in force, each spelled
  the way the file spells it. The path above them. It must not list `port`, `hostName`,
  `hostUserId`, `dedicated`, `logFile`, `marketName` or `creatorUserId` — hosting imposes
  or ignores all seven, and a key you can edit and watch do nothing is worse than one
  that is absent
- `/trade hostconfig write` → the file appears at that path, holding exactly the lines
  the command just listed. Nothing changes: every value is the one already in force
- Run `write` again → **refused**, naming the file. It never overwrites: the file is
  hand-edited by definition and the writer round-trips through Gson, so a second write
  would silently drop any key Gson does not know — a misspelt one, which is exactly the
  case somebody is trying to see. Delete it to regenerate
- Edit `maxWelcomeGrant` to `500`, host again → the console says it is hosting under the
  rules in that file, and a grant of `1000` is now refused at 500 rather than 10,000.
  **This is the check that the file is read at all**, and the one worth doing twice
- Set `admission: "allowlist"` with an empty `allow`, then `/trade hostconfig` → it
  reports the file as unusable, naming the field, and warns that hosting will ignore all
  of it. Confirm hosting then does exactly that
- Try the command on somebody else's Minecraft server → refused with a reason. There is
  no local world, and the rules belong to the world a market is hosted from
- Copy a dedicated server's `server-config.json` in as `host-config.json`, then
  `/trade hostconfig` → the ceiling reads **10,000**, not 1,000,000, and migrations read
  on. `dedicated` is stamped false before anything is resolved, because it is what those
  two defaults resolve against. `R1c` pins this without Minecraft

## E18. Where two branches parted

*Run 2026-08-22 and exact.* Alice's branch reached 71 events and Bob's 54; the console
reported `parted after event 22, 32 of ours since`, and comparing the two logs on disk
afterwards put the last common sequence at 22 with the first disagreement at 23. The
"32 of ours" follows from 54 − 22.

**One thing to know before running it, which cost an evening the first time.** The number
only appears on the path that asks for it: `noteForkFromRefusal`, which fires on a
**Connect that comes back FORK**. The discovery poll sets a divergence too, and its
`splitAt` is `-1`, so the banner falls back to *"differs at event N"* — where N is the
other side's head. Two clients polling each other therefore show two different numbers
(52 and 47, in the sitting), neither of which is a split point and both of which are
correct for what that message says. If you are looking at numbers that disagree, you are
looking at the poll. Press Connect.

That gap is real and still open — see the session log's §0.20 and backlog item 7.

Until now the protocol could say *that* your chain disagreed with a host's and never
*where*: the FORK refusal compares one hash at one point, and the split is somewhere at
or below it. So "differs at event 400" might have meant four events of divergence or four
hundred, and nothing could tell them apart.

Build a fork the way `B2` does — Alice hosts, Bob syncs, Bob disconnects, **both** trade,
Bob reconnects.

- The FORKED banner now names where you parted: *"you parted after event N, and
  everything either of you did since is on one branch only"*
- **N must be the last event you both actually hold.** Count from the trade that split
  you: if Bob disconnected at event 30 and both sides then traded, N is 30. Too high
  hides events that were only ever Bob's; too low would offer back orders Alice still
  holds, which is how a reset creates duplicates
- The host console shows the search finishing; the client logs
  `parted after event N, M of ours since`
- **Diverge by a lot.** Trade fifty or more events on each side and confirm the number is
  still exact — the search narrows a bracket rather than walking, so an off-by-one in it
  would only show up over a long divergence
- Kill the host between the refusal and the banner → the banner still appears, saying it
  could not locate the split rather than failing outright. Asking is a second round trip
  on purpose, so losing it costs the detail and not the refusal

It does not change what a reset does yet. That is the next backlog item — refunding the
deposits a reset would otherwise destroy, which needs exactly this number to know which
deposits were only ever on the losing branch.

## E19. Items handed back when a fork is discarded

*Run 2026-08-22 and correct.* Alice and Bob forked a 'newQ' market; the split search
found event 22 and Bob's branch ran to 54. His only deposits after the split were seq 53
(60 cobblestone) and seq 54 (1 crafting table), and those two were exactly what came
back. His four pre-split deposits — seqs 7, 8, 17 and 18 — stayed where they were and
returned with the shared history when he re-synced Alice's 71 events. The `insufficient
item balance` refusal that follows a re-place attempt is the correct sequel, not a fault:
the goods are in his inventory now, not the market's.

**What the sitting changed is the wording, not the arithmetic** — see below, and the
session log's §0.20.

Discarding a forked branch used to destroy items outright: anything deposited after the
split physically left your inventory, and the branch holding the only record of it was
deleted. Balances from before the split come back when the shared history is adopted;
orders come back as a checklist; those items were simply gone.

They are returned now. The split point — `E18` — is what made it possible to tell which
deposits were only ever on the losing branch.

Fork as in `B2`, then **while forked**:

- Deposit something distinctive, say 40 iron. Note your inventory count
- **Reset log** → the 40 iron are back in your inventory. The console says
  `returned 40 minecraft:iron_ingot — deposited after the split, and the reset discarded
  the only record of them`
- Your pre-split balance comes back on reconnecting, as before, and is **not** duplicated

The failure worth hunting is the opposite one — **items appearing twice**. Each of these
must return nothing:

- Deposit 10 after the split, then **withdraw** all 10, then reset → nothing returned.
  They are already in your inventory
- Deposit 20 after the split, then **sell** them to the other client, then reset →
  nothing returned for the sold 20. You hold credits for them; the buyer holds the goods
- Deposit 20 and rest a **sell order** for all 20 → all 20 come back. Reserved goods are
  still yours
- Items you held from **before** the split → never returned by the reset, because the
  host's copy still says they are yours

Then the boring one that matters: reset a market you never forked from, with no
divergence at all → nothing is returned, and nothing is lost.

### E19b. What the confirmation says

The dialog is now built from the same `ResetCost` the reset acts on, so it names the real
items rather than describing the shape of them. Reported from the first sitting as reading
like everything was gone, on a screen that was about to hand 61 items back.

- With a fork, the overlay must name **the split point**, **the items by name and
  count**, **the number of orders**, and — this is the part the old wording never said at
  all — that credits earned since the split and the trades themselves are gone for good
- **The numbers must match what the console then reports.** They come from one call now;
  a dialog that promised different items than the action returns would be believed, which
  is worse than no dialog
- With **no** fork, none of the recovery sentences appear. There is nothing to rejoin, so
  every one of them would be false
- Deposit **four or more different kinds** of item after the split, then open the dialog →
  three are named and the rest are counted. The overlay stacks a line at a time and has no
  scroll; an uncapped list grows it with your inventory
- **Check the buttons are on screen** at 1920×1080 and at the smallest window Minecraft
  allows, with the longest version of the message — four item kinds, orders, and a fork.
  The box used to pin to `y=20` and grow downwards off the bottom

## E20. A fork warning that has been answered

New, never run. Reported from play: Alice warned that Bob was on a different branch, Bob
discarded his branch and joined her, and **the banner stayed up for the rest of the
session.**

Only `observeHostHead` ever retired a divergence, and it retires one by watching the
named host advertise a matching head. Somebody who stops hosting in order to come and
join you is no longer a host, so nothing could ever revisit the claim. Two ways out now,
one for each direction the players might settle it in.

**They join you** — you host, they discard and connect:

- Fork as in `B2`. Confirm the FORKED banner is up on the host
- The other player discards and connects to you
- The banner must go, within a frame of the Market screen being open. The console says
  `<name> is synced to this host now — retiring the fork warning about them`
- **Trading must work immediately.** The banner drives `MS_FORKED`, so a stale one is not
  cosmetic — check you can place an order without reconnecting first

**You join them** — they host, you discard and connect:

- Same fork, the other way round. The banner must go on connecting
- Nothing is printed for this one: adopting their chain retires every claim at once,
  because each was measured against a head you no longer have

Then the two that must **not** clear:

- A fork with a host you have neither joined nor been joined by → the banner stays. Only
  evidence retires it, never time
- **Three participants**: fork from Alice, then have Carol join you while still forked
  from Alice. Carol syncing says nothing about Alice, and the banner must survive it.
  Matching is by display name, so this is also the check that a second player joining
  cannot retire somebody else's warning by having a name that happens to collide

The name matching is the known weakness, and it is deliberate: a FORK refusal carries
`hostName` and no identity, so a name is what the two sides have in common. The cost of a
collision is a warning retired early on a market the player is already connected to; the
cost of not asking was a warning that never went away.

## E21. The re-place list, when there is more of it than fits

New, never run. Nine orders came back from an `E19` run; the box fits fewer; the rest
were drawn nowhere and reachable by nothing. The list is the only record of what to put
back, so the far end of it being unreachable loses exactly what the feature exists to
save.

Get a checklist longer than the box — ten or more orders on the losing branch is easy,
since every `DepositAndList` makes one — then:

- **Scroll it.** The wheel works anywhere over the box, and every row can be brought into
  view. Before this it stopped at the panel bottom and the rest did not exist
- **Click a row after scrolling.** It must re-place *that* row. The drawing and the hit
  test both go through `replaceRowY` and `replaceRowVisible`, so a mismatch here would be
  the oldest defect in this file — and scrolling is what makes it possible at the top of
  the list as well as the bottom
- **Click above the first row and below the last.** Nothing happens. A row scrolled out of
  sight must not still be clickable where it used to be
- Re-place rows until fewer remain than fit → the offset clamps and the list does not sit
  scrolled past its own contents
- The header still dismisses the whole list, at any offset

Worth doing on the **Market** tab and in the side column both, since `replaceInSideColumn`
puts the box in two different places.
