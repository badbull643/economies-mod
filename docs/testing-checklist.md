# In-game testing checklist

*Everything a headless test cannot reach. Automated suites cover the engine rules and
their wiring; this covers the parts that need a keyboard and a screen.*

*Groups A to D are the roadmap work and are finished bar two items that cannot be forced.
Group E is everything built after it, and none of that has been run yet.*

Run `./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest
depositCapTest attestationTest hostTrustTest` first — if any of those fail, stop, because everything
below assumes they pass.

## Already confirmed — do not spend time here

- Tab row, Home dashboard with a populated market, item cell palette
- `[server]` badge in the host list, "About this market" naming a dedicated host
- Alert band above the tabs, host rows trimmed
- Migration end to end, cross-poll divergence, connect-time fork refusal, `CatchUp`
  correctly declining a non-fast-forward
- Dedicated launcher: `--help`, `--write-config`, bootstrap, `--creator-key`, bad port,
  unknown argument, busy port
- **A1** `/trade` queries, **A2** multiple markets, **A3** the trading fee control,
  **A6** removing a market, **A4** the fork-reset re-place checklist, **B2** fork then
  reset
- Deposit caps and all three cheat routes, confirmed live while debugging them: created
  with cheats, enabled later via Open to LAN, and enabled-then-reloaded

**Group A is complete.** A4 was the last one, run against the fork from B2.

**Except A5, which has been un-marked — see `docs/testing/group-e.md` E8.** Its last
step, "spend down to under 5 credits and try to sell → refused, naming the amount", was
the recipe for a bug that duplicated the items: the refusal it looked for could only have
come from a path that had already deposited them. It passed because the refusal is all
anybody checked. Re-run it as E8, which says what else to look at.

---

## Group A — one client, no server

One outstanding: A5 has been un-marked.

### A5. Listing fee — RE-RUN as E8

Passed once, on a build where its last step was the recipe for an item-duplication bug.
The refusal it checks for was correct; what it did not check was whether the goods had
already been deposited on the way to producing it. They had. See `docs/testing/group-e.md`
E8 for the full step list — do that rather than this.

### A6. Removing a market — DONE

<details>
<summary>Kept for reference</summary>

### A5. Listing fee

Creator only, on the Market tab beside the trading fee.

- Set a listing fee of `5` → `About this market` gains a line saying it is kept even if
  you cancel
- Place an order → credits drop by 5 immediately, whether buy or sell
- **Cancel that order** → the reservation comes back, the 5 does not. That is the whole
  mechanism; if it is refunded, it deters nothing
- Place a **tiny** order → still costs 5. Flat is the point: it prices the number of
  orders, not their value
- Spend down to under 5 credits and try to sell → refused, naming the amount. A seller
  offering goods still needs credits to list, which is the awkward part worth seeing
- Set it back to `0`

### A6. Removing a market — new

- With two markets in the world, switch to the **second** one → **Remove this market**
- The DANGER text should say what is lost and that your other markets are untouched
- After removing, you should be back on the first market with it intact
- Switch to the **first** market → the Remove button should be gone. It cannot be
  removed; discarding its history is the route for that

</details>

### A4. Fork-reset re-place checklist — DONE

Passed live on two machines. Two things it turned up, both now fixed:

- The list only appeared after pressing **Refresh hosts** while forked. `divergence` was
  set in one place only — the discovery poll — so the reset offered an empty checklist.
  The fork in this recipe arrives as `AHEAD`, not `FORK`: the host tests "client ahead
  of me" first, and Bob has out-traded Alice by step 3. `offerCatchUp` was already
  finding the divergence and printing it, but not recording it. It does now, so
  connecting is enough.
- Note which refusal you get. `AHEAD` (`diverged ... not a fast-forward`) yields the
  checklist; a true `FORK` cannot, because the split point is unknown and guessing low
  would offer back orders the host still holds.
- The box jumped to the other side of the screen on a tab change, because its column
  was chosen from the active tab. It now sits in the third column on every tab.

<details>
<summary>Kept for reference</summary>

Needs a fork (see B2), then:

- Reset → the DANGER text should mention orders being listed afterwards
- After the reset, the **Old orders** box appears in the third column, full height,
  matching the panels beside it
- Rows show item sprites; hovering a trimmed row shows the full text
- Clicking a row re-places that order; clicking the title dismisses the list
- **The one that matters:** only orders placed *after* the divergence appear. Orders from
  before it come back on reconnecting, and offering those too would create duplicates.

Note the list only counts orders still **resting** — anything that filled or was
cancelled is not offered back, so place the post-split order away from the best price.

</details>

---

## Group B — two clients

`./gradlew runClientAlice` and `./gradlew runClientBob`.

### B1. Fill notifications and the tax together

- Alice hosts, Bob connects, place matching orders
- Both should get a fill notice; the seller's credit should be net of any fee
- **Make the sale big enough for the fee to exist.** At 2.5% nothing is taken below a
  40-credit sale, so trade e.g. 10 at 10 rather than 10 at 2. `About this market` states
  the threshold

### B4. What an order is about to do — new

- Place a sell far above the best bid → the status line should say what it is waiting
  for and at what price, not just "Sell sent..."
- Place one that crosses → it should say it should trade now
- Place the first order on an untouched item → "nobody is buying yet"

### B2. Fork, then reset — DONE

Passed live on two machines, following the recipe below. Ran A4 against the result.

<details>
<summary>Kept for reference</summary>

The recipe that worked last time — the trap is that only one side advancing gives you
`CatchUp`, not a fork:

1. Alice hosts; Bob connects and syncs
2. Bob disconnects to LOCAL
3. **Both** trade — Alice on her host, Bob in his local market
4. Bob connects to Alice → expect `diverged ... not a fast-forward`, the FORKED banner,
   and the Market tab offering **Reset** and not Migrate
5. Then run A4 against it

</details>

### B3. Sequence-gap recovery — never fired live

Hard to force deliberately; watch for it rather than chase it. If a client ever logs
`sequence gap: expected N got M — resync needed`, confirm it reconnects and catches up
rather than silently freezing. Any occurrence is worth reporting.

---

## Group C — dedicated server

Each item needs a config change and a restart. Group them into one sitting.

**Use `docs/testing/group-c-runbook.md`** — the configs below are written out there as
ready-to-swap files, in a run order that puts the destructive one last, each with every
*other* rule switched off so a refusal cannot be misattributed. The JSON in this section
is kept as the statement of what each test is about; the runbook is what to type.

```bash
./gradlew hostServer --args="--config docs/testing/c1-admission.json"
```

Note `--write-config` writes the file and **exits** — it does not start a server.

**C1, C3, C5, C5b, C6 and C4 are done.** Still open: **C2** deposit caps — the cap
itself fired, but the check that matters, that a refusal consumes no allowance, needs
re-running from a fresh window.

### C1. Admission control — DONE

```json
{ "admission": "allowlist", "allow": ["<Alice's uuid>"] }
```

- Alice connects; Bob is refused with "admits invited identities only"
- Add `"deny": ["<Alice's uuid>"]` as well → Alice is now refused too (deny beats allow)
- Set `"admission": "allow-list"` (misspelt) → the server should refuse to start

### C2. Deposit caps

```json
{ "maxDepositUnitsPerWindow": 100, "depositWindowMinutes": 60 }
```

- Deposit 60 → accepted. Deposit 60 again → refused, naming the limit
- Deposit 40 → accepted. **This is the important one**: the refusal must not have
  consumed any allowance
- Console shows `[host] deposit cap: <uuid> tried N, has M left of 100`

### C3. World attestation — DONE

```json
{ "refuseCreativeWorlds": true }
```

- Connect from a creative world → refused
- Connect from a survival world → admitted
- Console logs `reports Nh in a survival world (<hash>)` on connect

**C5. The two cheat routes — DONE, and the ones worth most here.**

Confirmed live, including the mid-session drop. Worth knowing it also fired by accident
twice during other testing: opening a world to LAN with cheats while connected got the
identity dropped *and banned*, and `ban()` writes the deny entry back into whichever
config file the host loaded. The next session then fails at the door for what looks like
an unrelated reason. That is why the runbook fixtures all ship `banOnWorldChange: false`
— turn it on when you mean to watch it, and clear `deny` afterwards.

```json
{ "refuseCheatWorlds": true, "refuseCreativeWorlds": false }
```

- Survival world **created with cheats on** → refused
- Survival world **created without cheats**, then **Open to LAN → Allow Cheats**, then
  connect → refused, and the reason should say commands were switched on after the
  world was created rather than that it has them
- **The one that matters:** connect from a clean world first, get in, and only *then*
  Open to LAN with cheats. You should be dropped mid-session. The handshake is a
  photograph, and this is the check that the picture is re-taken

**C5b. Claimed play time — DONE, and then switched off on purpose.**

```json
{ "maxDepositUnitsPerPlayHour": 100, "refuseCreativeWorlds": false }
```

- From a world with only an hour or two of play, deposit far more than that affords →
  refused, naming the contradiction

Fired correctly on a 1.56-hour world at 202 items. Kept as a feature, left at the
default of off, for two reasons found while running it:

- It weighs a rolling window against a lifetime — items deposited inside
  `depositWindowMinutes` against the whole world's play time. Barely binding on an old
  world, harsh on a new one, and a restart clears the window anyway.
- It is a rate limit on a stock. A tree farm fills a chest in minutes; depositing it is
  refused on a young world however honestly it was grown.

Both costs land on honest players, since the hours are self-reported. The full
reasoning is on `ServerConfig.maxDepositUnitsPerPlayHour`, where somebody would be
about to switch it on. Prefer `maxDepositUnitsPerWindow`, which needs no client
cooperation, and `maxDepositMultipleOfHandled`, which is keyed to statistics the game
maintains.

Running it also turned up a refusal that contradicted itself: the hours printed to one
decimal while the ceiling was floored from the real value, so 1.56 hours read "1.6"
beside a limit of 156 and multiplying gave 160. It now states the rate and enough
precision to reconcile.

**C6. The statistics multiple — DONE.** Not in the original list; added with the runbook
because the rule had no live test of any kind.

```json
{ "maxDepositMultipleOfHandled": 3 }
```

- Deposit more than three times what your statistics say you have handled of that item →
  refused, naming what they show
- Then withdraw some and deposit it back → **accepted**, because the allowance is
  `handled × 3 + what this market has withdrawn to you`

Both confirmed: 62 oak logs against 0 handled were refused, and iron went back in after
being withdrawn. Note the second is the subtle one — a withdrawal reaches your inventory
through `insertStack` and increments no statistic, so without that term the market would
refuse you re-depositing goods it handed you a moment earlier.

One caveat. The run used 0 handled, which is degenerate — it proves the rule fires, not
that the ×3 is computed right; for that, mine ~10 of something and try 30 then 31.

Running it also turned up that the statistics rule did not switch deposit counting on,
so each deposit was judged in isolation and the rule could be walked through by splitting
one into several. Fixed — the fixture no longer needs the large window value it used to
carry as a workaround.

### C4. Welcome grant policy — DONE

Confirmed: the market's log carries `"grantAmount":50` at seq 2 and the joiner's
`WelcomeGrant` is `"amount":50`.

The first attempt looked like a failure and was not. Editing `welcomeGrant` on an
**existing** market does nothing at all — the amount is the market's, fixed when it was
created, and the config only sets it for a market the server creates itself. Two changes
came out of that:

- Genesis now records the grant whatever it is. It used to be written only when it
  differed from the default, so a market made on the default carried no policy at all
  and fell back to a constant that nothing could be compared against.
- A startup warning when the two disagree, naming both figures and what a newcomer will
  actually receive. Note it does **not** mean anything is broken — grants still go out
  at the market's amount, correctly. It means the setting is being ignored.

The console line to look for on a correct run is now
`created '<name>' (<id>) — welcome grant 50`.

<details>
<summary>Kept for reference</summary>

```json
{ "welcomeGrant": 50 }
```

- Bootstrap a **new** market with this (delete `server-market.jsonl` first, with the
  server stopped)
- A joining identity should receive 50, not 1000

Deleting the log gives the market a new id, so any client that had synced the old one is
refused as a different market and needs Reset or a fresh world. That is expected.

</details>

---

## Group D — the known gap

### D1. Slow-client fan-out — no automated coverage

The async fan-out has no test, because provoking TCP backpressure depends on OS buffer
sizes and a flaky test here would be worth less than none.

To exercise it by hand: connect two clients, suspend one (stop the process rather than
disconnecting it), then keep trading on the other. The market must keep working for
everyone else, and the suspended client should eventually be dropped with
`too far behind to keep up` rather than stalling the server.

---

## Group E — everything built after the roadmap closed

**Nothing here has been run in game.** The stipend, the escalating listing fee, the
welcome grant control and a Market column that now scrolls: ten commits with automated
coverage behind them and zero live minutes.

Full plan in `docs/testing/group-e.md`. **Do E1 first** — the layout changed under every
other test on that list, so a fault there will look like a fault in whatever you were
actually testing.

| | |
| --- | --- |
| E1 | the Market column: nothing below the panel, four policy rows, scrolling |
| E2 | the listing fee climbing with orders held open |
| E3 | the stipend end to end, countdown to claim |
| E4 | turning the stipend off, and being able to |
| E5 | the welcome grant control, behind DANGER |
| E6 | a dedicated server opening a market with rules in its config |
| E7 | a policy change not wiping the other policy fields |

## What to report

Anything that differs from the expected outcome, plus:

- Any console line containing `BUG:`
- Any refusal whose message does not say what to do next
- Any text running outside its panel — that class of fault has recurred repeatedly and
  is easiest to catch by eye
