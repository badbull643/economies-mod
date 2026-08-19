# Group C — the dedicated-server sitting

*Ready-to-swap configs for the Group C items in `docs/testing-checklist.md`, in the
order they should be run. The checklist says what to look for; this says what to type
and what would make a result meaningless.*

Each file here is a complete config, not a fragment. Point the launcher at one:

```bash
./gradlew hostServer --args="--config docs/testing/c1-admission.json"
```

Stop the server, change the `--config`, start it again. That is the whole loop.

## Before the first run

`./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest depositCapTest attestationTest`

Last run 2026-08-17: all seven green, 374 / 6 / 5 / 16 / 9 / 6 / 12.

## Three things that will waste an hour if you don't know them

**`--write-config` writes the file and exits.** It does not start a server. The command
block in the checklist is a generate-me-a-file step, not the one that runs anything —
drop `--write-config` to actually listen.

**A ban is written back into whichever config file is loaded.** `ServerConfig.ban` saves
to `sourceFile`, so a ban triggered while running `c5-cheat-routes.json` edits
`c5-cheat-routes.json`. Every file here therefore ships with `banOnWorldChange: false`:
ban-and-persist is already covered by `attestationTest` — four of its twelve checks —
and leaving it on makes each cheat test single-use and quietly rewrites the fixture
underneath you. Turn it on deliberately if you want to watch it, and clear `deny`
afterwards.

**That is how the last sitting went wrong.** `server-config.json` was still carrying
`deny: ["10920508-d5d8-3eed-93d2-92f193afe7d7"]` from the cheat-route debugging. That
UUID is *Alice* — so C1, whose first line is "Alice connects", would have failed at the
first step for a reason that has nothing to do with admission. Cleared now.

## The identities

Offline-mode UUIDs, derived from `--username`, so they are stable and already filled in:

| Player | UUID |
| --- | --- |
| Alice | `10920508-d5d8-3eed-93d2-92f193afe7d7` |
| Bob | `faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b` |
| server | `82acf50d-4cd0-4853-997d-f0e839344742` |

Every file pins `hostUserId` to that server identity. Left null, the launcher assigns a
fresh random one and saves it back into the file — so eight variants would become eight
different participants in one market, and each fixture would rewrite itself on first
start.

## Why each file switches everything else off

The three deposit rules run in sequence on the same event — window cap, then statistics
multiple, then claimed play time — and each rejects with its own message. Leaving a
second one on means a refusal you attribute to the rule under test may be a different
rule firing. The live baseline has `maxDepositMultipleOfHandled: 3`, which is exactly the
one that would silently confound C2. So each file here enables its own rule and zeroes
the rest.

`requireAttestation: true` wherever the rule needs a claim. Both the statistics check
(`HostServer.java:1193`) and the play-hour check (`:1229`) are guarded on
`claim != null` and pass silently when no attestation arrived — a pass that means
"nothing was checked" looks identical to a pass that means "checked and fine".

---

## Run order

C4 goes last because it destroys the log. Everything else is non-destructive.

### 1 — C1 admission · `c1-admission.json`

- Alice connects; Bob is refused with "admits invited identities only"

### 2 — C1 deny beats allow · `c1b-admission-deny-wins.json`

Alice is on both lists.

- Alice is now refused too — "that identity is not allowed on this server"

### 3 — C1 misspelt mode · `c1c-admission-misspelt.json`

- The server refuses to start. Confirmed already, without a client:

  ```
  [host] admission must be "open" or "allowlist", not "allow-list"
  ```

  Exit code 2. You can re-check this one any time without Minecraft running.

### 4 — C2 deposit caps · `c2-deposit-cap.json`

100 units per 60 minutes, every other deposit rule off.

- Deposit 60 → accepted. Deposit 60 again → refused, naming the limit
- Deposit 40 → accepted. **The one that matters**: the refusal must not have consumed
  any allowance
- Console: `[host] deposit cap: <uuid> tried N, has M left of 100`

The window lives in memory and starts empty, so restarting the server hands everyone a
fresh 100 — don't read that as a bug, and don't restart mid-test.

### 5 — C3 creative worlds · `c3-refuse-creative.json`

- Connect from a creative world → refused
- Connect from a survival world → admitted
- Console on connect: `reports 10.0h in a survival world (<hash>)`

### 6 — C5 the two cheat routes · `c5-cheat-routes.json`

- Survival world **created with cheats on** → refused
- Survival world **created without cheats**, then Open to LAN → Allow Cheats, then
  connect → refused, and the reason should say commands were switched on *after* the
  world was created, not merely that it has them
- **The one that matters:** connect from a clean world, get in, and only *then* Open to
  LAN with cheats. You should be dropped mid-session. The handshake is a photograph;
  this checks the picture is re-taken

  ```
  [host] Joiner enabled commands in their world after creating it
  [host] Joiner no longer passes: commands were switched on in this world after it was created
  ```

  With `banOnWorldChange: false` no ban follows, so this is repeatable.

### 7 — C5 claimed play time · `c5b-play-hour.json`

100 units per claimed hour.

- From a world with an hour or two of play, deposit far more than that affords →
  refused, naming the contradiction

### 8 — C6 statistics multiple · `c6-statistics-multiple.json` — new, not on the checklist

`maxDepositMultipleOfHandled` ships enabled in the live baseline and has no live test.

- Deposit more than three times what your own statistics say you have ever handled of
  that item → refused
- Then withdraw some of it and deposit it back → **accepted**, because the allowance is
  `handled × 3 + what this market has withdrawn to you`. This is the regression closed
  in `cfc9163`

**Note the `maxDepositUnitsPerWindow: 1000000` in that file.** It is not a cap under
test — it is there to switch deposit *counting* on, and needing it is a real gap:

> `HostServer.java:266` enables counting when `maxDepositUnitsPerWindow > 0 ||
> maxDepositUnitsPerPlayHour > 0` — `maxDepositMultipleOfHandled` is not in that list. So
> with the statistics rule set **alone**, as the live baseline has it,
> `DepositLimiter.tracking()` is false, `usedBy` always returns 0, and every deposit is
> judged on its own rather than against the running total. Someone with 10 handled iron
> can deposit 30, repeatedly, for as long as they like. `DepositLimiter.tracking()`
> (`DepositLimiter.java:89`) exists precisely to stop this for the play-hour rule — its
> javadoc says counting tied to the cap "would have seen each deposit alone and never a
> sum". The statistics rule has the same hole and was not added to the same fix.

Worth fixing before this step is meaningful on the default config.

### 9 — C4 welcome grant · `c4-welcome-grant.json` — last, and destructive

`welcomeGrant` is only ever written at genesis, and only when it differs from the default
1000 (`writeInitialPolicy`, `HostServer.java:1686`). The current log has no `MarketPolicy`
event at all for that reason, and runs on the built-in 1000. So this needs a new market:

```bash
rm server-market.jsonl
./gradlew hostServer --args="--config docs/testing/c4-welcome-grant.json"
```

- Console: `[host] welcome grant for this market set to 50`
- A joining identity receives 50, not 1000

Deleting the log changes the market id, so any client that had synced to the old market
will now be refused as a different market. That is correct behaviour, not a failure —
but it is why this goes last.

## Afterwards

`server-config.json` is the live baseline and is gitignored; the files in this directory
are fixtures and are tracked. Nothing here writes to the baseline unless you point the
launcher at it.
