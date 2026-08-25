# EconomiesMod

A player-run market for Minecraft, with a real order book.

Deposit items, list them at a price, and let your friends buy them. Orders rest until they
fill. Nobody hands out money and nobody adjudicates a trade — every player's game keeps the
whole history and works the balances out for itself.

**Minecraft 1.16.5 · Fabric · client-side · singleplayer and Open to LAN**

---

## What makes it different from a chest with a sign on it

**It is a real order book.** List 20 iron at 5 credits; somebody bids 6; the trade happens
at 5 and the difference is theirs. Partly filled orders leave the rest resting.

**Nobody is in charge of the numbers.** Every event is signed by whoever made it and
chained to the one before. The person hosting decides what order events happen in and
nothing else — they cannot give themselves credits or delete a trade, because everybody
else recomputes a different answer and notices immediately.

**It survives the host logging off.** The history is a file. Whoever holds it can host
next, and the market carries on.

---

## Scope — read this before installing

**Singleplayer and Open to LAN.** A market lives beside a world on your own machine, so
there is no market when you join a server somebody else runs. The mod says so on its own
screen rather than pretending; it is inert there and safe to leave installed.

That is a design boundary, not a to-do: the ledger reaches real inventories through the
server-side player object, which only exists for a world your game is running. Proper
multiplayer would need a companion mod on the server.

There is also a **standalone dedicated server** — one jar, no Minecraft — for a group that
would rather one machine always be up. See [the handbook](docs/HANDBOOK.md#6-playing-together).

---

## Install

1. [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.16.5
2. [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `economiesmod-0.1.0.jar` into `.minecraft/mods`

---

## Five minutes in

1. Open your inventory — there is an emerald button beside the recipe book. (Or bind a key
   in **Options → Controls → EconomiesMod**; nothing is bound by default.)
2. **Market tab → Create a new market.** You are now its creator, which means you set its
   rules.
3. **Trading tab → Sell.** Pick an item, a quantity and a price. The items leave your
   inventory and rest on the book.
4. **Open to LAN**, then **Network tab → Host**. Your friends use **Connect** with your
   address.
5. They buy; you get credits; everybody's copy agrees because everybody replayed the same
   events.

`/trade balance`, `/trade orders` and `/trade price <item>` read the market from chat
without opening anything.

---

## What I would like feedback on

This is a first public build, and the parts I am least sure of are economic rather than
technical:

- **Does the money hold its value?** Credits enter through a welcome grant and an optional
  stipend, and leave only through the trading fee. Whether those balance over weeks of real
  play is a guess — no market has yet been played long enough to find out.
- **Is the stipend interval sensible?** It pays out once per 50 fills the market settles.
  That number was reasoned about, never observed.
- **Does trading feel like trading?** The screen, the order book, the notifications — the
  things you only learn by using.

Bugs, confusions and "I expected X and got Y" are all welcome in
[issues](https://github.com/badbull643/economies-mod/issues). A copy of `latest.log` helps
more than anything else; the mod logs what it did and why.

---

## Known limitations

- **Singleplayer and LAN only**, as above.
- **Minecraft 1.16.5 and Fabric.** No other versions or loaders yet.
- **A forked market can be recovered from but never merged.** If two copies of one market
  are both traded on while apart, the mod finds where they parted, hands back the items you
  deposited since, and lists the orders you would need to re-place — but one branch has to
  be discarded. This is inherent to the design and is argued out in the design notes.
- **Items with NBT are skipped** — an enchanted pickaxe is not interchangeable with a plain
  one, so the market has no way to price it.
- **Trading happens only while somebody is hosting** and at least one other player is
  connected. There is no offline order matching.

---

## Documentation

| | |
| --- | --- |
| [`docs/HANDBOOK.md`](docs/HANDBOOK.md) | How to use it, and how it works. Start here |
| [`docs/design/`](docs/design) | Design notes for the harder decisions, measured rather than asserted |
| [`docs/BACKLOG.md`](docs/BACKLOG.md) | What is deliberately not built, and what it costs to leave it |

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
