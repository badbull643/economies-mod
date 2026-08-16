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

---

## Group A — one client, no server

Cheapest group. Nothing here needs a second window.

### A1. `/trade` queries — never run at all

- `/trade` — lists the three subcommands
- `/trade balance` — credits and holdings, or "Holding nothing in the market"
- `/trade orders` — your resting orders, or the "nothing resting" line
- `/trade price ` then **Tab** — completion should offer item ids
- `/trade price minecraft:iron_ingot` — best bid, best ask, last traded
- Run any of them in a world with **no** market — expect "No market here yet", not a crash

### A2. Multiple markets per world — never exercised

- Market tab → **Add another market** → confirm
- Expect: status says you are now in an empty slot, and the screen offers Create /
  Import / Connect
- Create a market in it, then check the **Markets in this world** list appears with two
  rows, the active one marked `>`
- Click the other row → it switches, and the header/credits change to that market
- **The one that matters:** switch back and confirm balances and orders are intact.
  Switching must not cost anything.
- Quit the world entirely, rejoin → it should still be on the slot you left it on

### A3. Fee control — never seen, creator only

Needs a market **you** created (check `About this market` shows no "Set by whoever
created this market" line).

- Market tab → a fee field and **Set trading fee** button should appear
- Type `2.5` → confirm → `About this market` should read `2.5%`
- Type `abc`, `-1`, `60` → each refused with a specific message, nothing changed
- Then **sell something to another player** and check the seller receives less than the
  gross. This is the only way to confirm the tax actually settles.
- Set it back to `0` → the fee line reads "none"

### A4. Fork-reset re-place checklist — never live

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
