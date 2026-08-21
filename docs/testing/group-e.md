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

- The first three resting orders each cost the base fee
- The fourth costs double, the fifth triple
- **Cancel one** and the next order costs less again — the fee prices what you are
  holding open, not what you have ever placed
- Somebody with nothing resting still pays the base fee. Never free is what the stipend's
  safety rests on

## E3. The stipend, end to end

A stipend needs a listing fee first. Setting one without gives:

> Set a listing fee first — a stipend with nothing to pay for it could be earned by
> trading with yourself

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
most a stipend can be is under 50. Try 60 and it should refuse, naming the head count.

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
