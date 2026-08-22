# Group E — everything built after the roadmap closed

*The stipend, the escalating listing fee, the welcome grant control, and a Market column
that now scrolls. All of it has automated coverage and none of it has been run in game.
The UI half matters most, because nobody has looked at it.*

Run the suites first. Expect `487 / 6 / 5 / 16 / 16 / 6 / 12 / 11`:

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
