# Group E — everything built after the roadmap closed

*The stipend, the escalating listing fee, the welcome grant control, and a Market column
that now scrolls. All of it has automated coverage and none of it has been run in game.
The UI half matters most, because nobody has looked at it.*

Run the suites first. Expect `437 / 6 / 5 / 16 / 16 / 6 / 12`.

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

```
listing fee 1, and set the free allowance above 0 by hand if you want to see escalation
```

Zero allowance means no escalation at all, which is the default and what existing markets
keep. With an allowance of 3:

- The first three orders each cost the base fee
- The fourth costs double, the fifth triple
- **Cancel one** and the next order costs less again — the fee prices what you are
  holding open, not what you have ever placed
- Somebody with nothing resting still pays the base fee. Never free is what the stipend's
  safety rests on

This is what it does now; it is not what it did when this was written. The code counted
only the orders already resting, so an allowance of three let *four* through at the base
fee, and the automated test pinned that while its own comment described the behaviour
above. Both corrected — `T1e` now reads the schedule the way this list does.

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
