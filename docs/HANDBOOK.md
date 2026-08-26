# EconomiesMod handbook

*What the mod does, how to use it, and how it works underneath.*

Two readers, one file. Part 1 is for somebody who just installed it and wants to trade with
three friends. Part 2 is for somebody about to change the code. Neither needs the other.

Where a design note or the session log argues something at length, this file says what was
decided and points at the argument instead of repeating it.

---

## Contents

**Part 1: using it**

1. [What this actually is](#1-what-this-actually-is)
2. [Getting in](#2-getting-in)
3. [Your first market](#3-your-first-market)
4. [Money, items, and orders](#4-money-items-and-orders)
5. [What it costs to trade](#5-what-it-costs-to-trade)
6. [Playing together](#6-playing-together)
7. [Playing with friends who aren't on your network](#7-playing-with-friends-who-arent-on-your-network)
8. [More than one market](#8-more-than-one-market)
9. [When something looks wrong](#9-when-something-looks-wrong)
10. [Host rules](#10-host-rules)
11. [Command reference](#11-command-reference)

**Part 2: how it works**

12. [The shape of the thing](#12-the-shape-of-the-thing)
13. [The event log](#13-the-event-log)
14. [Events, and what each one means](#14-events-and-what-each-one-means)
15. [validate and apply](#15-validate-and-apply)
16. [The order book](#16-the-order-book)
17. [The money rules](#17-the-money-rules)
18. [The network](#18-the-network)
19. [Divergence: noticing and locating a fork](#19-divergence-noticing-and-locating-a-fork)
20. [Loading a market quickly](#20-loading-a-market-quickly)
21. [The client](#21-the-client)
22. [Items and the inventory](#22-items-and-the-inventory)
23. [Migration, archives and slots](#23-migration-archives-and-slots)
24. [The trust model](#24-the-trust-model)
25. [Rules this codebase keeps rediscovering](#25-rules-this-codebase-keeps-rediscovering)
26. [Tests](#26-tests)
27. [Where to read more](#27-where-to-read-more)
28. [Running a server, and how the jar is put together](#28-running-a-server-and-how-the-jar-is-put-together)

---
---

# Part 1: using it

## 1. What this actually is

A market you and your friends run yourselves, inside Minecraft. You put items in, list them
at a price, and somebody else buys them with credits. No shops, no villagers, no admin
handing out money.

Three things make it different from a chest with a sign on it.

**It's a real order book.** You list 20 iron at 5 credits. Somebody bids 6. The trade
happens at 5 and the difference is theirs. Orders sit on the book until they fill or you
cancel them.

**Nobody is in charge of the numbers.** Every player's game keeps its own copy of
everything that ever happened in the market and works out the balances itself. Whoever is
hosting decides what order things happen in, and that's all they decide. They can't give
themselves credits or delete a trade, because everyone else would compute a different
answer and spot it immediately.

**It survives the host logging off.** The history is a file. Anyone who has it can host
next, and the market carries on. There's also a dedicated server for a group that would
rather one machine stayed up.

What that costs you, plainly: everyone needs a copy of the history, trading only happens
while somebody is hosting and at least one other person is connected, and two copies that
both get traded on while apart will *fork*. [§9](#9-when-something-looks-wrong) is mostly
about that last one.

---

## 2. Getting in

Three ways in. You only need one.

**The inventory button.** Open your inventory. There's an emerald next to the recipe book
button. That's the market.

**A key.** Nothing is bound by default, so the mod doesn't quietly take a key another mod
wanted. Bind one in **Options → Controls → EconomiesMod**.

**Chat commands.** `/trade balance`, `/trade orders` and `/trade price <item>` read the
market without opening anything. Full list in [§11](#11-command-reference).

### The screen

Five tabs across the top.

| Tab | What's there |
| --- | --- |
| **Home** | Your balance, recent activity, a price chart, and any warnings |
| **Trading** | Buy, sell, cancel, withdraw. The things you do most |
| **Network** | Connect to somebody, host, see who's around, migrate |
| **Market** | The market itself: create, import, export, and its rules if you made it |
| **Settings** | Placeholder for now |

### The inventory panel

Next to your inventory is a small panel listing the most recent things **other people**
have put up for sale, under the market's name. Click the header to swap between `Sell
orders` and `Buy orders`.

Your own orders are never in it, since you already know what you listed. If it says *"only
your 4 orders"*, the panel is working and the book just has nothing but yours in it.

`/trade panel` turns it off or changes how many rows it shows.

---

## 3. Your first market

### Making one

**Market tab → Create a new market.** Give it a name. You're now its creator, which matters
later: only the creator can change the market's rules.

You'll be asked to confirm, because a market you create shares no history with one your
friends already use, and two markets can never be merged. If somebody already has one, join
theirs instead.

### Joining somebody else's

**Network tab, type their address, Connect.** Your game downloads the whole history,
verifies every signature in it, and rebuilds the market from scratch. That's why the first
join takes a moment.

If they're on the same machine or the same network, **Refresh hosts** finds them without
typing anything.

You can't join while holding a market of your own in that world. They're separate economies
and the mod will say so. Use a second [slot](#8-more-than-one-market) for theirs.

### Getting the history when nobody's online

**Market tab → Export to a file** hands you the whole market as a file. Somebody else uses
**Import one from a file** and has it, with no host running and nobody online at the same
time.

An imported file is treated as a file from a stranger. Every signature in it is checked and
every rule re-applied before a single credit of it is believed.

### Your starting money

The first time you appear in a market, the host gives you a **welcome grant**. It happens
once per person per market, it's recorded in the history like everything else, and the
creator sets the amount.

---

## 4. Money, items, and orders

### Credits and items live inside the market

Depositing takes an item out of your Minecraft inventory and credits it to you in the
market's ledger. Withdrawing does the reverse. In between, the item is just a number, and
that's what lets it be traded while you're both offline.

### The four things you can do

| Action | What happens |
| --- | --- |
| **Sell** | Takes items from your inventory and lists them at your price |
| **Buy** | Places a bid, matching anything already at or below your price |
| **Cancel** | Takes an order off the book. Whatever it was holding comes back |
| **Withdraw** | Turns credited items back into real ones in your inventory |

### How a trade happens

Orders sit on a **book**, one per item, sorted by price. A buy and a sell cross when the
buyer will pay at least what the seller is asking.

**The resting order sets the price.** Bid 6 for something resting at 5 and you pay 5.
Turning up late doesn't cost you anything.

**Ties go to whoever got there first.** Two sells at the same price fill in the order they
were placed.

**One order can fill many.** A sell of 10 into ten resting bids of 1 is ten trades at once,
and the book empties in a single frame. The mod tells you afterwards: *"Sold 9 of 10 Dirt
across 9 orders, +18, 1 still resting"*.

Anything that doesn't fill immediately **rests** until it does. A partly filled order leaves
the remainder on the book.

### Knowing when something traded

Chat tells you when one of your resting orders fills, which is the one you weren't watching
for. The action bar can do the same, more briefly. Both are settings.

Filling somebody else's order is reported too, in grey, since that's feedback for something
you just did rather than news.

---

## 5. What it costs to trade

Four numbers, all set by the creator, all visible on the Market tab.

**Welcome grant.** What a new arrival starts with. Capped at 10,000 when somebody hosts from
their game. A dedicated server may go higher.

**Trading fee.** A percentage of each trade, taken from the seller's proceeds and destroyed
rather than paid to anyone. It drains the money supply instead of being income for a host.

**Listing fee.** Charged when you place an order and never refunded, including if you cancel.
It's there so placing orders isn't free, because a market where it is free can be spun in
circles for profit.

The listing fee can also **escalate**: a market can give everyone a few free orders and
charge more the more you hold open at once. Set to zero, nothing escalates and every listing
costs the same.

**Stipend.** See below.

### The stipend, and why it exists

The welcome grant used to be the only way credits ever entered a market. That means the money
supply grows when *people* arrive and never again, while goods keep piling in. Prices sink
until everything costs 1 and price stops meaning anything.

The stipend fixes that. Any registered player can **claim** a fixed amount once per *N fills
the market settles*, 50 by default. Click **Claim stipend** on the Market tab.

Two details that look arbitrary and aren't.

**It counts fills, not time or events.** Deposits, withdrawals and cancellations are free
actions, so a stipend paid per event could be farmed by one person alone in an empty market.
A fill needs two orders to cross, and every order costs a listing fee.

**A market refuses a stipend that outruns its own fees.** If the payout across every
registered player is more than the listing fees collect over the same interval, the market is
printing money and the rule turns it down. It's checked when the stipend is set and again at
every claim, since a market can outgrow one that was affordable when it was chosen.

---

## 6. Playing together

Somebody has to be **hosting** for trades to happen. Two ways to do it.

### Hosting from your game

**Network tab → Host.** Your game starts listening on a port and your friends connect to it.
You carry on playing, and the market runs inside your session.

Hosting moves around freely. Stop hosting and anybody else holding the full history can host
instead, which is why the history matters more than any particular machine does.

The Host button greys itself when it shouldn't be pressed: while a dedicated server is
already serving that market, or when your copy holds only a summary rather than the history
behind it. If it refuses when you click, it says why.

### The dedicated server

A standalone process with no Minecraft and no world, keeping a market up whether or not
anybody is playing. Hosting from your game suits a friend group. A server suits a group that
would rather one machine was always there.

#### What the operator needs

**One jar and a Java 8 runtime.** No Minecraft, no Gradle, no copy of this repository.

```
./gradlew serverJar          # build/libs/economies-server.jar, about 600 KB
```

Hand that file to whoever is running the server. It's self-contained: `core` imports no
Minecraft, and gson is packed in beside it.

*The mod jar won't do.* Minecraft supplies gson at runtime, so a standalone JVM loading the
mod jar dies on `NoClassDefFoundError` before it prints anything.

From the repository you can run `./gradlew hostServer --args="…"` instead. Every command
below works either way: swap `./gradlew hostServer --args="<flags>"` for `java -jar
economies-server.jar <flags>`.

Keeping one running, updating it and what to back up are in
[§28](#28-running-a-server-and-how-the-jar-is-put-together), along with what's inside the jar.

#### Setting one up

**1. Put the jar somewhere that isn't the repository.** Everything the server creates lands
beside its log file, and one of those things is a private key.

```
mkdir ~/economies-server && cp economies-server.jar ~/economies-server/ && cd ~/economies-server
```

**2. Write a config.** Nothing exists yet, so this creates one with every setting in it.

```
java -jar economies-server.jar --config server.json --write-config
```

**3. Edit it.** Three settings matter before the first start.

```jsonc
"logFile": "market.jsonl",             // beside the jar, anywhere but the repository
"port": 25555,
"hostName": "our server",              // what players see in the host list

"policy": {                    // the market's own economics, see below
  "taxBps": 100,               // 1% trading fee, taken from the seller and destroyed
  "welcomeGrant": 500,         // what a newcomer starts with
  "listingFee": 2,             // charged to place an order, never refunded
  "listingFreeOrders": 0,      // orders held open before the fee climbs, 0 for never
  "stipendAmount": 0,          // 0 for no stipend
  "stipendEveryFills": 50
}
```

**4. Start it.**

```
java -jar economies-server.jar --config server.json
```

The first start with an empty log creates the market. You should see roughly:

```
[host] created 'our server's market' owned by this server
[host] policy updated from this config at event 3: tax 0 to 100, listing fee 0 to 2
[host] listening on port 25555
```

**5. Join it.** In game, **Network tab → address → Connect**, using `host:25555`. On the same
machine, `localhost:25555`.

#### What it creates, all beside the log

| File | What it is |
| --- | --- |
| `market.jsonl` | The market. The only irreplaceable file, so **back it up** |
| `server-identity.key` | The server's private key. Don't share it, don't commit it |
| `server-peers.json` | Who connected and from where. The operator's own note |
| `known-keys.json` | Identities seen before, so they're recognised again |

#### Changing things afterwards

**Host rules** are admission, deposit caps, world checks, migration limits, the
welcome-grant ceiling, port and name. Edit the config, restart. They belong to whoever is
hosting and take effect immediately.

**The market's economics** are the `policy` block. Edit, restart, and a server that created
its market publishes the change as a `MarketPolicy` event, exactly as a player would from
the Market screen. It's the creator, after all.

Two things worth knowing:

- **Anything you leave out is left alone.** A block mentioning only the tax won't touch the
  grant or the fee, which is why the fields are absent rather than zero when unset.
- **A policy the market would refuse is reported and skipped**, like a stipend that outruns
  its listing fees or a grant above the host's ceiling. The market keeps running on what it
  had.

Running `--write-config` again rewrites the file with the numbers actually in force, which
is the quickest way to see where a market ended up.

#### Who owns the market

By default the server does, and the operator changes policy through the config.

**`--creator-key` names a player as creator instead**, so the rules get changed from the
Market screen in game. Worth it when a person should own the market and the box is only
hardware. Otherwise the config is simpler and the key is one more thing to keep somewhere.
It's used on the first start only, needs `creatorUserId` in the config, and can't be changed
afterwards, since the creator is recorded at genesis and nothing moves it.

#### If something looks wrong

| It says | It means |
| --- | --- |
| `the policy block in this config is not being applied` | This server is hosting a market it didn't create. Only the creator sets policy |
| `the policy block in this config was refused: …` | The numbers break a market rule, usually the stipend interlock. The market is fine and unchanged |
| `this market grants N, and welcomeGrant is set to M` | The amount is the market's, fixed when it was created. The config setting only chooses whether this server issues grants at all |
| `port already in use` | Something else has 25555, or a previous run hasn't exited |

#### Two differences from a friend hosting

A dedicated server **doesn't hand out its peer list**. `server-peers.json` stays the
operator's note rather than something clients learn from. And **clients keep only a
snapshot** of a dedicated market rather than the whole history, since the box is always
there. `/trade archive on` opts back in for anybody who wants a full copy.

### Who can join

The host decides, and which file it reads depends on which kind of host it is. This is one
of the easier things to get wrong when moving between the two.

| Hosting from | Reads |
| --- | --- |
| Your game | `host-config.json`, beside that world's market. `/trade hostconfig write` creates it |
| A dedicated server | Its own `--config` file, the one `--write-config` produced |

Both hold the same host rules and mean the same things. See [§10](#10-host-rules). By
default anybody may join either.

---

## 7. Playing with friends who aren't on your network

**Open to LAN only reaches your own network.** If your friends are elsewhere, something has
to make their machine able to reach yours, and there are two connections that need it, which
is the bit people miss.

| Connection | What it is |
| --- | --- |
| Minecraft | Open to LAN, on whatever port Minecraft picks |
| The market | This mod's own socket, on the port in the Host field, 25555 by default |

They're separate. Forwarding one and not the other gets you a world you can join with a
market you can't, or the reverse.

### The easy way: a virtual LAN

**ZeroTier**, **Radmin VPN** or **Hamachi** put everybody on one virtual network, and both
connections then work exactly as they do at home. No router settings, no public IP, and
nothing to undo afterwards.

With ZeroTier: everyone installs it, one person creates a network and shares its ID, everyone
joins that ID, and the creator authorises them in the ZeroTier panel. Each machine then has a
second address, usually starting `10.` or `172.`, and that's the address to type into
**Connect**, and the one to give people for Open to LAN.

This is how the mod's own two-machine testing was done, and it's what to reach for first.

### The other way: port forwarding

If you'd rather not install anything, forward **both** ports on the host's router:

- the market port, 25555 by default
- the port Minecraft prints when you click Open to LAN, which changes every time unless you
  set it

Then friends connect to your public IP. It works, but it exposes both ports to the internet,
and Minecraft's LAN port moving each session makes it tedious.

### The third way: a dedicated server

If the box already has a public address, none of this applies. Run
[the dedicated server](#the-dedicated-server) on it and everyone connects to `address:25555`.
Only the market needs to reach it, since nobody is joining a Minecraft world on that machine.

### If it won't connect

- **Check the market port, not Minecraft's.** They're different numbers and the mod's is the
  one in the Host field.
- **The host has to be hosting.** Network tab, Host, and it should say it's listening.
- **On a virtual LAN, use the virtual address.** Your normal `192.168.…` address is not
  reachable from another network even when ZeroTier is running.
- **Firewalls block the market port too.** Windows asks about Minecraft the first time and
  says nothing about this mod's socket, which is a separate rule.

---

## 8. More than one market

A world can hold several markets with one of them **active**. Market tab → **Add another
market** makes a new slot, and the switcher moves between them.

Why switch rather than belong to both at once? Currency doesn't move between markets and an
inventory can't be spent twice, so being in two at once is sound in principle. The problem is
that every figure in the mod would have to fan out across markets: your position, the fork
checks, migration, notifications, the whole screen. Switching costs almost nothing and covers
what people actually wanted, which is a friend group some evenings and a bigger server
otherwise.

It also takes the one-way-door feeling out of leaving a market, since you can join another
without destroying the first.

### Archiving

Connect to a **dedicated server** and your game keeps only a summary of where the market got
to, not every event behind it. That history lives on the server, and there's no reason for a
hundred players to each keep a copy.

If you want the full thing, either to host it yourself or just to have it, `/trade archive
on` and reconnect once. `/trade archive` says which you've got.

A rotating host is unaffected, because there, keeping the history is how hosting rotates.

---

## 9. When something looks wrong

### "You are behind"

Somebody else's copy has more events than yours. Connect to them and catch up.

The mod remembers the furthest the market has ever been seen to reach, even when nobody is
online, so it can still warn you a week later when discovery finds nothing at all.

### "Your history diverged"

Two copies were both traded on while they were apart, so there are now two versions of the
past. This is the one genuinely bad state and it can't be merged: the two chains disagree
about what happened, and so does every balance after the split.

What the mod does about it:

- **It finds where you parted** and says so: *"you parted from the other copy after event
  42."*
- **Everything before the split is safe.** It's in their copy too and comes back when you
  rejoin.
- **Reset** discards your branch and rejoins theirs. Before it does, it works out what that
  costs and hands back what it can:
  - **Items you deposited after the split** go back into your inventory. Nothing else
    records them, so that's the one loss a reset would otherwise cause outside the ledger.
  - **Orders you placed after the split** come back as a checklist so you can re-place them.
- What can't come back: credits you earned after the split, and trades made against a book
  that no longer exists. Those are ledger entries with no existence outside the branch being
  discarded.

### "Log unusable"

The file has a line the mod can't read, or the chain doesn't add up. The market is still
shown, with a banner and a Reset button, because emptying somebody's market on the screen
they came to fix it from is the wrong answer to a bad line.

A damaged history can't be hosted, since serving it would hand a joiner a chain that breaks
partway. The Host button says so and points at Reset.

### Migration, or leaving with what you hold

If you want to *move* to another market rather than rejoin your own, **Migrate my position**
carries your credits and items across, provided the destination accepts migration. The market
you're leaving is abandoned rather than merged, and the destination host may cap how much can
arrive.

---

## 10. Host rules

Two kinds of rule, and mixing them up causes most of the surprises.

### Market rules: in the history, the same for everybody

Welcome grant, trading fee, listing fee, stipend. They're recorded as events, every copy
applies them identically, and only the **creator** can change them.

### Host rules: in a file, belonging to whoever is hosting

Admission, deposit caps, world checks, migration limits, the welcome-grant ceiling. They
**change when hosting rotates**, because they're that host's defence against its clients
rather than the market's defence against its host.

Where they live depends on who's hosting: `host-config.json` beside that world's market when
somebody hosts from their game, or the dedicated server's own `--config` file. Same settings,
same meanings, two files, because they belong to the host rather than to the market.

`/trade hostconfig` prints the ones in force for a world, and `/trade hostconfig write`
creates the file with every setting in it. For a server, `--write-config`.

| Setting | What it does |
| --- | --- |
| `admission` | `open`, or an allowlist, plus a deny list |
| `maxDepositUnitsPerWindow` + `depositWindowMinutes` | Rate-limit how much anyone can deposit |
| `requireAttestation` | Turn away clients that won't describe their world |
| `refuseCreativeWorlds`, `refuseCheatWorlds` | Turn away worlds reporting creative mode or cheats |
| `maxDepositUnitsPerPlayHour` | Weigh deposits against claimed playtime |
| `maxDepositMultipleOfHandled` | Weigh deposits against what Minecraft says they've handled |
| `maxMigratedCredits`, `acceptsMigration` | Limit or refuse incoming migrations |
| `maxWelcomeGrant` | The ceiling this host will issue |
| `banOnWorldChange` | Ban an identity that changes world mid-session |

**What the world checks are worth.** Everything a client says about its own world is a claim
rather than evidence. A modified client says whatever it likes, and signing it would only
prove who said it. They're worth having for the casual case, somebody who turned on cheats
once and thought nothing of it, and for making two claims contradict each other. They aren't
security.

### Rules a group can agree once

Because host rules travel with the host, a market is only as protected as its most permissive
member. The creator can **publish** a set of defaults into the history with `/trade hostrules
publish`, and any host adopts them for anything it hasn't set itself.

Published rules are advisory: recorded, read by hosts, enforced by nothing. `/trade
hostrules` shows what was agreed and what this host does with it.

---

## 11. Command reference

Every command is client-side and reads your own copy. None of them talk to the Minecraft
server you're on.

| Command | What it does |
| --- | --- |
| `/trade balance` | Your credits and holdings |
| `/trade orders` | Your resting orders |
| `/trade price <item>` | The book for one item |
| `/trade hostconfig` | The rules this world hosts under |
| `/trade hostconfig write` | Create `host-config.json` with every setting in it |
| `/trade hostrules` | The rules this market's group agreed once |
| `/trade hostrules publish` | Publish them, creator only |
| `/trade archive` | Whether this copy keeps the whole history |
| `/trade archive on` / `off` | Change that |
| `/trade panel` | The inventory listings panel |
| `/trade panel on` / `off` | Show or hide it |
| `/trade panel rows <1-15>` | How many listings it shows |

**Trading deliberately isn't here.** Buying, selling and cancelling stay on the screen, and
resetting or migrating stay behind its confirmations. The rule the codebase applies: a
command may write when getting it wrong can't cost anybody their items, their credits, or
their market.

---

# Part 2: how it works

## 12. The shape of the thing

Three ideas, and everything else follows from them.

**1. The market is a list of events, not a database of balances.** Nothing anywhere
stores "Alice has 400 credits". What exists is an append-only log. *Alice was granted
1000*, *Alice listed 20 iron at 5*, *Bob's bid crossed it*, and every replica walks that
list and works the balance out. Two replicas holding the same list always agree, because
they ran the same arithmetic over the same input.

**2. The log is signed and chained.** Every event carries a signature from the identity
that authored it, and every entry carries the hash of the one before. You cannot insert,
reorder, or edit an event without breaking the chain, and you cannot author one as
somebody else without their key.

**3. A host orders events, and does nothing else.** When you place an order, your client
*proposes* it; the host assigns it the next sequence number, appends it, and broadcasts
it to everyone. That is the whole of the host's power. It cannot invent a balance because
balances are never sent, and every client computes its own from the same events.

```
        propose                      append + broadcast
client ────────────► host ─────────────────────────────► every client
                      │                                       │
                      │ assigns seq, chains hash              │ applies in order,
                      ▼                                       ▼ computes state itself
                 the log file                            identical state
```

### The packages

| Package | What lives there |
| --- | --- |
| `core` | The engine: log, events, state, book, money rules. No Minecraft imports at all. |
| `core.net` | Sockets and protocol: `HostServer`, `MarketClient`, `Message`. |
| `client` | Everything Minecraft: screens, the inventory bridge, notifications, commands. |
| `mixin` | One accessor, to read where vanilla drew its inventory panel. |

`core` importing no Minecraft is why the whole engine can be tested on a bare JVM in
milliseconds, and the suites in section 26 never launch the game.

---

## 13. The event log

One JSON object per line, appended, never rewritten. `EventLog` owns the file.

```jsonc
{ "seq": 42,
  "prevHash": "9f3c…",           // the hash of entry 41
  "hash": "1a77…",               // sha-256 over this entry's canonical form
  "eventType": "PlaceOrder",
  "signature": "MEUCIQ…",        // by the author's key, over the canonical payload
  "event": { "userId": "…", "marketId": "…", "itemId": "minecraft:iron_ingot",
             "price": 5, "volume": 20, "isBid": false } }
```

### Appending

```
append(event, signature):
    seq      = lastSeq + 1
    prevHash = lastHash
    hash     = sha256(canonical(seq, prevHash, eventType, event, signature))
    write one line
    lastSeq, lastHash = seq, hash
```

The signature is inside the hash, so the chain binds *authorship* as well as order. That
is what lets a log be handed to somebody who was never present and still be checked.

### Three fields that carry more weight than they look

**`marketId` on every event.** Without it, a signed event lifted out of one market's log
verifies perfectly in another, and replaying somebody's real deposits into a different market
would be indistinguishable from them making those deposits there. Every event names the
market it was authored for, and applying it anywhere else is refused.

**`seq 1` must be a `MarketCreated`.** Every market has an identity from its first line,
so "a different market" stops being indistinguishable from "a diverged history".

**`clientEventId`.** A client-chosen id on the events that move real items, so a proposal
that was sent twice, or sent and then recovered after a crash, can be recognised rather
than applied twice.

### Reading it

Three ways, and choosing wrong is a performance bug that only shows up on big markets:

| Call | Cost | Use when |
| --- | --- | --- |
| `forEach(from, visitor)` | One entry in memory at a time; stops early if the visitor says so | Almost always |
| `forEachAfter(seq, visitor)` | Skips earlier lines **without parsing them** | Continuing from a snapshot |
| `readFrom(seq)` | Materialises the whole log into a list, about a kilobyte per event | Only when you genuinely need every event at once |

A 100,000-event market is roughly 57 MB on disk and around 100 MB as a list, so
`readFrom` on a long log was a several-hundred-megabyte allocation inside a Minecraft
client. Most of the compaction work was replacing those calls.

`forEach` **stops at the first line it cannot parse** rather than throwing, because a log
from another version is a damaged log and not a crash. Throwing took the whole world down
at startup, which left no way to reach the Reset button that would have fixed it.

---

## 14. Events, and what each one means

Twelve kinds. Everything the market can do is one of these.

| Event | Who authors it | What it does |
| --- | --- | --- |
| `MarketCreated` | The creator | Genesis. Names the market, registers the creator's key. Legal only at seq 1. |
| `KeyRegistered` | The player | Binds a userId to a public key, signed by that key, so it is self-certifying. |
| `WelcomeGrant` | The host | Opening balance, once per identity per market. |
| `MarketPolicy` | The creator only | Tax, grant, listing fee, free allowance, stipend. |
| `Stipend` | Any registered player | Claims the periodic payout. |
| `MigrateBalance` | The host | Credits somebody what they held in a market they left. |
| `HostDefaults` | The creator only | Publishes host rules as defaults. Enforced by nothing. |
| `Deposit` | The player | Items leave a real inventory and enter the ledger. |
| `Withdraw` | The player | The reverse. |
| `PlaceOrder` | The player | A bid or an ask. |
| `CancelOrder` | The player | Takes one back off the book. |
| `DepositAndList` | The player | Deposit and list in one event, so the pair cannot half-happen. |

Two are worth dwelling on.

**`MarketPolicy` is the whole policy, not a patch.** Anything it does not restate is set
to zero. This nearly wiped a welcome grant once and did silently wipe a stipend the moment
those fields were added, so the submit path now builds a policy from current state and
hands it to the caller to modify, so forgetting a field keeps its value instead of clearing
it.

**`DepositAndList` exists because two events could half-happen.** Depositing and then
listing is two proposals, and the second can be refused after the first has landed.

---

## 15. validate and apply

**The single most important rule in the codebase.** `EventApplier` is the only way
`MarketState` is ever mutated, and it has two entry points:

```
validate(state, event)  →  may this happen?      (no mutation, no side effects)
apply(state, event)     →  make it so.           (assumes it may)
```

Every money rule, every permission check, every ceiling lives in `validate`. `apply`
enforces almost nothing.

That split is what makes a host work: the host asks `validate` **before** appending, so a
refused event never enters the log at all. Every replica then applies what it receives,
knowing the host already asked.

Three real defects came from forgetting this, which is why it is stated so loudly:

- **A history import called `apply` without `validate`**, on the one path documented as
  treating a log as a file from a stranger. A hand-built log could carry a welcome grant
  for any sum, repeated as often as you like. Measured before the fix: a replayed balance
  of 3,000,999,997 against a published grant of 10.
- **A sell you could not afford duplicated the items**, because one half of the check
  lived in `validate` and the fee lived in `apply`: validate passed, the event was
  appended, and then apply refused it.
- **A test asked `apply` whether events were refused**, so three refusals passed that were
  never refused. The rules live in `validate`.

If you add a rule, it goes in `validate`. If you write a test for a rule, it asks
`validate`.

---

## 16. The order book

One `OrderBook` per item. Two `TreeMap`s keyed by price, one for asks and one for bids,
each price mapping to a queue of the orders at it. Price-time priority falls straight out
of that shape: the best ask is the map's **first** entry and the best bid is its **last**,
and within a price level the oldest order is the head of the queue.

```
submit(order):
    while the other side has a level that crosses our price, and we still have volume:
        best = a bid takes asks.firstEntry()  (lowest ask)
               an ask takes bids.lastEntry()  (highest bid)
        stop unless best crosses our price
        for each resting order at that level, oldest first:
            traded = min(our remaining, theirs)
            record a Fill at THEIR price           # the resting order sets the price
            reduce both
            drop the resting order when it hits zero
        drop the price level when its queue empties
    if any volume is left, rest it at our price
```

Two consequences worth naming:

**The taker gets the maker's price.** Bidding 6 against a resting ask of 5 pays 5. Coming
late does not cost you, and the spread belongs to whoever was patient.

**One order produces many fills.** A sell of 10 into ten resting bids of 1 is ten fills
from a single event. That matters beyond arithmetic: fills are what the stipend interval
counts, and it is why nine rows can leave the screen in one frame.

Every public method is synchronized on the book, because the sequencer or network thread
matches into it while the render thread walks it to draw, and building a list from a live
deque mid-cross is a `ConcurrentModificationException`.

---

## 17. The money rules

All in `validate`, all in `MarketState`, all integer arithmetic. **No floats anywhere near
a price**: Java without `strictfp` is not reproducible across platforms, and two replicas
that disagree in the last bit have forked.

### Where credits come from and go

| | |
| --- | --- |
| **In** | Welcome grants; stipend claims; migrations from another market |
| **Out** | The trading fee, which is *burned*: taken from the seller's proceeds and destroyed |
| **Neither** | A trade itself. Credits move sideways between two players. |

### The ceilings

| Rule | Value |
| --- | --- |
| Welcome grant, compiled maximum | 1,000,000 |
| Welcome grant, hosting from a game | 10,000 |
| Listing fee | at most 1,000 |
| Free orders before escalation | at most 1,000 |
| Stipend amount | at most 100,000 |
| Stipend interval | 50 fills by default |
| Trading fee | at most 5,000 basis points (50%) |

Basis points rather than a percentage, because a rate that changes settlement has to be an
integer for every replica to reach the same balance.

### The stipend interlock

```
stipendOutpacesItsFees(amount, everyFills, listingFee, claimants):
    if amount <= 0:      no stipend, nothing to check
    if listingFee <= 0:  refuse, since fills would cost nothing to produce
    collected = listingFee × everyFills          # what the market takes in per interval
    paid      = amount × max(1, claimants)       # what it pays out per interval
    refuse if paid >= collected
```

It was wrong twice before it was right, and both mistakes are instructive:

- **A fill was costed at two listing fees.** One order sweeping a stacked book produces a
  fill per resting order it consumes, so twenty fills for twenty-one fees. The floor is one
  fee per fill.
- **It counted one claimant.** Every registered identity claims per interval, so the
  payout multiplies by the head count while the fees do not.

It is asked in three places rather than reimplemented in them: when policy is set, at every
claim, and by the screen that offers the control.

---

## 18. The network

Newline-delimited JSON over a plain socket. `MessageChannel` frames it; `Message` holds the
shapes.

| Message | Direction | Purpose |
| --- | --- | --- |
| `Hello` | client → host | Identity, key, where my history ends, which market I hold, what my world looks like |
| `Sync` | host → client | The history you are missing, chunked; plus who the host is and known peers |
| `Propose` | client → host | Please sequence this event |
| `Accepted` | host → all | It is now event N; here is the line |
| `Rejected` | host → client | It is not, and why |
| `CatchUp` / `CatchUpResult` | either | Events one side has and the other does not |
| `HashQuery` / `HashReply` | client → host | "What hash do you have at sequence N?" |
| `Query` / `QueryReply` | either | Discovery: are you hosting, which market, how far along |
| `MigrateRequest` / `MigrateResult` | client → host | Bring my position from another market |
| `Attest` | client → host | What this client says about its world |
| `Error` | either | Something structural |

### The handshake

```
client                                   host
  │ Hello(userId, key, lastSeq, lastHash, marketId, attestation)
  ├────────────────────────────────────────────►
  │                                        check admission, market identity,
  │                                        whether our chains agree at lastSeq
  │ Sync(logLines…, complete=false)             ◄── chunked if long
  │ Sync(logLines…, complete=true)
  ◄────────────────────────────────────────────┤
  │ verify every line, apply in order, then live
```

Refusals happen here rather than later: a different market, a diverged chain, an identity
that is not admitted, a world the host declines.

**Chunking exists because a fresh joiner pulls the entire market**, which outgrows a single
frame long before anything else does. Only the first chunk carries the host identity and
peer list, and the rest are log lines. Both ends stream: the host sends without building the
whole history in memory, and the client applies frame by frame rather than gathering it
first.

### Sequencing

The host runs one queue and one thread:

```
loop:
    take the next proposal
    verify its signature against the registered key
    ask host rules (admission, deposit caps, attestation, migration limits)
    ask EventApplier.validate
    if refused → Rejected(reason)
    else       → append to the log, apply locally, broadcast Accepted to everyone
```

One thread is the whole ordering guarantee: sequence numbers are assigned in one place, so
there is no path by which two events get the same number.

### Gaps

Broadcasts can be missed. A client applying an `Accepted` checks the sequence number it
expected against the one it received, and on a mismatch asks for what it missed rather than
applying an event on top of a hole, since the state would be silently wrong from then on.

---

## 19. Divergence: noticing and locating a fork

A fork is two copies of one market that were both written to while apart. The chain hashes
make it *detectable*; the work is in noticing early and saying something useful.

### Three ways it is noticed

**On connect.** `Hello` carries `lastSeq` and `lastHash`. The host compares the hash it
holds at that sequence number. The same means you are simply behind; different means you
have forked.

**On a poll.** Discovery asks peers what they are hosting and how far along. A peer *ahead*
of us cannot be compared by height alone, since being at 300 says nothing about whether it
is our 300. So the poll asks `HashQuery` for their hash **at our head**. Matching means they
extend us and the height is real; not matching means a fork, and their height is not ours
to record.

**Against the high-water mark**, which is the case with nobody online: see below.

### Locating where you parted

Not a binary search but a **bracketed multi-probe search**, because a round trip costs far
more than a hash comparison and one message can carry many sequence numbers.

```
splitPoint():
    agreed   = 0        # highest seq we have confirmed identical
    disputed = -1       # lowest seq we have confirmed different

    for at most 8 rounds:
        upper  = disputed > 0 ? disputed - 1 : our head
        stop if upper <= agreed
        probes = up to 24 seqs spread through (agreed, upper], always including upper
        send ONE HashQuery carrying all of them

        never probe above the host's own head again, since beyond it there is
        nothing to disagree with, only nothing at all

        for each answer:
            matches ours   → raise  agreed   if higher
            differs        → lower  disputed if lower
        stop if nothing usable came back        # rather than spin
        return agreed if disputed == agreed + 1 # they are adjacent: that is the split

    return agreed       # ran out of rounds; a floor, not the answer, and better than nothing
```

Twenty-four probes a round and eight rounds settles a market far larger than any that
exists, usually in one or two round trips. `upper` is always among the probes because the
common shape by far is a client that simply extends or trails a host, where the
disagreement is at the top and a single probe settles it.

Two details worth copying if you write anything similar: it **never probes above the host's
head**, since "nothing there" is not disagreement; and when it runs out of rounds it returns
`agreed`, a point actually checked and matched, as a floor rather than reporting failure.
Erring early is the safe direction: it under-reports what the fork cost, and under-reporting
cannot hand back an order somebody else still holds.

That number is what lets the reset dialog say *"you parted from the other copy after event
42"*, and what lets it work out which orders and deposits are yours alone.

### The high-water mark

Discovery only finds hosts running *right now*, and the expensive case is the opposite:
somebody returns after a week, nobody is online, and they host a copy that is hundreds of
events behind. Everyone else is then refused, and if that host trades at all the market
genuinely forks.

So the furthest the market has ever been seen to reach is written down, **with who reported
it**. Provenance matters: a bare number could not be withdrawn, and a peer that truthfully
reported being ahead may itself fork away four seconds later. Their later word replaces
their earlier one, in either direction.

---

## 20. Loading a market quickly

Opening a world means turning the log into state. On a long market that was the slowest
thing the mod did, and the fix is worth reading as a method rather than as a result.

**It was measured before it was designed.** A 100,000-event load parsed the file **four
times**: the `EventLog` constructor finding its last entry, `verifyChain`, `damageReason`
verifying again, and the replay. The profile: 45% hex formatting, 44% repeated parsing, and
**1% rebuilding the state**, which is the only part a snapshot removes.

So the cheap fixes came first, and the snapshot after:

```
  before                4208 ms
  after the cheap fixes 1135 ms      # one hash function, one streaming pass
  with a snapshot        190 ms
```

### MarketSnapshot

The state, written beside the log as JSON, keyed to the chain hash at the sequence number
it was taken at. A load restores it and replays only what came after.

```
load(log):
    snap = the snapshot beside the log, if it still describes this log
    if none:  verify and replay the whole file in one pass
    else:     restore state, then forEachAfter(snap.seq), and earlier lines are
              stepped over as bytes, never parsed
    tell the log where it ends (the walk just found out)
    write a new snapshot if the log has run far enough past the last one
```

**Why skipping the prefix is safe.** The snapshot names the chain hash at its own sequence
number and is used only if the log still carries that hash there. Rewrite history and
re-chain it: every hash from the edit onward changes, the snapshot is discarded, the log is
replayed in full. Edit one entry *without* re-chaining: the hash at our sequence number is
unchanged, so the snapshot is used, and that is the right answer, because the state in it
was computed before the edit, from the log as it was when it verified. The tampered entry
is never read.

**The shape fingerprint.** `MarketState` gains fields, thirteen on one day and twenty-one a
week later, and a serialiser that was complete last month quietly stops being complete. So
the snapshot records a fingerprint reflected over `MarketState`'s declared fields, and one
written against a different shape is discarded unread. Add a field, and every existing
snapshot becomes a slow load, which is the failure everybody can afford.

**Snapshot-only replicas.** A client of a dedicated server keeps the snapshot and no log.
That is legitimate, since the history is on the server, but it means "I have state" and "I have
the history behind it" are different questions. `logCoversHead` answers the second, and is
false only for that case. It is what stops a replica with no history offering to host one.

---

## 21. The client

### MarketStateHolder: one market, three modes

Everything Minecraft-side goes through it. It owns the active market for the current world
and is in exactly one of:

| Mode | Meaning |
| --- | --- |
| `LOCAL` | Reading a log on disk; nothing is being served or received |
| `CONNECTED` | A `MarketClient` is applying somebody else's broadcasts |
| `HOSTING` | A `HostServer` is running in this process |

**State changes arrive through one path in every mode.** Local replay, a synced history and
a live broadcast all funnel through the same applied hook, because the moment `LOCAL` and
`CONNECTED` have separate paths they drift, and a drift between them is a fork with extra
steps.

A host also connects to *itself* as a client, so hosting and playing use the same code path
as everybody else rather than a privileged one.

### The screen

`MarketScreen` is one 4,700-line class and the file most defects have lived in: five in a
single review, three the session before. Splitting it is the open piece of work, and the
rule for whoever does it is **split by component, never by layer**: separating render from
hit-test is how you get a control that is invisible and still clickable, which has already
happened twice.

Two things to know before changing it:

- **`requireConnected()` is where trading is gated**, on every action, and it tests the live
  socket rather than the mode. There is no guard inside `submit()`.
- **Long refusal messages get trimmed.** The footer draws one line at roughly 50–70
  characters, and a longer message loses its second half, which is usually the part that
  says what to do. Short line on screen, detail to the console.

### The inventory panel

Reads the **book**, not the activity feed. The feed is the last 64 events of any kind, so a
filtered view of it can be empty while the market is busy, and can show an order cancelled
an hour ago. The book is what is for sale now.

Reading the book also answers "which events make a listing" for free, since `PlaceOrder`
and `DepositAndList` both put an order there, and it gives "most recent" for nothing, as an
order's id **is** the sequence number of the event that created it.

---

## 22. Items and the inventory

The ledger and a Minecraft inventory are two systems, and nothing spans both
transactionally. Every deposit and withdraw therefore has a window where one has happened
and the other has not, and a crash inside it is invisible afterwards. The items are simply
gone, or simply never arrived, with nothing anywhere recording that anything was owed.

`PendingOps` is that record: written before the risky half, cleared after it. Anything still
there at startup is a window somebody fell into, and the two directions recover
differently:

**Deposit.** Items leave the inventory first, then the event is proposed. The log settles
it exactly: if an event with this `clientEventId` is in there, the deposit landed and the
ledger already holds the value; if it is not, it never will, and the items are safely
returned.

**Withdraw.** The event is applied first, then the items are handed over. Nothing records
whether the hand-over completed, so these are **deliberately not re-given**. Doing it again
would mint items whenever the interruption landed after the give rather than before it. The
player is told instead.

That asymmetry, settling what can be settled and refusing to guess at what cannot, is the whole
design.

---

## 23. Migration, archives and slots

**Migration** moves what you hold from one market to another. The destination host issues a
`MigrateBalance` crediting your position there, and the market you left is abandoned rather
than merged. It is capped by the receiving host (`maxMigratedCredits`), can be refused
outright (`acceptsMigration`), and is weighed against the same deposit rules as anything
else. A migration that skipped them was a real hole, since every deposit rule hung off a
path a migration never took.

**Archives** move a *history* rather than a position: export the log to a file, import it
elsewhere. It solves bootstrapping, because otherwise joining an existing market requires
somebody who holds it to be online at the same moment as you, and a group that fails to
arrange that will, with nobody making a mistake, start a second market that can never be
merged with the first.

An imported log is trusted for nothing: **every signature is verified and every rule
re-checked**. The hash chain alone proves nothing here, because anyone can author a
perfectly self-consistent chain of invented events. Only the signatures separate a real
history from a fabricated one.

**Slots** are several markets in one world, one active at a time, each with its own
directory and log. The default slot stays exactly where single-market worlds already keep
it, so nothing existing had to move.

---

## 24. The trust model

**What a host can do:** decide the order of events, refuse to sequence one, refuse a
connection, and stop.

**What a host cannot do:** change anybody's balance, author an event as somebody else, alter
or delete history, or hand out a state anybody believes. Balances are never transmitted,
only events, and every replica computes its own.

**What a client cannot do:** author events as somebody else, replay old events (the sequence
and the chain both refuse), lift an event from another market (`marketId` is signed), or
exceed the money rules, which are checked before anything is appended.

**What nothing here protects against, stated so nobody assumes otherwise:**

- **A client lying about its own world.** Attestation is a claim, not evidence. It catches
  the casual case and nothing else.
- **Somebody running two accounts.** Nothing here is a proof of personhood; the stipend
  interlock limits the damage rather than the behaviour.
- **A host that stops.** Availability is not a property this design provides. Whoever holds
  the history can host next, and that is the answer.
- **A fork, once both sides have written.** It can be detected, located, and reset away
  from. It cannot be merged, and three schemes for merging it were considered and refused.
  see the design notes.

---

## 25. Rules this codebase keeps rediscovering

Read these before changing something load-bearing. Each was paid for.

**Two things that must agree, kept in two places.** The recurring defect, by a wide margin.
Render and hit-test. The rule that counts deposits, and the window that validates them. The
screen's copy of a formula, and the engine's. Every fix has been the same move: collapse it
to one place and have both callers ask.

**Before changing an invariant, grep every reader of it.** One session changed "the log is
the complete record of this replica" and then went looking for problems in the *new* code.
Eleven defects followed, every one of them in an old reader of the old assumption,
including a gate that answered "yes" to the single case it existed to refuse.

**A guard that makes failure safe also makes it silent.** And its mirror: a mechanism
invisible when it works cannot be watched working. Three separate features read as broken
while working correctly, and each cost more time than a real defect would have.

**A check that cannot fail is worse than no check, because it is counted.** Every fix is
verified to *fail with the fix disabled* before it is trusted. That has caught a check
against a default field that was null anyway, a ceiling compared as a substring, a test
whose client never set the guard being tested, and a check that asserted the opposite of its
own label and passed.

**Measure before designing.** The compaction plan was written against an unmeasured cost
model and changed substantially once profiled: the thing it was written to fix turned out
to be 1% of the cost. And numbers rot: a headline measurement in the design notes was wrong
by 4× four days after it was taken, because a later change added a full pass over the file
that nobody re-measured.

**A note asking somebody to remember is not a mechanism.** `EventCanonical` opens with
*"if you add a field to an event type, add it here too, since anything omitted is unsigned and
therefore tamperable in transit."* Correct, prominent, and insufficient: a whole event
type was added and never appeared in the file, so its eight fields travelled unsigned. The
warning was right about the failure and could not prevent it. It is backed by two
mechanisms now. The chain throws on a type it does not know, and a reflection check walks
every declared field of every event subclass and fails if changing one does not change the
signed payload.

**A name is not an identity.** Two markets can share a name and share nothing else.

---

## 26. Tests

Nine suites, no Minecraft, run in seconds:

```
./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest \
    depositCapTest attestationTest hostTrustTest splitPointTest
```

| Suite | What it covers |
| --- | --- |
| `coreTests` | The engine: log, chain, events, book, money rules, policy, snapshots, branch arithmetic |
| `chunkTest` | A history too big for one frame still transfers |
| `replayGuardTest` | Replayed history is distinguishable from live traffic |
| `gapRecoveryTest` | A client that misses a broadcast notices, and stays connected while it recovers |
| `admissionTest` | Admission holds on every door, not just the front one |
| `depositCapTest` | A deposit cap holds against a real client over a real socket |
| `attestationTest` | The attestation reaches the host and is acted on |
| `hostTrustTest` | A host is trusted to order events and for nothing else |
| `splitPointTest` | Finding where two branches parted |

CI runs all nine on every push.

**What no test does:** launch Minecraft, or measure speed. Every screen defect in this
project's history was found by a person looking at a screen: four geometry bugs in one
review, and eleven defects across five play sessions that seven hundred automated checks did
not see. The live checklist is `docs/testing/group-e.md`, and it is not optional.

---

## 27. Where to read more

| File | What is in it |
| --- | --- |
| `docs/design/log-compaction.md` | Loading and snapshots, measured; the options that were refused |
| `docs/design/fork-rebase.md` | Why a forked branch is not rebased, and what a reset gives back instead |
| `docs/BACKLOG.md` | Everything deliberately not built, in the order worth doing, with what it costs to keep not doing it |
| `docs/SESSION-LOG.md` | The current state of the project, and the defects behind most of the rules in section 25 |
| `docs/testing/group-e.md` | The live checklist, the only thing standing between a green CI tick and a claim that this works |

The javadoc in `core` is not decoration. Most classes there open with why they exist and
what was tried first; `MarketSnapshot`, `WorldAttestation`, `PendingOps`, `MarketSlots` and
`MarketHighWater` in particular carry arguments that are not repeated anywhere else.

---

## 28. Running a server, and how the jar is put together

[§6](#6-playing-together) is the walkthrough. This is what is underneath it, for whoever
has to keep one alive or change how it ships.

### Why a separate jar exists at all

The mod jar cannot run a server. Minecraft supplies gson at runtime, so a bare JVM loading
it dies on `NoClassDefFoundError: com/google/gson/JsonElement` before printing a line,
and the failure names a missing class rather than a missing dependency, which tells an
operator nothing.

`serverJar` builds a self-contained one instead:

```groovy
from(sourceSets.main.output) { include 'io/github/badbull643/economiesmod/core/**' }
from configurations.runtimeClasspath.filter { it.name.startsWith('gson') }
        .collect { zipTree(it) }
manifest { attributes 'Main-Class': '…core.net.HostServer' }
```

Three decisions in nine lines:

**Only `core`.** That package imports no Minecraft, the same property that lets the test
suites run on a bare JVM in milliseconds, so it needs nothing else to run. `client` and
`mixin` would drag the game in behind them, and there is nothing in either that a server
uses.

**Gson unpacked rather than nested.** `java -jar` reads no classpath from inside a jar, so
a nested dependency is invisible. It has to be flattened in beside the classes that call
it.

**A `Main-Class` manifest**, so the command is `java -jar` and not a classpath incantation.

The result is about 600 KB, and roughly half of it is gson.

### What a server does not know

Nothing in that jar understands Minecraft. Item ids are opaque strings. `minecraft:iron_ingot`
is matched, counted and priced without the server having any idea what an iron ingot is,
and a modded item id works exactly as well. That is why the ledger can be an engine on its
own, and it is worth remembering before writing anything server-side that wants to reason
about items.

### Updating one

The jar is disposable; the log is the state. Stop the process, replace the jar, start it
again. Nothing in the market is stored anywhere but `market.jsonl`, and the config is read
fresh on every start.

Two version cautions, both of which fail loudly rather than quietly:

- **A snapshot written by a different build is discarded**, because `MarketState`'s shape
  fingerprint changed. The console says so and the market replays in full: one slow start,
  then it is gone.
- **An event type an older build does not know stops it reading the log at that line.** A
  server downgraded past a feature that has already been used will report a damaged log
  rather than skip events it cannot understand, which is the right way round.

### Keeping it up

It runs in the foreground and logs to stdout, so it wants whatever the host uses for that:
`systemd`, `screen`, `nohup`. There is **no shutdown hook**: the process is meant to be
stoppable at any moment, and it is safe to stop at any moment, because durability comes
from how each event is written rather than from a clean exit.

Every append opens the file, writes one line, and closes it. There is no in-memory buffer
holding events that a stop could lose. An event that has been acknowledged to a client is
already on disk.

### Backups

`market.jsonl` is the only irreplaceable file. `server-identity.key` is worth keeping too.
Losing it means the server becomes a new identity, and if it created the market it also
loses the ability to change that market's policy. It can be regenerated if you accept
that. `server-peers.json` and `known-keys.json` rebuild themselves.

The log is append-only, so copying it from a running server is safe in the way that
matters: the copy is a prefix of the real thing. The worst case is a torn final line, and
a torn line is refused rather than half-applied, so you lose the last event and never the
integrity of the ones before it.

**What no backup gives you:** a market restored from an old copy is *behind*, and if
anybody kept playing on the live one, restoring it forks the market. The high-water mark
exists to notice exactly that, but it can only warn. See [§19](#19-divergence-noticing-and-locating-a-fork).
