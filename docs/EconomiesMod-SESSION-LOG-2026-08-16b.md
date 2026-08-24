# EconomiesMod — Session Log & Roadmap

*Continues `EconomiesMod-PROJECT-LOG.md`. Covers the session that followed it: a second UI pass (inventory button, clickable alerts, vanilla tabs, Home dashboard) plus a long design conversation about the rotating-host / dedicated-server split that produced no code but a fair amount of decided design.*

*Protocol version is still `"7"` — nothing this session changed the wire format. Branch `trust-model-and-migration`, still ahead of `origin` and still not opened as a PR.*

---

## Part 1 — What was actually built

### 1. Inventory market button

A way into the market from the inventory, beside the recipe book, because the `M` keybind is undiscoverable — nothing in the game says it exists.

**New files:** `client/InventoryMarketButton.java`, `mixin/HandledScreenAccessor.java` (the project's first mixin; `economiesmod.mixins.json` had been sitting empty).

- Emerald button at `panelX + 126`, **20×18** — vanilla's recipe button is at `+104` and the same size, confirmed from bytecode.
- **Re-anchors every frame**, not at `init()`. Opening the recipe book shifts the whole GUI and vanilla only repositions its own button. Doing it inside `renderButton` rather than from a render callback also survives `init()` running twice on window resize.
- Extends **`PressableWidget`, not `ButtonWidget`** — `ButtonWidget.renderButton` draws background then tooltip, leaving nowhere to put an icon (after the call it lands on the tooltip, before it the background covers it).
- `M` still works. This is an addition, never a substitution.

**Two bugs found by screenshot and fixed:**

- **Label overflow.** `PressableWidget` draws its message centred, and "Market" is far wider than 20px, so it spilled across the slots either side. Fixed with a blank message plus a `getNarrationMessage()` override so the narrator still gets the name.
- **Permanently pressed-in look.** `ClickableWidget` passes its own height through as the texture's *source* height, and the shared button texture is 20 tall — so an 18-tall button samples the top 18 rows and never draws the bottom bevel. Fixed by drawing the frame as four quadrants, each half taking its 9 rows from the matching end of the source. The recipe button escapes this only because it is a `TexturedButtonWidget` with its own 18-tall texture.

**Build note worth keeping:** `./gradlew build` produces **no refmap**, which initially looked like a bug that would break the mixin in production. It isn't — this Loom version doesn't use the mixin AP at all and remaps annotations during `remapJar`. Verified by inspecting the shipped jar: `@Mixin(class_465)` and `@Accessor("field_2776")`, the intermediary names. A `mixin { defaultRefmapName }` block was briefly added and then reverted; Loom explicitly tells you to remove it.

### 2. Clickable alert strip

The market warnings (damaged / behind / forked) already existed as static labels at `y=42`, on every screen. They are now an actionable strip.

- Each alert **routes to the screen that answers it** — "N events behind" opens **Network**, because catching up is done by connecting; forked and damaged open **Market**.
- **Moved to draw after the panels.** They were previously drawn straight after the header, but `frameTop()` can be as low as 52 on a short window, so a second simultaneous alert was painted underneath a panel. The one message that must not be missed was the one thing that could be covered.
- Render and hit-test both measure from a single `alertRect(index, text)` — the `discoveryStartY()` lesson from the previous session, not repeated.

### 3. Hamburger → vanilla tab bar

The hamburger was the single loudest "this is a mod" signal on the screen: Minecraft has no hamburger menus anywhere. Replaced with a tab row along the top of the panel, which is what the advancements screen is.

**Removed entirely:** `navOpen`, `burgerRect()`, `navRowRect()`, `renderBurger`, `renderNav`, `navClicked`, `NAV_W`, `NAV_ROW_H`, `BURGER_SIZE`, the Escape-closes-menu branch in `keyPressed`, and the `navOpen` guard in `mouseScrolled`.

**Added:** `tabRect()`, `renderTabs()`, `tabsClicked()`.

- Tabs anchor to the **panel**, not the window, so they travel with the content box when it re-centres.
- Content-box floor raised **34 → 46** to give the tab row its own band.
- Alerts moved to `alertTop()` = `frameTop() + 8`, since the tab row took the space they used to occupy.
- Clock re-anchored to the content box (it had been positioned off `burgerRect()`).
- Panel colours extracted to `PANEL_BG` / `PANEL_EDGE_TOP` / `PANEL_EDGE_BOTTOM` so tabs and `vanillaPanel()` cannot drift apart.

**Design call, made and then reversed mid-implementation:** the active tab initially bled 6px down over the panel border to read as "joined", which is what vanilla does. But vanilla tabs sit above *one* panel and this screen has three with gaps — a bleeding tab would as often as not run into empty space. The active tab is raised 2px and lit gold instead.

### 4. Home's three empty panels, filled

- **Most traded** (left) — every traded item ranked by units moved, with icon, volume and last price. Volume rather than trade count: one player moving a stack is more of a market than six people swapping single items.
- **Price** (middle bottom) — sparkline for the selected item, **falling back to the busiest item** when nothing is selected, labelled `(busiest)` when it does. The fallback matters: Home is the landing tab, so on first arrival nothing has been picked, and an empty panel there reads as broken rather than as "you haven't chosen anything".
- **Activity** (right) — the tail of the log in plain language, newest first.

**Supporting pieces:**

- `TradeHistory.volumeFor()` — core addition beside the existing `countFor`/`lastPrice`. Sums in place rather than via `recentFor`, which copies; the panel reads every traded item every frame, so the allocation was what mattered, not the iteration.
- **Activity feed on `MarketStateHolder`**, fed from **`APPLIED`** — the one choke point both LOCAL and CONNECTED already share, so the two cannot drift apart. Bounded at 64, synchronized (network reader writes, render thread reads), and `recentActivity()` returns a copy.
- `trim()` using vanilla's `trimToWidth` rather than a per-character loop.

**Two deliberate judgement calls:**

- **The feed is not filtered to `live` events** — the opposite call from the withdraw handler. A synced history is exactly what someone joining wants in a "recent activity" panel, and unlike handing over items, showing an event twice costs nothing.
- **LOCAL needed hand-seeding.** Local replay goes straight through `EventApplier`, not `APPLIED`, so it never populated the feed. A synced history arrives via `APPLIED` and fills it for free, so only the local path calls `seedActivity`.

### 5. Verification state

`coreTests` 184, `chunkTest` 6, `replayGuardTest` 5 — all green throughout. Every Minecraft API assumption in this session was checked against the remapped 1.16.5 jar before being written against, including button texture geometry, `Screen.renderTooltip` visibility, `PressableWidget`'s abstract surface, and `ClickableWidget`'s depth-test behaviour.

**Not verified:** nothing in the Home dashboard has been seen with a populated market. All three panels are empty-state until something trades.

---

## Part 2 — Assessment delivered, no code

**An external review claiming a critical item-minting vulnerability was assessed as overblown.** Its reading of the code was accurate — `Deposit`/`DepositAndList` validation checks only quantity, price and itemId, with no host-side inventory check. But the conclusion does not follow: the attack needs no modified client at all, because every participant runs their own singleplayer world and can `/give` themselves anything, then deposit through the completely honest client path. The check the review wants would *pass*.

The check is not merely unimplemented but **unimplementable** in this architecture — the host is another player's client looking at a different world, and there is no shared authoritative world state anywhere by design. This is the documented "world provenance/anti-cheat" limitation, not a new bug.

The migration-amplification claim was also wrong: `HostServer` has no allowlist, so the attacker's shortest path into a second market is to join it and deposit there directly. Migration is a strictly longer route to an already-unbounded outcome.

**What survives from it:** at 1000+ players this is a real product problem, but the mitigations are economic and operational, not cryptographic. That fed directly into the dedicated-server design below.

---

## Part 3 — New design decided (not implemented)

### A. The rotating-host / dedicated-server split

**Only three things differ today.** `HostServer` is already mode-agnostic. The differences are the entry point (`main()` with positional args vs `startHosting()`), bootstrap intent (dedicated auto-creates on an empty log), and identity (a hardcoded fallback UUID and `server-identity.key`).

**Decisions:**

- **The split is an operator concern, not a player concern.** From a player's seat a dedicated server is just a host that is always up. No client-side mode toggle — that would make two things look different that genuinely aren't.
- **Make the difference literally one object: `ServerConfig`.** Welcome grant, admission policy, rate limits, bind address. Client builds friend-group defaults; `main()` loads JSON. Follows the `Settings`/`PeerCache`/`MarketHighWater` pattern exactly. This delivers the design's own promise of "one config difference".
- **One player-facing signal:** a dedicated flag on `Message.Sync`, shown as a badge in the host lists. It answers "will this still be here tomorrow, and do I need to care about hosting".
- **Demote, don't hide, the Host button** on a dedicated market. Hiding creates "where did it go"; greying with a reason teaches the model.

### B. Listing tax

**Hard constraint:** the rate cannot be a server-side setting. `EventApplier` is deterministic and every client replays independently — a host applying an unknown fee forks the market. There is no cheap shortcut here; a host-local config is not "less correct", it is non-functional.

**Decisions:**

- New **`Event.MarketPolicy`**, creator-signed, ordered in the log. Rate lives in `MarketState`, applied inside fill settlement.
- **Transaction tax on fills**, not a listing fee, as the primary mechanism — it scales with value and can't bankrupt someone who lists optimistically.
- **Burn the proceeds.** There is currently an unbounded money *source* (`DEFAULT_WELCOME_GRANT = 1000` per identity) and no sink at all. A burning tax is the matching sink. Paying the operator makes them an accumulating whale; a treasury needs a spend mechanism that doesn't exist.
- **Exact rounding rule, specified not implied** — round down with a floor of zero. Every replica must compute the identical number.
- **Bounds validated in `EventApplier`** (0–50%). A fat-fingered 10000% must be rejected, not faithfully replayed by everyone.
- **Must appear in `EventCanonical`** — that file's own comment says anything omitted there is unsigned and tamperable. A rate left out lets a host rewrite the tax in flight.
- **Build it adjustable from day one.** The expensive work (rate in state, applied in `EventApplier`, deterministic rounding, protocol bump) is identical for fixed and adjustable. The delta is ~100 lines including tests. Retrofitting later costs a *second* protocol bump plus migration for markets created in between.
- Replay handles non-retroactivity **for free** — trades before a rate change settle at the old rate because state hadn't changed yet. Still worth a test, precisely because it's correctness you get by accident.

### C. Multiple markets per world

- **Not concurrent membership.** It's sound in principle — currency isn't fungible across markets and the inventory can't double-spend — but every singular assumption (`NetPosition`, high-water, migration, fork detection, fill notifications, the whole UI) would have to fan out. Very large change, fairly niche want.
- **Instead: multiple logs, one active, switchable.** The logs are already separate files; `MarketStateHolder` just pins one. Covers "friends some evenings, the big server otherwise" for a fraction of the cost, and softens migration's one-way-door feel.
- **The two setups stay the same engine.** A dedicated server is a market whose host never rotates.

### D. World security / anti-cheat (dedicated server)

The ceiling is real: **you cannot prove a player's items were legitimately obtained**, because their world is theirs. What a dedicated server buys is the ability to impose conditions as a price of admission.

1. **Anomaly detection + deposit caps** — *start here*. Pure server-side observation, requires zero client cooperation, and is the only layer not defeated by a modified client. Someone depositing 10,000 diamonds in an hour did not mine them.
2. **World attestation** — record seed, creation time, whether cheats or creative were ever enabled; sign at handshake. A modified client lies, but this stops the casual case, which is most of it. Cheap insurance, must not be oversold.
3. **Committed provenance chain** — periodically commit the head of a local provenance chain into the market log. Doesn't stop lying at the moment of recording, but makes *retroactive* fabrication detectable. This is the Certificate Transparency pattern already in the prior-art list, applied one layer down.
4. **Economic friction** — the tax makes churning fake goods cost something.

### E. Admin tooling on a server with no Minecraft

**The organising insight: admin actions come in two kinds, with two different natural homes, and neither needs a GUI on the server.**

- **Ledger-changing** (tax rate, grant amount, force-cancel) — must be signed events, because clients verify independently and there is no out-of-band edit.
- **Server-local** (allowlist, rate limits, who's connected, restart, backup) — change nothing in the ledger. Refusing a connection isn't a market fact.

**Decisions:**

- **Server-local → stdin console commands.** `who`, `allow`, `deny`, `limits`, `save`, `stop`. Same model as a vanilla Minecraft server, so operators already know it. Also the recovery path when nobody can connect. Add file logging while there.
- **Ledger-changing → the mod itself is the admin tool.** The admin runs Minecraft, connects to their own server as an ordinary client, and gets controls on the Market tab, authorized by the key they hold rather than where they sit. The screen already exists; the authority is cryptographic so location is irrelevant.
- **`--creator-key` at bootstrap, so the admin's key writes genesis.** Verified that `WelcomeGrant` is *not* creator-gated — validation checks only the target, never the author — which is necessary for rotating hosts and means creator-gating is currently unused and free to become the admin rule. The server keeps its own key for grants; the admin's key never lives on the server. Better posture: compromising the box gets grant-signing and denial of service, not policy control.
- Standing cost: lose the admin key and policy freezes forever. Consistent with the project's universal finding; needs an explicit ops backup note.

### F. `/trade` commands

**Verdict: yes, scoped, and later.**

- **`ClientCommandManager` and `FabricClientCommandSource` are already available** in `fabric-command-api-v1` 1.1.3 — no mixin needed.
- **Client-side is architecturally correct**, not just convenient: the market connection is the mod's own socket to a `HostServer` and has nothing to do with the Minecraft server being played on.
- **Trading verbs yes** (`buy`, `sell`, `cancel`, `price`, `balance`). **Lifecycle verbs no** (`reset`, `migrate`, `import`, `host`) — those are exactly what the guided Market screen exists to protect, and a command puts a market-destroying action one typo from execution with no DANGER overlay and no double-click guard.
- **Start with queries.** `/trade price <item>` printing the book to chat is non-destructive, duplicates no mutation logic, needs no confirmation design, and works while doing something else.
- **The constraint that decides whether this stays cheap: commands and GUI must share one submission path.** Separate paths mean validation drifts and you find out via a bug report.
- Use brigadier's `ItemStackArgumentType`, so tab-completion replaces what the item picker deliberately retired.
- **Timing: after the dedicated-server work**, because market-makers are who wants this and they're a dedicated-server phenomenon.

### G. Explicitly dropped

**Regrouping the nav by usage frequency** (folding Market and Settings behind a "Setup" tab). Its entire rationale was that five equal-looking entries hid the important ones — the tab row plus clickable alerts already solved that, and nesting them a level deeper would be worse than a flat row of five.

---

## Part 4 — Carried forward, still open

- **Sequence-gap recovery.** `MarketClient.applyLine` still tolerates a live mid-session gap rather than triggering catch-up. Confirmed unchanged. Deferred every time it comes up; it touches the most safety-critical path in the client.
- **In-game two-client verification of the guided Market screen** — the forked-vs-different-market distinction has still never been exercised live.
- **Fork-reset re-place checklist** — infrastructure exists, just needs reusing.
- **Hygiene, all confirmed still present:** `status`/`recoveryNote` still `static`; `Message.SteppingDown` still dead; `HostServer.broadcast` still synchronous per client; item-picker slot palette still bright.
- **No `.gitignore` at all**, and 434 tracked files under `build/` and `.gradle/` — compiled classes, Gradle lock binaries, test-scratch fixtures. This is why `git status` opens with a wall of noise. A task chip was raised and not picked up.
- **PR against `main` still not opened.** `main` is 36 commits behind.

---

## Part 5 — Suggested order

Three structural constraints drive this ordering:

1. **Batch every wire-format change into one protocol bump.** The tax and the dedicated flag on `Sync` both change the protocol. Doing them separately costs two bumps and two compatibility windows.
2. **`ServerConfig` gates everything server-policy-shaped** — admission, grant policy, rate limits, the launcher, the admin console.
3. **The tax needs a display surface before it ships.** If a fee exists and isn't shown, the first fill is a nasty surprise. Adding a tax *requires* the Market tab rework.

### Phase 0 — cheap, independent, do first

1. **Screenshot-verify this session's UI** — Home with a populated market especially; all three panels are empty-state until something trades.
2. **`.gitignore` + untrack build artifacts.** Makes every later diff readable.
3. **Hygiene sweep:** `status`/`recoveryNote` to instance fields, delete `Message.SteppingDown`, item-picker slot palette.

### Phase 1 — safety-critical engine

4. **Sequence-gap recovery.** Wire the existing catch-up machinery to the live gap detection that currently shrugs. Wants its own two-client testing pass.
5. **Two-client verification of the guided Market screen** — pairs naturally with #4's testing session, same setup.

### Phase 2 — dedicated-server foundation

6. **`ServerConfig` + real launcher + `--creator-key` bootstrap.** Small, unblocks everything below.
7. **Async broadcast fan-out.** Already on the hygiene list; at 200 clients one slow socket stalls the sequencer, so its severity changes rather than its difficulty.
8. **Admission control.** The actual gap, and the honest answer to the security review.

### Phase 3 — the protocol bump, batched into one

9. **`Event.MarketPolicy` + the tax**, adjustable from the start, with the rounding rule and bounds pinned down.
10. **Dedicated flag on `Message.Sync`** + host-list badges — same bump.
11. **Market tab → "about this market"**: name, operator, dedicated or rotating, tax rate, grant, caps, *and* the existing problem guidance. Required by #9, not optional polish.
12. **Grant policy config** — falls out of `ServerConfig` and pairs with the tax as source/sink.

### Phase 4 — anti-cheat

13. **Anomaly detection + deposit caps.** Server-side, trusts the client for nothing.
14. **World attestation** — cheap, stops the casual case, must be described honestly.

### Phase 5 — later, in rough value order

15. **`/trade` commands**, queries first.
16. **Multiple markets per world**, switchable not concurrent.
17. **Fork-reset re-place checklist.**
18. **Committed provenance chain.**
19. **Snapshotting / log compaction** — design properly before building. A checkpoint clients simply trust throws away the trust model, which is what makes it the hardest item on this list.

---

## Method notes worth carrying forward

- **Screenshot verification keeps earning its place.** Both inventory-button bugs this session were invisible to every test suite and obvious in a screenshot.
- **Check Minecraft API assumptions against the remapped jar before writing against them.** `javap -c` on the merged jar settled the button texture geometry, the tooltip draw order, and the depth-test behaviour — each of which would otherwise have been a screenshot bug.
- **A missing artefact is not automatically a bug.** The absent refmap looked like a production-breaking mixin problem and was correct behaviour for this Loom version. Verify the output, not the intermediate.
- **Verify a claimed fix actually landed** — grep for the call site rather than trusting a tool's success report. Carried over from last session and still sound practice.
