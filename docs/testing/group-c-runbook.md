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

```
./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest \
    depositCapTest attestationTest hostTrustTest splitPointTest
```

Nine suites now: `531 / 6 / 5 / 16 / 25 / 6 / 12 / 16 / 22` as of 2026-08-22.
`hostTrustTest` and `splitPointTest` are newer than this runbook.

**Two settings on this page have moved since it was written.** A dedicated server now
declines migrations unless `acceptsMigration: true`, and caps a market's welcome grant at
whatever `maxWelcomeGrant` says — unset means the compiled ceiling on a dedicated box and
10,000 in somebody's game. Neither changes a Group C fixture, but a migration refused
here is now a setting rather than a fault.

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
rule firing. So each file here enables its own rule and zeroes the rest — the statistics
multiple in particular would silently confound C2 if left on.

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

`maxDepositMultipleOfHandled` had no live test of any kind. It is off by default, like
the other two deposit rules.

- Deposit more than three times what your own statistics say you have ever handled of
  that item → refused
- Then withdraw some of it and deposit it back → **accepted**, because the allowance is
  `handled × 3 + what this market has withdrawn to you`. This is the regression closed
  in `cfc9163`

**No window cap needed in that file any more.** It used to carry a large
`maxDepositUnitsPerWindow` purely to switch deposit *counting* on, because the host
enabled counting for the cap and the play-hour rule but not for the statistics multiple.
Set alone, the limiter had a zero-length window, `usedBy` always answered 0, and each
deposit was judged in isolation — so ten handled iron authorised thirty deposited, then
thirty more. Fixed: all three rules now ask `ServerConfig.countsDeposits()`, and a zero
window under any of them is refused at startup rather than quietly accepted.

### 9 — C4 welcome grant · `c4-welcome-grant.json` — last, and destructive

`welcomeGrant` is only ever written at genesis. It sets the amount for a market this
server creates itself and reaches no existing one, so this needs a new market:

```bash
rm server-market.jsonl
./gradlew hostServer --args="--config docs/testing/c4-welcome-grant.json"
```

- Console: `created '<name>' (<id>) — welcome grant 50`. That line only appears when the
  policy is actually written, so it is the confirmation the bootstrap took
- A joining identity receives 50, not 1000
- Start against an existing market whose figure differs and you get the mismatch warning
  instead, naming both and what a newcomer will actually receive

Deleting the log changes the market id, so any client that had synced to the old market
will now be refused as a different market. That is correct behaviour, not a failure —
but it is why this goes last.

## Afterwards

`server-config.json` is the live baseline; the files in this directory
are fixtures and are tracked. Nothing here writes to the baseline unless you point the
launcher at it.
