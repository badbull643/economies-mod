# EconomiesMod — Project Log

*Compiled as a reference for continuing development in Claude Code.*

*Last updated: 16 August 2026 — after the core-hardening session AND the full UI rebuild that followed it. Protocol version is still `"7"` (no wire-format changes this session). Branch `trust-model-and-migration` is pushed to `origin` and tracked — plain `git push`/`git pull` work. This file supersedes all earlier versions; if you're starting a new chat, this is the complete picture.*

*The headline change this session: the UI is no longer the biggest gap. It was a rewritten from a single flat 1194-line screen into a five-destination app (Home/Trading/Network/Market/Settings) with a real item picker, a live inventory panel, a state-driven Market screen, and fill notifications — built interactively against the user's own hand-drawn mockups, with several real bugs found from screenshots and fixed. Alongside it: a real item-duplication exploit was found and closed, inventory atomicity landed, trade history and cross-poll divergence detection were added, and the core test suite grew from 123 to 184 checks plus two new integration suites. See §7 and §8.*

---

## 1. The overarching idea

A Minecraft (Fabric, 1.16.5) mod that lets players on **separate survival worlds** participate in a shared virtual economy — a real market between worlds, without requiring a shared server or persistent infrastructure.

**The core premise:** specialization is a natural but mechanically unrewarded part of multiplayer Minecraft. One friend likes mining, another likes farming, another likes building — but there's no good way to trade across separate worlds beyond ad-hoc requests in Discord. This mod adds a real market between worlds.

**Two hosting modes, one engine:**
- **Rotating host** — for small friend groups. Whoever's online can host from inside their own Minecraft client; no dedicated server needed. Every participant holds a durable replica of the market's history, so hosting can move between people without the market resetting.
- **Dedicated server** — for larger communities. The same `HostServer` class runs standalone (no Minecraft on the classpath) on a box with a real public address. Same protocol, same trust model, one config difference.

**Trust model:** players sign their own actions with a personal keypair. The market's entire history is a hash-chained, signed event log that every client verifies independently — no single host, malicious or buggy, can forge trades, hide orders, or rewrite history undetected. This has been adversarially tested (`LogTamper`) and holds.

**Design philosophy — "low-effort, no-commitment":** the market is meant to feel optional and casual. Set a buy order, log off, come back later to filled orders — and now you actually get told about it (see fill notifications, §4). "Market infrastructure, not a market" — the mod supplies primitives (matching, wallets, trust); communities decide policy on top.

**Longer-term ambition:** at friend-group scale it's a convenience feature. At larger scale (1000+ players on a dedicated server) the same mechanics could produce real price discovery, market-makers, speculation, and a genuine trading culture — the Home dashboard's "most traded" and price-chart panels (§7) are a first step toward that, not yet filled in.

---

## 2. Architecture

### The one governing rule
**`core` never imports `net.minecraft.*`.** The engine is pure Java; only `client` touches Minecraft. This is why the whole core test suite runs headless in seconds via `./gradlew coreTests`.

```
io.github.badbull643.economiesmod/
├── core/                          — pure Java, no Minecraft imports
│   ├── Order, Fill, OrderBook     — matching engine
│   ├── WalletRegistry             — per-user currency ledger
│   ├── ItemBalanceRegistry        — per-user item ledger (heldBy(userId))
│   ├── MarketState                — root coordinator: books + wallets + items +
│   │                                market identity + key directory + grants +
│   │                                migration record + trade history.
│   │                                peekBook/hasBook read WITHOUT creating —
│   │                                bookFor still creates on read, for the one
│   │                                caller (placing an order) that needs that.
│   ├── Trade, TradeHistory        — NEW. Every fill, per item, bounded at 512/item.
│   │                                lastPrice() is "what it actually last traded
│   │                                for" as opposed to a bid/ask, which is only
│   │                                what someone is currently hoping for.
│   ├── Event, EventCanonical      — event types + what gets signed (marketId included)
│   ├── EventLog                   — append-only, hash-chained JSONL log
│   ├── EventApplier               — the ONLY gateway that mutates MarketState
│   ├── EventVerifier              — picks the right key and checks a signature
│   ├── AppliedEvent               — NEW. Wraps (SequencedEvent, EventApplier.Result,
│   │                                live). Carries fills to the client — they used
│   │                                to be computed and thrown away — and the `live`
│   │                                flag is what stops a synced history being
│   │                                mistaken for a live broadcast. See §4.
│   ├── PendingOps                 — NEW. Durable journal of half-finished
│   │                                deposit/withdraw operations. See §4.
│   ├── Settings                   — NEW. Persisted player preferences (port, last
│   │                                item/host, notification prefs). Per-username,
│   │                                in the config dir, same pattern as identity/peers.
│   ├── NetPosition                — a player's full position; used by describeLoss
│   │                                AND by migration
│   ├── MarketBootstrap            — creates a market (writes the genesis event)
│   ├── MarketArchive              — export / verifying import of a whole history
│   ├── MarketHighWater            — how far this market has been seen to reach
│   ├── LogTamper                  — dev tool: forge an event and re-chain (§8)
│   ├── MarketTests                — the suite; 184 checks, groups A–Q (§8)
│   ├── PlayerKeys                 — RSA-2048 keypair, sign/verify
│   └── net/
│       ├── Message                — Hello, Propose, Sync, Accepted, Rejected, Error
│       │                            (hostSeq/hostHash), Query, QueryReply,
│       │                            MigrateRequest/Result, CatchUp/CatchUpResult
│       ├── MessageChannel         — framing + the shared byte-budget chunker every
│       │                            bulk path (Sync/CatchUp/MigrateRequest) uses.
│       │                            Has its OWN message-type registry — standing hazard
│       ├── HostServer             — single-threaded sequencer; handles Migrate and
│       │                            CatchUp as pre-handshake exchanges, rate-limited
│       │                            and bulk-bounded
│       ├── MarketClient           — connects, syncs, proposes, verifies, applies;
│       │                            requestMigration() / offerCatchUp()
│       ├── ChunkTest              — end-to-end oversized-history sync (§8)
│       ├── ReplayGuardTest        — NEW. End-to-end proof that a synced history is
│       │                            never mistaken for live traffic (§8)
│       ├── Probe / PeerPoll       — liveness check + parallel discovery poll
│       └── PeerCache              — known peer addresses
└── client/                        — Minecraft-facing
    ├── MarketScreen               — NEW ARCHITECTURE. 2829 lines (grew from 1194 —
    │                                see §7). One physical file, five logical
    │                                destinations behind a hamburger nav: Home,
    │                                Trading, Network, Market, Settings.
    ├── MarketStateHolder          — LOCAL / CONNECTED / HOSTING mode holder;
    │                                migrateTo(), fast-forward, high-water, settings
    │                                and pending-ops accessors
    ├── FillNotifier                — NEW. Chat/action-bar notice when one of your
    │                                orders fills, styled as a server notice,
    │                                rate-limited with batching. See §4.
    ├── InventoryBridge            — server-side inventory manipulation (mutation)
    │                                PLUS a new client-side read-only held(player)
    │                                for display — see §4.
    ├── MinecraftIds               — Minecraft type ↔ engine type translation
    └── EconomiesmodClient         — mod entrypoint, lifecycle hooks, wires FillNotifier
```

### Key design decisions (and why) — additions this session

- **`AppliedEvent.live` is how a client tells a synced history apart from a broadcast.** Applying a synced event and applying a live one are the same operation as far as `EventApplier` is concerned — nothing downstream could previously tell them apart. Anything with an effect *outside the ledger* (giving items, sending a notification) must check `live`; anything that only reads state can ignore it, because a replayed event moves the ledger exactly as the live one did. This one flag closed a real duplication exploit (§4) and is what fill notifications are built on.
- **One durable journal for physical operations that cross a system boundary.** `PendingOps` — deposit is recovered exactly (the log either contains the clientEventId or it never will), withdraw is only ever *reported*, never re-given, because Minecraft's inventory ops aren't idempotent and re-giving on a stale record would mint items.
- **Settings and PendingOps both follow the `MarketHighWater` pattern**: flat public-field record class, Gson, load-in-constructor catching `Exception` (never just `IOException` — must not block world load), save-on-mutate. `Settings` is the first *global* (not per-world) file in the mod besides identity/peers, and like those it's suffixed with the player's username so the `clientAlice`/`clientBob` dev launches don't collide.
- **The UI is a hand-rolled app, not a stack of vanilla screens.** No tab widget exists in 1.16.5, so navigation, the picker, and confirmation modals are all drawn and hit-tested by hand rather than composed from `ConfirmScreen`/child screens — opening a real child `Screen` calls `removed()`+`init()` on the way back, which would have wiped the trade-entry fields and re-fired a discovery poll on every confirmation. See §7 for the whole architecture.

---

## 3. What's built and verified

- **Matching engine**, **ledger**, **event log**, **trust model** — all as before, still verified.
- **Networking, chunking, migration, fast-forward** — all as before (§5), all verified in-game in the prior session's campaign (§8).
- **NEW — Item-duplication exploit found and closed.** Withdraw items → Reset log → Connect → the sync used to replay your own historical `Withdraw` and hand the items over a second time. Repeatable indefinitely. Fixed via `AppliedEvent.live`; `ReplayGuardTest` reproduces the exploit against the *unfixed* code path and confirms it fails there before confirming the fix. See §4.
- **NEW — Inventory atomicity (partial, deliberately).** Deposit crash window closed exactly via `PendingOps` + a startup log scan. Withdraw crash window is reported, not auto-healed — see §4 for why that's the right call, not a shortcut.
- **NEW — A second real item-loss bug found and fixed**: `InventoryBridge.give` ignored `giveItemStack`'s return value, so withdrawing into a full inventory silently destroyed the items (ledger debited, items gone, journal cleared because the hand-over had "completed"). Now drops the overflow at the player's feet, same as vanilla `/give`.
- **NEW — Trade history.** Every fill is recorded per item, bounded, and now actually read — by the Trading screen's Price view and by the Markets overview's "last" column. Previously built by nothing, read by nothing but tests.
- **NEW — Cross-poll divergence detection.** Discovery already polls signed `(seq, hash)` from every host it finds; that's now compared against the local chain, so a fork can surface passively before anyone tries to connect and gets refused.
- **NEW — The whole UI**, rebuilt from a flat 1194-line screen into a five-destination app. See §7 in full; this is the majority of this session's work.
- **NEW — Fill notifications.** A server-notice-styled chat/action-bar message when one of your orders trades, worded and coloured differently depending on whether you were the resting order (news) or the aggressor (feedback for a button you just pressed). Rate-limited with batching.
- **Test suite grew from 123 to 184 core checks** (groups A–Q now), plus two new integration suites: `chunkTest` (6 checks, unchanged) and `replayGuardTest` (5 checks, new).

---

## 4. Fix log — this session

### Core hardening (before the UI work started)

**The withdraw-replay item-duplication exploit.** `MarketClient.applyLine` fires the applied callback for every line during a sync, exactly as it does for a live broadcast — `MarketStateHolder`/`EconomiesmodClient` had no way to tell the two apart. The handler that hands over real items for your own `Withdraw` events ran for *historical* withdrawals too. Concretely: withdraw items, Reset log, Connect — the resync replays your old `Withdraw`, you get the items again, and you can repeat this indefinitely. It also fires on any fresh rejoin of a market you have history in, since offline UUIDs are derived from the username. **Fixed** by widening the applied callback from a bare `SequencedEvent` to `AppliedEvent` (event + `Result` + `live`), with `live = false` for the whole duration of a sync. `ReplayGuardTest` proves it: builds a history containing the joiner's own withdrawal, syncs it to a fresh client, and asserts the replayed line is *not* marked live — verified to fail (marked live) with the guard removed, and separately asserts a freshly authored event still arrives live, so the flag can't regress into simply disabling the handler.

**Inventory atomicity, deliberately partial.** Two crash windows exist because the ledger and the physical inventory are separate systems with nothing spanning both transactionally:
- **Deposit**: items are removed from the inventory *before* the event is proposed. `PendingOps.recordDeposit` writes a journal entry first, keyed by the event's `clientEventId`. On next startup, the log is scanned: if the event landed, the ledger already has the value and the entry is dropped; if it never will (the proposal died with the process), the items are returned. Exact, because the log is authoritative and a `clientEventId` either appears in it or provably never will.
- **Withdraw**: the ledger is debited *before* the physical hand-over runs a tick later. This direction **cannot** be made exact — nothing records whether `give()` completed, and Minecraft's inventory operations aren't idempotent, so re-giving on the strength of a surviving journal entry would mint items whenever the crash landed *after* the give rather than before it. These entries are reported on next login, never acted on. Losing items is a bad outcome; duplicating them is worse, and this is a deliberate asymmetry, not an oversight.

**Items lost to a full inventory.** `InventoryBridge.give` called `giveItemStack` and ignored its `boolean` return — that method is *only* `inventory.insertStack`, and drops nothing itself. Withdrawing into a full inventory silently destroyed the items: ledger debited, items gone, and the pending-ops journal cleared because the hand-over had reported success. Fixed to drop the overflow at the player's feet (same fallback vanilla's `/give` uses).

**Trade history and cross-poll divergence** — both described in §3, both additive, no core-file surprises.

**Hygiene**: `MarketState.java` cut from 993 to 225 lines (dead commented-out legacy test code against an API that no longer compiles); `MarketStateHolder.mode` made `volatile`; several dead-code items removed. `MarketState.peekBook`/`hasBook` added so UI code that merely *displays* the market doesn't spam empty order books into existence for every item glanced at — `bookFor` still creates on read, kept for the one caller (placing an order) that actually needs that.

### The UI rebuild

This is the bulk of the session. Original plan was a modest three-tab split; the user then supplied hand-drawn mockups (hamburger nav, a persistent header, a three-column Trading screen with a swappable info panel + inventory list, item slots instead of typed IDs) and the whole thing was redesigned around those. Built in an explicit sequence, each step compiling and testable on its own:

1. **Chrome** — persistent header (hosting status, credits, selected item's market credit, real-world clock, hamburger) and a hand-drawn nav panel (Home/Trading/Network/Market/Settings) replacing the three-tab bar. Deliberately not built from real child `Screen`s or `ConfirmScreen` — opening one calls `removed()`+`init()` on the way back, which would wipe the amount/price/order-id fields and re-fire discovery on every confirmation. The nav and every modal are drawn *inside* `MarketScreen.render()` instead.
2. **Drawing primitives** — a scissor-clipping helper (`RenderSystem.enableScissor` takes raw framebuffer pixels with the origin bottom-left and no scale transform of its own — every scroll region needs both a scale-factor conversion and a Y flip, done in exactly one place) and an item-icon helper (`ItemRenderer.renderInGui` ignores the `MatrixStack` entirely and draws through the legacy `RenderSystem` matrix; without raising the z-offset first, the way vanilla's `HandledScreen.drawSlot` does, icons render *behind* anything filled afterward). Proven by making the order book scroll instead of truncating at six rows per side.
3. **Inventory panel** — Trading's right column: what the player is actually carrying, read from the **client-side** inventory (which is exactly what pressing E shows — agreeing with that is the point; mutation still goes through the server-side path). Aggregated across stacks, NBT-bearing stacks excluded and counted separately, clickable to select that item.
4. **Item picker** — the big one. Retires the typed `minecraft:iron_ingot` field entirely; a button now opens a modal grid searching every item's **display name** (`Item.getName()`, not the registry id), seeded by default with items the market already trades or that you're carrying so the common case needs no typing at all. Its search box is a plain `String`, not a `TextFieldWidget` — widgets render at one fixed point in `super.render`, underneath the modal that's supposed to cover them, so the picker has to eat its own key events.
5. **Left panel view switcher** — Trading's left column now cycles Order book / Markets (every item at once, best bid/ask/last, click to select) / Price (a capped-width sparkline from `TradeHistory`).
6. **Guided Market screen** — the fix for a real confusion caught during earlier in-game testing, where Connect and Migrate sat adjacent and looked equally plausible. The screen now derives a situation (damaged / no-market / forked / behind / connected / offline) and shows *only* the actions that apply, each with the reasoning in plain language. **Migrate only appears when discovery has actually seen a host running a different market** — offering it unconditionally is what let it be confused with Reset in the first place.
7. **Settings screen** — exposes what `Settings` already persisted with nowhere to show it: chat/action-bar toggles (independent — chat is scrollback you read later, the action bar is glanceable and gone) and a batch-rate slider (0 = always batch). `CheckboxWidget`/`SliderWidget` don't self-report changes, so both are subclassed to write straight through on touch.
8. **Home dashboard** — Hosts is live (from existing discovery polling); the other three slots are empty titled panels by explicit user choice ("first pass just hosts, ideally all four eventually").
9. **Fill notifications** (`FillNotifier`) — see §3. Built last because it depended on `AppliedEvent` already carrying fills.
10. **Cosmetic pass** — the whole thing re-skinned from flat modern boxes to vanilla's actual look: `vanillaPanel()` replicates the tooltip frame (near-black fill, violet gradient edge) for every panel, modal, and the nav; item cells got the real inset bevel (grey body, dark top-left highlight, light bottom-right); overlay buttons became real `ButtonWidget` instances drawn manually (not registered, so they don't end up under the panel in the render order) instead of hand-filled rectangles, so they actually match the real buttons next to them.

**Real bugs found from user screenshots during this pass, all fixed**:
- **Depth-test leak.** Item icons leave depth testing *enabled* after drawing. A panel `fill()`'d afterward got depth-rejected against text already on screen, so buttons behind the item picker and confirmation overlays showed straight through them. Fixed by disabling depth testing around panel chrome and re-enabling it only around the icon grid that needs it.
- **Translucent backdrop bleed.** A separate issue from the above — the modal backdrop itself was only ~88% opaque, so text outside the panel's bounds stayed legible through the dim. Made briefly fully opaque, then **reverted at the user's explicit request** — they want the world visibly transparent behind the item picker specifically, and accepted the readability trade-off.
- **`setSuggestion` never clearing.** Grey placeholder text draws *after* whatever's typed and never stops on its own — typing `5` into the amount field left it reading `5qty`. Fixed with a `hint()` helper that clears/restores the suggestion via a changed-listener.
- **Two edits that silently failed to land.** During the left-panel-views step, two `Edit` calls reported success but the file was being modified concurrently and the changes never actually reached disk — the three new views (Order book/Markets/Price) were fully written but never wired into `render()`/`mouseClicked`, so the switcher drew but did nothing for a full commit. Caught later by grepping for the call sites rather than trusting the tool's reported success. **Worth remembering**: a successful edit result is not proof a change is in the file when something else may be touching it concurrently — verify by grep, especially for a step that's additive rather than one that would break a build if missing.
- **Content overhanging its own panel, on every screen.** Once panels got real frames drawn around them, content kept sticking out past the frame edges — titles above the top border, lists running past the bottom. Root cause: everything was still positioned relative to the *control rows* beside a panel rather than the panel's own bounds, because those had been added at different times. Fixed by introducing `panelTop()`/`panelBottom()` as the single source both the frame and its contents measure from.
- **Clicking a host did nothing.** Same root cause, worse consequence: `discoveryStartY()` meant "header row" to the re-place-order list but "first entry" to the host list, so the click handler added the header's row-height offset to a value that already included it. Every click landed one row low, and the last host in the list was unreachable. Fixed by making both callers agree on what the helper returns, and — to stop this recurring — making `renderDiscovery` compute its own `y` from that same helper instead of receiving it as a parameter that could drift from what the hit-test used.
- **Trading row proportions.** The quantity/price/order-id fields — typed into constantly — were narrower than the item-picker button, which only ever shows a name it can afford to trim. Rebalanced; Withdraw also moved to its own row since it had been sharing one with the order-id box and Cancel, leaving three controls fighting for a width that comfortably fits two.

### Still open

**Sequence-gap recovery.** `MarketClient.applyLine` still just tolerates a live mid-session gap rather than triggering catch-up (confirmed unchanged this session). Deliberately deferred every time it's come up — it touches the most safety-critical verification path in the client and deserves its own two-client testing pass, not a late addition to a long session.

**Fork-reset re-place checklist.** Reset after a fork still silently discards any orders placed after the divergence point, unlike Migrate, which offers a checklist. The infrastructure (`OldOrder`/`pendingReplace`/the re-place UI) already exists and just needs reusing, scoped to events after the fork point. Parked as a background task chip; nobody's picked it up.

**Home's three empty widget slots** — most-traded (from `TradeHistory`), a price chart, an activity feed (tail of the event log). All buildable now; the Price view already proves `TradeHistory` renders fine in a small space.

**Small hygiene items, confirmed still present by direct grep this session** (not re-litigated, just re-checked so this list stays honest): `status`/`recoveryNote` in `MarketScreen` are still `static` rather than instance fields — this was flagged as a cleanup item when the header was built and never actually done; `Message.SteppingDown` is still dead code; `HostServer.broadcast` still sends synchronously per client; `applyLine` (client) still persists a line before `EventApplier` validates it semantically (deliberate, documented risk for now).

**Item picker slot brightness.** The picker's icon grid now uses the real vanilla slot colour (light grey body), which is authentically correct but reads much brighter than the near-black panel it sits on — flagged as possibly worth a darker slot palette that keeps the bevel structure. Not yet revisited.

### Explicitly rejected claims (do not "fix")
Cancel refund double-spend, self-trade item/currency loss, "missing signature verification during replay" (deliberate for locally-built logs). Tying the welcome grant to the world/save rather than the market (unverifiable, and would deadlock a fresh market's zero money supply).

---

## 5. Market fragmentation — unchanged, still solved

No changes this session. See the previous log entry's detail if needed; the short version: Layer 0 (fast-forward, automatic), Layer 1 (genesis prevents accidental creation), Layer 2 (export/import bootstrap), Layer 3 (Reset for same-market forks, Migrate for genuinely different markets, both anti-double-mint guards verified firing in-game). The Market screen built this session (§4) is a UI-layer improvement on top of this, not a change to the underlying logic — it now *tells you* which of Reset/Migrate applies instead of showing both unconditionally.

---

## 6. Other known limitations / deferred features
World provenance/anti-cheat, encryption assumption (VPN mesh), no admin tooling, no snapshotting, shared-world unsupported, NAT traversal out of scope, MC version port estimated ~1–2 days.

---

## 7. The UI — rebuilt, no longer the biggest gap

### Architecture
One physical class (`MarketScreen`, 2829 lines — up from 1194, but doing five screens' worth of work now, not one crowded one), five logical destinations reached through a hamburger nav drawn in the top-right corner rather than a tab bar. Every destination's widgets are built once in `init()` and shown/hidden via `visible`/`active` rather than rebuilt — `ClickableWidget` skips both rendering and hit-testing when invisible, verified against the actual remapped 1.16.5 jar, so this is safe and field contents survive switching destinations.

**Home** — a dashboard: Hosts (live), three empty panels reserved for most-traded / price chart / activity feed.

**Trading** — three columns: a left panel that switches between Order book, Markets (cross-item overview), and Price (sparkline); middle trade controls built around a clickable item slot instead of a typed field; a right column showing what you're physically carrying, click-to-select.

**Network** — host list (in the left panel, not stacked under the buttons — that placement bug is fixed, see §4) and connect/host/disconnect/stop controls.

**Market** — state-driven. Reads the situation and shows only the applicable actions with the reasoning spelled out; Migrate is gated on discovery having actually found a foreign host.

**Settings** — notification toggles and the batch-rate slider, writing straight through to `Settings` on touch.

**Modals**: an `Overlay` system (`NOTICE`/`CONFIRM`/`DANGER`) replaces what used to be five two-click "arm" flags scattered across handlers. Queued, so a second warning can't clobber an unread first one; `DANGER` overlays ignore clicks for ~400ms after opening so a double-click aimed at whatever's underneath can't carry through as a confirmation. The item picker is a similar hand-drawn modal, deliberately kept translucent over the game world per explicit user preference.

**Styling**: everything the mod draws itself now imitates vanilla — the tooltip-frame panel look, real inset-bevel item slots, real `ButtonWidget` instances for overlay buttons (drawn manually, not registered, so render order stays correct) instead of hand-filled rectangles. Layout is sized from the window between a floor and a ceiling rather than fixed, so it no longer looks sparse on an ordinary window or overflows at high GUI scale.

### What's not yet done
Home's three placeholder panels; the item-picker slot colour (correct but bright); the fork-reset re-place checklist reusing the same UI migration already has.

### What needs in-game verification, not more code
**The guided Market screen's forked-vs-different-market distinction** has never been exercised with two live clients since it was built — only reasoned through. That's the one behavioural claim from the whole rebuild worth confirming before trusting it.

---

## 8. Testing methodology

**Unit — `./gradlew coreTests`.** 184 checks now, groups A–Q, no Minecraft, seconds. Prints a tally and exits non-zero on failure.

**Integration — `./gradlew chunkTest`.** Unchanged from last session: builds a genuinely oversized history and syncs it to a fresh client, sized off the frame cap (not the chunk budget) so it fails without the fix rather than merely exercising it.

**NEW — `./gradlew replayGuardTest`.** Builds a history containing the joiner's own past withdrawal, syncs it to a fresh client, and asserts the replayed line is never marked `live`. Verified to genuinely fail (catch the bug) with the guard removed before being trusted — the same discipline `chunkTest` established last session.

**Adversarial — `LogTamper`.** Unchanged.

**In-game.** No new formal two-client campaign this session (the eight-scenario campaign from the prior session still stands, see the previous log version if needed in full — fast-forward, fork, migration ×3, the M6 exploit, multi-chunk migrate/catchup/sync, protocol mismatch, all passed with on-disk verification). This session's in-game work was almost entirely **UI verification via screenshots**: the user ran the client, screenshotted the actual rendered screen at each step, and several real bugs (depth-test leak, host-click dead zone, content overhanging panels, cramped trading-row widths) were found and fixed this way rather than by reasoning about the code. That method is worth repeating — none of those four bugs would have been caught by the core test suite, since none of them are core-engine logic.

**Method note carried forward**: verify a claimed fix is actually in the file, especially after any tool call reports success during a period of concurrent file modification — grep for the change, don't just trust the result. This bit once this session (§4, the dead left-panel-switcher commit).

---

## 9. Prior art consulted
Unchanged: Raft's election restriction, Keybase sigchain, Nostr NIP-41, Secure Scuttlebutt, Certificate Transparency gossip (now partially built — see cross-poll divergence, §3), split-brain prevention literature.

**Universal finding, still holds**: no system recovers a lost key cryptographically. `KeyReplaced` stays parked.

---

## 10. Suggested next steps

1. **In-game verification of the guided Market screen**, specifically the forked-vs-different-market distinction with two live clients. The single highest-value remaining check — everything else shipped this session has already been screenshot-verified or unit-tested.
2. **Sequence-gap recovery** — wire the existing catch-up machinery to the live mid-session gap detection that currently just shrugs. Wants its own testing pass given what it touches.
3. **Fill Home's three empty panels** — most-traded and the price chart both have their data source (`TradeHistory`) already proven by the Trading screen's Price view; the activity feed needs a tail-read of the event log, which doesn't exist yet as a helper.
4. **Fork-reset re-place checklist** — reuse migration's existing UI, scoped to post-divergence events.
5. **Hygiene sweep, round two** — `status`/`recoveryNote` to instance fields, `Message.SteppingDown` removal, `HostServer.broadcast` async fan-out, item-picker slot palette.
6. Consider opening the PR against `main` — the branch is pushed and GitHub offered a link; nobody's opened it yet.
