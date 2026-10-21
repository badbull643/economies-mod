# EconomiesMod handbook

*What the mod does, how to use it*

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

Nobody is in charge of the numbers, every player's game keeps its own copy of everything that
ever happened in the market and works out the balances itself. Whoever is hosting decides what
order things happen in and that's all they decide, they can't give themselves credits or delete
a trade because everyone else would work out a different answer and spot it straight away.

It also survives the host logging off. The history is a file so anyone who has it can host next
and the market carries on, and there's a dedicated server for a group that would rather one
machine stayed up.

everyone needs a copy of the history, trading only happens while somebody is hosting
and at least one other person is connected, and two copies that
both get traded on while apart will *fork*. [§9](#9-when-something-looks-wrong) is mostly
about that last one.

---

## 2. Getting in

Open your inventory and click the emerald next to the recipe book button, that's the market
screen. You can also bind a key to it in Options, then Controls, then EconomiesMod (nothing is
bound by default), and there's chat commands like `/trade balance` that read the market without
opening anything (full list in [§11](#11-command-reference)).

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

In the Market tab press Create a new market and give it a name. You're now its creator, which
matters later since only the creator can change the market's rules.

You'll be asked to confirm, because a market you create shares no history with one your
friends already use, and two markets can never be merged. If somebody already has one, join
theirs instead.

### Joining somebody else's

In the Network tab type their address and press Connect. Your game downloads the whole history,
checks every signature in it and rebuilds the market from scratch, which is why the first join
takes a moment.

If they're on the same machine or the same network or using something like Zerotier, **Refresh hosts** finds them without
typing anything (you must have joined each other once prior for this too work).

You can't join while holding a market of your own in that world. They're separate economies
and the mod will give a warning. Use a second [slot](#8-more-than-one-market) for theirs.

### Getting the history when nobody's online

In the Market tab, Export to a file gives you the whole market as a file and somebody else can
use Import one from a file to get it, with no host running and nobody online at the same time.

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

The order that was already resting sets the price, so if you bid 6 for something resting at 5
you pay 5 and turning up late doesn't cost you anything. If two sells are at the same price,
whoever placed theirs first gets filled first.

One order can also fill lots of others at once, a sell of 10 into ten resting bids of 1 is ten
trades and the book empties. The mod tells you afterwards: *"Sold 9 of 10 Dirt
across 9 orders, +18, 1 still resting" (the notification doesn't currently show will be fixed soon)*.

Anything that doesn't fill immediately **rests** until it does. A partly filled order leaves
the remainder on the book.

### Knowing when something traded

Chat tells you when one of your resting orders fills, which is the one you weren't watching
for. The action bar can do the same, more briefly. Both are settings.

Filling somebody else's order is reported too.

If some of your orders traded while you were offline, you get one line in chat adding it all up
when you reconnect, like *"While you were away: sold 20 Cobblestone, bought 2 Diamond · +40cr ·
see History"*. It only lists the biggest three (the rest just get counted), it uses the same
notification settings, and History still has everything.

---

## 5. What it costs to trade

these numbers are adjustable via the market creator in-game for the rotating mode and adjustable 
via the server config for the dedicated server.

The welcome grant is what a new arrival starts with. Capped at 300 (may change this too be much lower 
too kill variance in welcome grants) when somebody hosts from their game. A dedicated server may go higher.

The trading fee is a percentage of each trade, taken from the seller's money and destroyed rather
than paid to anyone, so it drains the money supply instead of being income for a host.

The listing fee gets charged when you place an order and is never refunded, even if you cancel.
It's there so placing orders isn't free, since a market where it's free can be spun in circles
for profit.

The listing fee can also **escalate**: a market can give everyone a few free orders and
charge more the more you hold open at once. Set to zero, nothing escalates and every listing
costs the same.

There's also the stipend, see [below](#the-stipend).

### The stipend

Optional, and can be disabled by the market creator.

The welcome grant used to be the only way credits ever entered a market. That means the money
supply grows when *people* arrive and never again, while goods keep piling in. Prices sink
until everything costs 1 and price stops meaning anything.

The stipend attempts too fix that so any registered player can **claim** a fixed amount once per *N fills
the market settles*, 50 by default (can be changed). Click **Claim stipend** on the Market tab.

It counts fills, not time or events. A fill needs two orders to cross, and every order costs a listing fee (if the host has set a listing fee).

A fill is one buy order meeting one sell order whatever the quantity, so selling 64 iron to one
buyer is one fill and one order matching three orders on the book is three fills. Anything that
isn't a trade, like placing an order or depositing, doesn't count.

The count is for the whole market, not just you. Everyone's trades move it along (bots' too) so
you don't have to trade to claim, if you claim when the market is at 1,080 fills your next claim
opens at 1,130 on the default of 50. A busy market pays out more often and a quiet one less.

New players have to wait a full interval, your count starts when you join so turning up in a
market that's already traded thousands of times doesn't pay out straight away. Missed intervals
don't add up either, claiming resets your count to now so 200 fills without a claim still only
pays once. The Claim stipend button only shows up when you can claim.

How much you get is up to your market's creator check the Market tab, or `/trade hostconfig`, for the
current amount. What it can be set to is tied to the **listing fee**, the market only
allows a stipend that the fees collected over one interval can actually cover, so a low listing
fee (or none at all) means a small stipend, or none.

A market won't allow a stipend that pays out more than its own fees bring in. If the payout
across every registered player is more than the listing fees collect over the same interval the
market would be printing money, so the rule turns it down. It's checked when the stipend gets set
and again on every claim, since a market can outgrow a stipend that was fine when it was picked.

The rule is `stipend × registered players < listing fee × fills per interval`. Registered players
means everyone who has ever joined, apart from bots, and that number only goes up. Players who
stopped playing still count, because the market can't tell they won't come back to claim.

Only the listing fee counts here, not the trading fee. The trading fee is a percentage of the
price and someone trading with themselves can just set the price to 1 and pay almost nothing,
but the listing fee is the same on every order so it's the only cost a fill is guaranteed to have.

The stipend gives back part of the listing fees rather than adding new money. Both fees get
destroyed and the rule keeps the stipend smaller than what the listing fees collect over the same
interval, so a market with a stipend still loses credits overall, it just moves credits from
whoever places the most orders to everyone who claims. New money still only comes in with new
players' welcome grants.

If you're picking one for a busy server, turn the stipend on once the market has been running for
a while rather than at the start:

1. Run the server for a week and type `stats` in the console every day or so. The `trades`
   line is the fill count, and `people` is the registered player count the rule uses.
2. Set the interval to about one day's fills, so each player can claim about once a day.
3. The most the stipend can be is `listing fee × interval ÷ people`. Set it to half of that or
   less, so there's room for the market to grow.
4. Look at `stats` again now and then. Once `people` doubles, claims start being refused until
   the interval goes up or the stipend comes down.

Example: listing fee 2, about 2,000 fills a day and 200 people gives an interval of 2,000 and a
maximum of 2 × 2,000 ÷ 200 = 20. A stipend of 10 lasts until there are 400 people.

---

## 6. Playing together

Somebody has to be **hosting** for trades to happen. Two ways to do it.

### Hosting from your game

In the Network tab press Host. Your game starts listening on a port for your friends to connect
to, and the market just runs inside your session while you carry on playing.

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

Just one jar and Java 8 or newer, `economies-server.jar` is the jar you need 

How to keep one running and what to back up is all in [§12](#12-running-a-server).

#### Setting one up

1. Give the jar its own folder, since the server writes several files beside wherever it runs
from (see the table below), including a private key.

Run this from wherever `economies-server.jar` currently is. It makes a new folder and copies the jar in:

```
mkdir ~/economies-server && cp economies-server.jar ~/economies-server/ && cd ~/economies-server
```

2. Write a config. Nothing exists yet so this creates one with every setting in it.

```
java -jar economies-server.jar --config server.json --write-config
```

3. Edit it, these settings matter before the first start.

```jsonc
"logFile": "market.jsonl",             // beside the jar, 
"port": 25555,
"hostName": "our server",              // what players see in the host list
"gameVersion": "1.21.11",              // the Minecraft version your players are on

"policy": {                    // the market's own economics, see below
  "taxBps": 100,               // 1% trading fee, taken from the seller and destroyed
  "welcomeGrant": 500,         // what a newcomer starts with
  "listingFee": 2,             // charged to place an order, never refunded
  "listingFreeOrders": 0,      // orders held open before the fee climbs, 0 for never
  "stipendAmount": 0,          // 0 for no stipend
  "stipendEveryFills": 50
}
```

The file also has a `welcomeGrant` at the top level, outside `policy`. Set it to the same
number as `policy.welcomeGrant`, or every start prints a "welcome grant mismatch" warning.
It's harmless, because the `policy` figure is the one newcomers get. Don't set it to 0 to quiet
the warning, though: 0 means this server hands out no welcome grants at all.

Leaving out `logFile` names the market `server-market.jsonl`. This handbook calls it
`market.jsonl` throughout.

4. Start it.

```
java -jar economies-server.jar --config server.json
```

The first start needs to know the Minecraft version, a new market won't be created until
`gameVersion` is set. Without it, the server stops straight away and says it "does not know
which Minecraft version its players are on". Either set `gameVersion` in the config (step 3),
or add it to the command:

```
java -jar economies-server.jar --config server.json --game-version 1.21.11
```

Use the version your players are on. It can't be changed once the market exists, and players
on any other version are turned away.

The first start with an empty log creates the market. You should see roughly:

```
[host] created 'our server's market' owned by this server
[host] policy updated from this config at event 3: tax 0 to 100, listing fee 0 to 2
[host] listening on port 25555
```

5. Join it. In game go to the Network tab, type the address and press Connect, using
`host:25555` (or `localhost:25555` if it's on the same machine).

#### What it creates, all beside the log

| File | What it is |
| --- | --- |
| `market.jsonl` | The market. The only file you can't get back, so back it up |
| `server-identity.key` | The server's private key. Don't share it, don't commit it |
| `server-peers.json` | Who connected and from where. The operator's own note |
| `known-keys.json` | Identities seen before, so they're recognised again |

Heads up, the server does store players' IP addresses. `server-peers.json` has the last one each
player connected from, and the console logs have every connection. It needs them to run and to
deal with cheaters, but IPs count as personal data (especially in the EU), so let your players
know and don't keep old logs forever. The deploy guide's setup deletes them after 30 days.

#### Changing things afterwards

Host rules are the settings about who can join and what the server will accept from them, like
the deposit caps and world checks. Just edit the config and restart, they belong to whoever is
hosting and take effect straight away.

The market's economics are the `policy` block. Edit it and restart, and a server that created
its market publishes the change as a `MarketPolicy` event, the same as a player would from the
Market screen.

Two things worth knowing:

- Anything you leave out is left alone, so a block that only mentions the tax won't touch the
  grant or the fee (that's why the fields are missing rather than zero when they're unset).
- A policy the market would refuse gets reported and skipped, like a stipend that outruns its
  listing fees or a grant above the host's ceiling, and the market keeps running on what it had.

The file can end up lying about what's really happening, an edit can be refused (the two
cases just above), or you might be hosting a market you didn't create, where the whole
`policy` block is ignored. Either way the file still shows whatever you last typed, not what
the market actually has.

Running `--write-config` again fixes that. it asks the
live market what its policy actually is right now and overwrites the file with that. So if
you're ever unsure whether an edit took effect, run `--write-config` again and read the file:
whatever it says afterward should be correct.

#### Who owns the market

By default the server does, and the operator changes policy through the config.

`--creator-key` names a player as creator instead, so the rules get changed from the
Market screen in game. Could be worth it when a person should own the market and the box is
only hardware. Otherwise the config is simpler and the key is one more thing to keep somewhere.

## Optional

You only use it on the server's very first start, the one that creates the market (step 4 above,
before there's a `market.jsonl` with anything in it), and two things have to be true for that
run:

1. `creatorUserId` is already set in the config to that player's identity (their UUID).
2. The start command carries the flag, pointing at that player's own key file:
   ```
   java -jar economies-server.jar --config server.json --creator-key alice.key
   ```

Every start after that is the ordinary command with no flag — `--creator-key` is ignored (and
says so) once a market already exists, because the creator was only ever needed to sign
genesis, and genesis has already happened. The key file doesn't need to live on this machine
again afterward.

#### If something looks wrong

| It says | It means |
| --- | --- |
| `the policy block in this config is not being applied` | This server is hosting a market it didn't create. Only the creator sets policy |
| `the policy block in this config was refused: …` | The numbers break a market rule, usually the stipend interlock. The market is fine and unchanged |
| `this market grants N, and welcomeGrant is set to M` | The amount is the market's, fixed when it was created. The config setting only chooses whether this server issues grants at all |
| `port already in use` | Something else has 25555, or a previous run hasn't exited |
| `does not know which Minecraft version its players are on` | A new market needs its version before it can be created. Set `gameVersion` in the config, or add `--game-version 1.21.11` (your players' version) to the start command |

#### Bots

A dedicated server can run bots: traders that keep a market moving when few people are on.
They trade through the same order book as everyone else, with the same rules and fees, and
players see their orders like anybody's.

Bots can only be switched on when the market is created, so add `--bots` to the very first
start, the one with an empty log:

```
java -jar economies-server.jar --config server.json --bots
```

This is the first start, so it needs the Minecraft version too. If `gameVersion` isn't set in
your config, the server stops without creating the market. Add it to the same command:

```
java -jar economies-server.jar --config server.json --bots --game-version 1.21.11
```

The config remembers it, so later starts are the ordinary command. On a market that already
exists `--bots` is ignored, and says so. Only a dedicated server runs bots; a market hosted
from a game never does.

There are two kinds:

| Kind | What it does | Starts with |
| --- | --- | --- |
| Producer | Makes one item (16 every 10 minutes, holding at most 256), sells it, and buys the other items to use up as upkeep | 1,000 credits and 256 of its item |
| Market maker | Puts up both a buy and a sell price on each item, so there is always someone to trade with. Once players have settled on a price for something else, it quotes that too | 1,500 credits and 32 of each item |

They only trade a few basic items (oak logs, cobblestone, coal, wheat and iron ingots), and a
market can have up to 8 of each kind. Producers decide every 30 seconds, market makers every 10.

The commands get typed into the server's own console (the terminal it's running in), not in
game:

| Command | What it does |
| --- | --- |
| `bot spawn producer [count] [item]` | Add producers. Without an item, each makes whichever item the fewest producers make |
| `bot spawn maker [count]` | Add market makers |
| `bot list` | Every bot: its name, id, whether it's running, its credits, open orders and what it holds |
| `bot pause <bot>` | Take its orders off the book and stop it |
| `bot resume <bot>` | Start it again |
| `bot help` | The list above |

To pick a bot you can use its name like `producer#2` or its id (just the first few characters
is enough, the ones `bot list` shows). For example:

```
bot spawn producer 3
bot spawn producer iron_ingot
bot spawn maker 2
bot pause producer#2
```

Count defaults to 1. `spawn` also takes `tick` or `threads`, which only changes how the server
schedules that bot, not how it trades; leave it on the default `tick`.

A bot can't be removed, only paused. It's part of the market's history from the moment it joins,
so pausing is how you stop one, its orders come off the book and it does nothing more until you
resume it.

The bots' keys live in a `bots` folder beside the log, so back that up along with `market.jsonl`.
A bot whose key gets lost stays in the market but this server can't run it any more, and `bot
list` shows it as "not running here".

A server started with nothing attached to its input, like a service, has no console to type
into. Start it in a terminal (or `screen`/`tmux`) when you want to manage the bots.

#### Watching the economy, and dealing with a cheater

These are typed into the same console as the bot commands. `help` lists everything.

| Command | What it does |
| --- | --- |
| `stats` | The money supply: credits held by players and by bots, what the fees have burned, and the total ever issued. Then the five richest and the ten most-held items, with their market price |
| `cancel-orders <player> [reason]` | Takes all of a player's orders off the book. What the orders reserved goes back to them; the listing fees they paid stay paid |
| `ban <player> [reason]` | Adds them to `deny` in the config, disconnects them, and cancels their orders |

For `<player>` you can use the name they connected with or their id (the first few characters
is enough). For example:

```
stats
cancel-orders Steve listing duped iron
ban 3f2a91c0 duping
```

Cancelling never takes anything off the player, their stuff just stops being for sale. The cancel goes into the market's history with your reason like every other
event, so every player can see it happened and why.

Only a server that created its market can cancel orders (that's the default). If the market was
created with `--creator-key` only that player can, and `cancel-orders` tells you so. `ban` still
adds them to `deny` and disconnects them either way.

With `banOnWorldChange` on, someone who gets banned for switching their world to creative or
cheats has their orders cancelled automatically too. To undo a ban, take them out of `deny` in
the config and restart.

In `stats`, "held" plus "burned" always adds up to "issued". If held keeps climbing while burned
stays small, money is coming in faster than the fees take it out and prices will creep up, so
raising the trading fee or the listing fee slows that down.

#### Sharing one market across Minecraft versions

A dedicated server can let more than one Minecraft version into the same market. Just list the
extra versions under `gameVersion` in the config and restart:

```jsonc
"gameVersion": "1.21.11",
"gameVersions": ["1.16.5", "1.20.1", "1.21.1", "26.3"],
```

Everyone trades in the same order books, so someone selling oak logs on 1.16.5 can sell to
someone on 26.3. You can only use items your version actually has though:

- If an item doesn't exist in your version you can't buy it or move it in or out of your game
  (so no buying copper on 1.16.5, it isn't in 1.16.5).
- Selling only needs you to have the item, so poplar logs you put in on 26.3 can still be sold
  from 1.16.5, you just can't take them out there.
- The market screen only shows items your version has, plus anything you're already holding.
- Renamed items still count as one item. The market uses the item's original name (`chain`) and
  each version shows its own, so 26.3 players see Iron Chain.
- Items from other mods can't be traded in a shared market, since there's no way to know which
  versions have them.

You need EconomiesMod 0.5.0 or newer to join a shared market, older versions get told to update.
Migrating a balance in from another market doesn't work in a shared market yet.

It works with every version the mod comes in, and the server won't start if the list has a
version it doesn't know.

If you play from more than one version, they all need the same identity file. Your orders and
balance belong to you as a player so they follow you between worlds and versions, and the market
knows it's you from a key file in your game's `config` folder,
`config/economiesmod-identity-<YourName>.key`.

- The official Minecraft launcher uses one folder for every version, so this just works.
- Launchers like the CurseForge app or Prism give each version its own folder, so
  each one ends up with its own key and joining from the second one gets refused with *"your
  signing key doesn't match the one this market has for you"*. To fix it, copy the `.key` file
  from the first version's `config` folder into the second one's (replace the one that's there)
  and connect again. In the CurseForge app you can right-click a profile and pick Open Folder to
  find `config`.

Keep that file private, anyone who has it can trade as you.

## Extra Notes

#### Two differences from a friend hosting

A dedicated server **doesn't hand out its peer list**. `server-peers.json` stays the
operator's note rather than something clients learn from. And **clients keep only a
snapshot** of a dedicated market rather than the whole history, since the box is always
there. `/trade archive on` opts back in for anybody who wants a full copy of the market.

### Who can join

The host decides, and which file it reads depends on which kind of host it is.

| Hosting from | Reads |
| --- | --- |
| Your game | `host-config.json`, beside that world's market. `/trade hostconfig write` creates it |
| A dedicated server | Its own `--config` file, the one `--write-config` produced |

Both hold the same host rules and mean the same things. See [§10](#10-host-rules). By
default anybody may join either.

---

## 7. Playing with friends who aren't on your network


| Connection | What it is | You need it for |
| --- | --- | --- |
| The market | This mod's own socket, on the port in the Host field, 25555 by default | Trading. Always. |

### The easy way: a virtual LAN

Something like ZeroTier or Radmin VPN puts everybody on one virtual network, and then the market
connection should just work.

With ZeroTier: everyone installs it, one person creates a network and shares its ID, everyone
joins that ID, and the creator authorises them in the ZeroTier panel. Each machine then has a
second address, usually starting `10.` or `172.`, and that's the address to type in before
pressing Connect.

Alternatively use a dedicated server too sidestep all of this.

### If it won't connect

- Check the market port, not Minecraft's. They're different numbers and the mod's is the one in
  the Host field.
- The host actually has to be hosting (they press Host in the Network tab and it should say it's
  listening).
- On a virtual LAN use the virtual address, your normal `192.168.…` address can't be reached from
  another network even when ZeroTier is running.
- Firewalls block the market port too. Windows asks about Minecraft the first time but says
  nothing about this mod's socket, which needs its own rule.

---

## 8. More than one market (May remove this feature)

A world can hold several markets with one of them **active**. Market tab → **Add another
market** makes a new slot, and the switcher moves between them.

Why switch rather than belong to both at once? Currency doesn't move between markets and an
inventory can't be spent twice.

### Archiving

Connect to a **dedicated server** and your game keeps only a summary of where the market got
to, not every event behind it. That history lives on the server, and there's no reason for a
hundred players to each keep a copy.

If you want the full thing, either to host it yourself or enable the command `/trade archive
on` in minecraft chat and reconnect once. `/trade archive` says which you've got.

A rotating host is unaffected.

---

## 9. When something looks wrong

### "You are behind"

Somebody else's copy has more events than yours. Connect to them and catch up.

The mod remembers the furthest the market has ever been seen to reach, even when nobody is
online, so it can still warn you a week later when discovery finds nothing at all.

### "Your history diverged"

Two copies were both traded on while they were apart, so there are now two versions of the
past. This is the one genuinely bad state and it can't be merged unfortunately: the two chains disagree
about what happened, and so does every balance after the split.

What the mod does about it:

- It finds where you parted and tells you, like *"you parted from the other copy after event
  42."*
- Everything before the split is safe, it's in their copy too and comes back when you rejoin.
- Reset throws away your branch and rejoins theirs. Before it does that it works out what it'll
  cost you and hands back what it can:
  - Items you deposited after the split go back into your inventory. Nothing else records them,
    so that's the one loss a reset would otherwise cause outside the ledger.
  - Orders you placed after the split come back as a checklist so you can place them again.
- What can't come back: credits you earned after the split, and trades made against a book
  that no longer exists. Those are ledger entries with no existence outside the branch being
  discarded.

### "Log unusable"

The file has a line the mod can't read, or the chain doesn't add up. The market is still
shown, with a banner and a Reset button, because emptying somebody's market on the screen
they came to fix it from is the wrong answer to a bad line.

A damaged history can't be hosted, since serving it would hand a joiner a chain that breaks
partway. The Host button says so and points at Reset.

### "This host serves Minecraft X players, and you are on Y"

you can restrict the number of versions a dedicated server works with but for the most part a market belongs to one Minecraft version, and nothing carries across to another. Items are
named differently between versions and the rules of the game differ.

Every host checks this before it sends anything: a player hosting from their game, and a
dedicated server alike. Your own game checks the host as well, so even an older host that
doesn't check can't pull you into a market on another version. Hosts on other versions are
left off your host list, and when that is why the list is empty it says so: *"nobody hosting
(1 on other Minecraft versions)"*. so just join a host that supports your version.

A dedicated server needs `--game-version` (or `gameVersion` in its config) when it creates its
market, and refuses to start without one rather than guess. An existing market with no setting
is treated as a Minecraft 1.16.5 market, which is what every server before the setting was
serving.

Since 0.5.0 a dedicated server can also share one market between several versions, and then the
message lists every version it takes (see
[Sharing one market across Minecraft versions](#sharing-one-market-across-minecraft-versions)).

In a shared market you might also see:

| Message | What it means |
| --- | --- |
| `joining it needs a newer EconomiesMod` | The market's shared between versions and your mod is older than 0.5.0, just update it |
| `… isn't in Minecraft X (it arrived in Y)` | That item doesn't exist in your version, so you can't deposit, buy or withdraw it from there |
| `… is called minecraft:chain in this market` | Only an outdated mod sends a renamed item under its new name, update it |
| `your signing key doesn't match the one this market has for you` | You're joining from a different game folder than before (like another version's profile in the CurseForge app). Copy `config/economiesmod-identity-<YourName>.key` from the folder you first joined with into this one's `config` folder |

### Migration, or leaving with what you hold

If you want to *move* to another market rather than rejoin your own, **Migrate my position**
carries your credits and items across, provided the destination accepts migration. The market
you're leaving is abandoned rather than merged, and the destination host may cap how much can
arrive.

---

## 10. Host rules

### Market rules: in the history, the same for everybody

Welcome grant, trading fee, listing fee, stipend. They're recorded as events, every copy
applies them identically, and only the **creator** can change them.

### Host rules: in a file, belonging to whoever is hosting

Admission, deposit caps, world checks, migration limits, the welcome grant ceiling.

Where they live depends on who's hosting: `host-config.json` beside that world's market when
somebody hosts from their game, or the dedicated server's own `--config` file.

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

The world checks are worth less than they look, since everything a client says about its own
world is a claim rather than evidence. A modified client says whatever it likes, and signing it would only
prove who said it. They're worth having for the casual case, somebody who turned on cheats
once and thought nothing of it, and for making two claims contradict each other. they aren't 
guaranteed to work.

### Rotating host defaults

Hosting from your game (Network tab → Host) uses the settings below until you write your own
`host-config.json` with `/trade hostconfig write`. Two of them resolve differently on a
dedicated server, marked below.

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

`refuseCreativeWorlds` and `refuseCheatWorlds` default on (unlike `requireAttestation`) because
the two world checks only ever act on a client that describes its own world, and a real client
always does, on every connect. So somebody in creative mode, or with commands
on, is turned away by default, `requireAttestation` is a different kind of setting: it refuses
anyone who says *nothing at all* about their world, which includes an older build that has
never heard of attestation.

`banOnWorldChange` (Important Setting would recommend turning on) only matters once someone is already connected
and playing normally, having passed the checks above honestly. If they then switch creative
mode or cheats on mid-session, the market disconnects them either way. Without this setting,
that's the whole of it: they're free to reconnect once they've turned it back off. With it on,
the disconnection becomes permanent: their identity is written into this host's deny list, and
they can't reconnect at all until you edit the file to take them back off it. It's off by
default because the evidence for it is a self-report: it only ever catches someone who told
the truth about changing their own world, and does nothing to a modified client that simply
stops telling the truth. 

### Rules a group can agree once

Most host rules are just defaults: the creator can publish a set into the history with
`/trade hostrules publish`, and any host adopts them for anything it hasn't set for itself —
but a host with an opinion of its own, in either direction, keeps it.

Six of them work differently though. Once they're published they become a floor, so a host can
be stricter than what the group agreed but never looser, whatever its own file says:

- `requireAttestation`
- `refuseCreativeWorlds`
- `refuseCheatWorlds`
- `banOnWorldChange`
- `maxDepositUnitsPerPlayHour`
- `maxDepositMultipleOfHandled`

That's on purpose. Refusing a connection never writes anything to the log so it can't fork
anyone's copy the way overriding a market rule would, which is what lets these six actually bind
instead of just being advice. Without it a market that's been kept strict for months would drop
back to the default settings the moment someone with no file of their own started hosting it,
and there'd be no undoing that since a deposit can't be taken back once it's accepted.

Everything else here is just advice, it gets recorded and hosts read it but a host that
disagrees can ignore it. `/trade hostrules` shows what was agreed and what this host does with
each part of it.

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
| `/trade name` | Whether other players see your name (on the leaderboard and so on) |
| `/trade name hide` / `show` | Hide it or show it again, kicks in next time you connect |

**Trading commands may be added at some point

---

## 12. Running a server

### Keeping one up

`java -jar economies-server.jar --config server.json` is a foreground process: closing the
terminal it's running in kills it. For anything meant to stay up, that terminal has to survive
you logging off , `screen` or `tmux` if you're comfortable with either, a proper service
manager (`systemd` on Linux, NSSM or Task Scheduler on Windows) if you also want it to restart
itself after a crash. Redirect its output to a file too: the console messages it prints
("[host] created…", "[host] listening on…", every refusal) are the only record of what it's
done unless you keep them.

There's nothing to shut down carefully. Stopping the process, however you do it, is safe —
every event is written and flushed to disk the moment it's appended, so stopping it never loses
anything that already happened.

### Updating

Stop the server, replace `economies-server.jar` with the new one, start it again. Nothing
else needs touching since everything else lives in its own files beside the jar, and swapping
the jar doesn't go near them.

Don't use an older version once you've updated, a newer build can write event types an
older one hasnt heard of. Downgrade the jar after that and the older build stops partway
through the log with "contains an event this version can't read" so not corrupted, just
unreadable to that particular build. Keep the old jar until you're sure about the new
one, rather than needing it back afterward.

### Backups

`market.jsonl` is the one that matters. It's the market so if you lose it and there's
nothing to restore from, short of a client somewhere holding the full history via `/trade
archive on`. Back it up on whatever schedule losing that much would actually cost you.

`server-identity.key` is worth keeping too An identity's key cant be replaced once it's registered and there's no event for re-keying one.

just ignore this one `market.jsonl.snapshot.json` was just an optimisation.

---

