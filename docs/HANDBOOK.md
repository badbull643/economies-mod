# EconomiesMod handbook

*What the mod does, how to use it*

Two readers, one file. Part 1 is for somebody who just installed it and wants to trade with
three friends. Part 2 is for somebody about to change the code. Neither needs the other.

Where a design note or the session log argues something at length, this file says what was
decided and points at the argument instead of repeating it.

---

## Contents

**Using the mod: **

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
12. [Running a server](#12-running-a-server)

---
---

# Part 1: using it

## 1. What this actually is

A market you and others can run yourselves, inside Minecraft. You put items in, list them
at a price, and somebody else buys them with credits.

A market can be run two ways either use a dedicated server as the central 
authority on the market and connect too that server every time or use the host option in game, 
where every person has a personal copy of the market information and the person hosting can change 

## Notes on the changing hosts setup 

**Nobody is in charge of the numbers.** Every player's game keeps its own copy of
everything that ever happened in the market and works out the balances itself. Whoever is
hosting decides what order things happen in, and that's all they decide. They can't give
themselves credits or delete a trade, because everyone else would compute a different
answer and spot it immediately.

**It survives the host logging off.** The history is a file. Anyone who has it can host
next, and the market carries on. There's also a dedicated server for a group that would
rather one machine stayed up.

everyone needs a copy of the history, trading only happens while somebody is hosting
and at least one other person is connected, and two copies that
both get traded on while apart will *fork*. [§9](#9-when-something-looks-wrong) is mostly
about that last one.

---

## 2. Getting in

**The inventory button.** Open your inventory. There's an emerald next to the recipe book
button. That's the market screen.

**A key.** Nothing is bound by default, bind one in **Options → Controls → EconomiesMod**.

**Chat commands.** `/trade balance`, `/trade orders` and `/trade price <item>` read the
market without opening anything. Full list in [§11](#11-command-reference).

### The screen

Five tabs across the top.

| Tab | What's there |
| --- | --- |
| **Home** | Your balance, recent activity, a price chart, and any warnings |
| **Trading** | Buy, sell, cancel, withdraw |
| **Network** | Connect to somebody, host, see who's around, migrate |
| **Market** | The market itself: create, import, export, and its rules if you made it |
| **Settings** | Placeholder for now (will be removed and settings fully controllable via in-game commands)|

### The inventory panel

Next to your inventory is a panel listing the most recent things **other people (so not your orders)**
have put up for sale, under the specific market you are on the market's name shows at the top of the tab. 
Click the header to swap between `Sell orders` and `Buy orders`.

Your own orders are never in it, since you already know what you listed. If it says *"only
your 4 orders"*, the panel is working and the book just has nothing but your orders in it.

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

If they're on the same machine or the same network or using something like Zerotier, **Refresh hosts** finds them without
typing anything (you must have joined each other once prior for this too work).

You can't join while holding a market of your own in that world. They're separate economies
and the mod will give a warning. Use a second [slot](#8-more-than-one-market) for theirs.

### Getting the history when nobody's online

**Market tab → Export to a file** hands you the whole market as a file. Somebody else uses
**Import one from a file** and has it, with no host running and nobody online at the same
time.

An imported file is treated as a file from a stranger. Every signature in it is checked and
every rule re-applied before a single credit of it is believed.

### Your starting money

The first time you appear in a market, the host gives you a **welcome grant**. It happens
once per person per market, it's recorded in the history like everything else, and the
creator sets the amount (this welcome grant feature may be subject to change).

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
and the book empties. The mod tells you afterwards: *"Sold 9 of 10 Dirt
across 9 orders, +18, 1 still resting" (the notification doesn't currently show will be fixed soon)*.

Anything that doesn't fill immediately **rests** until it does. A partly filled order leaves
the remainder on the book.

### Knowing when something traded

Chat tells you when one of your resting orders fills, which is the one you weren't watching
for. The action bar can do the same, more briefly. Both are settings.

Filling somebody else's order is reported too.

---

## 5. What it costs to trade

these numbers are adjustable via the market creator in-game for the rotating mode and adjustable 
via the server config for the dedicated server.

**Welcome grant.** What a new arrival starts with. Capped at 300 (may change this too be much lower 
too kill variance in welcome grants) when somebody hosts from their game. A dedicated server may go higher.

**Trading fee.** A percentage of each trade, taken from the seller's proceeds and destroyed
rather than paid to anyone. It drains the money supply instead of being income for a host.

**Listing fee.** Charged when you place an order and never refunded, including if you cancel.
It's there so placing orders isn't free, because a market where it is free can be spun in
circles for profit.

The listing fee can also **escalate**: a market can give everyone a few free orders and
charge more the more you hold open at once. Set to zero, nothing escalates and every listing
costs the same.

**Stipend.** See [below](#the-stipend).

### The stipend

Optional, and can be disabled by the market creator.

The welcome grant used to be the only way credits ever entered a market. That means the money
supply grows when *people* arrive and never again, while goods keep piling in. Prices sink
until everything costs 1 and price stops meaning anything.

The stipend attempts too fix that so any registered player can **claim** a fixed amount once per *N fills
the market settles*, 50 by default (can be changed). Click **Claim stipend** on the Market tab.

**It counts fills, not time or events.**. A fill needs two orders to cross, and every order costs a listing fee (if the host has set a listing fee).

How much you get is up to your market's creator check the Market tab, or `/trade hostconfig`, for the
current amount. What it can be set to is tied to the **listing fee**, the market only
allows a stipend that the fees collected over one interval can actually cover, so a low listing
fee (or none at all) means a small stipend, or none.

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

A standalone process, keeping a market up whether or not
anybody is playing. Hosting from your game suits a smaller group. A server suits a group that
would rather one machine was always there.

#### What the operator needs

**One jar and a Java 8 runtime.** `economies-server.jar` is the jar you need

Keeping one running, updating it and what to back up are in
[§12](#12-running-a-server).

#### Setting one up

**1. Give the jar its own folder.** The server writes several files beside wherever it runs
from (see the table below), including a private key.

Run this from wherever `economies-server.jar` currently is. It makes a new folder and copies the jar in:

```
mkdir ~/economies-server && cp economies-server.jar ~/economies-server/ && cd ~/economies-server
```

**2. Write a config.** Nothing exists yet, so this creates one with every setting in it.

```
java -jar economies-server.jar --config server.json --write-config
```

**3. Edit it.** Three settings matter before the first start.

```jsonc
"logFile": "market.jsonl",             // beside the jar, 
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
the Market screen. 

Two things worth knowing:

- **Anything you leave out is left alone.** A block mentioning only the tax won't touch the
  grant or the fee, which is why the fields are absent rather than zero when unset.
- **A policy the market would refuse is reported and skipped**, like a stipend that outruns
  its listing fees or a grant above the host's ceiling. The market keeps running on what it
  had.

**The file can end up lying about what's really happening**, an edit can be refused (the two
cases just above), or you might be hosting a market you didn't create, where the whole
`policy` block is ignored. Either way the file still shows whatever you last typed, not what
the market actually has.

**Running `--write-config` again fixes that.** it asks the
live market what its policy actually is right now and overwrites the file with that. So if
you're ever unsure whether an edit took effect, run `--write-config` again and read the file:
whatever it says afterward should be correct.

#### Who owns the market

By default the server does, and the operator changes policy through the config.

**`--creator-key` names a player as creator instead**, so the rules get changed from the
Market screen in game. could be worth it when a person should own the market and the box is only
hardware. Otherwise the config is simpler and the key is one more thing to keep somewhere.
It's used on the first start only, needs `creatorUserId` in the config, and can't be changed
afterwards, since the creator is recorded at genesis and nothing moves it (wouldnt).

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

### Rotating host defaults

Hosting from your game (Network tab → Host) uses the settings below until you write your own
`host-config.json` with `/trade hostconfig write`. Two of them resolve differently on a
dedicated server, marked below, because the two deployments are different in kind: a friend's
game is a group that already trusts each other, a dedicated server is more often facing a
stranger.

| Setting | Rotating default | Dedicated default | What it means |
| --- | --- | --- | --- |
| `admission` | `open` | `open` | Anyone may connect |
| `maxDepositUnitsPerWindow` | `0` (off) | `0` (off) | No cap on deposits |
| `requireAttestation` | `false` | `false` | A client that describes nothing about its world still gets in |
| `refuseCreativeWorlds` | `true` | `true` | A world reporting creative mode is refused |
| `refuseCheatWorlds` | `true` | `true` | A world reporting commands or cheats enabled is refused |
| `maxDepositUnitsPerPlayHour` | `0` (off) | `0` (off) | No playtime-based deposit weighting |
| `maxDepositMultipleOfHandled` | `0` (off) | `0` (off) | No statistics-based deposit weighting |
| `maxMigratedCredits` | `0` (unlimited) | `0` (unlimited) | Any migrated balance is accepted |
| `acceptsMigration` | **true** | **false** | Whether an incoming migration is accepted at all |
| `maxWelcomeGrant` | **300** | **1,000,000** | The most a policy change may grant a newcomer |
| `banOnWorldChange` | `false` | `false` | See below |

**Why `refuseCreativeWorlds` and `refuseCheatWorlds` default on, unlike `requireAttestation`.**
The two world checks only ever act on a client that describes its own world, and a real client
always does, unconditionally, on every connect. So somebody in creative mode, or with commands
on, is turned away by default, on the reasoning that an honest mistake ("I forgot I'd left
cheats on") is the likelier case in a friend group, and it costs them nothing but turning
cheats off and reconnecting. `requireAttestation` is a different kind of setting: it refuses
anyone who says *nothing at all* about their world, which includes an older build that has
never heard of attestation. That's a much bigger decision than the other two, so it's left for
you to make rather than made for you.

**What `banOnWorldChange` actually does.** It only matters once someone is already connected
and playing normally, having passed the checks above honestly. If they then switch creative
mode or cheats on mid-session, the market disconnects them either way. Without this setting,
that's the whole of it: they're free to reconnect once they've turned it back off. With it on,
the disconnection becomes permanent: their identity is written into this host's deny list, and
they can't reconnect at all until you edit the file to take them back off it. It's off by
default because the evidence for it is a self-report: it only ever catches someone who told
the truth about changing their own world, and does nothing to a modified client that simply
stops telling the truth. Switching it on is choosing a hard line the moment a friend's honesty
catches them out, over disconnecting them and leaving the door open.

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

