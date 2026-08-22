package io.github.badbull643.economiesmod.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;




public class MarketState {
    // The CommodityMarketRegistry from sketch a map for timebeing
    //
    // Concurrent because activeItems() hands its key set out and the render thread walks
    // it — listing every tradable item, pricing a listing fee — while EventApplier is
    // creating the book for an item nobody has traded before. Item ids are validated
    // non-null before any book exists, so a map that rejects null keys costs nothing.
    private final Map<String, OrderBook> markets = new ConcurrentHashMap<>();
    private final WalletRegistry wallets = new WalletRegistry();

    // What has actually traded. Derived from replay like everything else, never stored
    // separately — a view with its own persistence could disagree with the log.
    private final TradeHistory trades = new TradeHistory();

    // Set once, by the MarketCreated genesis event. Null means this state was built
    // from an empty log (or one predating market identity).
    // Set once, at genesis, then read from handshake threads and the render thread.
    private volatile UUID marketId;
    private volatile String marketName;
    // Who wrote the genesis event. Named in the log, so every replica agrees on it
    // without anyone having to be told.
    private volatile UUID creator;

    // userId → public key, built from MarketCreated and KeyRegistered events. This is
    // the directory that lets a log be verified by someone who wasn't there when it
    // was written; it must come from the log, never from a side file.
    //
    // Concurrent: the sequencer thread writes it while per-connection handshake threads
    // read it to decide admission. A plain HashMap read during another thread's resize
    // can spin forever on Java 8.
    //
    // That reasoning was written here and then not carried anywhere else, which left
    // the wallets, the item ledger, the trade history and every order book being read
    // by the render thread while the applier wrote them. They each guard themselves
    // now — see WalletRegistry, ItemBalanceRegistry, TradeHistory and OrderBook.
    private final Map<UUID, String> keyDirectory = new ConcurrentHashMap<>();

    // Who has already had a welcome grant in this market. Per-market by construction:
    // it is derived from this log and no other. Concurrent for the same reason.
    private final Set<UUID> granted = ConcurrentHashMap.newKeySet();

    public UUID marketId() { return marketId; }
    public String marketName() { return marketName; }
    public UUID creator() { return creator; }

    public String publicKeyOf(UUID userId) { return keyDirectory.get(userId); }
    public boolean isRegistered(UUID userId) { return keyDirectory.containsKey(userId); }
    public int registeredCount() { return keyDirectory.size(); }
    public Set<UUID> registeredUsers() { return new HashSet<>(keyDirectory.keySet()); }
    public boolean hasBeenGranted(UUID userId) { return granted.contains(userId); }

    // "<fromMarketId>:<beneficiary>" for every migration already honoured, so the same
    // branch can't be cashed in twice.
    private final Set<String> migrationsDone = ConcurrentHashMap.newKeySet();

    // Identities that held a position in a market someone migrated away from. They get
    // their own verified balance if they turn up, but never a fresh welcome grant on
    // top — that combination is the one way migration could mint currency.
    private final Set<UUID> accountedElsewhere = ConcurrentHashMap.newKeySet();

    public boolean hasMigrated(UUID fromMarketId, UUID beneficiary) {
        return migrationsDone.contains(fromMarketId + ":" + beneficiary);
    }

    public boolean isAccountedElsewhere(UUID userId) {
        return accountedElsewhere.contains(userId);
    }

    /**
     * Identities that have already carried a balance into this market, from anywhere.
     *
     * Distinct from both sets above, and the distinction is the whole point.
     * migrationsDone is keyed to a source market, so it cannot see somebody arriving
     * again from a market they have just created. accountedElsewhere holds everyone who
     * was *registered* in a market somebody migrated away from — which is a different
     * group entirely: it exists to deny them a second welcome grant, not to deny them
     * their own balance, and its own note says they get that "if they turn up".
     *
     * Using accountedElsewhere for this refused the second person to migrate out of a
     * shared market, because the first migration had already filed everyone who lived
     * there. Which is the ordinary case, not the abusive one.
     */
    private final Set<UUID> migratedIn = ConcurrentHashMap.newKeySet();

    public boolean hasMigratedIn(UUID beneficiary) {
        return migratedIn.contains(beneficiary);
    }

    void recordMigration(UUID fromMarketId, UUID beneficiary, List<UUID> participants) {
        migrationsDone.add(fromMarketId + ":" + beneficiary);
        migratedIn.add(beneficiary);
        if (participants != null) accountedElsewhere.addAll(participants);
    }

    /**
     * One lock over the whole state, above the per-class monitors.
     *
     * Those monitors stop any single collection being read while it is being written.
     * They cannot make a *set* of reads agree with each other, because settling one
     * event touches several: a fill credits the buyer's items and the seller's money in
     * two separate steps, so a reader between them sees credits gone and goods not yet
     * arrived. Counting orders across every book has the same problem — the fee that
     * count decides has to be one number every replica reaches, not a walk that another
     * thread can rearrange halfway through.
     *
     * So EventApplier.apply holds the write lock for a whole event, and anything reading
     * more than one thing at once takes the read lock. Single reads need neither; their
     * own monitor is enough, and making every getter contend would put a lock in the
     * render loop for no gain.
     *
     * Reentrant, and a write-lock holder may take the read lock — which it does, since
     * apply calls straight back into openOrderCount through the listing fee.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock guard =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /**
     * Held while reading several things that have to agree with each other.
     *
     * Public so a caller building a composite view — a migration valuation, a frame that
     * must not show a half-settled fill — can hold one moment still. Always in a
     * try/finally; never held while doing anything slow.
     */
    public java.util.concurrent.locks.Lock readLock() { return guard.readLock(); }

    /** Held by EventApplier.apply for the whole of one event, and by nothing else. */
    java.util.concurrent.locks.Lock writeLock() { return guard.writeLock(); }

    /** Called only by EventApplier. First registration for a userId wins. */
    void registerKey(UUID userId, String publicKey) {
        keyDirectory.put(userId, publicKey);
        // Their stipend interval starts here, so joining a market that has already
        // traded thousands of times does not pay out on arrival. Done here rather than
        // at the call site because there is more than one way to become registered —
        // genesis registers the creator — and the two drifting apart is how this kind of
        // rule stops holding. putIfAbsent so a re-registration cannot reset the clock.
        stipendedAtFill.putIfAbsent(userId, fillsEver);
    }

    void markGranted(UUID userId) { granted.add(userId); }

    // How many fills this market had settled the last time each identity claimed the
    // stipend. Set on registration too, so a newcomer waits a full interval like
    // everyone else rather than claiming immediately off the back of other people's
    // trading — otherwise joining a busy market would pay out at once, and joining
    // several would pay out several times.
    private final Map<UUID, Long> stipendedAtFill = new ConcurrentHashMap<>();

    /** Fills settled when this identity last claimed, or registered. */
    public long stipendedAtFill(UUID userId) {
        Long at = stipendedAtFill.get(userId);
        return at == null ? 0 : at;
    }

    void markStipended(UUID userId, long atFill) {
        stipendedAtFill.put(userId, atFill);
    }

    /** Called only by EventApplier when it applies the genesis event. */
    void setMarketIdentity(UUID id, String name, UUID creator) {
        this.marketId = id;
        this.marketName = name;
        this.creator = creator;
    }

    // ─────────── economic policy ───────────

    /**
     * Transaction tax on fills, in basis points. Zero until a MarketPolicy event says
     * otherwise, so every market created before policy existed behaves exactly as it did.
     */
    private volatile int taxBps = 0;

    public int taxBps() { return taxBps; }

    /** Called only by EventApplier, which has already checked the bounds and the author. */
    void setTaxBps(int bps) { this.taxBps = bps; }

    /**
     * What a new identity is granted, in credits.
     *
     * Defaults to the value every market used when this was a compiled-in constant, so
     * logs written before policy existed still validate — their grants were all for
     * exactly this amount.
     */
    private volatile long welcomeGrant = ServerConfig.DEFAULT_WELCOME_GRANT;

    public long welcomeGrant() { return welcomeGrant; }

    void setWelcomeGrant(long amount) { this.welcomeGrant = amount; }

    /**
     * The most a market may grant a newcomer.
     *
     * A ceiling on a fat finger, not a security boundary — the security is that grants
     * must equal the market's policy exactly, so the number a liar can give themselves
     * is the same one an honest host would have given them anyway.
     */
    public static final long MAX_WELCOME_GRANT = 1_000_000L;

    /**
     * Flat credits charged for placing an order. Zero, and off, until policy says
     * otherwise.
     *
     * Charged on both sides. A sell costs credits even though it offers goods, which is
     * the one genuinely awkward consequence: somebody holding items and no money cannot
     * list at all. That is the real cost of pricing order placement rather than order
     * value, and the reason this wants to stay small next to the welcome grant.
     */
    private volatile long listingFee = 0;

    public long listingFee() { return listingFee; }

    void setListingFee(long fee) { this.listingFee = fee; }

    /**
     * How many orders an identity may hold open before the fee starts climbing.
     *
     * A flat fee prices every order the same, which prices the wrong thing: somebody
     * repricing one order pays exactly as much per order as somebody papering the book
     * with two hundred, and the flat amount lands hardest on whoever has least. This
     * charges the base fee up to the allowance and adds one base fee per order beyond
     * it, so the cost falls on the behaviour that motivated the fee.
     *
     * Never free, even inside the allowance — the base fee still applies. A zero
     * marginal cost would make events costless to produce, and the stipend is only safe
     * because producing the activity it pays out on is not.
     *
     * Zero means no escalation at all, matching every other policy field here, so a
     * market that predates this keeps exactly the flat fee it had.
     */
    private volatile int listingFreeOrders = 0;

    public int listingFreeOrders() { return listingFreeOrders; }

    void setListingFreeOrders(int n) { this.listingFreeOrders = n; }

    /** Credits claimable once per stipendEveryFills fills. Zero, and off, by default. */
    private volatile long stipendAmount = 0;
    private volatile long stipendEveryFills = 0;

    public long stipendAmount() { return stipendAmount; }
    public long stipendEveryFills() { return stipendEveryFills; }

    void setStipend(long amount, long everyFills) {
        this.stipendAmount = amount;
        this.stipendEveryFills = everyFills;
    }

    /**
     * The most a market may pay out per interval.
     *
     * A ceiling on a fat finger like MAX_WELCOME_GRANT, not a security boundary — the
     * security is the interlock in validate, which refuses a stipend that pays out more
     * than producing the fills to earn it would cost.
     */
    public static final long MAX_STIPEND = 100_000L;

    /**
     * Trades between stipends when a market turns one on.
     *
     * Infrequent, but not so infrequent it never lands. This is a counterweight to
     * prices drifting down over a market's whole life, not an income anybody should be
     * waiting on — something arriving every few minutes reads as the point of playing,
     * and a market where holding out for the next payment beats trading has been made
     * worse rather than better.
     *
     * Fifty rather than a hundred because this figure is what a rotating market will
     * actually use: nothing else can set it there, since the interval is not offered as
     * a control. And fills only accrue while somebody is hosting and connected — there
     * is no offline trading, so a few friends meeting a couple of evenings a week
     * produce them slowly. A hundred risked a counterweight that never arrives, which is
     * the same as not having one.
     *
     * Also the figure the interlock is judged against: the fee revenue over an interval
     * is what has to exceed one payment, so halving this halves the largest stipend a
     * given listing fee can carry.
     */
    public static final long DEFAULT_STIPEND_EVERY_FILLS = 50L;


    /**
     * What listing costs this identity right now, given what they already have resting.
     *
     * A pure function of state, so every replica charges the same — which is why it
     * counts open orders rather than orders placed lately. "Lately" would need a clock,
     * and a rule whose answer depends on when it is asked cannot live in the replicated
     * layer without forking replicas. See DepositLimiter for the same argument.
     */
    public long listingFeeFor(UUID userId) {
        if (listingFee <= 0) return 0;
        // Zero means off, as it does for every other policy field here — so a market
        // that never sets an allowance keeps the flat fee it had before this existed,
        // and escalation is something somebody turns on.
        if (listingFreeOrders <= 0) return listingFee;

        // The order being placed counts towards the allowance it is charged against.
        // "How many you may hold open before the fee climbs" means the one that takes
        // you past the allowance is the one that pays more — counting only what was
        // already resting gave an allowance of three four orders at the base fee, one
        // more than the field's own description and the checklist both say.
        long over = Math.max(0, openOrderCount(userId) + 1 - listingFreeOrders);
        return Math.multiplyExact(listingFee, over + 1);
    }

    /**
     * Orders this identity has resting across every book.
     *
     * Under the read lock, because this is the input to a fee and a fee is a number
     * every replica has to reach independently and agree on. Each book's own monitor
     * makes any one of them safe to read; it does not stop the walk across them being
     * rearranged halfway through, and "the answer depends on when you asked" is the one
     * property a replicated rule may never have. Cheap: uncontended except for the
     * instant an event is settling.
     */
    public long openOrderCount(UUID userId) {
        if (userId == null) return 0;
        readLock().lock();
        try {
            long n = 0;
            for (String itemId : activeItems()) {
                OrderBook book = peekBook(itemId);
                if (book == null) continue;
                for (Order o : book.restingAsks()) if (userId.equals(o.userID())) n++;
                for (Order o : book.restingBids()) if (userId.equals(o.userID())) n++;
            }
            return n;
        } finally {
            readLock().unlock();
        }
    }

    /**
     * The most a market may charge to list.
     *
     * Low on purpose. This prices the number of orders, and a fee big enough to feel
     * like a tax is big enough to stop people repricing — which costs a market more
     * than the spam it was reached for.
     */
    public static final long MAX_LISTING_FEE = 1_000L;

    /**
     * The most orders a market may let someone hold open before the fee climbs.
     *
     * Not a safety bound — a large allowance is harmless, because the base fee is still
     * charged on every order and that floor is what the stipend interlock rests on. It
     * is a bound on nonsense: an allowance bigger than anyone's book is escalation
     * switched off in a way that reads as though it were switched on.
     */
    public static final int MAX_LISTING_FREE_ORDERS = 1_000;

    /** A listing fee and the allowance that goes with it, read off one field. */
    public static final class ListingFeeSetting {
        public final long fee;
        public final int freeOrders;

        public ListingFeeSetting(long fee, int freeOrders) {
            this.fee = fee;
            this.freeOrders = freeOrders;
        }
    }

    /**
     * Reads a listing fee written as "2", or "2/3" for a fee with an allowance, or null
     * if it is neither.
     *
     * Lives here rather than beside the text field that calls it for the same reason
     * bpsFromPercent does: it can then be tested without Minecraft, and this one needs
     * it — a two-number field has more ways to be typed wrongly than a one-number field.
     *
     * One control for both because they are one decision. A fee and the allowance it
     * escalates past are meaningless apart, and this project has had the same bug four
     * times from keeping two things that must agree in two places. The stipend control
     * already works this way, setting amount and interval together.
     *
     * Syntax only. Whether the numbers make sense together — an allowance on a fee of
     * zero, a fee above the ceiling — is the caller's, so it can say which one is wrong
     * instead of refusing the whole field with one message.
     */
    public static ListingFeeSetting listingFeeFromText(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;

        int slash = t.indexOf('/');
        if (slash != t.lastIndexOf('/')) return null;      // "2/3/4"

        String feePart = slash < 0 ? t : t.substring(0, slash);
        String freePart = slash < 0 ? "0" : t.substring(slash + 1);
        // "2/" is a half-finished thought, not an allowance of zero. Saying so beats
        // quietly setting a flat fee the typist did not ask for.
        if (feePart.trim().isEmpty() || freePart.trim().isEmpty()) return null;

        try {
            long fee = Long.parseLong(feePart.trim());
            int free = Integer.parseInt(freePart.trim());
            if (fee < 0 || free < 0) return null;
            return new ListingFeeSetting(fee, free);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Basis points are per ten thousand. Named so the 10000 is never a loose literal. */
    public static final int BPS_DIVISOR = 10_000;

    /** The highest rate a market may set, in basis points. */
    public static final int MAX_TAX_BPS = 5_000;

    /**
     * The tax on one fill.
     *
     * Every replica computes this independently and must agree to the credit, so the
     * rule is fixed here rather than left to whoever calls it: multiply in long, divide
     * once, truncate. Amounts are always positive, so integer division is a floor —
     * true by accident of the inputs rather than by construction, which is why there is
     * a test pinning it rather than a comment hoping for it.
     *
     * Rounding down also means the tax can be zero on a small fill. That is deliberate:
     * the alternative, rounding up, takes a credit from a one-credit trade and makes the
     * effective rate on small trades wildly higher than the number the market advertises.
     */
    /**
     * Reads a fee written as a percentage and returns basis points, or -1 if it is not
     * one.
     *
     * Lives in core rather than beside the text field that calls it so it can be tested
     * without Minecraft — the conversion is the one place a human-entered decimal meets
     * a number every replica has to agree on.
     *
     * BigDecimal, not Double.parseDouble. Parsing "2.5" to a double and multiplying by
     * 100 can land on 249.99999999999997, and truncating that gives a market a 2.49%
     * fee nobody asked for. Two decimal places is exactly what basis points can hold,
     * so anything finer is refused rather than quietly rounded.
     */
    public static int bpsFromPercent(String text) {
        if (text == null) return -1;
        try {
            java.math.BigDecimal pct = new java.math.BigDecimal(text.trim());
            if (pct.scale() > 2) return -1;
            int bps = pct.movePointRight(2).intValueExact();
            return bps < 0 ? -1 : bps;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The smallest sale this rate actually takes anything from, or 0 when there is no
     * rate.
     *
     * Rounding down means a percentage of a small enough sale is nothing, and with
     * integer credits that is unavoidable: a market whose items go for one or two
     * credits cannot express 2.5% of a sale at all. The rate is not broken when that
     * happens, but it is invisible, and somebody who set a fee and watched it take
     * nothing deserves to be told which of the two they are looking at.
     *
     * Ceiling division, because the fee bites at the first amount where
     * amount * bps reaches one whole ten-thousandth.
     */
    public static long smallestTaxableSale(int bps) {
        if (bps <= 0) return 0;
        return (BPS_DIVISOR + bps - 1) / bps;
    }

    public static long taxOn(long amount, int bps) {
        if (bps <= 0 || amount <= 0) return 0;
        long tax = Math.multiplyExact(amount, (long) bps) / BPS_DIVISOR;
        // Cannot exceed the trade it is levied on, whatever the rate.
        return Math.min(tax, amount);
    }

    public WalletRegistry wallets() { return wallets; }

    public TradeHistory trades() { return trades; }

    /**
     * Files fills into the trade history. Called only by EventApplier, which is the
     * only place that knows both the fills and the event that caused them — the
     * matching engine has no clock and no sequence number of its own.
     */
    void recordTrades(long seq, long timestamp, List<Fill> fills) {
        if (fills == null) return;
        for (Fill f : fills) {
            trades.record(new Trade(seq, timestamp, f));
            fillsEver++;
        }
    }

    /**
     * How many fills this market has ever settled. The stipend's clock.
     *
     * Counted rather than timed, because a replica replaying a year later must reach the
     * same answer as the host that wrote it — the same reason the deposit cap cannot
     * live in this layer at all.
     *
     * Fills specifically, and not sequence numbers, because sequence is free to
     * manufacture: Deposit, Withdraw and CancelOrder all advance it and cost nothing, so
     * a stipend paid per sequence number could be farmed in a market of one by
     * depositing the same dirt over and over. A fill needs two orders to cross, and
     * placing an order costs a listing fee that is never zero. That is the whole reason
     * the fee has a floor.
     */
    private volatile long fillsEver = 0;

    public long fillsEver() { return fillsEver; }

    // Get-or-create the order book for a given item
    public OrderBook bookFor(String itemId) {
        return markets.computeIfAbsent(itemId, k -> new OrderBook());
    }

    /**
     * The order book for an item, or null if it has never had one.
     *
     * For readers. {@link #bookFor} creates on read, which is correct when an order is
     * about to be placed and wrong everywhere else — a UI listing every item a player
     * holds would quietly fill the map with empty books for items nobody has ever
     * traded, and anything iterating {@link #activeItems()} afterwards would see them.
     */
    public OrderBook peekBook(String itemId) {
        return markets.get(itemId);
    }

    /** Whether this item has a book at all, without creating one. */
    public boolean hasBook(String itemId) {
        return markets.containsKey(itemId);
    }

    //item balance section
    ///////////////////////
    private final ItemBalanceRegistry itemBalances = new ItemBalanceRegistry();

    public ItemBalanceRegistry itemBalances() { return itemBalances; }



    /** Called after the client physically removes the item from a player's inventory. */
    public void deposit(UUID  userId, String itemId, long qty) {
        if (qty <= 0) return;
        long current = itemBalances.getBalance(userId, itemId);
        if (current > Long.MAX_VALUE - qty) return;   // would overflow — reject
        itemBalances.adjust(userId, itemId, +qty);
    }
    /////////////////////


    // Single entry point: submit an order, match it, settle the fills.
    public SubmitResult submitOrder(Order order) {
        SubmitResult check = canSubmit(order);
        if (!check.accepted) return check;

        // Charged on placement, kept on cancellation, and burned like the trading fee.
        // Refunding it would deter nothing, which is the only thing it is for.
        //
        // Read before the order joins the book. listingFeeFor adds this order to the
        // count itself, so reading afterwards would count it twice — and canSubmit,
        // which has to agree to the credit, reads it from the same side of the join.
        long fee = listingFeeFor(order.userID());
        if (fee > 0) {
            wallets.adjust(order.userID(), -fee);
        }

        // Reserve
        if (!order.isBid()) {
            itemBalances.adjust(order.userID(), order.itemID(), -order.volume());
        } else {
            wallets.adjust(order.userID(), -Math.multiplyExact(order.volume(), order.value()));
        }

        OrderBook book = bookFor(order.itemID());
        List<Fill> fills = book.submit(order);

        for (Fill f : fills) {
            itemBalances.adjust(f.buyerId(), f.itemId(), +f.quantity());

            // Levied on the seller's proceeds, not the buyer's outlay. The buyer's side
            // already reserves at their limit price and refunds the difference below,
            // and threading a deduction through that is how refund arithmetic acquires
            // the double-spend bugs this project has already rejected once. Taking it
            // here changes one credit and leaves the reservation untouched.
            //
            // Burned, not paid to anyone. The welcome grant is currently an unbounded
            // source of money with no sink; a tax that credited the operator would just
            // move the unboundedness to them, and a treasury needs a way to spend that
            // does not exist. Destroying it is the only option that closes the loop.
            long tax = taxOn(f.amount(), taxBps);
            wallets.adjust(f.sellerId(), +(f.amount() - tax));

            long reservedForThisFill = f.quantity() * buyerOrderPrice(order, f);
            long actuallySpent = f.amount();
            if (reservedForThisFill > actuallySpent) {
                wallets.adjust(f.buyerId(), reservedForThisFill - actuallySpent);
            }
        }

        return SubmitResult.ok(fills);
    }


    public boolean canWithdraw(UUID userId, String itemId, long qty) {
        return qty > 0 && itemBalances.getBalance(userId, itemId) >= qty;
    }

    public boolean canCancel(long orderId, String itemId, boolean isBid, UUID userId) {
        Order o = bookFor(itemId).find(orderId, isBid);
        return o != null && o.userID().equals(userId);
    }
    /** Checks whether an order would be accepted, without mutating anything. */
    public SubmitResult canSubmit(Order order) {
        if (order.volume() <= 0 || order.value() <= 0) {
            return SubmitResult.reject("volume and price must be positive");
        }

        if (!order.isBid()) {
            long available = itemBalances.getBalance(order.userID(), order.itemID());
            if (available < order.volume()) {
                return SubmitResult.reject("insufficient item balance");
            }
            // A sell offers goods but still pays to be listed, so it needs credits it
            // is not otherwise spending. Checked here rather than discovered during
            // settlement, where the order would already have been accepted.
            String unaffordable = listingUnaffordable(order.userID());
            if (unaffordable != null) return SubmitResult.reject(unaffordable);
        } else {
            long maxCost;
            try {
                maxCost = Math.addExact(
                        Math.multiplyExact(order.volume(), order.value()),
                        listingFeeFor(order.userID()));
            } catch (ArithmeticException e) {
                return SubmitResult.reject("order value too large");
            }
            if (wallets.getBalance(order.userID()) < maxCost) {
                long fee = listingFeeFor(order.userID());
                return SubmitResult.reject(fee > 0
                        ? "insufficient credits — this costs " + maxCost
                                + " including the " + fee + " listing fee"
                        : "insufficient credits");
            }
        }

        return SubmitResult.ok(Collections.emptyList());
    }

    /**
     * Why this identity cannot pay to list right now, or null.
     *
     * One place, because two callers need the same answer about the same moment. An
     * ordinary sell asks it through canSubmit; a deposit-and-list has to ask it before
     * the deposit rather than after, and a second copy of the arithmetic is how those
     * two would come to disagree.
     */
    private String listingUnaffordable(UUID userId) {
        long fee = listingFeeFor(userId);
        if (fee > 0 && wallets.getBalance(userId) < fee) {
            return "listing costs " + fee + " credits and you have "
                    + wallets.getBalance(userId);
        }
        return null;
    }

    /**
     * Whether a deposit-and-list would be accepted, asked before anything is deposited.
     *
     * The two halves of that event cannot be checked the way an ordinary order is. The
     * deposit is what makes the listing affordable in goods, so canSubmit cannot answer
     * until it has happened — and asking afterwards meant a refusal left the goods in
     * the ledger on an event the author had just been told had failed, while the client
     * handed the physical items back. Items on both sides of that is the whole reason
     * this exists.
     *
     * So: everything the listing needs that the deposit does not provide, asked here,
     * and asked by validate and apply alike.
     */
    public SubmitResult canDepositAndList(UUID userId, String itemId, long qty, long price) {
        if (qty <= 0 || price <= 0) {
            return SubmitResult.reject("volume and price must be positive");
        }
        // deposit() declines silently on overflow rather than throwing, which would
        // leave the order short of goods it was told it had. Refuse it here instead.
        long held = itemBalances.getBalance(userId, itemId);
        if (held > Long.MAX_VALUE - qty) {
            return SubmitResult.reject("that would overflow your balance of " + itemId);
        }
        String unaffordable = listingUnaffordable(userId);
        if (unaffordable != null) return SubmitResult.reject(unaffordable);

        return SubmitResult.ok(Collections.emptyList());
    }

    public boolean withdraw(UUID userId, String itemId, long qty) {
        if (qty <= 0) return false;
        long available = itemBalances.getBalance(userId, itemId);
        if (available < qty) return false;
        itemBalances.adjust(userId, itemId, -qty);
        withdrawn.merge(userId + " " + itemId, qty, Long::sum);
        return true;
    }

    /**
     * How much of an item this market has ever handed to somebody.
     *
     * Only grows, and derived from the log like everything else here, so every replica
     * agrees on it.
     *
     * Exists for one reason: a withdrawal puts items in a player's inventory through
     * insertStack, which increments no statistic — exactly as /give does. Without this,
     * the deposit rules that weigh a player against their own statistics would refuse
     * them the goods this market gave them, which is the one case where their
     * provenance is not in doubt at all: it was in the ledger a moment ago.
     */
    public long withdrawnBy(UUID userId, String itemId) {
        if (userId == null || itemId == null) return 0;
        Long n = withdrawn.get(userId + " " + itemId);
        return n == null ? 0 : n;
    }

    // "<uuid> <itemId>" rather than a nested map: nothing ever iterates one half of it.
    // A space is safe as the join — a UUID is hex and dashes, an item id is a namespaced
    // path, and neither can contain one.
    private final Map<String, Long> withdrawn = new ConcurrentHashMap<>();

    private long buyerOrderPrice(Order incoming, Fill f) {
        // If the incoming order is the buyer, use its price.
        // Otherwise the buyer is a resting bid, whose price IS the fill price
        // (trades execute at the resting order's price), so no improvement applies.
        return incoming.isBid() ? incoming.value() : f.price();
    }

    public boolean cancelOrder(long orderId, String itemId, boolean isBid, UUID userId) {
        Order o = bookFor(itemId).cancel(orderId, isBid, userId);
        if (o == null) return false;

        if (o.isBid()) {
            wallets.adjust(userId, Math.multiplyExact(o.volume(), o.value()));
        } else {
            itemBalances.adjust(userId, itemId, o.volume());
        }
        return true;
    }


    public Set<String> activeItems() {
        return markets.keySet();
    }

    Map<String, OrderBook> markets() { return markets; }

    public static class SubmitResult {
        public final boolean accepted;
        public final String reason;
        public final List<Fill> fills;

        private SubmitResult(boolean accepted, String reason, List<Fill> fills) {
            this.accepted = accepted;
            this.reason = reason;
            this.fills = fills;
        }

        public static SubmitResult ok(List<Fill> fills) {
            return new SubmitResult(true, null, fills);
        }

        public static SubmitResult reject(String reason) {
            return new SubmitResult(false, reason, Collections.emptyList());
        }
    }

}
