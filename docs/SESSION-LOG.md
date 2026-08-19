# EconomiesMod — Session Log

*Continues `EconomiesMod-PROJECT-LOG.md` and `EconomiesMod-SESSION-LOG-2026-08-16b.md`.
This file is the current picture; where it disagrees with those, this one is right.*

*Branch `trust-model-and-migration`, **44 commits ahead of `origin` and never pushed**.
`main` has no PR. Protocol version moved `"7"` → `"8"` this session, and since nothing
has been pushed, **protocol 8 has never left this machine** — which is why several
fields were added to it for free rather than waiting for a second bump.*

---

## 1. Where the roadmap stands

The previous log's Phases 0–5 are complete or deliberately closed:

| Phase | Item | State |
| --- | --- | --- |
| 0 | `.gitignore`, hygiene sweep | done |
| 1 | Sequence-gap recovery | done, `gapRecoveryTest` |
| 1 | Two-client Market screen verification | **done live** — Reset offered, Migrate withheld |
| 2 | `ServerConfig` + launcher + `--creator-key` | done, exercised from the shell |
| 2 | Async broadcast fan-out | done; no automated test, see §5 |
| 2 | Admission control | done, `admissionTest` |
| 3 | `Event.MarketPolicy` + tax | done |
| 3 | Dedicated flag + host badges | done, seen live |
| 3 | Market tab "about this market" | done, seen live |
| 3 | Grant policy | done, and made enforceable — see §3 |
| 4 | Deposit caps | done, `depositCapTest` |
| 4 | World attestation | done, `attestationTest`, extended well past the original scope |
| 5 | `/trade` queries | done, verified live |
| 5 | Multiple markets per world | done, verified live |
| 5 | Fork-reset re-place checklist | done; **not yet verified live** (test A4) |
| 5 | Committed provenance chain | **not built** — superseded, see §4 |
| 5 | Log compaction | **not built, deliberately** — `docs/design/log-compaction.md` |

## 2. Test suites

```
coreTests 374   chunkTest 6   replayGuardTest 5   gapRecoveryTest 16
admissionTest 9   depositCapTest 6   attestationTest 12
```

Four suites are new this session. Each engine change was verified to **fail** with its
fix disabled before being trusted, per the project's existing discipline.

## 3. Two real vulnerabilities found and closed

**Unbounded mint via `WelcomeGrant`.** `EventApplier.validate` checked that a grant was
positive and once-per-identity, but never checked the *amount* or the *author* — so any
identity could sign itself a grant for any sum and every replica would accept it.
Confirmed before fixing: a self-authored grant of 1,000,000,000 validated. Two routes in:
a server configured with a zero grant never marks anyone granted, so the once-only rule
never fires; and a grant authored in one's own local world migrates in at full value.

Authorship cannot be the fix — hosting rotates, so a replica replaying later cannot know
who was sequencing. The amount is the enforceable part, so `grantAmount` joined
`MarketPolicy` in the log and validation now requires a grant to equal it. Authorship
stops mattering: the most a liar can give themselves is what an honest host would have.

**Every local submission was refused.** `submit()` stamped `marketId` onto an event
*after* calling `validate`, and `checkGenesis` refuses any event whose `marketId` is not
the market's — so validation always saw null. It surfaced as the fee control being
rejected, but applied to everything authored offline. Pinned by `Z1`.

## 4. Anti-cheat, which grew well beyond the roadmap

The roadmap had "world attestation — cheap, stops the casual case". It ended up as four
layers, driven by the user finding each bypass in turn. Research (§9) confirmed no
third-party tool solves this: every Minecraft anti-cheat assumes a shared authoritative
server, which this architecture deliberately lacks.

1. **Deposit caps** — pure host-side observation, needs no client cooperation. Cannot
   live in `EventApplier`: the answer depends on when it is asked, so a replica
   replaying later would judge a window long past and fork.
2. **World attestation** — self-reported, and judged by *contradiction* rather than
   belief, after the pattern used to catch spoofed clients elsewhere in Minecraft. A
   world claiming twenty minutes that deposited four thousand diamonds is making two
   statements that cannot both be true.
3. **Cheat detection, three routes.** Created with cheats; enabled later via Open to LAN
   (which sets a runtime flag and writes nothing to the save); and enabled, used, then
   *reloaded to clear it* — closed by the mod writing its own marker into the world,
   since Minecraft forgets and the marker does not.
4. **Statistics** — the strongest signal available, and the reason the provenance chain
   in the roadmap is now redundant. Minecraft counts mined, crafted and picked up;
   `/give` increments none of it (`GiveCommand` calls `insertStack` and touches no
   statistic). It survives world reloads *and the mod being switched off*, because the
   game maintains it either way.

Two bypasses were found by the user testing and are closed:

- **Drop-and-collect laundering.** Picking an item up increments `PICKED_UP` whatever its
  origin, including one thrown down a second earlier. Counting picked-up **net of
  dropped** closes it exactly; mining is unaffected, since an ore drops an item nobody
  dropped.
- **Re-depositing what the market gave you.** A withdrawal reaches the inventory through
  `insertStack` and increments nothing, so the market's own goods looked spawned. The
  allowance is now `handled × multiple + what this market has withdrawn to you`, read
  from state rather than from the client's claim.

**The ceiling, which must not be oversold.** Every layer but the deposit cap is
client-supplied. A modified client reports whatever it likes. What is closed is every
route that needs no tools at all — which is the one people actually use.

## 5. Known gaps

- **Slow-client fan-out has no automated test.** Provoking TCP backpressure depends on OS
  buffer sizes, and a flaky test in these suites would be worth less than none. Wants a
  manual check (test D1).
- **Log compaction unbuilt**, with the reasoning written down rather than the code.
- **`main` has no PR** and nothing is pushed.

## 6. Testing — what has actually been done

Checklist lives in `docs/testing-checklist.md`.

**Done live:** A1 `/trade` queries · A2 multiple markets · A3 trading fee · A5 listing
fee · A6 removing a market. Plus, incidentally during debugging: the fork/Reset
distinction, migration, `CatchUp` correctly declining a non-fast-forward, the dedicated
launcher's whole CLI, deposit caps, and all three cheat routes.

**Not done:**

- **A4** — fork-reset re-place checklist. Needs a fork first, so pair it with B2.
- **Group B** — two clients: fill notices with a fee large enough to exist, fork then
  reset, sequence-gap recovery, order outlook wording.
- **Group C** — dedicated server: admission, deposit caps, attestation, grant policy.
  Note C1–C4 need `server-config.json`; a market hosted from the game reads
  `host-config.json` in that world instead, and the two are separate on purpose.
- **Group D** — the slow-client case above.

## 7. Things worth knowing before touching this

- **A policy file belongs to whoever hosts.** With two worlds involved this is easy to
  get wrong; both launchers now log which rules they loaded, or that they loaded none.
- **A `MarketPolicy` event carries the whole policy.** Both fee controls write through one
  `submitPolicy` for that reason — separate paths would each have to remember to restate
  the others, which is how setting a fee would have zeroed the welcome grant.
- **The alert band moves the panels.** Widgets are positioned once in `init()` from
  `rowY`, which knows nothing about alerts, so `reflowForAlerts` shifts them by the
  band's change. Anything that sets an absolute widget position per frame must add
  `alertBandH()` itself.
- **A fee that rounds to nothing is not broken.** At 2.5%, nothing is taken below a
  40-credit sale. Both the About panel and the confirmation now say so, because a
  working fee taking zero looks exactly like a broken one.

## 8. Carried forward, still open

- `Message.SteppingDown` is gone; `status`/`recoveryNote` are no longer static — the old
  hygiene list is cleared.
- Item-picker slot palette: done, darkened.
- Two files disappeared from the working tree mid-session (`settings.gradle`,
  `server-config.json`) with no explanation. The first was restorable from git, the
  second is gitignored and silently regenerated as defaults. Worth watching.

## 9. Prior art consulted this session

Fabric/Paper anti-cheat survey — CheaterDeleter, Kappa's, Fragments, MTSAntiCheat,
Inertia, AntiSpoof, and PaperMC's own guidance. Conclusion: mod-list verification is
"easy to bypass and requires client cooperation", and a modified client faithfully
replicating vanilla packets "may be impossible to distinguish using server-side plugins
alone". AntiSpoof's contradiction approach — catching a client that claims to be vanilla
while registering plugin channels — is the idea the attestation design was built on.
