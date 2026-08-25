# Text review — the Market tab and the pop-up dialogs

*Everything the mod says in the two places that carry its explanations: the guidance
column on the Market tab, and the confirmation dialogs. Written out in full so the wording
can be judged as writing rather than read one line at a time out of a source file.*

**How to use this.** Mark anything you want changed — a note, a rewrite, a "cut this" —
and the edits go back into `MarketScreen.java`. Line numbers are given so a change can be
found; they move, the text does not.

Two things to keep in mind while reading:

- **The dialogs are the last thing between somebody and an irreversible action.** Discard,
  Remove and Migrate destroy a market's local history. Those bodies are not decoration.
- **The guidance is what a lost person reads.** Every line of it is shown *because
  something is in a particular state*, so it should say what that state is, what it costs,
  and what to do — in that order.

---

## 1. The Market tab guidance

One heading and one paragraph, chosen by the market's situation. Below them come the
market's facts and the slot switcher.

### 1.1 The log is unreadable — `MarketScreen.java:2158`

> **This world's market log is unreadable**
>
> *[the damage reason, or "The file is damaged."]* Nothing can be done with it until it is
> discarded. If someone else still has this market, you get everything back when you
> reconnect to them.

### 1.2 No market here — `:2166`

> **This world has no market**
>
> Two markets can never be merged, so if your friends already have one, join theirs from
> the Network screen rather than starting your own. Otherwise, create one here to begin an
> economy of your own, or import a file somebody exported.

*Rewritten 2026-08-25. It read "do NOT create one, since two markets can never be merged",
which shouted, and put the warning where somebody who had already clicked Create would
find it. The reason comes first now.*

### 1.3 Forked — `:2173`

> **You have diverged from this market**
>
> *[where the split was]*. Discarding and reconnecting is the way back, and it costs only
> what you did after the split. Everything before it is in their copy too. Migrating is for
> moving to a different market rather than rejoining this one, so a host declines it on
> purpose, to keep both copies whole.

*Rewritten 2026-08-25. "the host will refuse it" read as a fault rather than a safeguard,
so it now says whose protection it is.*

### 1.4 Behind — `:2181`

> **Your copy is behind**
>
> *[N]* events have happened that you do not have. Connect to someone serving this market
> from the Network screen and you will catch up automatically. Do not host until you have.

### 1.5 Connected — `:2188`

> **Connected to '*[market]*'**
>
> Trading is live, and anything you buy or sell settles for everybody at once. Your balance
> and your resting orders are on the Trading tab. If somebody cannot be online while anyone
> is hosting, you can export a copy of this market for them from here.

*Rewritten 2026-08-25. It opened with "Everything is in order", which tells somebody nothing
they could not see, and spent the rest of the paragraph on export. This is the one state a
working market gets to explain itself in, so it says what is live and where to go next, and
export is the afterthought it always was.*

### 1.6 Holding, nobody serving — `:2194`

> **You hold '*[market]*'**
>
> Nobody is serving it. Host it from the Network screen so others can trade, or connect to
> someone who already is.

### 1.7 When another host is visible — `:2222`

Two variants, appended below the paragraph above.

**A dedicated server, running a different market:**

> *[host]* is a dedicated server running a separate market ('*[name]*'). It does not take
> migrations. To join it, use Add another market and connect from that one — this market
> stays as it is, and you arrive there on their welcome grant like anyone else.

**Somebody's game, running a different market:**

> *[host]* is running a separate market ('*[name]*'). Migrating carries your whole position
> there and abandons this one. Add another market instead to join without giving this one
> up.

---

## 2. The dialogs

Nine. The DANGER ones are marked; those are red, need a second click, and stand in front of
something that cannot be undone.

### 2.1 Create a market — `:748`

> **Create '*[name]*'?**
>
> This starts a separate economy. Anyone who joins it will not see trades from any market
> your friends already use, and the two can never be merged afterwards. To join an existing
> one instead, use Connect.
>
> `[Create]`

### 2.2 Import from a file — `:838`

> **Import a market from file?**
>
> This world will adopt the market in your economiesmod-imports folder as its own. Every
> event in it is verified before anything is written, so a tampered file is refused rather
> than trusted.
>
> `[Import]`

### 2.3 Add another market — `:2387`

> **Add another market to this world?**
>
> This world can hold several markets and use one at a time. The one you are in now keeps
> everything it has: its history, balances and orders. You can switch back whenever you
> like. The new one starts empty, so the next step is to create it, import one, or connect
> to somebody hosting.
>
> `[Add]`

### 2.4 Hosting while behind — **DANGER** — `:869`

> **You are *[N]* events behind**
>
> Hosting now would refuse everyone who is up to date, and split the market the moment you
> trade. Connect to someone serving it first and you will catch up automatically.
>
> `[Host anyway]`

### 2.5 A dedicated server already has it — **DANGER**, no confirm button — `:905`

> **You cannot host this market**
>
> *[host]* is a dedicated server serving this market right now (*[N]* events), and it does
> not stop. Hosting it as well means two hosts sequencing one market, which splits it into
> two histories that can never be merged. You would lose everything you traded on the
> losing side. Connect to it from the Network tab instead.

### 2.6 Somebody else is hosting it — **DANGER** — `:918`

> ***[host]* is already hosting this**
>
> *[host]* is serving this market right now (*[N]* events). Two hosts at once will split it
> into two economies that cannot be merged. Connect to them instead.
>
> `[Take over]`

*Worth a look: the button says "Take over", which sounds like a supported handover. What it
actually does is start a second host, which is the thing the body just described as
splitting the market.*

### 2.7 Migrate — **DANGER** — `:1021`

> **Migrate to *[host]*:*[port]*?**
>
> Your position in '*[market]*' (*[what you hold]*) is verified by that host and credited
> to you there. This market is then discarded. Only use this for a market with no history
> in common with theirs; if you have diverged from the same market, Reset is what you want
> instead.
>
> `[Migrate]`

### 2.8 Remove a market from this world — **DANGER** — `:2418`

> **Remove '*[name]*' from this world?**
>
> You would lose *[what you hold]*, and this world's copy of its history goes with it. If
> somebody else still hosts this market you can join them again and get everything back; if
> nobody does, it is gone. Your other markets in this world are untouched.
>
> `[Remove]`

### 2.9 Discard this world's market — **DANGER** — `:594`

The longest, and the only one assembled from parts. After a fork it reads roughly:

> **Discard this world's market?**
>
> You parted from the other copy after event *[N]*. The *[M]* events since are yours alone.
>
> Comes back when you reconnect: everything up to event *[N]*: your credits, holdings and
> trades are in their copy too.
>
> Handed to your inventory: *[items deposited since the split]*
>
> Listed afterwards to put back by hand: *[orders]*
>
> Gone for good: credits you earned since the split, and the trades behind them.
>
> This cannot be undone.
>
> `[Discard]`

With no fork, the first two paragraphs are replaced by:

> You would lose *[what you hold]*. Nothing here is held anywhere else unless somebody is
> still hosting this market.

---

## 3. The smaller ones on the same tab

Set through the Market tab's own controls, and each stands in front of a policy change:

| Where | Title |
| --- | --- |
| `:1756` | **Remove the trading fee?** / **Set the trading fee to *[N]*%?** |
| `:1872` | *[listing fee title]* — `[Set fee]` |
| `:1964` | **Grant newcomers *[N]* credits?** — DANGER |
| `:2051` | **Stop paying a stipend?** / *[stipend title]* |

---

## Notes for whoever edits these

**Length is a constraint, not a style choice.** Dialog bodies wrap inside the overlay, so
they can run long; the *status line* underneath the screen cannot — it is one trimmed line
of roughly 50–70 characters depending on GUI scale. Two messages have already had to be cut
down for that reason.

**Say the state, then the cost, then the action.** Every paragraph above that reads well
does this, and the two that read badly do not.

**Nothing here is translated.** All of it is literal English in `MarketScreen.java`; there
is a `lang` folder with nothing in it. If localisation ever matters, this file is the
inventory of what would have to move.
