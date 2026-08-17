# In-game testing checklist

*Everything built this session that a headless test cannot reach. Automated suites cover
the engine rules and their wiring; this covers the parts that need a keyboard and a
screen.*

Run `./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest
depositCapTest attestationTest` first — if any of those fail, stop, because everything
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
  **A5** the listing fee, **A6** removing a market
- Deposit caps and all three cheat routes, confirmed live while debugging them: created
  with cheats, enabled later via Open to LAN, and enabled-then-reloaded

**Everything in Group A is done except A4**, which needs a fork and so belongs with B2.

---

## Group A — one client, no server

Only A4 remains here, and it needs a fork — do it straight after B2.

### A5. Listing fee — DONE

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

### A4. Fork-reset re-place checklist — the one still to do here

Needs a fork (see B2), then:

- Reset → the DANGER text should mention orders being listed afterwards
- After the reset, the **Old orders** box appears in the third column, full height,
  matching the panels beside it
- Rows show item sprites; hovering a trimmed row shows the full text
- Clicking a row re-places that order; clicking the title dismisses the list
- **The one that matters:** only orders placed *after* the divergence appear. Orders from
  before it come back on reconnecting, and offering those too would create duplicates.

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

### B2. Fork, then reset

The recipe that worked last time — the trap is that only one side advancing gives you
`CatchUp`, not a fork:

1. Alice hosts; Bob connects and syncs
2. Bob disconnects to LOCAL
3. **Both** trade — Alice on her host, Bob in his local market
4. Bob connects to Alice → expect `diverged ... not a fast-forward`, the FORKED banner,
   and the Market tab offering **Reset** and not Migrate
5. Then run A4 against it

### B3. Sequence-gap recovery — never fired live

Hard to force deliberately; watch for it rather than chase it. If a client ever logs
`sequence gap: expected N got M — resync needed`, confirm it reconnects and catches up
rather than silently freezing. Any occurrence is worth reporting.

---

## Group C — dedicated server

Each item needs a `server-config.json` change and a restart. Group them into one sitting.

```bash
./gradlew hostServer --args="--config server-config.json --write-config"
```

### C1. Admission control

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

### C3. World attestation

```json
{ "refuseCreativeWorlds": true }
```

- Connect from a creative world → refused
- Connect from a survival world → admitted
- Console logs `reports Nh in a survival world (<hash>)` on connect

**C5. The two cheat routes — new, and the ones worth most here.**

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

```json
{ "maxDepositUnitsPerPlayHour": 100, "refuseCreativeWorlds": false }
```

- From a world with only an hour or two of play, deposit far more than that affords →
  refused, naming the contradiction

### C4. Welcome grant policy

```json
{ "welcomeGrant": 50 }
```

- Bootstrap a **new** market with this (delete `server-market.jsonl` first)
- A joining identity should receive 50, not 1000
- Console: `welcome grant for this market set to 50`

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

## What to report

Anything that differs from the expected outcome, plus:

- Any console line containing `BUG:`
- Any refusal whose message does not say what to do next
- Any text running outside its panel — that class of fault has recurred repeatedly and
  is easiest to catch by eye
