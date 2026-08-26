# Economies Mod (still experimental so things may fail)

A player run market for Minecraft, with a real order book.

able to place Buy and Sell orders for items at a price. Orders sit until they fill.

**Minecraft 1.16.5 · Fabric · client side · singleplayer and Open to LAN**

**[Download the latest release](https://github.com/badbull643/economies-mod/releases/latest)**
— the mod jar for Minecraft, and a standalone server jar if you want a dedicated market.

---

## How it works

**It's a real order book.** List 20 iron at 5 credits, somebody bids 6, and the trade
happens at 5 with the difference going to them. Partly filled orders leave the rest resting.

There are two ways too get a market working either use a dedicated server as the central 
authority on the market and connect too that server every time or use the host option in game, 
where every person has a personal copy of the market information and the person hosting can change 

## Notes on the changing hosts setup

**Nobody is in charge of the numbers.** Every event is signed by whoever made it and chained
to the one before. Whoever is hosting decides what order events happen in and nothing else.
They can't give themselves credits or delete a trade, because everyone else would compute a
different answer and spot it straight away.

**It survives the host logging off.** The history is a file. Anyone who has it can host next
and the market carries on.

---

## Before you install: what it does and doesn't cover

**Singleplayer and Open to LAN.** A market lives beside a world on your own machine, so
there's no market when you join a server somebody else runs. The mod gives a warning for this, 
and the Mods inert there, so as far as its been tested should be safe too keep installed however
would still recommend disabling the Mod to be sure.

Other people elsewhere can still play: a virtual LAN like ZeroTier or Radmin works, and so does
port forwarding (wouldn't recommend the port forwarding just use the virtual LAN since its 
much simpler too setup and safer). The handbook has
[a section on it](docs/HANDBOOK.md#7-playing-with-friends-who-arent-on-your-network).

There's also a **standalone dedicated server**, for a group that
would rather one machine stayed up. See
[the handbook](docs/HANDBOOK.md#the-dedicated-server).

---

## Install

1. [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.16.5
2. [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `economiesmod-0.1.0.jar` into `.minecraft/mods`

---

## Using the Mod

1. Open your inventory. There's an emerald button next to the recipe book. (Or bind a key in
   **Options → Controls → EconomiesMod**. Nothing is bound by default.)
2. **Market tab → Create a new market.** You're its creator now, which means you set its
   rules.
3. **Trading tab → Sell.** Pick an item, a quantity and a price. The items leave your
   inventory and rest on the book.
4. **Network tab → Host**. Other people use **Connect** with your address or alternatively if 
   a dedicated server is used enter the dedicated server address and press connect.
5. They buy, you get credits, and everybody's copy agrees because everybody replayed the
   same events.

`/trade balance`, `/trade orders` and `/trade price <item>` read the market from chat
without opening anything, enter /trade too get the full list of commands.


## Current limitations

- **Singleplayer and LAN only**, as above.
- **Minecraft 1.16.5 and Fabric.** No other versions or loaders yet.
- **A forked market can be recovered from but never merged.** If two copies of one market are
  both traded on while apart, the mod finds where they parted, hands back the items you
  deposited since, and lists the orders you'd need to re place. One branch still has to be
  discarded.
- **Items with NBT are skipped.** An enchanted pickaxe isn't interchangeable with a plain one,
  so the market has no way to price it.
- **Trading only happens while somebody is hosting** and at least one other player is
  connected. There's no offline order matching.

---

## Documentation

| | |
| --- | --- |
| [`docs/HANDBOOK.md`](docs/HANDBOOK.md) | How to use it and how it works, including playing with people who aren't on your network. Start here |

---


## Licence

MIT. See [LICENSE.txt](LICENSE.txt).

---

Credits are virtual, exist only inside your own world's market, and can never be exchanged
for real money or any out-of-game currency.
