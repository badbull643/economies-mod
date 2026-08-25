# EconomiesMod

A player-run market for Minecraft, with a real order book.

Deposit items, list them at a price, and let your friends buy them. Orders sit there until
they fill. Nobody hands out money and nobody settles an argument about a trade, because
every player's game keeps the whole history and works the balances out for itself.

**Minecraft 1.16.5 · Fabric · client-side · singleplayer and Open to LAN**

---

## What makes it different from a chest with a sign on it

**It's a real order book.** List 20 iron at 5 credits, somebody bids 6, and the trade
happens at 5 with the difference going to them. Partly filled orders leave the rest resting.

**Nobody is in charge of the numbers.** Every event is signed by whoever made it and chained
to the one before. Whoever is hosting decides what order events happen in and nothing else.
They can't give themselves credits or delete a trade, because everyone else would compute a
different answer and spot it straight away.

**It survives the host logging off.** The history is a file. Anyone who has it can host next
and the market carries on.

---

## Before you install: what it does and doesn't cover

**Singleplayer and Open to LAN.** A market lives beside a world on your own machine, so
there's no market when you join a server somebody else runs. The mod says so on its own
screen rather than pretending, and it's inert there, so it's safe to leave installed.

That's a design boundary rather than a to-do. The ledger reaches real inventories through
the server-side player object, which only exists for a world your own game is running.
Proper multiplayer would need a companion mod on the server.

Friends elsewhere can still play: a virtual LAN like ZeroTier or Radmin works, and so does
port forwarding. The handbook has
[a section on it](docs/HANDBOOK.md#7-playing-with-friends-who-arent-on-your-network),
including the part people get caught by, which is that Minecraft and the market use two
different ports.

There's also a **standalone dedicated server**, one jar and no Minecraft, for a group that
would rather one machine stayed up. See
[the handbook](docs/HANDBOOK.md#the-dedicated-server).

---

## Install

1. [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.16.5
2. [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `economiesmod-0.1.0.jar` into `.minecraft/mods`

---

## Five minutes in

1. Open your inventory. There's an emerald button next to the recipe book. (Or bind a key in
   **Options → Controls → EconomiesMod**. Nothing is bound by default.)
2. **Market tab → Create a new market.** You're its creator now, which means you set its
   rules.
3. **Trading tab → Sell.** Pick an item, a quantity and a price. The items leave your
   inventory and rest on the book.
4. **Open to LAN**, then **Network tab → Host**. Your friends use **Connect** with your
   address.
5. They buy, you get credits, and everybody's copy agrees because everybody replayed the
   same events.

`/trade balance`, `/trade orders` and `/trade price <item>` read the market from chat
without opening anything.

---

## What I'd like feedback on

This is a first public build, and the parts I'm least sure of are economic rather than
technical.

- **Does the money hold its value?** Credits come in through a welcome grant and an optional
  stipend, and leave only through the trading fee. Whether those balance over weeks of real
  play is a guess, because no market has been played long enough to find out.
- **Is the stipend interval sensible?** It pays out once per 50 fills the market settles.
  That number was reasoned about and never observed.
- **Does trading feel like trading?** The screen, the book, the notifications. The things you
  only learn by using.

Bugs, confusions and "I expected X and got Y" are all welcome in
[issues](https://github.com/badbull643/economies-mod/issues). A copy of `latest.log` helps
more than anything else, since the mod logs what it did and why.

---

## Known limitations

- **Singleplayer and LAN only**, as above.
- **Minecraft 1.16.5 and Fabric.** No other versions or loaders yet.
- **A forked market can be recovered from but never merged.** If two copies of one market are
  both traded on while apart, the mod finds where they parted, hands back the items you
  deposited since, and lists the orders you'd need to re-place. One branch still has to be
  discarded. That's inherent to the design and argued out in the design notes.
- **Items with NBT are skipped.** An enchanted pickaxe isn't interchangeable with a plain one,
  so the market has no way to price it.
- **Trading only happens while somebody is hosting** and at least one other player is
  connected. There's no offline order matching.

---

## Documentation

| | |
| --- | --- |
| [`docs/HANDBOOK.md`](docs/HANDBOOK.md) | How to use it and how it works, including playing with friends who aren't on your network. Start here |
| [`docs/design/`](docs/design) | Design notes for the harder decisions, measured rather than asserted |
| [`docs/BACKLOG.md`](docs/BACKLOG.md) | What's deliberately not built, and what it costs to leave it |

---

## Building it yourself

```
./gradlew build          # the mod jar, in build/libs
./gradlew serverJar      # the standalone dedicated server, one runnable jar
./gradlew coreTests chunkTest replayGuardTest gapRecoveryTest admissionTest \
    depositCapTest attestationTest hostTrustTest splitPointTest
```

The engine imports no Minecraft, so those nine suites run on a bare JVM in seconds. CI runs
them on every push.

---

## Licence

MIT. See [LICENSE.txt](LICENSE.txt).
