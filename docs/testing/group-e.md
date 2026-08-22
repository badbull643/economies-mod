# Group E — everything built after the roadmap closed

*The stipend, the escalating listing fee, the welcome grant control, and a Market column
that now scrolls. All of it has automated coverage and none of it has been run in game.
The UI half matters most, because nobody has looked at it.*

Run the suites first. Expect `494 / 6 / 5 / 16 / 21 / 6 / 12 / 16`:

```
./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest \
    depositCapTest attestationTest hostTrustTest
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
  setup that row is **Remove this market**. Fixed, and untested by eye
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

`maxMigratedCredits` in `host-config.json` is the answer to that, and it is **off by
default** so nothing changes until a host sets it:

```json
{ "maxMigratedCredits": 500 }
```

- Build the two markets above, or any pair with different grants
- With the cap unset, the migration goes through and the supply jumps. Watch the credit
  totals on both sides — this is the behaviour, not a bug, and it is worth seeing once
- Set `maxMigratedCredits` to something under what the incoming player holds → the
  migration is refused before anything is written, naming both figures
- Set it above → it goes through as before
- `maxMigratedCredits: -1` → the server refuses to start, saying to use 0

It bounds one migration, not a career of them. Somebody arriving repeatedly under the cap
still adds up — but they cannot, because of E12.

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
- **Stop hosting** is greyed while you are not hosting; **Disconnect** is greyed while
  you are. Only one of the pair is ever live
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
